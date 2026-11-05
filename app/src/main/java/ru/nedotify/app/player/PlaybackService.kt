package ru.nedotify.app.player

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.*
import androidx.core.net.toUri
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*
import ru.nedotify.app.MainActivity
import ru.nedotify.app.NeDotifyApp
import ru.nedotify.app.data.*
import ru.nedotify.app.downloads.*
import kotlinx.coroutines.flow.*

@UnstableApi
class PlaybackService:MediaSessionService() {
    private val app get()=application as NeDotifyApp
    private val repository get()=app.repository
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var decks:DualDeckPlayer
    private val player:ExoPlayer get()=decks.active
    private lateinit var session:MediaSession
    private val history=ListeningHistory(SystemClock::elapsedRealtime) {record->app.ioScope.launch {
        try {
            // One fence for the checkpoint and the removal. The track row and
            // the history row are written together, so a delete that lands in
            // between either precedes the record (the track is gone, the record
            // is skipped) or follows it (the record is removed with the track).
            // The old three-step sequence could write the row back after the
            // user had deleted it.
            repository.recordSession(record)
        }
        catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception) {
            android.util.Log.e("NeDotify","Listening history could not be saved",error)
            withContext(Dispatchers.Main) {
                if(!destroyed && ::session.isInitialized)
                    session.sendError(SessionError(SessionError.ERROR_IO,"Не удалось сохранить историю прослушивания. Проверьте свободное место"))
            }
        }
    }}
    private var retryCount=0
    private var restore:Job?=null
    private var recovery:Job?=null
    private var pendingSet:Job?=null
    private val checkpoint=QueueCheckpoint(POSITION_STEP_MS)
    private var audioSettings=AudioSettings()
    private var autoplayAttempt=""
    private var autoplayRetryAt=0L
    private var destroyed=false
    private val checkpointPolling=MutableStateFlow(false)
    private val autoplayPolling=MutableStateFlow(false)
    private var interactionVersion=0L
    private var selectionVersion=0L
    private var explicitQueueBoundaryId:String?=null
    private var explicitQueueBoundaryIndex=-1
    /** The controller that asked for the last play: a refusal is reported to it. */
    private var lastPlayController:MediaSession.ControllerInfo?=null
    private val selectionGate=PendingSelectionGate()
    private data class RestoredQueue(val items:List<MediaItem>,val index:Int,val position:Long,
        val shuffleOrder:IntArray?,val repeat:Int,val shuffled:Boolean)

    override fun onCreate() {
        super.onCreate()
        // The media client: no total call budget, a per-hop URL policy and the
        // headers the CDNs expect. The metadata client would cut a track off
        // in the middle, because Media3 reads the body through one call.
        val upstream=DefaultDataSource.Factory(this,OkHttpDataSource.Factory(repository.network.mediaHttp))
        // The cache key must not be the signed URL: every re-resolution signs
        // a new one, and the bytes that were already fetched would be
        // orphaned. The resolver puts the identity of the track into the data
        // spec, and the key factory turns it into the key.
        val cached=CacheDataSource.Factory().setCache(app.audioCache)
            .setCacheKeyFactory {spec->streamingCacheKey(spec.key,spec.uri.toString())}
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Resolve BEFORE CacheDataSource chooses its key and stores redirect metadata.
        // open() is a synchronous loader contract; resolution is cancellable and deadline-bound.
        val source=ResolvingDataSource.Factory(cached) {spec->
            if(spec.uri.scheme!="nedotify")spec else runBlocking {
                val track=repository.track(spec.uri.lastPathSegment.orEmpty())?:throw SourceFailure("Трек отсутствует в библиотеке")
                val resolved=repository.resolve(track)
                val expectedHls=track.mimeType.equals("application/x-mpegURL",true)
                val actualHls=resolved.mimeType.equals("application/x-mpegURL",true)
                if(expectedHls!=actualHls)throw SourceFailure("Источник изменил формат записи. Найдите трек в поиске заново")
                // The identity travels with the request: the cache key is built
                // from it, not from the signature of the URL.
                val identity=if(actualHls)HLS_MANIFEST_KEY_PREFIX+track.id else track.id
                spec.withUri(resolved.url.toUri()).buildUpon().setKey(identity).build()
            }
        }
        fun buildDeck()=ExoPlayer.Builder(this).setMediaSourceFactory(OfflineSources(DefaultMediaSourceFactory(source),app.downloads)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(),false)
            setHandleAudioBecomingNoisy(false);setWakeMode(C.WAKE_MODE_NETWORK)
        }
        decks=DualDeckPlayer(this,buildDeck(),buildDeck(),scope).apply {
            // A user action supersedes the running restore/autoplay/refresh requests.
            // It never cancels a queue selection: the queue the user asked for survives.
            onUserInteraction={interactionVersion++;restore?.cancel();recovery?.cancel();retryCount=0}
            // The environment changed or another controller paused: a play that is
            // already queued must not resume, but the pending selection is untouched.
            onPlaybackInterrupted={selectionGate.interruptPlay()}
            onPlaybackRefused={reason->reportRefusal(reason)}
            onQueueSet={id,index->explicitQueueBoundaryId=id;explicitQueueBoundaryIndex=index}
            // The gate decides once per executed play; the decision is consumed.
            playRefusal={playWhenReady->
                val stale=selectionGate.evaluatePlay(playWhenReady)
                // An allowed play has no offender: the next refusal is broadcast.
                if(!stale)lastPlayController=null
                playbackRefusal(playWhenReady,stale)
            }
            canStartPlayback={selectionGate.canStartPlayback}
            onHandover={id->history.transition(id,true)}
            onFocusDenied={session.sendError(SessionError(SessionError.ERROR_INVALID_STATE,"Аудиовыход занят. Повторите воспроизведение"))}
        }
        session=MediaSession.Builder(this,decks).setSessionActivity(PendingIntent.getActivity(this,0,
            Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object:MediaSession.Callback {
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onPlayerCommandRequest(mediaSession:MediaSession,controller:MediaSession.ControllerInfo,playerCommand:Int):Int {
                    // The command is enqueued now and executed after the queue is
                    // installed: the gate remembers it so an interruption that
                    // happens in between can refuse the stale play.
                    if(playerCommand==Player.COMMAND_PLAY_PAUSE) {
                        selectionGate.expectQueuedPlay()
                        lastPlayController=controller
                    }
                    return SessionResult.RESULT_SUCCESS
                }
                override fun onMediaButtonEvent(mediaSession:MediaSession,controller:MediaSession.ControllerInfo,intent:Intent):Boolean {
                    // The media button and the notification's play button never reach
                    // onPlayerCommandRequest: they call the player directly. Such a
                    // play is a fresh request and must never be refused.
                    val keyEvent=IntentCompat.getParcelableExtra(intent,Intent.EXTRA_KEY_EVENT,KeyEvent::class.java)
                    if(keyEvent!=null&&isFreshPlayKeyCode(keyEvent.keyCode))selectionGate.expectFreshPlay()
                    return false
                }
                override fun onDisconnected(mediaSession:MediaSession,controller:MediaSession.ControllerInfo) {
                    // The controller is gone: the command it queued will never be
                    // executed, so it must not stay in the gate's ledger.
                    selectionGate.abandonCommands()
                    if(lastPlayController==controller)lastPlayController=null
                }
                override fun onConnect(mediaSession:MediaSession,controller:MediaSession.ControllerInfo):MediaSession.ConnectionResult {
                    return if(controller.packageName==packageName || controller.isTrusted)
                        MediaSession.ConnectionResult.accept(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                    else MediaSession.ConnectionResult.reject()
                }
                override fun onSetMediaItems(mediaSession:MediaSession,controller:MediaSession.ControllerInfo,items:MutableList<MediaItem>,startIndex:Int,startPositionMs:Long):ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    // A new selection supersedes an older pending one; a transport command
                    // does not, so the pending selection is guarded by its own version.
                    interactionVersion++;selectionVersion++
                    restore?.cancel();recovery?.cancel();pendingSet?.cancel();retryCount=0;decks.cancelTransition()
                    return resolveFuture(controller,true) {
                        val selected=readQueue(items.map {it.mediaId},startIndex)
                        MediaSession.MediaItemsWithStartPosition(mediaItems(selected.items),selected.startIndex,
                            if(selected.keptRequestedStart)startPositionMs else 0L)
                    }
                }
                override fun onAddMediaItems(mediaSession:MediaSession,controller:MediaSession.ControllerInfo,items:MutableList<MediaItem>):ListenableFuture<MutableList<MediaItem>> {
                    interactionVersion++
                    return resolveFuture(controller,false) {mediaItems(readQueue(items.map {it.mediaId},0).items).toMutableList()}
                }
                override fun onPlayerInteractionFinished(mediaSession:MediaSession,controllerInfo:MediaSession.ControllerInfo,commands:Player.Commands) {
                    if(commands.contains(Player.COMMAND_PREPARE)){retryCount=0;recovery?.cancel()}
                }
            }).build()
        decks.addListener(object:Player.Listener {
            override fun onMediaItemTransition(item:MediaItem?,reason:Int) {
                recovery?.cancel();retryCount=0
                // The boundary belongs to the queue that set it: only the transition
                // that the set itself caused is an explicit queue boundary. A later,
                // unrelated transition to the same item is not a boundary - the listen
                // must not be split (P3-05).
                val newQueue=isExplicitQueueBoundary(reason,item?.mediaId,player.currentMediaItemIndex,
                    explicitQueueBoundaryId,explicitQueueBoundaryIndex)
                explicitQueueBoundaryId=null;explicitQueueBoundaryIndex=-1
                if(!decks.isHandover)history.transition(item?.mediaId,
                    reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                    forceNewSession=newQueue || reason!=Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            }
            override fun onIsPlayingChanged(isPlaying:Boolean) {history.playing(isPlaying)}
            override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int) {
                if(!playWhenReady && reason==Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)recovery?.cancel()
            }
            override fun onEvents(player:Player,events:Player.Events) {
                updatePolling()
                if(events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION,Player.EVENT_IS_PLAYING_CHANGED,Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,Player.EVENT_TIMELINE_CHANGED,Player.EVENT_POSITION_DISCONTINUITY))checkpointQueue()
            }
            override fun onPlaybackStateChanged(state:Int) {if(state==Player.STATE_ENDED)history.checkpoint(true)}
            override fun onPlayerError(error:PlaybackException) {
                val offline=player.currentMediaItem?.localConfiguration?.tag as? OfflineAsset
                if(offline!=null) {
                    val reason="Не удалось прочитать скачанный трек. Загрузите его повторно"
                    session.sendError(SessionError(SessionError.ERROR_IO,reason))
                    scope.launch {repository.dao.downloadState(offline.id,offline.generation,"failed",reason)}
                    return
                }
                val id=player.currentMediaItem?.mediaId
                // A self-healing error must not be shown: the automatic recovery runs
                // first and reports the failure only when it does not help.
                if(id==null || retryCount>=1 || !player.playWhenReady) {
                    reportPlaybackError(error);return
                }
                retryCount++
                val position=player.currentPosition;val index=player.currentMediaItemIndex
                recovery=scope.launch {
                    // The self-healing path runs exactly when something has already
                    // gone wrong: no exception may escape into the app's crash handler.
                    try {
                        repository.invalidate(id)
                        ensureActive()
                        if(!destroyed&&player.currentMediaItem?.mediaId==id &&
                            player.currentMediaItemIndex==index && player.playWhenReady) {
                            decks.cancelTransition()
                            player.seekTo(index,position);player.prepare() // prepare retains playWhenReady; a paused user is never resumed.
                        }
                    } catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception) {
                        // The recovery did not help: the failure is reported like any
                        // other playback error.
                        reportPlaybackError(error)
                    }
                }
            }
        })
        val restoreVersion=interactionVersion
        restore=scope.launch {
            try {
                guardedPlaybackMutation(
                    prepare={
                        val saved=repository.preferences.queue()
                        val snapshot=saved.takeIf {it.isNotBlank()}?.let {QueueSnapshotCodec.decode(it)}
                        if(snapshot==null||snapshot.ids.isEmpty())null else {
                            val ids=snapshot.ids
                            val selected=readQueue(ids,snapshot.index)
                            val remap=selected.originalIndices.withIndex().associate {it.value to it.index}
                            RestoredQueue(mediaItems(selected.items),selected.startIndex,
                                if(selected.keptRequestedStart)snapshot.positionMs else 0,
                                snapshot.shuffleOrder.takeIf {it.sorted()==ids.indices.toList()}?.mapNotNull {remap[it]}?.toIntArray(),
                                snapshot.repeat.coerceIn(0,2),snapshot.shuffle)
                        }
                    },
                    stillCurrent={restoreVersion==interactionVersion && player.mediaItemCount==0},
                    apply={saved->if(saved!=null) {
                        player.setMediaItems(saved.items,saved.index,saved.position)
                        saved.shuffleOrder?.let {player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(it,0L))}
                        player.repeatMode=saved.repeat;player.shuffleModeEnabled=saved.shuffled
                        // Keep the restored queue paused and lazy until the user's first play.
                    }})
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){ /* Bad/empty saved data cannot prevent a new queue. */ }
        }
        scope.launch {checkpointPolling.collectLatest {playing->if(playing)while(isActive) {
            delay(5000)
            if(player.isPlaying){history.checkpoint();checkpointQueue()}
        }}}
        scope.launch {repository.audioSettings.settings.collect {settings->
            if(settings.autoplay!=audioSettings.autoplay)autoplayAttempt=""
            audioSettings=settings;decks.configure(settings);updatePolling()
        }}
        scope.launch {autoplayPolling.collectLatest {enabled->if(enabled)while(isActive) {
            delay(2000)
            if(!audioSettings.autoplay || !decks.playWhenReady || player.mediaItemCount==0 ||
                player.playerError!=null || (!player.isPlaying && player.playbackState!=Player.STATE_ENDED) ||
                player.repeatMode!=Player.REPEAT_MODE_OFF || SystemClock.elapsedRealtime()<autoplayRetryAt)continue
            // Use traversal order rather than numeric index: shuffling can end on any item.
            val timeline=player.currentTimeline
            if(timeline.isEmpty)continue
            val next=timeline.getNextWindowIndex(player.currentMediaItemIndex,Player.REPEAT_MODE_OFF,player.shuffleModeEnabled)
            if(next!=C.INDEX_UNSET && timeline.getNextWindowIndex(next,Player.REPEAT_MODE_OFF,player.shuffleModeEnabled)!=C.INDEX_UNSET)continue
            val ids=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}
            val key=ids.joinToString("\u0000")
            if(autoplayAttempt==key) {decks.finishIfEnded();continue}
            autoplayAttempt=key
            val requestVersion=interactionVersion
            try {
                val recommendations=repository.flowCandidates(ids.toSet(),12)
                ensureActive()
                fun stillCurrent()=requestVersion==interactionVersion && audioSettings.autoplay && decks.playWhenReady &&
                    player.playerError==null && (player.isPlaying || player.playbackState==Player.STATE_ENDED) &&
                    (0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}==ids
                if(stillCurrent()) {
                    val additions=recommendations.filterNot {it.id in ids}.distinctBy {it.id}
                    if(additions.isNotEmpty()) {
                        val appended=guardedPlaybackMutation(
                            prepare={repository.stageQueue(additions,append=true);mediaItems(additions)},
                            stillCurrent=::stillCurrent,
                            apply={items->
                                val traversal=if(player.shuffleModeEnabled)DualDeckPlayer.shuffleTraversal(player) else null
                                decks.addMediaItems(items)
                                if(traversal!=null)player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(
                                    appendedShuffleTraversal(traversal,ids.size,items.size),0L))
                                if(player.playbackState==Player.STATE_ENDED && decks.playWhenReady) {
                                    decks.seekTo(ids.size,0L);decks.prepare()
                                }
                            })
                        if(!appended)autoplayAttempt=""
                    } else decks.finishIfEnded()
                } else autoplayAttempt=""
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {autoplayAttempt="";autoplayRetryAt=SystemClock.elapsedRealtime()+30_000}
        }}}
        scope.launch {
            var previousOffline:Boolean?=null
            val completed=repository.dao.downloads().map {rows->rows.mapNotNull {row->
                row.download.takeIf {it.state=="complete" && it.uri.isNotBlank()}?.let {OfflineAsset(it.trackId,it.uri,it.mimeType,it.generation)}
            }.associateBy {it.id}}.distinctUntilChanged()
            combine(repository.preferences.appearance.map {it.offline}.distinctUntilChanged(),completed) {offline,assets->offline to assets}
                .collectLatest {(offline,assets)->
                    if(offline && previousOffline!=true) {
                        interactionVersion++;decks.cancelTransition()
                        // Stop an already-open remote/HLS loader before any Room await. A
                        // paused ExoPlayer may keep buffering; stop retains its queue/position.
                        val current=player.currentMediaItem
                        if(current!=null && current.localConfiguration?.tag !is OfflineAsset &&
                            current.localConfiguration?.uri?.scheme !in listOf("content","file"))player.stop()
                    }
                    previousOffline=offline
                    try {refreshQueueSources(offline,assets)}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception) {
                        if(offline)decks.pause()
                        session.sendError(SessionError(SessionError.ERROR_IO,"Не удалось обновить доступность скачанной музыки"))
                    }
                }
        }
    }
    private fun updatePolling() {
        checkpointPolling.value=player.isPlaying
        autoplayPolling.value=audioSettings.autoplay && decks.playWhenReady &&
            (player.isPlaying || player.playbackState==Player.STATE_ENDED)
    }
    private suspend fun refreshQueueSources(offline:Boolean,assets:Map<String,OfflineAsset>) {
        // Do not drop a persistent download-state change merely because a seek/pause raced
        // its lookup: retry against the latest queue, while collectLatest cancels stale assets.
        while(currentCoroutineContext().isActive && player.mediaItemCount>0) {
            val requestVersion=interactionVersion
            val ids=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}
            val applied=guardedPlaybackMutation(
                prepare={mediaItems(repository.queueTracks(ids).values.toList()).associateBy {it.mediaId}},
                stillCurrent={requestVersion==interactionVersion &&
                    (0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}==ids},
                apply={refreshed->
                    val index=player.currentMediaItemIndex;val position=player.currentPosition;val intent=player.playWhenReady
                    val currentAsset=player.currentMediaItem?.localConfiguration?.tag as? OfflineAsset
                    val includeCurrent=offline || !player.isPlaying || (currentAsset!=null && assets[currentAsset.id]!=currentAsset)
                    val items=(0 until player.mediaItemCount).map(player::getMediaItemAt)
                    val replacements=planQueueSourceRefresh(items,refreshed,index,includeCurrent) {it.mediaId}
                    if(replacements.isNotEmpty()) {
                        decks.cancelTransition()
                        val wasPrepared=player.playbackState!=Player.STATE_IDLE
                        val order=DualDeckPlayer.shuffleTraversal(player)
                        replacements.forEach {player.replaceMediaItem(it.index,it.item)}
                        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order,0L))
                        if(replacements.any {it.index==index}) {
                            player.seekTo(index,position)
                            if(wasPrepared || intent)player.prepare()
                            player.playWhenReady=intent
                        }
                    }
                    val current=player.currentMediaItem
                    if(offline && current?.localConfiguration?.tag !is OfflineAsset &&
                        current?.localConfiguration?.uri?.scheme !in listOf("content","file"))decks.pause()
                })
            if(applied)return
            delay(50)
        }
    }
    private suspend fun readQueue(ids:List<String>,start:Int):QueueSelection<Track> = withContext(Dispatchers.IO) {
        selectQueue(ids,repository.queueTracks(ids),start)
    }
    private fun <T> resolveFuture(controller:MediaSession.ControllerInfo,replacesQueue:Boolean,block:suspend ()->T):ListenableFuture<T> {
        val future=SettableFuture.create<T>()
        // A pending selection is guarded by the selection version: only a newer
        // selection supersedes it. A transport command never does.
        val requestVersion=selectionVersion
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try {
                val result=block();ensureActive()
                if(replacesQueue && requestVersion!=selectionVersion)future.cancel(false) else future.set(result)
            }
            catch(cancelled:CancellationException) {
                // The selection was abandoned: the controller must learn that the queue
                // it asked for will not be installed. A superseded selection is not an
                // error: the newer one takes its place.
                if(requestVersion==selectionVersion && scope.isActive)
                    session.sendError(controller,SessionError(SessionError.ERROR_INVALID_STATE,"Не удалось подготовить очередь. Повторите выбор трека"))
                future.cancel(false);throw cancelled
            }
            catch(error:Exception) {
                // A raw exception message is never forwarded: it can carry a URL, a
                // file path or a SQL fragment. Only the app's own text is shown.
                session.sendError(controller,SessionError(SessionError.ERROR_BAD_VALUE,
                    failureText(error,"Не удалось подготовить очередь. Повторите выбор трека")))
                future.setException(error)
            }
        }
        if(replacesQueue) {pendingSet=job;selectionGate.begin(future)}
        future.addListener({
            if(future.isCancelled)job.cancel()
            if(replacesQueue)selectionGate.complete(future)
        },MoreExecutors.directExecutor())
        job.invokeOnCompletion {cause->if(cause is CancellationException)future.cancel(false)}
        job.start()
        return future
    }
    /** The failure is reported only when no automatic recovery can hide it. */
    private fun reportPlaybackError(error:PlaybackException) {
        // Send a plain string as well: a remote controller need not reconstruct
        // the app-specific exception class when Media3 parcels the cause chain.
        mediaSessionError(error)?.let {session.sendError(SessionError(SessionError.ERROR_IO,it))}
    }
    /**
     * The refusal is reported to the controller that asked for the play; the
     * other controllers are not told about a command they did not send.
     */
    private fun reportRefusal(reason:String) {
        val controller=lastPlayController
        lastPlayController=null
        val error=SessionError(SessionError.ERROR_INVALID_STATE,reason)
        if(controller!=null&&session.connectedControllers.any {it==controller}) {
            val delivered=runCatching {session.sendError(controller,error)}.isSuccess
            if(delivered)return
        }
        runCatching {session.sendError(error)}
    }
    private suspend fun mediaItems(tracks:List<Track>):List<MediaItem> {
        val completed=tracks.map {it.id}.chunked(900).flatMap {repository.dao.completedDownloads(it)}.associateBy {it.trackId}
        if(completed.isNotEmpty())withContext(Dispatchers.IO){app.downloads.storage}
        return tracks.map {trackMediaItem(it,completed[it.id])}
    }
    private fun checkpointQueue(force:Boolean=false) {
        val snapshot=currentQueueSnapshot()
        if(!checkpoint.shouldWrite(snapshot,force))return
        app.saveQueue(QueueSnapshotCodec.encode(snapshot))
    }
    private fun currentQueueSnapshot():QueueSnapshot {
        val ids=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}
        val order=player.shuffleOrder
        val shuffled=mutableListOf<Int>();var index=order.firstIndex
        while(index!=C.INDEX_UNSET && shuffled.size<player.mediaItemCount){shuffled+=index;index=order.getNextIndex(index)}
        return QueueSnapshot(ids,player.currentMediaItemIndex,player.currentMediaItem?.mediaId.orEmpty(),
            player.repeatMode,player.shuffleModeEnabled,shuffled,player.currentPosition)
    }
    override fun onGetSession(controllerInfo:MediaSession.ControllerInfo)=session
    override fun onTaskRemoved(rootIntent:Intent?) {if(!isPlaybackOngoing)stopSelf()}
    override fun onDestroy() {
        destroyed=true
        history.playing(false);checkpointQueue(true);scope.cancel();session.release();decks.release()
        super.onDestroy()
    }

    companion object {
        /**
         * The playback position is persisted on a much slower cadence than the
         * queue itself. The checkpoint runs every 5 s, so a 5 s step would write
         * on every tick: the settings file was rewritten ~12 times a minute
         * (REG P2-01). The position may be up to half a minute stale after a
         * crash; the queue identity is always saved at once.
         */
        private const val POSITION_STEP_MS=30_000L
    }
}
