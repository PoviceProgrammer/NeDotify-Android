package ru.nedotify.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmartRulesTest {
    private val day = 86_400_000L
    private val now = 30 * day
    private fun candidate(id: String, title: String = id, artist: String = "Артист", source: String = "local",
        favorite: Boolean = false, added: Long = now - day, listened: Long = 0, plays: Int = 0,
        last: Long = 0, downloaded: Boolean = false) = DiscoveryCandidate(
            Track(id, title, artist, source = source, addedAt = added, favorite = favorite, durationMs = 300_000),
            listened, plays, last, downloaded)

    @Test fun filtersAreCombinedAndCyrillicSearchIsCaseInsensitive() {
        val rows = listOf(
            candidate("a", "Тихий ВЕЧЕР", "Мой Артист", favorite = true, listened = 70_000, plays = 1),
            candidate("b", "Тихий вечер", "Мой Артист", favorite = false, listened = 70_000, plays = 1),
            candidate("c", "Тихий вечер", "Другой", favorite = true, listened = 70_000, plays = 1),
            candidate("d", "Тихий вечер", "Мой Артист", favorite = true, listened = 59_999, plays = 1))
        val rules = SmartRules(favorite = true, artist = "мой артист", query = "вечер", minListenedMs = 60_000,
            addedWithinDays = 7, unplayed = false)
        assertEquals(listOf("a"), SmartPlaylistEngine.select(rows, rules, now).map { it.id })
    }

    @Test fun downloadedAndUnplayedUseRealDownloadAndHistoryState() {
        val rows = listOf(candidate("a", downloaded = true), candidate("b"),
            candidate("c", downloaded = true, listened = 1, plays = 1))
        assertEquals(listOf("a"), SmartPlaylistEngine.select(rows,
            SmartRules(downloaded = true, unplayed = true), now).map { it.id })
        assertEquals(listOf("b"), SmartPlaylistEngine.select(rows,
            SmartRules(downloaded = false), now).map { it.id })
        assertEquals(listOf("c"), SmartPlaylistEngine.select(rows,
            SmartRules(minListenedMs = 1), now).map { it.id })
    }

    @Test fun datesExcludeFutureAdditionsAndRecentlyPlayedTracks() {
        val rows = listOf(candidate("a", last = now - 8 * day, plays = 1),
            candidate("b", last = now - day, plays = 1), candidate("c"),
            candidate("d", added = now + 1), candidate("e", added = now - 8 * day))
        assertEquals(listOf("a", "c"), SmartPlaylistEngine.select(rows,
            SmartRules(addedWithinDays = 7, notPlayedDays = 7), now).map { it.id })
    }

    @Test fun sortingIsDeterministicAndLimitAppliesAfterFilters() {
        val rows = listOf(candidate("c", listened = 50), candidate("b", listened = 90), candidate("a", listened = 90))
        assertEquals(listOf("a", "b"), SmartPlaylistEngine.select(rows,
            SmartRules(order = SmartOrder.MOST_PLAYED, limit = 2), now).map { it.id })
        assertEquals(listOf("c"), SmartPlaylistEngine.select(rows,
            SmartRules(order = SmartOrder.LEAST_PLAYED, limit = 1), now).map { it.id })
        assertEquals(listOf("a", "b", "c"), SmartPlaylistEngine.select(rows,
            SmartRules(order = SmartOrder.TITLE), now).map { it.id })
    }

    @Test fun legacyRulesReceiveNeutralDefaultsForNewConditions() {
        val full = SmartRules(source = "youtube", query = "море", favorite = false)
        val legacy = JSONObject(full.encode()).apply {
            remove("downloaded"); remove("unplayed"); remove("notPlayedDays")
        }
        assertEquals(full, SmartRules.decode(legacy.toString()))
        val all = full.copy(source = "yandex", downloaded = true, unplayed = false, notPlayedDays = 30)
        assertEquals(all, SmartRules.decode(all.encode()))
    }

    @Test fun invalidRulesCannotBePersistedOrSilentlyCoerced() {
        fun rejects(json: String) { assertThrows(SourceFailure::class.java) { SmartRules.decode(json) } }
        rejects(JSONObject(SmartRules().encode()).put("limit", "100").toString())
        rejects(JSONObject(SmartRules().encode()).put("downloaded", 1).toString())
        rejects(JSONObject(SmartRules().encode()).put("order", "SQL").toString())
        rejects(JSONObject(SmartRules().encode()).put("query", "bad\ntext").toString())
        rejects(JSONObject(SmartRules().encode()).put("unknown", true).toString())
        rejects(JSONObject(SmartRules().encode()).put("notPlayedDays", 0).toString())
        rejects(SmartRules().encode() + "{}")
        rejects("{" + "\"version\":1,".repeat(2) + "\"query\":\"x\"}")
        assertThrows(SourceFailure::class.java) { SmartRules(source = "unknown").encode() }
        assertThrows(SourceFailure::class.java) { SmartRules(limit = 0).checked() }
    }

    @Test fun sourceAndAlbumSearchRespectActualMetadata() {
        val rows = listOf(candidate("a", source = "yandex").let { it.copy(track = it.track.copy(album = "Тёплый закат")) },
            candidate("b", source = "soundcloud"))
        assertEquals(listOf("a"), SmartPlaylistEngine.select(rows,
            SmartRules(source = "yandex", query = "ЗАКАТ"), now).map { it.id })
    }
    @Test fun ruleTextCannotSilentlyChangeWhenEncodedAsUtf8() {
        assertThrows(SourceFailure::class.java){SmartRules(artist="\uD800").encode()}
        assertThrows(SourceFailure::class.java){SmartRules(query="\uDC00").checked()}
        assertThrows(SourceFailure::class.java){SmartRules.decode(JSONObject(SmartRules().encode()).put("query","\uD800").toString())}
        val valid=SmartRules(query="Песня 🎵")
        assertEquals(valid,SmartRules.decode(valid.encode()))
    }
}
