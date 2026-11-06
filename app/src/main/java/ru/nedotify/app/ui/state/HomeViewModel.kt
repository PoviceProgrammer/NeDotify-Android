package ru.nedotify.app.ui.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.nedotify.app.data.*
import ru.nedotify.app.ui.*

class HomeViewModel(application:Application,savedState:SavedStateHandle):FeatureViewModel(application,savedState) {
    val tracks=repository.dao.observeTracks().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val recent=repository.dao.recent().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val playlists=repository.dao.playlists().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val discovery=DiscoveryController(repository,viewModelScope)
    init {viewModelScope.launch {discovery.message.collect {value->if(value!=null){events.postMessage(value);discovery.clearMessage()}}}}
}
