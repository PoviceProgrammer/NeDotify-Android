package ru.nedotify.app.ui.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.nedotify.app.data.*
import ru.nedotify.app.ui.*

class LyricsViewModel(application:Application,savedState:SavedStateHandle):FeatureViewModel(application,savedState) {
    private val playback get()=app.playbackConnection.playback
    private val _lyricsState=MutableStateFlow(LyricsState())
    val lyricsState=_lyricsState.asStateFlow()
    private var lyricsJob:Job?=null
    init {
        viewModelScope.launch {playback.map {it.id}.distinctUntilChanged().collect {id->
            lyricsJob?.cancel();_lyricsState.value=LyricsState(trackId=id)
        }}
        viewModelScope.launch {repository.preferences.appearance.map {it.offline}.distinctUntilChanged().collect {offline->
            if(offline && _lyricsState.value.loading){lyricsJob?.cancel();_lyricsState.value=LyricsState(playback.value.id,error="Для поиска текста нужно подключение к сети")}
        }}
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
            catch(_:Exception){if(playback.value.id==id)_lyricsState.value=LyricsState(id,error=if(repository.preferences.appearance.first().offline)"Для поиска текста нужно подключение к сети" else "Не удалось получить текст. Повторите попытку")}
        }
    }
}
