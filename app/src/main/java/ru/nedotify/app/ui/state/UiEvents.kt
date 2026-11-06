package ru.nedotify.app.ui.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide delivery of platform requests and notices; contains no feature data. */
class UiEvents {
    val message = MutableStateFlow<String?>(null)
    val requestedRoute = MutableStateFlow<String?>(null)
    val requestNotifications = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestLocalFile = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val requestAvatar = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openExternalUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    fun postMessage(text: String) { message.value = text }
    fun acknowledgeMessage(text: String) { if (message.value == text) message.value = null }
}
