package ru.nedotify.app.ui.state

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import ru.nedotify.app.NeDotifyApp

/** Shared construction/error delivery only. Each feature owns its own state and jobs. */
abstract class FeatureViewModel(application: Application, protected val savedState: SavedStateHandle) : AndroidViewModel(application) {
    protected val app = application as NeDotifyApp
    protected val repository get() = app.repository
    protected val events get() = app.uiEvents
    protected val message get() = events.message
    protected fun task(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { events.postMessage(error.message ?: "Не удалось выполнить действие") }
    }
    /** Release only a grant changed by this operation, after durable references are checked. */
    protected suspend fun releaseDocumentIfUnused(value: String) = withContext(Dispatchers.IO) {
        if (!value.startsWith("content://")) return@withContext
        if (repository.preferences.profile.first().avatarUri == value) return@withContext
        if (repository.dao.allTracks().any { it.localUri == value || it.coverUrl == value }) return@withContext
        runCatching { app.contentResolver.releasePersistableUriPermission(value.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Unit
    }
}
