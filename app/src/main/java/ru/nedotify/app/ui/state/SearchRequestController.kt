package ru.nedotify.app.ui.state

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.nedotify.app.data.Track
import ru.nedotify.app.ui.SearchState

/** Owns one active search route; only small parameters survive process recreation. */
class SearchRequestController(
    private val scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val fetch: suspend (String, String, suspend (String) -> Unit) -> List<Track>,
    offline: Flow<Boolean> = flowOf(false),
    private val debounceMs: Long = 350,
) {
    val query = savedState.getStateFlow("query", "")
    val source = savedState.getStateFlow("source", "all")
    val category = savedState.getStateFlow("category", "tracks")
    val selectedGroupKey = savedState.getStateFlow("search_group", "")
    private val output = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = output.asStateFlow()
    private var active = false
    private var offlineNow = false
    private var generation = 0L
    private var pending: Job? = null

    init { scope.launch {
        offline.distinctUntilChanged().collect { value ->
            offlineNow = value
            if (value && source.value != "local") {
                savedState["source"] = "local"
                savedState["search_group"] = ""
                request()
            }
        }
    } }
    fun onActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) request() else cancel()
    }
    fun setQuery(value: String) {
        val clean = value.take(200)
        if (query.value == clean) return
        savedState["query"] = clean
        savedState["search_group"] = ""
        request()
    }
    fun setSource(value: String) {
        if (value !in setOf("all", "local", "soundcloud", "youtube")) return
        val next = if (offlineNow) "local" else value
        if (source.value == next) return
        savedState["source"] = next
        savedState["search_group"] = ""
        request()
    }
    fun setCategory(value: String) {
        if (value in setOf("tracks", "artists", "albums")) {
            savedState["category"] = value
            savedState["search_group"] = ""
        }
    }
    fun selectGroup(value: String) { savedState["search_group"] = value.take(500) }
    fun submit(value: String, provider: String) {
        savedState["query"] = value.take(200)
        if (provider in setOf("all", "local", "soundcloud", "youtube"))
            savedState["source"] = if (offlineNow) "local" else provider
        savedState["search_group"] = ""
        active = true
        request()
    }
    private fun cancel() {
        generation++
        pending?.cancel()
        pending = null
        if (output.value.busy) output.value = output.value.copy(busy = false)
    }
    private fun request() {
        cancel()
        if (!active) return
        val text = query.value.trim()
        if (text.isBlank()) { output.value = SearchState(); return }
        val provider = source.value
        val token = generation
        output.value = SearchState(busy = true)
        pending = scope.launch {
            delay(debounceMs)
            try {
                var partial: String? = null
                val rows = fetch(text, provider) { warning -> partial = warning }
                ensureActive()
                if (active && token == generation) output.value = SearchState(tracks = rows, warning = partial)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive()
                if (active && token == generation) output.value = SearchState(error = error.message ?: "Ошибка поиска")
            }
        }
    }
}
