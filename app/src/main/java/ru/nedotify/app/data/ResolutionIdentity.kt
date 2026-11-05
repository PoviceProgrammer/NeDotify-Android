package ru.nedotify.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * The identity gate of the resolution cascade.
 *
 * A second provider may serve a track only when the app's own matcher admits
 * the candidate as the *same recording*. The gate is the app's matcher, not a
 * new rule: `CatalogMatcher` decides which provider can supply a track, and
 * the cascade must not invent a relation of its own.
 *
 * The library's identity is provider-scoped — a track's id is
 * `<source>:<sourceId>` — and a track that is already in the library has no
 * relation to another provider's recording. Such a relation is created on
 * demand: the other providers are searched and the gate decides whether a
 * candidate is the same recording. A successfully resolved candidate is then
 * retained by LibraryRepository as a ProviderMapping. Future sessions recheck
 * that stored candidate against the current metadata before using it.
 */
object ResolutionIdentity {
    /**
     * The candidate that may serve [track], or `null` when the gate admits
     * nothing. A candidate of the track's own source is never a fallback.
     */
    fun admitted(track: Track, candidates: List<Track>): Track? {
        if (candidates.isEmpty()) return null
        val metadata = CatalogTrack(track.source, track.sourceId, track.title,
            listOf(track.artist), track.album, track.durationMs)
        val match = CatalogMatcher.match(metadata, candidates) ?: return null
        return match.track.takeIf { it.source != track.source && it.id != track.id }
    }

    /**
     * The sources that may serve a fallback for the track, in the order they
     * are searched.
     *
     * The own source of the track is never searched again: its attempt has
     * just failed, and the search would repeat the same failure.
     */
    fun fallbackSources(track: Track): List<String> =
        listOf("youtube", "soundcloud").filter { it != track.source }

    /**
     * The candidate that may serve [track], or `null`.
     *
     * A candidate the caller already owns is admitted by the gate at once.
     * Otherwise the other providers are searched, and the gate decides whether
     * a candidate is the *same recording*.
     *
     * The search is bounded: it may not eat the whole budget of the cascade,
     * and a search that fails is not an error of the resolution — the cascade
     * simply ends with the first attempt's error.
     *
     * @param remaining the milliseconds that are left of the budget.
     * @param search the search of one source; it returns the candidates it
     *   found, and an empty list when the source is not available.
     */
    suspend fun findFallback(track: Track, supplied: List<Track>, remaining: Long,
        budgetMillis: Long, search: suspend (String) -> List<Track>): Track? {
        admitted(track, supplied)?.let { return it }
        val budget = minOf(remaining, budgetMillis)
        if (budget <= 0L) return null
        val found = mutableListOf<Track>()
        return try {
            withTimeout(budget) {
                for (source in fallbackSources(track)) {
                    try { found += search(source) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                    admitted(track, found)?.let { return@withTimeout it }
                }
                null
            }
        } catch (cancelled: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}
