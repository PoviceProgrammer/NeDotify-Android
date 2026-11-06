package ru.nedotify.app.ui.state

import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.nedotify.app.NeDotifyApp
import ru.nedotify.app.data.*
import ru.nedotify.app.player.PlaybackService
import ru.nedotify.app.player.playbackQueue
import ru.nedotify.app.player.trackMediaItem
import ru.nedotify.app.ui.*

enum class PlaybackConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

/** One application-owned MediaController mirror. Runtime queue/decoders belong to the service. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackConnection(private val app:NeDotifyApp) {
    private val appContext=app
    private val repository get()=app.repository
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val message get()=app.uiEvents.message
    private val _playback=MutableStateFlow(NowPlaying())
    val playback:StateFlow<NowPlaying> = _playback.asStateFlow()
    private val _positionMs=MutableStateFlow(0L)
    val positionMs:StateFlow<Long> = _positionMs.asStateFlow()
    private val _connection=MutableStateFlow(PlaybackConnectionState.CONNECTING)
    val connection:StateFlow<PlaybackConnectionState> = _connection.asStateFlow()
    private var controller:MediaController?=null
    private var cleared=false
    private val playerErrors=PlayerErrorState()
    private var shownMediaError:String?=null
    private var flowJob:Job?=null
    private var selectionOwner:Any?=null
    private data class PreparedPlayback(val player:MediaController,val items:List<Track>,val trackId:String)
    private val playRequests=PlaybackPreparation<PreparedPlayback>(scope)
    private val listener=object:Player.Listener {
        override fun onEvents(player:Player,events:Player.Events)=update(player,events.containsAny(Player.EVENT_TIMELINE_CHANGED,Player.EVENT_MEDIA_METADATA_CHANGED))
        override fun onPlayerError(error:PlaybackException) {
            message.value=playbackFailureMessage(error)
        }
    }
    /**
     * The controller is built from the session token. The future is one-shot: a
     * disconnected controller can never play again, so a new one is built
     * (P3-108) — otherwise every playback entry point would be a silent no-op.
     */
    private val connector:ControllerConnector=ControllerConnector(
        build={
            val token=SessionToken(appContext,ComponentName(appContext,PlaybackService::class.java))
            MediaController.Builder(appContext,token).setListener(object:MediaController.Listener {
                override fun onError(controller:MediaController,error:SessionError) {
                    // A null message must never clear the channel: the error is dropped then.
                    val text=sessionErrorMessage(error.message)
                    // A refused command (a play the gate declined) never reaches the
                    // player as a media error, so the player screen would stay silent.
                    // The refusal becomes player state and is rendered there.
                    playerErrors.onCommandRefused(_playback.value.id,text)
                    message.value=text
                    publishPlayerError()
                }
                override fun onDisconnected(controller:MediaController) {
                    this@PlaybackConnection.controller=null;_connection.value=PlaybackConnectionState.DISCONNECTED
                    message.value="Плеер недоступен. Повторите попытку"
                    // The controller is dead: a new one takes its place.
                    try {
                        _connection.value=PlaybackConnectionState.CONNECTING
                        connector.reconnect()
                    } catch(_:Exception) {
                        _connection.value=PlaybackConnectionState.DISCONNECTED
                        message.value="Не удалось подключиться к плееру. Повторите действие"
                    }
                }
            }).buildAsync()
        },
        release={MediaController.releaseFuture(it)},
        onBuilt={built->built.addListener({
            try {
                val created=built.get()
                if(cleared) {MediaController.releaseFuture(built);return@addListener}
                controller=created;_connection.value=PlaybackConnectionState.CONNECTED
                created.addListener(listener)
                update(created,true)
            } catch(error:Exception) {
                _connection.value=PlaybackConnectionState.DISCONNECTED;message.value="Не удалось подключиться к плееру"
            }
        },ContextCompat.getMainExecutor(appContext))})
    private fun connect() {
        if(cleared)return
        _connection.value=PlaybackConnectionState.CONNECTING
        try {connector.connect()}
        catch(_:Exception) {
            _connection.value=PlaybackConnectionState.DISCONNECTED
            message.value="Не удалось подключиться к плееру. Повторите действие"
        }
    }
    /** One deliberate retry; a pending or live connection is never rebuilt. */
    fun retryConnection() {scope.launch {
        if(!cleared && controller==null && _connection.value==PlaybackConnectionState.DISCONNECTED)connect()
    }}
    init {
        // The application lazy may first be requested by a background caller.
        // Building and mutating the MediaController always stays on its main looper.
        scope.launch {connect()}
        scope.launch {
            combine(_positionMs.subscriptionCount,playback.map {it.playing}.distinctUntilChanged()){count,playing->count>0 && playing}
                .collectLatest {poll->if(poll)while(currentCoroutineContext().isActive) {
                    controller?.let {_positionMs.value=it.currentPosition.coerceAtLeast(0)};delay(500)
                }}
        }
        scope.launch {repository.preferences.appearance.map {it.offline}.distinctUntilChanged().collectLatest {offline->
            if(offline) {
                cancelPendingPlaybackAndWait()
                val id=_playback.value.id
                val needsNetwork=repository.dao.track(id)?.localUri.isNullOrBlank() && repository.dao.download(id)?.state!="complete"
                if(needsNetwork && _playback.value.id==id)controller?.pause()
            }
        }}
    }
    private fun update(player:Player,rebuildQueue:Boolean=false) {
        val item=player.currentMediaItem
        val id=item?.mediaId.orEmpty()
        if(id!=_playback.value.id) {

            // A refusal of the previous track must not follow the user.
            playerErrors.onTrackChanged(id)
        }
        _positionMs.value=player.currentPosition.coerceAtLeast(0)
        // The error is part of the player state: the message channel shows it to
        // the user, and the player screen renders it (area A7).
        val mediaError=player.playerError?.let {playbackFailureMessage(it)}
        playerErrorMessage(player.playerError,shownMediaError)?.let {message.value=it}
        shownMediaError=mediaError
        _playback.value=NowPlaying(id,item?.mediaMetadata?.title?.toString().orEmpty(),
            item?.mediaMetadata?.artist?.toString().orEmpty(),item?.mediaMetadata?.artworkUri?.toString().orEmpty(),
            player.isPlaying,player.playbackState==Player.STATE_BUFFERING,
            player.duration.takeIf { it!=C.TIME_UNSET }?.coerceAtLeast(0)?:0,player.shuffleModeEnabled,player.repeatMode,
            player.currentMediaItemIndex,
            if(rebuildQueue)queueRows(player) else _playback.value.queue,player.playWhenReady,
            playerErrors.errorFor(id,mediaError))
    }
    /** Publish the player error without waiting for the next player event. */
    private fun publishPlayerError() {
        val id=_playback.value.id
        val error=playerErrors.errorFor(id,controller?.playerError?.let{playbackFailureMessage(it)})
        if(error!=_playback.value.error)_playback.value=_playback.value.copy(error=error)
    }
    /**
     * The queue rows are reused between emissions: an unchanged row keeps its instance and
     * an unchanged queue keeps its list, so the mirror's equality check stays cheap.
     */
    private fun queueRows(player:Player):List<QueueRow> {
        val count=player.mediaItemCount
        val previous=_playback.value.queue
        val rows=ArrayList<QueueRow>(count)
        var changed=previous.size!=count
        for(index in 0 until count) {
            val old=previous.getOrNull(index)
            val row=queueRow(player.getMediaItemAt(index),old)
            if(row!==old)changed=true
            rows+=row
        }
        return if(changed)rows else previous
    }
    fun play(track:Track,queue:List<Track>) {
        // A new selection supersedes the preparation that is running.
        commands.run(PlaybackCommand.SELECT) {playSelected(track,queue)}
    }
    private suspend fun preparePlayback(track:Track,queue:List<Track>):PreparedPlayback {
        val player=controller?:run {
            retryConnection()
            controller?:throw SourceFailure("Плеер подключается. Повторите действие")
        }
        val items=playbackQueue(track,queue)
        repository.stageQueue(items);repository.remember(track)
        return PreparedPlayback(player,items,track.id)
    }
    private fun startPrepared(request:PreparedPlayback) {
        val player=request.player;val items=request.items
        // The items are built by the shared factory: an item without a URI would
        // make the source factory throw. The service rebuilds them with the
        // resolved sources anyway.
        player.setMediaItems(items.map {trackMediaItem(it,null)},
            items.indexOfFirst {it.id==request.trackId}.coerceAtLeast(0),0)
        player.prepare();player.play()
    }
    private fun playSelected(track:Track,queue:List<Track>) {
        playRequests.submit(load={preparePlayback(track,queue)},onReady=::startPrepared,
            onFailure={error->message.value=error.message?:"Не удалось начать воспроизведение"})
    }
    /**
     * Drop the pending preparation.
     *
     * Only a **new selection** may drop it: the track the user tapped is the
     * one that must play. A transport or queue command acts on the player that
     * is running and must not silently discard that selection (REG P2-65) —
     * the preparation is up to 20 s long for a catalogue track, and a cancel
     * there left the user with the previous track and no error.
     */
    private fun cancelPendingPlayback() {selectionOwner=null;playRequests.cancel();flowJob?.cancel()}
    /** The one place that decides whether a command drops the pending preparation. */
    private val commands=PlaybackCommands {cancelPendingPlayback()}
    /**
     * Cancel the pending preparation and wait until it has really stopped.
     *
     * The preparation writes the track into the library. A delete that runs
     * while that write is still in flight would be undone by it: the track
     * would come back after the user removed it. The delete therefore waits
     * for the cancelled preparation to release the library.
     */
    private suspend fun cancelPendingPlaybackAndWait() {selectionOwner=null;playRequests.cancelAndAwait();flowJob?.cancel()}
    fun toggle() {
        if(controller==null) {
            retryConnection()
            message.value="Плеер подключается. Повторите действие"
            return
        }
        // The user retries: a refusal of the previous attempt is no longer news.
        playerErrors.onRetry();publishPlayerError()
        commands.run(PlaybackCommand.TRANSPORT) {
        controller?.let {
        if(it.playerError!=null) {it.prepare();it.play()}
        else if(it.playbackState==Player.STATE_ENDED) {it.seekToDefaultPosition();it.play()}
        else if(it.playWhenReady)it.pause()
        else {if(it.playbackState==Player.STATE_IDLE)it.prepare();it.play()}
    } } }
    fun next() = commands.run(PlaybackCommand.TRANSPORT) {controller?.seekToNextMediaItem()}
    fun previous() = commands.run(PlaybackCommand.TRANSPORT) {controller?.seekToPrevious()}
    fun seek(position:Long) = commands.run(PlaybackCommand.TRANSPORT) {controller?.seekTo(position)}
    fun shuffle() = commands.run(PlaybackCommand.TRANSPORT) {controller?.let { it.shuffleModeEnabled=!it.shuffleModeEnabled }}
    fun repeat() = commands.run(PlaybackCommand.TRANSPORT) {controller?.let { it.repeatMode=nextRepeatMode(it.repeatMode) }}
    fun queueIndex(index:Int) = commands.run(PlaybackCommand.QUEUE) {
        controller?.let {if(index in 0 until it.mediaItemCount) {it.seekTo(index,0);it.play()}}}
    fun removeQueueItem(index:Int) = commands.run(PlaybackCommand.QUEUE) {
        controller?.let {if(index in 0 until it.mediaItemCount)it.removeMediaItem(index)}}
    fun moveQueueItem(from:Int,to:Int) = commands.run(PlaybackCommand.QUEUE) {
        controller?.let {if(from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount)it.moveMediaItem(from,to)}}
    fun clearQueue() = commands.run(PlaybackCommand.QUEUE) {
        controller?.let {player->
            val active=player.currentMediaItemIndex
            if(active in 0 until player.mediaItemCount) {
                if(active+1<player.mediaItemCount)player.removeMediaItems(active+1,player.mediaItemCount)
                if(active>0)player.removeMediaItems(0,active)
            } else player.clearMediaItems()
        }
    }
    fun startFlow() {
        if(flowJob?.isActive==true)return
        // The flow is a new selection: it supersedes a running preparation.
        commands.run(PlaybackCommand.SELECT) {
        flowJob=task {
            val queue=DiscoveryEngine(repository).selectFlow(emptySet(),30)
            currentCoroutineContext().ensureActive()
            if(queue.isEmpty())throw SourceFailure("Для Flow пока нет доступных треков. Добавьте музыку или выполните поиск")
            repository.audioSettings.update {it.copy(autoplay=true)}
            currentCoroutineContext().ensureActive();playSelected(queue.first(),queue)
        }
        }
    }

    /** The catalogue supplies only its selected track; preparation retains one shared ordering. */
    fun selectCatalog(owner:Any,load:suspend ()->Track,onFailure:(Exception)->Unit,onComplete:()->Unit) {
        commands.run(PlaybackCommand.SELECT) {
            selectionOwner=owner
            playRequests.submit(load={val track=load();preparePlayback(track,listOf(track))},onReady=::startPrepared,
                onFailure=onFailure,onComplete={if(selectionOwner===owner)selectionOwner=null;onComplete()})
        }
    }
    fun cancelCatalog(owner:Any) {if(selectionOwner===owner)cancelPendingPlayback()}
    suspend fun prepareForRemoval(id:String) {
        cancelPendingPlaybackAndWait()
        controller?.let {player->(player.mediaItemCount-1 downTo 0).filter {player.getMediaItemAt(it).mediaId==id}.forEach(player::removeMediaItem)}
    }
    fun pauseIfCurrent(id:String) {if(_playback.value.id==id)controller?.pause()}
    fun replaceTrack(track:Track,offline:OfflineDownload?):Int {
        val item=trackMediaItem(track,offline)
        var replaced=0
        controller?.let {player->(0 until player.mediaItemCount).filter {player.getMediaItemAt(it).mediaId==track.id}
            .forEach {index->player.replaceMediaItem(index,item);replaced++}}
        return replaced
    }
    private fun task(block:suspend ()->Unit)=scope.launch {
        try {block()}catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){message.value=error.message?:"Не удалось выполнить действие"}
    }
    /** Process/test teardown only; feature screens never close the shared connection. */
    fun close() {
        cleared=true;cancelPendingPlayback();controller?.removeListener(listener)
        connector.close();controller=null;_connection.value=PlaybackConnectionState.DISCONNECTED;scope.cancel()
    }
}
