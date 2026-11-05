package ru.nedotify.app.data

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.sqlite.db.SimpleSQLiteQuery

data class DiscoveryState(val tracks: List<Track> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val description: String = "")
data class SmartTrackState(val playlistId:String?=null,val tracks:List<Track> = emptyList(),val loading:Boolean=false)

/** Conflate invalidations before the expensive Room aggregation, retaining an immediate first result. */
internal fun <T> limitedDiscoveryRefresh(changes:Flow<Int>,intervalMs:Long=30_000,fetch:suspend ()->T):Flow<T> = flow {
    changes.conflate().collect {
        emit(fetch())
        delay(intervalMs)
    }
}

/** Owned by the controller, so a network request is cancelled even while Home is not composed. */
internal class RecommendationLoader(private val scope:CoroutineScope,offline:Flow<Boolean>,
    private val fetch:suspend ()->DiscoveryState,private val output:MutableStateFlow<DiscoveryState>) {
    private var requested=false
    private var load:Job?=null
    init {scope.launch {offline.distinctUntilChanged().collect {if(requested)request(force=true)}}}
    fun request(force:Boolean=false) {
        if(requested && !force)return
        requested=true;load?.cancel()
        load=scope.launch {
            output.value=output.value.copy(loading=true,error=null)
            try {
                val next=fetch();currentCoroutineContext().ensureActive();output.value=next
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception) {
                currentCoroutineContext().ensureActive()
                output.value=output.value.copy(loading=false,error=error.message?:"Не удалось составить подборку")
            }
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun observeSmartPlaylistTracks(playlists: Flow<SmartPlaylist?>,
    candidates: (Long) -> Flow<List<DiscoveryCandidate>>, clock: Flow<Long>, onError: (String) -> Unit): Flow<List<Track>> =
    playlists.distinctUntilChanged().flatMapLatest { playlist ->
        if (playlist == null) flowOf(emptyList()) else {
            // Keep observing this row after a damaged rule: a later edit repairs it in place.
            val rules = try { SmartRules.decode(playlist.rulesJson) }
                catch (error: SourceFailure) { onError(error.message ?: "Повреждены правила плейлиста"); null }
            if (rules == null) flowOf(emptyList()) else {
                val times=if(rules.addedWithinDays!=null || rules.notPlayedDays!=null)clock else clock.take(1)
                times.flatMapLatest {now-> candidates(now).map {SmartPlaylistEngine.select(it,rules,now)} }
                    .onStart {emit(emptyList())}
            }
        }
    }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiscoveryController(private val repository: LibraryRepository, private val scope: CoroutineScope) {
    val recommendation = MutableStateFlow(DiscoveryState())
    val message = MutableStateFlow<String?>(null)
    val selectedSmartId = MutableStateFlow<String?>(null)
    private val engine = DiscoveryEngine(repository)
    private val smartWriteLock=Mutex()
    private val recommendationsLoader=RecommendationLoader(scope,repository.preferences.appearance.map {it.offline},
        engine::recommendations,recommendation)
    val smartPlaylists: StateFlow<List<SmartPlaylist>> = repository.dao.smartPlaylists().catch {
        message.value = "Не удалось открыть умные плейлисты"; emit(emptyList())
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val smartTrackState: StateFlow<SmartTrackState> = selectedSmartId.flatMapLatest { id ->
        if (id == null) flowOf(SmartTrackState()) else observeSmartPlaylistTracks(repository.dao.observeSmartPlaylist(id),
            {now->limitedDiscoveryRefresh(repository.dao.observeDiscoveryChanges(SimpleSQLiteQuery("SELECT 1"))) {
                repository.dao.allDiscoveryCandidates(maxOf(now,System.currentTimeMillis()))
            }}, clock()) { message.value = it }.map {SmartTrackState(id,it)}
            .onStart {emit(SmartTrackState(id,loading=true))}
            .catch { error -> message.value = error.message ?: "Не удалось обновить умный плейлист"; emit(SmartTrackState(id)) }
    }
        .flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(5_000), SmartTrackState())
    val smartTracks:StateFlow<List<Track>> = smartTrackState.map {it.tracks}
        .stateIn(scope,SharingStarted.WhileSubscribed(5_000),emptyList())

    fun loadRecommendations() = recommendationsLoader.request()
    fun refreshRecommendations() = recommendationsLoader.request(force=true)
    suspend fun selectFlow(excluding: Set<String>, limit: Int): List<Track> = engine.selectFlow(excluding, limit)
    fun openSmart(id: String) { selectedSmartId.value = id }
    fun closeSmart() { selectedSmartId.value = null }
    fun clearMessage() { message.value = null }
    fun createSmart(name: String, rules: SmartRules) = task {
        val playlist = SmartPlaylist(UUID.randomUUID().toString(), checkedName(name), rules.requireFilter().encode())
        smartWriteLock.withLock {
            if(repository.dao.allSmartPlaylists().size>=100)throw SourceFailure("Поддерживается до 100 умных плейлистов")
            repository.dao.putSmartPlaylist(playlist)
        }
        selectedSmartId.value = playlist.id
        message.value = "Умный плейлист создан"
    }
    fun editSmart(id: String, name: String, rules: SmartRules) = task {
        if (repository.dao.editSmartPlaylist(id, checkedName(name), rules.requireFilter().encode()) != 1)
            throw SourceFailure("Умный плейлист уже удалён")
        message.value = "Правила сохранены"
    }
    fun deleteSmart(id: String) = task {
        repository.dao.deleteSmartPlaylist(id)
        if (selectedSmartId.value == id) selectedSmartId.value = null
        message.value = "Умный плейлист удалён. Треки остались в библиотеке."
    }
    private fun checkedName(name: String): String = name.trim().also {
        if (it.isBlank() || it.length > 120 || it.any { character -> character < ' ' }) throw SourceFailure("Введите название до 120 символов")
    }
    private fun task(block: suspend () -> Unit) { scope.launch(Dispatchers.IO) {
        try { block() } catch (error: CancellationException) { throw error }
        catch (error: Exception) { message.value = error.message ?: "Не удалось изменить умный плейлист" }
    } }
    private fun clock(): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) { emit(System.currentTimeMillis()); delay(60_000) }
    }
}
