package ru.nedotify.app.ui.state

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*

class SearchViewModel(application: Application, savedState: SavedStateHandle) : FeatureViewModel(application, savedState) {
    private val requests = SearchRequestController(viewModelScope, savedState,
        { query, source, partial -> repository.search(query, source, partial) },
        repository.preferences.appearance.map { it.offline })
    val query = requests.query
    val source = requests.source
    val category = requests.category
    val selectedGroupKey = requests.selectedGroupKey
    val search = requests.state
    val offline = repository.preferences.appearance.map { it.offline }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val searchHistory = repository.preferences.searchHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun onActive(value: Boolean) = requests.onActive(value)
    fun setQuery(value: String) = requests.setQuery(value)
    fun setSource(value: String) = requests.setSource(value)
    fun setCategory(value: String) = requests.setCategory(value)
    fun selectGroup(value: String) = requests.selectGroup(value)
    fun search(query: String, source: String) = requests.submit(query, source)
    fun rememberSearch(query: String) = task { repository.preferences.rememberSearch(query) }
    fun removeSearch(query: String) = task { repository.preferences.removeSearch(query) }
    fun clearSearchHistory() = task { repository.preferences.clearSearchHistory() }
}
