package ru.nedotify.app.ui.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.nedotify.app.data.*
import ru.nedotify.app.ui.*

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LibraryViewModel(application:Application,savedState:SavedStateHandle):FeatureViewModel(application,savedState) {
    val tracks=repository.dao.observeTracks().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val _playlistsLoaded=MutableStateFlow(false)
    val playlistsLoaded:StateFlow<Boolean> = _playlistsLoaded.asStateFlow()
    val playlists=repository.dao.playlists().onEach {_playlistsLoaded.value=true}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val favoriteCount=repository.dao.observeFavoriteCount().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    val selectedPlaylistId=savedState.getStateFlow("playlist","")
    val libraryFilter=savedState.getStateFlow("library_filter","all")
    val sort=savedState.getStateFlow("library_sort","recent")
    val discovery=DiscoveryController(repository,viewModelScope)
    val playlistSelection=selectedPlaylistId.flatMapLatest {id->if(id.isBlank())flowOf(null) else repository.dao.observePlaylist(id)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    init {
        savedState.get<String>("smart_playlist")?.takeIf {it.isNotBlank()}?.let(discovery::openSmart)
        viewModelScope.launch {discovery.selectedSmartId.collect {savedState["smart_playlist"]=it.orEmpty()}}
        viewModelScope.launch {discovery.message.collect {value->if(value!=null){events.postMessage(value);discovery.clearMessage()}}}
    }
    fun setSort(value:String) {if(value in setOf("recent","title","artist"))savedState["library_sort"]=value}
    fun selectLocalFile(track:Track) {if(track.source=="local")events.requestLocalFile.tryEmit(track.id)}
    fun favorite(track:Track)=task {repository.dao.favorite(track)}
    fun deleteTrack(track:Track)=task {
        app.playbackConnection.prepareForRemoval(track.id)
        app.downloads.removeNow(track.id)
        repository.deleteTrack(track.id)
        releaseDocumentIfUnused(track.localUri)
        events.postMessage("Трек удалён из библиотеки")
    }
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
}
