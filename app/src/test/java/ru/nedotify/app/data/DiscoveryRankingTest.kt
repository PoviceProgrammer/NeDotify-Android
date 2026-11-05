package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test

class DiscoveryRankingTest {
    private val now = 20 * 86_400_000L
    private fun row(id: String, artist: String = id, source: String = "soundcloud", favorite: Boolean = false,
        uri: String = "", downloaded: Boolean = false, last: Long = 0) = DiscoveryCandidate(
        Track(id, id, artist, source = source, favorite = favorite, localUri = uri, addedAt = 1),
        lastPlayed = last, downloaded = downloaded)

    @Test fun offlineMixUsesOnlyAttachedLocalAndCompleteRemoteDownloads() {
        val rows = listOf(row("cloud"), row("saved", downloaded = true), row("youtube", source = "youtube", downloaded = true),
            row("yandex", source = "yandex", downloaded = true), row("file", source = "local", uri = "content://song"),
            row("unattached", source = "local"), row("unknown", source = "other"))
        assertEquals(setOf("saved", "youtube", "yandex", "file"), DiscoveryRanking.select(rows, emptySet(), 50, now, true).map { it.id }.toSet())
        assertTrue("cloud" in DiscoveryRanking.select(rows, emptySet(), 50, now, false).map { it.id })
    }

    @Test fun favoriteAndArtistDiversityAreBothRepresented() {
        val rows = listOf(row("a", artist = "Мой Артист", favorite = true),
            row("b", artist = "мой артист", favorite = true), row("c", artist = "Другой", favorite = true))
        val selected = DiscoveryRanking.select(rows, emptySet(), 3, now, false)
        assertEquals(3, selected.size)
        assertNotEquals(selected[0].artist.lowercase(), selected[1].artist.lowercase())
    }

    @Test fun skippedIdsDuplicatesLimitsAndStableOrderingAreRespected() {
        val rows = listOf(row("c"), row("b"), row("a"), row("a"))
        assertEquals(listOf("a", "c"), DiscoveryRanking.select(rows, setOf("b"), 50, now, false).map { it.id })
        assertEquals(listOf("a"), DiscoveryRanking.select(rows, setOf("b"), 1, now, false).map { it.id })
        assertTrue(DiscoveryRanking.select(rows, emptySet(), 0, now, false).isEmpty())
        assertTrue(DiscoveryRanking.select(rows, emptySet(), -1, now, false).isEmpty())
        val many = (0..100).map { row(it.toString()) }
        assertEquals(50, DiscoveryRanking.select(many, emptySet(), 500, now, false).size)
    }

    @Test fun recentPlayIsPenalizedWithoutUsingTrackDurationAsListeningEvidence() {
        val rows = listOf(row("recent", last = now - 1), row("new"), row("future", last = now + 1))
        val selected = DiscoveryRanking.select(rows, emptySet(), 3, now, false)
        assertEquals("recent", selected.last().id)
        assertTrue(DiscoveryRanking.select(emptyList(), emptySet(), 10, now, false).isEmpty())
    }
}
