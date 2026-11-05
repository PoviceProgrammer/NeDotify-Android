package ru.nedotify.app.data

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** Independent bounded source requests: one unavailable provider cannot erase another's results. */
suspend fun aggregateSearch(tasks:List<suspend ()->List<Track>>,providerTimeoutMs:Long=15_000,maxResults:Int=300,
    timeoutExemptIndices:Set<Int> = emptySet(),onPartialFailure:(suspend (String)->Unit)? = null):List<Track> {
    require(providerTimeoutMs>0 && maxResults in 1..1_000)
    if(tasks.isEmpty())return emptyList()
    val permits=Semaphore(4)
    val outcomes=supervisorScope {
        tasks.mapIndexed {index,task->async {
            try {Result.success(permits.withPermit {
                if(index in timeoutExemptIndices)task() else withTimeout(providerTimeoutMs) {task()}
            })}
            catch(error:TimeoutCancellationException) {currentCoroutineContext().ensureActive();Result.failure(error)}
            catch(error:CancellationException) {throw error}
            catch(error:Exception) {Result.failure(error)}
        }}.awaitAll()
    }
    val tracks=outcomes.asSequence().mapNotNull {it.getOrNull()}.flatten().distinctBy {it.id}.take(maxResults).toList()
    // An empty local library must not disguise both unavailable network sources as "no matches".
    if(tracks.isEmpty() && outcomes.any {it.isFailure})throw SourceFailure("Поиск завершён не во всех источниках. Проверьте подключение или выберите поиск на устройстве.")
    if(tracks.isNotEmpty() && outcomes.any {it.isFailure})onPartialFailure?.invoke("Показаны доступные результаты. Поиск завершён не во всех источниках; повторите поиск позже.")
    return tracks
}

data class SearchResultGroup(val key:String,val title:String,val artist:String,val tracks:List<Track>)

/** These are groups within the returned tracks, never invented complete artist or album catalogs. */
object SearchResultGroups {
    private fun key(value:String)=value.trim().replace(Regex("\\s+")," ").lowercase(Locale.ROOT)
    private fun knownArtist(value:String)=key(value) !in setOf("","неизвестный исполнитель","unknown artist","various artists","<unknown>")
    private fun knownAlbum(value:String)=key(value) !in setOf("","неизвестный альбом","unknown album","<unknown>","—","-")
    fun artists(tracks:List<Track>):List<SearchResultGroup> = tracks.distinctBy {it.id}.filter {knownArtist(it.artist)}
        .groupBy {key(it.artist)}.map {(id,rows)->SearchResultGroup(id,rows.first().artist.trim(),"",rows)}
        .sortedWith(compareBy<SearchResultGroup> {key(it.title)}.thenBy {it.key})
    fun albums(tracks:List<Track>):List<SearchResultGroup> = tracks.distinctBy {it.id}.filter {knownAlbum(it.album)}
        .groupBy {key(it.artist)+"\u0000"+key(it.album)}.map {(id,rows)->
            SearchResultGroup(id,rows.first().album.trim(),rows.first().artist.trim(),rows)
        }.sortedWith(compareBy<SearchResultGroup> {key(it.title)}.thenBy {key(it.artist)}.thenBy {it.key})
}
