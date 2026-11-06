package ru.nedotify.app.ui.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.nedotify.app.data.*
import ru.nedotify.app.ui.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadsViewModel(application:Application,savedState:SavedStateHandle):FeatureViewModel(application,savedState) {
    private val downloadsManager get()=app.downloads
    val downloads=repository.dao.downloads().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val downloadStates=downloads.map {rows->rows.associate {it.download.trackId to it.download.state}}.distinctUntilChanged()
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyMap())
    init {viewModelScope.launch {downloadsManager.errors.collect(events::postMessage)}}
    fun download(track:Track)=task {events.requestNotifications.tryEmit(Unit);downloadsManager.enqueue(track)}
    fun pauseDownload(id:String)=task {downloadsManager.pause(id)}
    fun resumeDownload(id:String)=task {events.requestNotifications.tryEmit(Unit);downloadsManager.resume(id)}
    fun removeDownload(id:String)=task {
        app.playbackConnection.pauseIfCurrent(id)
        downloadsManager.remove(id)
        repository.dao.track(id)?.let {app.playbackConnection.replaceTrack(it,null)}
        events.postMessage("Скачанный файл удалён")
    }
}
