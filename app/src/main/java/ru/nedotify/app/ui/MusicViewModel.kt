package ru.nedotify.app.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.*
import androidx.media3.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.nedotify.app.NeDotifyApp
import ru.nedotify.app.data.*
import ru.nedotify.app.player.PlaybackService
import ru.nedotify.app.player.AudioRuntime
import ru.nedotify.app.player.playbackQueue
import ru.nedotify.app.player.trackMediaItem

/**
 * One row of the queue. An unchanged row keeps its instance between emissions, so the
 * now-playing mirror's equality check stays a pointer comparison.
 */
data class QueueRow(val id:String,val title:String,val artist:String="",val artwork:String="",val durationMs:Long=0) {
    /** The rows used to be pairs; the player screen still reads .first and .second. */
    val first get()=id
    val second get()=title
}
data class NowPlaying(val id:String="",val title:String="",val artist:String="",val artwork:String="",
    val playing:Boolean=false,val buffering:Boolean=false,val duration:Long=0,
    val shuffle:Boolean=false,val repeat:Int=0,val activeIndex:Int=-1,val queue:List<QueueRow> = emptyList(),
    val playWhenReady:Boolean=false,val error:String?=null)
data class SearchState(val busy:Boolean=false,val tracks:List<Track> = emptyList(),val error:String?=null)
data class ReportState(val busy:Boolean=true,val report:ListeningReport?=null,val error:String?=null)
data class ProfileSaveState(val busy:Boolean=false,val error:String?=null,val revision:Long=0)
data class LyricsState(val trackId:String="",val loading:Boolean=false,val plain:String="",
    val lines:List<LrcLine> = emptyList(),val error:String?=null,val instrumental:Boolean=false,val source:String="")

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicViewModel(application:Application,private val savedState:SavedStateHandle):AndroidViewModel(application) {
    /** The application context; the inherited property is private. */
    private val appContext:Application=application
    val repository=(application as NeDotifyApp).repository
    private val downloadsManager=(application as NeDotifyApp).downloads
    val discovery=DiscoveryController(repository,viewModelScope)
    val audioSettings=repository.audioSettings.settings.stateIn(viewModelScope,SharingStarted.Eagerly,AudioSettings())
    val audioStatus=AudioRuntime.status
    val connections=repository.providers.status
    val openExternalUrl=MutableSharedFlow<String>(extraBufferCapacity=1)
    private val _catalog=MutableStateFlow(CatalogBrowserState())
    val catalog=_catalog.asStateFlow()
    private var catalogJob:Job?=null
    val catalogImports=CatalogImportController.forRepository(repository)
    private var catalogGeneration=0L
    private var flowJob:Job?=null
    private var catalogPlayback=false
    val downloads=repository.dao.downloads().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val downloadStates=downloads.map {rows->rows.associate {it.download.trackId to it.download.state}}.distinctUntilChanged()
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyMap())
    val requestNotifications=MutableSharedFlow<Unit>(extraBufferCapacity=1)
    val requestLocalFile=MutableSharedFlow<String>(extraBufferCapacity=1)
    val requestAvatar=MutableSharedFlow<Unit>(extraBufferCapacity=1)
    val tracks=repository.dao.observeTracks().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val recent=repository.dao.recent().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val playlists=repository.dao.playlists().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val favoriteCount=repository.dao.observeFavoriteCount().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    val appearance=repository.preferences.appearance.stateIn(viewModelScope,SharingStarted.Eagerly,Appearance())
    val profile=repository.preferences.profile.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),UserProfile())
    val searchHistory=repository.preferences.searchHistory.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val selectedPlaylistId=savedState.getStateFlow("playlist","")
    val libraryFilter=savedState.getStateFlow("library_filter","all")
    private val _reportPeriod=MutableStateFlow(runCatching {ReportPeriod.valueOf(savedState.get<String>("report_period")?:"WEEK")}.getOrDefault(ReportPeriod.WEEK))
    val reportPeriod=_reportPeriod.asStateFlow()
    private val reportRetry=MutableStateFlow(0)
    private val reportClock=flow {
        while(currentCoroutineContext().isActive) {
            emit(System.currentTimeMillis() to java.time.ZoneId.systemDefault());delay(60_000)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val report=reportPeriod.flatMapLatest {period->
        combine(reportRetry,reportClock) {_,time->time}
            .flatMapLatest {time->ListeningReports(repository.dao).observe(period,time.first,time.second)
                .map {ReportState(busy=false,report=it)}
                .catch {error->emit(ReportState(busy=false,error=error.message?:"Не удалось загрузить отчёт"))}}
            .onStart {emit(ReportState())}
    }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),ReportState())
    val playback=MutableStateFlow(NowPlaying())
    val positionMs=MutableStateFlow(0L)
    /** The error the player screen renders: the media error or a refused command. */
    private val playerErrors=PlayerErrorState()
    /** The media error that was already shown, so it is not shown twice. */
    private var shownMediaError:String?=null
    val search=MutableStateFlow(SearchState())
    val message=MutableStateFlow<String?>(null)
    private val _preferenceReset=MutableStateFlow<PreferenceReset?>(null)
    val preferenceReset=_preferenceReset.asStateFlow()
    val requestedRoute=MutableStateFlow<String?>(null)
    private val _profileSave=MutableStateFlow(ProfileSaveState())
    val profileSave=_profileSave.asStateFlow()
    private val _lyricsState=MutableStateFlow(LyricsState())
    val lyricsState=_lyricsState.asStateFlow()
    private val _backupBusy=MutableStateFlow(false)
    val backupBusy=_backupBusy.asStateFlow()
    private val _backupStatus=MutableStateFlow("")
    val backupStatus=_backupStatus.asStateFlow()
    private val _backupPreview=MutableStateFlow<BackupSummary?>(null)
    val backupPreview=_backupPreview.asStateFlow()
    private val _backupError=MutableStateFlow<String?>(null)
    val backupError=_backupError.asStateFlow()
    private val _backupNotice=MutableStateFlow("")
    val backupNotice=_backupNotice.asStateFlow()
    private var pendingBackup:BackupPreview?=null
    private val attachingLocal=mutableSetOf<String>()
    @OptIn(ExperimentalCoroutinesApi::class)
    val playlistSelection=selectedPlaylistId.flatMapLatest {id->
        if(id.isBlank())flowOf(null) else repository.dao.observePlaylist(id)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    private var controller:MediaController?=null
    private var cleared=false
    private var searchJob:Job?=null
    private data class PreparedPlayback(val player:MediaController,val items:List<Track>,val trackId:String)
    private val playRequests=PlaybackPreparation<PreparedPlayback>(viewModelScope)
    private var lyricsJob:Job?=null
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
                    playerErrors.onCommandRefused(playback.value.id,text)
                    message.value=text
                    publishPlayerError()
                }
                override fun onDisconnected(controller:MediaController) {
                    this@MusicViewModel.controller=null
                    message.value="Плеер недоступен. Повторите попытку"
                    // The controller is dead: a new one takes its place.
                    connector.reconnect()
                }
            }).buildAsync()
        },
        release={MediaController.releaseFuture(it)},
        onBuilt={built->built.addListener({
            try {
                val created=built.get()
                if(cleared) {MediaController.releaseFuture(built);return@addListener}
                controller=created
                created.addListener(listener)
                update(created,true)
            } catch(error:Exception) {
                message.value="Не удалось подключиться к плееру"
            }
        },ContextCompat.getMainExecutor(appContext))})
    private fun connect() {if(!cleared)connector.connect()}
    init {
        viewModelScope.launch {repository.preferences.pendingReset.collect {_preferenceReset.value=it}}
        viewModelScope.launch {downloadsManager.errors.collect {message.value=it}}
        task {repository.providers.initialize()}
        viewModelScope.launch {catalogImports.state.collect {state->
            applyCatalogImportState(state)
            state.message?.let {message.value=it;catalogImports.clearMessage()}
        }}
        viewModelScope.launch {discovery.message.collect {value->if(value!=null) {message.value=value;discovery.clearMessage()}}}
        connect()
        viewModelScope.launch {
            combine(positionMs.subscriptionCount,playback.map {it.playing}.distinctUntilChanged()){count,playing->count>0 && playing}
                .collectLatest {poll->if(poll)while(currentCoroutineContext().isActive) {
                    controller?.let {positionMs.value=it.currentPosition.coerceAtLeast(0)};delay(500)
                }}
        }
    }
    private fun update(player:Player,rebuildQueue:Boolean=false) {
        val item=player.currentMediaItem
        val id=item?.mediaId.orEmpty()
        if(id!=playback.value.id) {
            lyricsJob?.cancel();_lyricsState.value=LyricsState(trackId=id)
            // A refusal of the previous track must not follow the user.
            playerErrors.onTrackChanged(id)
        }
        positionMs.value=player.currentPosition.coerceAtLeast(0)
        // The error is part of the player state: the message channel shows it to
        // the user, and the player screen renders it (area A7).
        val mediaError=player.playerError?.let {playbackFailureMessage(it)}
        playerErrorMessage(player.playerError,shownMediaError)?.let {message.value=it}
        shownMediaError=mediaError
        playback.value=NowPlaying(id,item?.mediaMetadata?.title?.toString().orEmpty(),
            item?.mediaMetadata?.artist?.toString().orEmpty(),item?.mediaMetadata?.artworkUri?.toString().orEmpty(),
            player.isPlaying,player.playbackState==Player.STATE_BUFFERING,
            player.duration.takeIf { it!=C.TIME_UNSET }?.coerceAtLeast(0)?:0,player.shuffleModeEnabled,player.repeatMode,
            player.currentMediaItemIndex,
            if(rebuildQueue)queueRows(player) else playback.value.queue,player.playWhenReady,
            playerErrors.errorFor(id,mediaError))
    }
    /** Publish the player error without waiting for the next player event. */
    private fun publishPlayerError() {
        val id=playback.value.id
        val error=playerErrors.errorFor(id,controller?.playerError?.let{playbackFailureMessage(it)})
        if(error!=playback.value.error)playback.value=playback.value.copy(error=error)
    }
    /**
     * The queue rows are reused between emissions: an unchanged row keeps its instance and
     * an unchanged queue keeps its list, so the mirror's equality check stays cheap.
     */
    private fun queueRows(player:Player):List<QueueRow> {
        val count=player.mediaItemCount
        val previous=playback.value.queue
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
    fun search(query:String,source:String) {
        searchJob?.cancel()
        if(query.isBlank()) { search.value=SearchState();return }
        searchJob=viewModelScope.launch {
            search.value=search.value.copy(busy=true,error=null)
            delay(350)
            try { search.value=SearchState(tracks=repository.search(query.trim(),source)) }
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){search.value=search.value.copy(busy=false,error=error.message?:"Ошибка поиска")}
        }
    }
    fun play(track:Track,queue:List<Track>) {
        // A new selection supersedes the preparation that is running.
        commands.run(PlaybackCommand.SELECT) {playSelected(track,queue)}
    }
    private suspend fun preparePlayback(track:Track,queue:List<Track>):PreparedPlayback {
        val player=controller?:throw SourceFailure("Плеер подключается. Повторите действие")
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
    private fun cancelPendingPlayback() {playRequests.cancel();flowJob?.cancel()}
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
    private suspend fun cancelPendingPlaybackAndWait() {playRequests.cancelAndAwait();flowJob?.cancel()}
    fun toggle() {
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
    fun acknowledgeMessage(text:String) {
        if(message.value==text)message.value=null
    }
    fun acknowledgePreferenceReset(reset:PreferenceReset) {
        if(repository.preferences.pendingReset.value==reset)repository.preferences.acknowledgeReset()
    }
    fun deleteTrack(track:Track)=task {
        // The pending preparation writes the track into the library. The delete
        // waits for it, so the track cannot come back after the user removed it.
        // This is the INVALIDATE case: it drops the selection and then waits
        // for the dropped work to release the library.
        cancelPendingPlaybackAndWait()
        controller?.let {player->
            (player.mediaItemCount-1 downTo 0).filter {player.getMediaItemAt(it).mediaId==track.id}
                .forEach(player::removeMediaItem)
        }
        downloadsManager.removeNow(track.id)
        repository.deleteTrack(track.id)
        releaseDocumentIfUnused(track.localUri)
        message.value="Трек удалён из библиотеки"
    }
    fun favorite(track:Track)=task {repository.dao.favorite(track)}
    fun download(track:Track)=task {requestNotifications.tryEmit(Unit);downloadsManager.enqueue(track)}
    fun pauseDownload(id:String)=task {downloadsManager.pause(id)}
    fun resumeDownload(id:String)=task {requestNotifications.tryEmit(Unit);downloadsManager.resume(id)}
    fun removeDownload(id:String)=task {
        if(playback.value.id==id)controller?.pause()
        downloadsManager.remove(id)
        val track=repository.dao.track(id) ?: return@task
        // The download is gone: every slot of this track falls back to the track's own
        // source. A hand-built item without a URI would make the source factory throw.
        val item=trackMediaItem(track,null)
        controller?.let {player->(0 until player.mediaItemCount).filter {player.getMediaItemAt(it).mediaId==id}
            .forEach {index->player.replaceMediaItem(index,item)}}
        message.value="Скачанный файл удалён"
    }
    fun importUris(uris:List<Uri>)=task {
        if(uris.isEmpty())return@task
        try {
            val result=repository.importUris(uris)
            message.value="Добавлено файлов: ${result.imported}"+if(result.failed>0)". Не удалось прочитать: ${result.failed}. Проверьте доступ к файлам" else ""
        } finally {withContext(NonCancellable) {uris.forEach {releaseDocumentIfUnused(it.toString())}}}
    }
    fun selectLocalFile(track:Track) {
        if(track.source=="local" && track.id !in attachingLocal)requestLocalFile.tryEmit(track.id)
    }
    fun attachLocalFile(trackId:String,uri:Uri) {
        if(!attachingLocal.add(trackId))return
        viewModelScope.launch {
            var previousUri=""
            try {
                previousUri=repository.dao.track(trackId)?.localUri.orEmpty()
                repository.attachLocal(trackId,uri)
                val track=repository.dao.track(trackId)
                if(track==null){message.value="Трек не найден в библиотеке";return@launch}
                val saved=repository.dao.download(trackId)?.takeIf {it.state=="complete"&&it.uri.isNotBlank()}
                // The replacement item is built by the shared factory: it carries the URI,
                // the offline tag and the metadata.
                val item=trackMediaItem(track,saved)
                var replaced=0
                controller?.let {player->(0 until player.mediaItemCount).filter {player.getMediaItemAt(it).mediaId==trackId}
                    .forEach {index->player.replaceMediaItem(index,item);replaced++}}
                message.value=if(replaced>0)"Аудиофайл связан с треком, очередь обновлена" else "Аудиофайл связан с треком"
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){message.value=error.message?:"Не удалось связать аудиофайл с треком"}
            finally {attachingLocal.remove(trackId);withContext(NonCancellable) {
                releaseDocumentIfUnused(previousUri);releaseDocumentIfUnused(uri.toString())
            }}
        }
    }
    fun exportBackup(uri:Uri)=backupTask("Сохраняем резервную копию…") {
        val summary=repository.backups.export(uri)
        message.value="Копия сохранена: ${summary.tracks} треков, ${summary.playlists} плейлистов"+if(summary.droppedSessions>0)". В копию не вошли старые прослушивания: ${summary.droppedSessions}; история на устройстве сохранена" else ""
    }
    fun inspectBackup(uri:Uri)=backupTask("Проверяем резервную копию…") {
        _backupNotice.value=""
        discardPendingBackup()
        _backupPreview.value=null
        _backupError.value=null
        val backup=repository.backups.inspectPreview(uri)
        pendingBackup=backup
        _backupPreview.value=backup.summary
    }
    fun inspectDesktopBackup(uris:List<Uri>,origin:String)=backupTask("Проверяем копию библиотеки ПК…") {
        discardPendingBackup();_backupPreview.value=null;_backupNotice.value=""
        val preview=repository.desktopImport.inspectDocuments(uris,origin)
        pendingBackup=repository.backups.stageDesktopImport(preview.backup,preview.notice);_backupPreview.value=preview.backup.summary;_backupNotice.value=preview.notice
    }
    fun dismissBackupPreview() {
        if(_backupBusy.value)return
        discardPendingBackup()
        _backupPreview.value=null
        _backupError.value=null
        _backupNotice.value=""
    }
    fun restoreBackup() {
        val backup=pendingBackup?:return
        backupTask("Объединяем библиотеку…") {
            val summary=repository.backups.restore(backup)
            discardPendingBackup()
            _backupPreview.value=null
            message.value="Копия обработана: ${summary.tracks} треков, ${summary.playlists} плейлистов"
        }
    }
    private fun backupTask(status:String,block:suspend ()->Unit) {
        if(_backupBusy.value)return
        _backupBusy.value=true
        _backupStatus.value=status
        _backupError.value=null
        viewModelScope.launch {
            try {block()}
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:OutOfMemoryError){
                // An allocation failure is an `Error`, not an `Exception`: it used to escape
                // every `catch (Exception)` and killed the process.
                val text="Копия слишком велика для памяти устройства. Повторите действие с меньшей копией"
                _backupError.value=text
                message.value=text
            }
            catch(error:Exception){
                val text=error.message?:"Не удалось обработать резервную копию. Повторите действие"
                _backupError.value=text
                message.value=text
            }
            finally {_backupBusy.value=false;_backupStatus.value=""}
        }
    }
    fun scan()=task {repository.scanLocal()}
    fun createPlaylist(name:String)=task {repository.createPlaylist(name)}
    fun addToPlaylist(id:String,track:Track)=task {repository.dao.addToPlaylist(id,track)}
    fun removeFromPlaylist(id:String,track:Track)=task {repository.dao.removeFromPlaylist(id,track.id)}
    fun setLibraryFilter(value:String) {if(value in setOf("all","favorites","local","downloads","playlist","playlists","smart"))savedState["library_filter"]=value}
    fun openPlaylist(id:String) {savedState["playlist"]=id;setLibraryFilter("playlist")}
    fun renamePlaylist(id:String,name:String)=task {
        if(name.isBlank())throw SourceFailure("Введите название плейлиста")
        repository.dao.renamePlaylist(id,name.trim().take(120))
    }
    fun deletePlaylist(id:String)=task {repository.dao.deletePlaylist(id);if(savedState.get<String>("playlist")==id)savedState["playlist"]=""}
    fun mode(mode:String)=task {repository.preferences.mode(mode)}
    fun glass(value:Boolean)=task {repository.preferences.glass(value)}
    fun updateAudio(transform:(AudioSettings)->AudioSettings)=task {repository.audioSettings.update(transform)}
    fun startFlow() {
        if(flowJob?.isActive==true)return
        // The flow is a new selection: it supersedes a running preparation.
        commands.run(PlaybackCommand.SELECT) {
        flowJob=task {
            val queue=discovery.selectFlow(emptySet(),30)
            currentCoroutineContext().ensureActive()
            if(queue.isEmpty())throw SourceFailure("Для Flow пока нет доступных треков. Добавьте музыку или выполните поиск")
            repository.audioSettings.update {it.copy(autoplay=true)}
            currentCoroutineContext().ensureActive();playSelected(queue.first(),queue)
        }
        }
    }
    fun configureSpotify(clientId:String)=task {repository.providers.configureSpotify(clientId)}
    private suspend fun requireOnline() {if(repository.preferences.appearance.first().offline)throw SourceFailure("Выключите офлайн-режим для подключения к сервису")}
    fun beginSpotifyLogin()=task {requireOnline();openExternalUrl.emit(repository.providers.beginSpotifyLogin())}
    fun handleSpotifyRedirect(uri:String) {
        requestedRoute.value="settings"
        task {requireOnline();repository.providers.handleSpotifyRedirect(uri);message.value="Spotify подключён"}
    }
    fun saveYandexToken(token:String)=task {requireOnline();repository.providers.saveYandexToken(token);message.value="Яндекс Музыка подключена"}
    fun logoutProvider(provider:String)=task {
        if(catalogImports.state.value.provider==provider)catalogImports.pause()
        if(_catalog.value.provider==provider)dismissCatalog()
        repository.logoutProvider(provider)
    }
    fun openCatalog(provider:String) {
        if(provider !in setOf("spotify","yandex"))return
        if(catalogPlayback)playRequests.cancel()
        catalogGeneration++
        catalogJob?.cancel()
        _catalog.value=CatalogBrowserState(provider=provider,title=if(provider=="spotify")"Spotify" else "Яндекс Музыка")
        applyCatalogImportState(catalogImports.state.value)
        catalogPlaylists()
    }
    fun dismissCatalog() {if(catalogPlayback)playRequests.cancel();catalogGeneration++;catalogJob?.cancel();_catalog.value=CatalogBrowserState()}
    fun catalogQuery(query:String) {_catalog.value=_catalog.value.copy(query=query.take(200))}
    fun catalogSearch()=loadCatalog("Результаты поиска") {provider->
        val query=_catalog.value.query.trim()
        if(query.isBlank())throw SourceFailure("Введите название трека или исполнителя")
        if(provider=="spotify")repository.providers.spotify.search(query) else repository.providers.yandex.search(query)
    }
    fun catalogLiked()=loadCatalog("Любимые треки") {provider->
        if(provider=="spotify")repository.providers.spotify.likedTracks() else repository.providers.yandex.likedTracks()
    }
    fun catalogPlaylists() {
        val provider=_catalog.value.provider
        if(provider.isBlank() || _catalog.value.importing)return
        catalogJob?.cancel();_catalog.value=_catalog.value.copy(busy=true,error=null,tracks=emptyList(),playlists=emptyList(),title="Мои плейлисты",hasSearched=false)
        catalogJob=viewModelScope.launch {
            try {
                requireOnline()
                val rows=if(provider=="spotify")repository.providers.spotify.playlists() else repository.providers.yandex.playlists()
                ensureActive()
                if(_catalog.value.provider==provider)_catalog.value=_catalog.value.copy(busy=false,playlists=rows)
            } catch(error:CancellationException){throw error}
            catch(error:Exception){ensureActive();if(_catalog.value.provider==provider)_catalog.value=_catalog.value.copy(busy=false,error=error.message?:"Не удалось открыть каталог")}
        }
    }
    fun catalogPlaylist(playlist:CatalogPlaylist)=loadCatalog(playlist.title) {provider->
        if(playlist.provider!=provider)throw SourceFailure("Источник плейлиста изменился")
        if(provider=="spotify")repository.providers.spotify.playlistTracks(playlist.id) else repository.providers.yandex.playlistTracks(playlist.id)
    }
    private fun loadCatalog(title:String,fetch:suspend(String)->List<CatalogTrack>) {
        val provider=_catalog.value.provider
        if(provider.isBlank() || _catalog.value.importing)return
        catalogJob?.cancel();_catalog.value=_catalog.value.copy(busy=true,error=null,tracks=emptyList(),playlists=emptyList(),title=title,hasSearched=true)
        catalogJob=viewModelScope.launch {
            try {requireOnline();val rows=fetch(provider);ensureActive();if(_catalog.value.provider==provider)_catalog.value=_catalog.value.copy(busy=false,tracks=rows)}
            catch(error:CancellationException){throw error}
            catch(error:Exception){ensureActive();if(_catalog.value.provider==provider)_catalog.value=_catalog.value.copy(busy=false,error=error.message?:"Не удалось открыть треки")}
        }
    }
    private suspend fun playableCatalogTrack(item:CatalogTrack):Track {
        requireOnline()
        if(item.provider=="yandex" && connections.value.yandexConnected)return repository.providers.yandex.playable(item)
        return CatalogMatcher.resolve(item,listOf(repository.soundCloud::search,repository.youtube::search)) {repository.resolve(it);Unit}
    }
    fun playCatalogTrack(item:CatalogTrack) {
        if(_catalog.value.busy || _catalog.value.importing || _catalog.value.provider!=item.provider)return
        // A catalogue track is a new selection: it supersedes a running preparation.
        commands.run(PlaybackCommand.SELECT) {
        val generation=catalogGeneration
        _catalog.value=_catalog.value.copy(busy=true,progress="Подбираем доступное аудио…",error=null)
        catalogPlayback=true
        playRequests.submit(load={
            val track=sourceDeadline(20_000,"Источник не ответил. Повторите поиск") {playableCatalogTrack(item)}
            preparePlayback(track,listOf(track))
        },onReady=::startPrepared,onFailure={error->
            updateCatalog(generation) {it.copy(error=error.message?:"Аудио для этой записи не найдено")}
        },onComplete={catalogPlayback=false;updateCatalog(generation) {it.copy(busy=false,progress="")}})
        }
    }
    fun importCatalog() {
        val state=_catalog.value
        if(state.importing || state.busy || state.tracks.isEmpty())return
        if(catalogImports.state.value.let {it.importing || it.paused}) {
            _catalog.value=state.copy(error="Сначала продолжите или завершите текущий импорт в настройках")
            return
        }
        catalogImports.start(state.provider,state.title,state.tracks)
    }
    fun pauseCatalogImport() {catalogImports.pause()}
    fun resumeCatalogImport() {catalogImports.resume()}
    fun discardCatalogImport() {catalogImports.discard()}
    private fun applyCatalogImportState(state:CatalogImportState) {
        if(state.provider.isBlank() || state.provider!=_catalog.value.provider)return
        _catalog.value=_catalog.value.copy(importing=state.importing,importPaused=state.paused,
            progress=if(state.importing || state.paused)"${state.title}: добавлено ${state.completed}; недоступно ${state.unavailable}; всего ${state.total}" else "",
            error=state.error)
    }
    private fun updateCatalog(generation:Long,change:(CatalogBrowserState)->CatalogBrowserState) {
        if(generation==catalogGeneration)_catalog.value=change(_catalog.value)
    }
    fun accent(value:String)=task {repository.preferences.accent(value)}
    fun background(value:Boolean)=task {repository.preferences.background(value)}
    fun lyricsAutoScroll(value:Boolean)=task {repository.preferences.lyricsAutoScroll(value)}
    fun rememberSearch(query:String)=task {repository.preferences.rememberSearch(query)}
    fun removeSearch(query:String)=task {repository.preferences.removeSearch(query)}
    fun clearSearchHistory()=task {repository.preferences.clearSearchHistory()}
    fun updateProfile(name:String,bio:String) {
        if(_profileSave.value.busy)return
        val revision=_profileSave.value.revision
        val draft=ProfileDraft.of(name,bio)
        if(!draft.valid) {_profileSave.value=ProfileSaveState(error=draft.error,revision=revision);return}
        _profileSave.value=ProfileSaveState(busy=true,revision=revision)
        viewModelScope.launch {
            try {repository.preferences.profile(draft.name,draft.bio);_profileSave.value=ProfileSaveState(revision=revision+1)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){_profileSave.value=ProfileSaveState(error=error.message?:"Не удалось сохранить профиль",revision=revision)}
        }
    }
    fun selectAvatar() {requestAvatar.tryEmit(Unit)}
    fun removeAvatar()=task {
        val previous=repository.preferences.profile.first().avatarUri
        repository.preferences.removeAvatar();releaseDocumentIfUnused(previous)
    }
    fun attachAvatar(uri:Uri)=task {
        val previous=repository.preferences.profile.first().avatarUri
        try {withContext(Dispatchers.IO) {
            if(uri.scheme!="content")throw SourceFailure("Выберите изображение через системное окно")
            val resolver=repository.context.contentResolver
            if(resolver.getType(uri)?.startsWith("image/")!=true)throw SourceFailure("Выберите изображение")
            resolver.openAssetFileDescriptor(uri,"r")?.use {}?:throw SourceFailure("Не удалось открыть изображение")
            repository.preferences.avatar(uri.toString())
        }} finally {withContext(NonCancellable) {releaseDocumentIfUnused(previous);releaseDocumentIfUnused(uri.toString())}}
    }
    fun selectReportPeriod(period:ReportPeriod) {savedState["report_period"]=period.name;_reportPeriod.value=period}
    fun retryReport() {reportRetry.value++}
    fun offline(value:Boolean)=task {
        repository.offline(value)
        if(value) {
            // The offline mode invalidates the selection: a preparation that needs
            // the network can no longer be started.
            commands.runSuspending(PlaybackCommand.INVALIDATE) {
            catalogGeneration++;catalogJob?.cancel();catalogImports.pause()
            if(_catalog.value.provider.isNotBlank())_catalog.value=_catalog.value.copy(busy=false,importing=false,progress="",error="Включён офлайн-режим")
            searchJob?.cancel();lyricsJob?.cancel();search.value=SearchState()
            if(_lyricsState.value.loading)_lyricsState.value=LyricsState(playback.value.id,error="Для поиска текста нужно подключение к сети")
            val id=playback.value.id
            val needsNetwork=repository.dao.track(id)?.localUri.isNullOrBlank() && repository.dao.download(id)?.state!="complete"
            if(needsNetwork && playback.value.id==id)controller?.pause()
            }
        }
    }
    fun loadLyrics()=loadLyricsInternal(false)
    fun retryLyrics()=loadLyricsInternal(true)
    private fun loadLyricsInternal(forceRefresh:Boolean) {
        val id=playback.value.id
        val current=_lyricsState.value
        if(!forceRefresh && current.trackId==id && (current.loading || current.plain.isNotBlank() || current.lines.isNotEmpty() || current.instrumental))return
        lyricsJob?.cancel()
        if(id.isBlank()) {_lyricsState.value=LyricsState();return}
        _lyricsState.value=LyricsState(id,loading=true)
        lyricsJob=viewModelScope.launch {
            try {
                val stored=repository.track(id)?:throw SourceFailure("Трек отсутствует в библиотеке")
                val track=stored.copy(durationMs=stored.durationMs.takeIf {it>0}?:playback.value.duration)
                val cached=if(forceRefresh)null else repository.lyrics.cached(track)
                if(cached==null && repository.preferences.appearance.first().offline)throw SourceFailure("Для поиска текста нужно подключение к сети")
                val result=cached?:withTimeout(12_000) {repository.lyrics.lyrics(track,forceRefresh)}
                if(playback.value.id==id)_lyricsState.value=LyricsState(id,plain=result.plain,lines=result.lines,instrumental=result.instrumental,source=result.source)
            }
            catch(_:TimeoutCancellationException) {if(playback.value.id==id)_lyricsState.value=LyricsState(id,error="Источник текста не ответил. Повторите попытку")}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(playback.value.id==id)_lyricsState.value=LyricsState(id,error=if(appearance.value.offline)"Для поиска текста нужно подключение к сети" else "Не удалось получить текст. Повторите попытку")}
        }
    }
    private fun task(block:suspend ()->Unit)=viewModelScope.launch {
        try { block() } catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){message.value=error.message?:"Не удалось выполнить действие"}
    }
    private fun discardPendingBackup() {
        val preview=pendingBackup?:return
        pendingBackup=null
        (appContext as NeDotifyApp).ioScope.launch {repository.backups.discard(preview)}
    }
    /** Release only a grant changed by our operation, after all durable references are checked. */
    private suspend fun releaseDocumentIfUnused(value:String)=withContext(Dispatchers.IO) {
        if(!value.startsWith("content://"))return@withContext
        if(repository.preferences.profile.first().avatarUri==value)return@withContext
        if(repository.dao.allTracks().any {it.localUri==value || it.coverUrl==value})return@withContext
        runCatching {appContext.contentResolver.releasePersistableUriPermission(value.toUri(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        Unit
    }
    override fun onCleared() {
        cleared=true
        discardPendingBackup()
        cancelPendingPlayback()
        controller?.removeListener(listener)
        connector.close()
        controller=null
        super.onCleared()
    }
}
