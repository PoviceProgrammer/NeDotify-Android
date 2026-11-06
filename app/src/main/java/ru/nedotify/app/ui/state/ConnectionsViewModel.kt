package ru.nedotify.app.ui.state

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.nedotify.app.data.*
import ru.nedotify.app.ui.*

class ConnectionsViewModel(application:Application,savedState:SavedStateHandle):FeatureViewModel(application,savedState) {
    val connections=repository.providers.status
    private val _catalog=MutableStateFlow(CatalogBrowserState(provider=savedState.get<String>("catalog_provider").orEmpty(),query=savedState.get<String>("catalog_query").orEmpty()))
    val catalog=_catalog.asStateFlow()
    private var catalogJob:Job?=null
    val catalogImports=CatalogImportController.forRepository(repository)
    private var catalogGeneration=0L
    private var catalogPlayback=false
    init {
        task {repository.providers.initialize()}
        viewModelScope.launch {catalogImports.state.collect {state->
            applyCatalogImportState(state)
            state.message?.let {message.value=it;catalogImports.clearMessage()}
        }}
        viewModelScope.launch {repository.preferences.appearance.map {it.offline}.distinctUntilChanged().collect {offline->
            if(offline){app.playbackConnection.cancelCatalog(this@ConnectionsViewModel);catalogJob?.cancel();catalogImports.pause()
                if(_catalog.value.provider.isNotBlank())_catalog.value=_catalog.value.copy(busy=false,error="Включён офлайн-режим")}
        }}
        if(_catalog.value.provider.isNotBlank())catalogPlaylists()
    }
    fun configureSpotify(clientId:String)=task {repository.providers.configureSpotify(clientId)}
    private suspend fun requireOnline() {if(repository.preferences.appearance.first().offline)throw SourceFailure("Выключите офлайн-режим для подключения к сервису")}
    fun beginSpotifyLogin()=task {requireOnline();events.openExternalUrl.emit(repository.providers.beginSpotifyLogin())}
    fun handleSpotifyRedirect(uri:String) {
        events.requestedRoute.value="settings"
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
        savedState["catalog_provider"]=provider
        if(catalogPlayback)app.playbackConnection.cancelCatalog(this)
        catalogGeneration++
        catalogJob?.cancel()
        _catalog.value=CatalogBrowserState(provider=provider,title=if(provider=="spotify")"Spotify" else "Яндекс Музыка")
        applyCatalogImportState(catalogImports.state.value)
        catalogPlaylists()
    }
    fun dismissCatalog() {savedState["catalog_provider"]="";if(catalogPlayback)app.playbackConnection.cancelCatalog(this);catalogGeneration++;catalogJob?.cancel();_catalog.value=CatalogBrowserState()}
    fun catalogQuery(query:String) {savedState["catalog_query"]=query.take(200);_catalog.value=_catalog.value.copy(query=query.take(200))}
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
        val generation=catalogGeneration
        _catalog.value=_catalog.value.copy(busy=true,progress="Подбираем доступное аудио…",error=null)
        catalogPlayback=true
        app.playbackConnection.selectCatalog(this,
            load={sourceDeadline(20_000,"Источник не ответил. Повторите поиск"){playableCatalogTrack(item)}},
            onFailure={error->updateCatalog(generation){it.copy(error=error.message?:"Аудио для этой записи не найдено")}},
            onComplete={catalogPlayback=false;updateCatalog(generation){it.copy(busy=false,progress="")}})
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
    override fun onCleared() {app.playbackConnection.cancelCatalog(this);super.onCleared()}
}
