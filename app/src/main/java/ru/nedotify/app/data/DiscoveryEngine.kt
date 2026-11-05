package ru.nedotify.app.data

import androidx.room.Embedded
import java.util.Locale
import kotlin.math.ln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

const val DISCOVERY_CANDIDATES_SQL = """SELECT t.*,
    COALESCE(SUM(CASE WHEN h.startedAt<=:now AND h.listenedMs>0 THEN h.listenedMs ELSE 0 END),0) AS listenedMs,
    (MAX(t.importedPlayCount, SUM(CASE WHEN h.startedAt<=:now AND h.id LIKE 'desktop:%' THEN 1 ELSE 0 END)) +
        SUM(CASE WHEN h.startedAt<=:now AND h.id NOT LIKE 'desktop:%' THEN 1 ELSE 0 END)) AS playCount,
    MAX(t.importedLastPlayed, MAX(CASE WHEN h.startedAt<=:now THEN h.startedAt ELSE 0 END)) AS lastPlayed,
    EXISTS(SELECT 1 FROM offline_downloads d WHERE d.trackId=t.id AND d.state='complete') AS downloaded
    FROM tracks t LEFT JOIN history h ON h.trackId=t.id GROUP BY t.id
    ORDER BY t.favorite DESC, t.addedAt DESC, t.id"""

data class DiscoveryCandidate(@Embedded val track: Track, val listenedMs: Long = 0,
    val playCount: Int = 0, val lastPlayed: Long = 0, val downloaded: Boolean = false)

/** Deterministic personal mix from measured history, favorites and new additions, with artist diversity. */
object DiscoveryRanking {
    fun select(candidates: List<DiscoveryCandidate>, excluding: Set<String>, limit: Int, now: Long, offline: Boolean): List<Track> {
        if (limit <= 0) return emptyList()
        fun artist(track: Track): String = artistKey(track.artist)
        val taste = candidates.filter { it.listenedMs > 0 || it.track.favorite }.groupBy { artist(it.track) }
            .mapValues { (_, rows) -> rows.sumOf { it.listenedMs.coerceAtLeast(0).toDouble() / 60_000 } + rows.count { it.track.favorite } * 4.0 }
        data class Scored(val track: Track, val artist: String, val score: Double)
        val pool = candidates.distinctBy { it.track.id }.filter { row ->
            row.track.id !in excluding && (row.track.localUri.isNotBlank() ||
                (row.track.source in setOf("soundcloud", "youtube", "yandex") && (!offline || row.downloaded)))
        }.map { row ->
            val name = artist(row.track)
            val affinity = if (name.isBlank() || name == "неизвестный исполнитель") 0.0 else ln(1 + (taste[name] ?: 0.0)) * 8
            val recentPenalty = if (row.lastPlayed > 0 && row.lastPlayed <= now && now - row.lastPlayed < 86_400_000L) 24 else 0
            val fresh = if (row.track.addedAt <= now && now - row.track.addedAt in 0..604_800_000L) 5 else 0
            Scored(row.track, name, (if (row.track.favorite) 38 else 0) + affinity +
                (if (row.listenedMs <= 0) 18.0 else ln(1 + row.playCount.coerceAtLeast(0).toDouble()) * 3) + fresh - recentPenalty)
        }.toMutableList()
        val selected = mutableListOf<Track>()
        val artistCounts = mutableMapOf<String, Int>()
        repeat(limit.coerceAtMost(50).coerceAtMost(pool.size)) {
            val next = pool.maxWithOrNull(compareBy<Scored> { it.score - (artistCounts[it.artist] ?: 0) * 22 }
                .thenByDescending { it.track.id }) ?: return selected
            pool.remove(next); selected += next.track
            artistCounts[next.artist] = (artistCounts[next.artist] ?: 0) + 1
        }
        return selected
    }
}

class DiscoveryEngine(private val repository: LibraryRepository) {
    /** Never resolves playback URLs. The caller owns the queue and supplies played/skipped IDs. */
    suspend fun selectFlow(excluding: Set<String>, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val offline = repository.preferences.appearance.first().offline
        val candidates = repository.dao.discoveryCandidates(System.currentTimeMillis())
        DiscoveryRanking.select(candidates, excluding, limit, System.currentTimeMillis(), offline)
    }

    suspend fun recommendations(): DiscoveryState = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val offline = repository.preferences.appearance.first().offline
        val candidates = repository.dao.discoveryCandidates(now)
        val local = DiscoveryRanking.select(candidates, emptySet(), 20, now, offline)
        if (offline || local.size >= 12) return@withContext DiscoveryState(local,
            description = if (offline) "Из доступной офлайн-музыки" else "На основе вашей коллекции и истории")
        // An artist from actual favorites/history is a seed, never an invented global chart.
        val seeds = candidates.filter { it.track.favorite || it.listenedMs > 0 }
            .sortedWith(compareByDescending<DiscoveryCandidate> { it.track.favorite }.thenByDescending { it.listenedMs })
            .map { it.track.artist.trim() }.filter { artistKey(it) !in setOf("", "неизвестный исполнитель", "unknown artist") }
            .distinctBy(::artistKey).take(2)
        if (seeds.isEmpty()) return@withContext DiscoveryState(local,
            description = "Добавьте музыку и отмечайте любимые треки — подборка станет точнее")
        val fetched = mutableListOf<Track>()
        var failure: String? = null
        for (seed in seeds) {
            try { fetched += withTimeout(15_000) { repository.search(seed, "soundcloud") }.take(12) }
            catch (_: TimeoutCancellationException) { failure = "Источник не ответил вовремя. Можно слушать свою коллекцию." }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { failure = "Не удалось обновить подборку из сети. Можно слушать свою коллекцию." }
        }
        DiscoveryState((local + fetched).distinctBy { it.id }.take(24), error = failure,
            description = "Коллекция и поиск по вашим любимым исполнителям")
    }
}

internal fun artistKey(value:String):String = value.trim().lowercase(Locale.ROOT).replace('ё','е').replace(Regex("\\s+")," ")
