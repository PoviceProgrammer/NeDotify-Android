package ru.nedotify.app.ui.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.nedotify.app.data.*
import ru.nedotify.app.ui.*

class SettingsViewModel(application:Application,savedState:SavedStateHandle):FeatureViewModel(application,savedState) {
    val appearance=repository.preferences.appearance.stateIn(viewModelScope,SharingStarted.Eagerly,Appearance())
    val audioSettings=repository.audioSettings.settings.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),AudioSettings())
    val audioStatus=ru.nedotify.app.player.AudioRuntime.status
    val audioFailure=repository.audioSettings.writeFailure
    val preferenceReset=repository.preferences.pendingReset
    val pendingResets=repository.preferences.pendingResets
    fun acknowledgePreferenceReset(reset:PreferenceReset) {if(repository.preferences.pendingReset.value==reset)repository.preferences.acknowledgeReset()}
    fun mode(mode:String)=task {repository.preferences.mode(mode)}
    fun glass(value:Boolean)=task {repository.preferences.glass(value)}
    fun updateAudio(transform:(AudioSettings)->AudioSettings)=task {repository.audioSettings.update(transform)}
    fun accent(value:String)=task {repository.preferences.accent(value)}
    fun background(value:Boolean)=task {repository.preferences.background(value)}
    fun lyricsAutoScroll(value:Boolean)=task {repository.preferences.lyricsAutoScroll(value)}
    fun offline(value:Boolean)=task {repository.offline(value)}
}
