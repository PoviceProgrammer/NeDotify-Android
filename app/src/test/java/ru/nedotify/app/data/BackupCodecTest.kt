package ru.nedotify.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class BackupCodecTest {
    private val libraryId = "41c40e27-ef0b-4e19-bbb8-d3df6b101f47"
    private val secondLibrary = "836ca8cc-2645-4f83-a339-09fb4e270594"
    private val youtube = Track("youtube:Abcde_123-x", "Тихий океан 🌊", "Исполнитель", "Вечер",
        durationMs = 240_000, source = "youtube", sourceId = "Abcde_123-x", favorite = true, addedAt = 123,
        sourceUrl = "https://music.youtube.com/watch?v=Abcde_123-x", coverUrl = "https://i.ytimg.com/vi/Abcde_123-x/hqdefault.jpg")
    private val local = Track("document:b3d88ee6-f061-4c36-b041-240037dbaf3b", "Моя песня", "Местный исполнитель",
        source = "local", localUri = "content://private.provider/tree/secret%2Fmusic/song.mp3", coverUrl = "file:///storage/private/cover.jpg",
        durationMs = 180_000, addedAt = 456, mimeType = "audio/mpeg")

    private fun fixture() = LibraryBackup(libraryId, 1_792_300_000_000, listOf(youtube, local),
        listOf(BackupPlaylist(Playlist("mix", "Мой плейлист", 100), listOf(local.id, youtube.id))),
        listOf(ListeningSession("session-1", youtube.id, 1_000, 22_345, false),
            ListeningSession("session-2", local.id, 3_000, 7_654, true)))
    private fun json() = JSONObject(String(BackupCodec.encode(fixture()), StandardCharsets.UTF_8))
    private fun decode(root: JSONObject) = BackupCodec.decode(root.toString().toByteArray(StandardCharsets.UTF_8))
    private fun rejects(bytes: ByteArray) = assertThrows(SourceFailure::class.java) { BackupCodec.decode(bytes) }
    private fun rejects(root: JSONObject) = rejects(root.toString().toByteArray(StandardCharsets.UTF_8))
    private fun rejects(text: String) = rejects(text.toByteArray(StandardCharsets.UTF_8))

    @Test fun `roundtrip preserves Cyrillic playlist order favorites and measured history`() {
        val original = fixture()
        val restored = BackupCodec.decode(BackupCodec.encode(original))
        val portable = BackupCodec.portableTrackId(local, libraryId)
        assertEquals(original.libraryId, restored.libraryId)
        assertEquals(original.exportedAt, restored.exportedAt)
        assertEquals(youtube, restored.tracks.first())
        assertEquals(local.copy(id = portable, localUri = "", coverUrl = ""), restored.tracks.last())
        assertEquals(listOf(portable, youtube.id), restored.playlists.single().trackIds)
        assertEquals(listOf(22_345L, 7_654L), restored.history.map { it.listenedMs })
        assertEquals(portable, restored.history.last().trackId)
        assertEquals(BackupSummary(2, 1, 1, 2, 1), restored.summary)
        assertEquals(restored, BackupCodec.decode(BackupCodec.encode(restored)))
    }

    @Test fun `native local identities are scoped to library and stable over multiple transfers`() {
        val first = BackupCodec.portableTrackId(local, libraryId)
        assertTrue(first.matches(Regex("portable-local:[0-9a-f]{64}")))
        assertNotEquals(first, BackupCodec.portableTrackId(local, secondLibrary))
        assertEquals(first, BackupCodec.portableTrackId(local.copy(id = first), secondLibrary))
        assertNotEquals(first, BackupCodec.portableTrackId(local.copy(id = "local:123"), libraryId))
        assertEquals(BackupCodec.portableTrackId(local.copy(id = "local:123"), libraryId),
            BackupCodec.portableTrackId(local.copy(id = "local:123"), libraryId.uppercase()))
        val transferred = BackupCodec.decode(BackupCodec.encode(fixture())).copy(libraryId = secondLibrary)
        val again = BackupCodec.decode(BackupCodec.encode(transferred))
        assertEquals(first, again.tracks.last().id)
        assertEquals(first, again.history.last().trackId)
        assertEquals(youtube.id, BackupCodec.portableTrackId(youtube, libraryId))
    }

    @Test fun `export removes device paths signed source URLs and private cover data`() {
        val sc = Track("soundcloud:12345", "Cloud", source = "soundcloud", sourceId = "12345",
            sourceUrl = "https://soundcloud.com/artist/song?secret_token=private-token#fragment",
            coverUrl = "https://i1.sndcdn.com/artworks-cover-large.jpg?Policy=secret-policy&Signature=private-signature",
            localUri = "file:///private/cached-stream.mp3", addedAt = 0)
        val bytes = BackupCodec.encode(fixture().copy(tracks = listOf(youtube.copy(sourceUrl = "https://cdn.test/stream?token=secret-token",
            coverUrl = "https://cdn.test/stream.mp3?Signature=secret"), local.copy(sourceId = "device-secret", sourceUrl = "C:\\private\\song.mp3"), sc)))
        val text = String(bytes, StandardCharsets.UTF_8)
        listOf("localUri", "content://", "file://", "private-token", "secret-policy", "private-signature", "device-secret", "C:\\", "secret-token", "Signature").forEach {
            assertFalse("Export leaked $it", text.contains(it))
        }
        val restored = BackupCodec.decode(bytes)
        assertEquals("", restored.tracks.first().coverUrl)
        assertEquals("https://music.youtube.com/watch?v=${youtube.sourceId}", restored.tracks.first().sourceUrl)
        assertEquals("https://soundcloud.com/artist/song", restored.tracks.last().sourceUrl)
        assertEquals("https://i1.sndcdn.com/artworks-cover-large.jpg", restored.tracks.last().coverUrl)
        assertEquals("audio/mpeg", restored.tracks[1].mimeType)
    }

    @Test fun `import cannot retain local grants paths or private remote links`() {
        rejects(json().apply { getJSONArray("tracks").getJSONObject(0).put("localUri", "content://private") })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(1).put("sourceUrl", "file:///private/song") })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(0).put("sourceUrl", "https://cdn.test/stream?token=secret") })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(0).put("coverUrl", "https://i.ytimg.com/vi/x/a.jpg?token=secret") })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(0).put("coverUrl", "https://i.ytimg.com/audio.m3u8") })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(0).put("coverUrl", "https://i.ytimg.com@attacker.test/cover.jpg") })
    }
    @Test fun `oversized public source page cannot produce a backup its decoder rejects`() {
        val track=Track("soundcloud:123","Трек",source="soundcloud",sourceId="123",sourceUrl="https://soundcloud.com/artist/"+"a".repeat(3000))
        val draft=fixture().copy(tracks=listOf(track),playlists=emptyList(),history=emptyList())
        val restored=BackupCodec.decode(BackupCodec.encode(draft))
        assertEquals("",restored.tracks.single().sourceUrl)
        assertEquals("123",restored.tracks.single().sourceId)
    }
    @Test fun `Yandex identities accepted by catalog remain portable at the full supported length`() {
        val id="123456789012345678901234"
        val track=Track("yandex:$id","Трек",source="yandex",sourceId=id)
        val draft=fixture().copy(tracks=listOf(track),playlists=emptyList(),history=emptyList())
        val restored=BackupCodec.decode(BackupCodec.encode(draft)).tracks.single()
        assertEquals(track.copy(sourceUrl="https://music.yandex.ru/track/$id"),restored)
    }

    @Test fun `duplicate track playlist session and playlist entries are rejected`() {
        rejects(json().apply { getJSONArray("tracks").put(getJSONArray("tracks").getJSONObject(0)) })
        rejects(json().apply { getJSONArray("playlists").put(getJSONArray("playlists").getJSONObject(0)) })
        rejects(json().apply { getJSONArray("history").put(getJSONArray("history").getJSONObject(0)) })
        rejects(json().apply { getJSONArray("playlists").getJSONObject(0).getJSONArray("trackIds").put(youtube.id) })
    }

    @Test fun `missing playlist and history references are rejected before persistence`() {
        rejects(json().apply { getJSONArray("playlists").getJSONObject(0).getJSONArray("trackIds").put("youtube:Missing_123") })
        rejects(json().apply { getJSONArray("history").getJSONObject(0).put("trackId", "missing") })
        assertThrows(SourceFailure::class.java) { BackupCodec.encode(fixture().copy(tracks = listOf(youtube))) }
    }

    @Test fun `source identities must match and local imports must be portable`() {
        rejects(json().apply { getJSONArray("tracks").getJSONObject(0).put("sourceId", "Other_id123") })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(0).put("source", "other") })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(1).put("id", "local:123") })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(1).put("id", "portable-local:" + "A".repeat(64)) })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(1).put("sourceId", "123") })
    }

    @Test fun `types bounds and unsupported format version are explicit failures`() {
        rejects(json().put("version", 4))
        rejects(json().put("version", "1"))
        rejects(json().put("format", "other-library"))
        rejects(json().put("libraryId", "1-1-1-1-1"))
        rejects(json().put("exportedAt", -1))
        rejects(json().apply { getJSONArray("tracks").getJSONObject(0).put("durationMs", "100") })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(0).put("favorite", 1) })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(0).put("title", JSONObject.NULL) })
        rejects(json().apply { getJSONArray("tracks").getJSONObject(0).put("title", "x".repeat(2_049)) })
        rejects(json().apply { getJSONArray("history").getJSONObject(0).put("listenedMs", -1) })
        rejects(json().apply { getJSONArray("history").getJSONObject(0).put("completed", "true") })
        val raw = String(BackupCodec.encode(fixture()), StandardCharsets.UTF_8)
        rejects(raw.replace("\"listenedMs\":22345", "\"listenedMs\":2.2345e4"))
        rejects(raw.replace("\"listenedMs\":22345", "\"listenedMs\":9223372036854775808"))
    }

    @Test fun `invalid UTF8 malformed strict JSON and extra tokens are rejected`() {
        rejects(byteArrayOf(0xC3.toByte(), 0x28))
        rejects("{not-json}")
        rejects("{'format':'nedotify-library'}")
        rejects("{\"format\":\"nedotify-library\",}")
        rejects("{\"format\":\"a\",\"format\":\"b\"}")
        rejects("{\"a\":1,\"\\u0061\":2}")
        rejects("{\"a\":01}")
        rejects("{\"a\":1.}")
        rejects("{\"a\":true/* comment */}")
        rejects(String(BackupCodec.encode(fixture()), StandardCharsets.UTF_8) + " []")
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + BackupCodec.encode(fixture())
        assertEquals(fixture().summary, BackupCodec.decode(withBom).summary)
    }

    @Test fun `nesting is bounded without treating brackets in text as structure`() {
        rejects("{\"nested\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}")
        val bracketed = fixture().copy(tracks = listOf(youtube.copy(title = "[{}] \\\"текст\\\" ".repeat(30)), local))
        assertEquals(bracketed.tracks.first().title, BackupCodec.decode(BackupCodec.encode(bracketed)).tracks.first().title)
    }

    @Test fun `unpaired surrogates cannot silently corrupt exported metadata`() {
        assertThrows(SourceFailure::class.java) {
            BackupCodec.encode(fixture().copy(tracks = listOf(youtube.copy(title = "bad\uD800"), local)))
        }
        val root = json()
        val raw = root.toString().replace("Тихий океан 🌊", "\\ud800")
        rejects(raw)
    }

    @Test fun `size and collection limits are enforced before serialization`() {
        rejects(ByteArray(BackupCodec.MAX_BYTES + 1))
        assertThrows(SourceFailure::class.java) { BackupCodec.encode(fixture().copy(tracks = List(20_001) { youtube })) }
        val playlists = List(2_001) { BackupPlaylist(Playlist("p$it", "Playlist", 1), emptyList()) }
        assertThrows(SourceFailure::class.java) { BackupCodec.encode(fixture().copy(playlists = playlists)) }
        val histories = List(100_001) { ListeningSession("h$it", youtube.id, 1, 0, false) }
        assertThrows(SourceFailure::class.java) { BackupCodec.encode(fixture().copy(history = histories)) }
        assertThrows(SourceFailure::class.java) {
            BackupCodec.encode(fixture().copy(tracks = listOf(youtube.copy(sourceUrl = "https://example.test/" + "x".repeat(8_192)), local)))
        }
        assertThrows(SourceFailure::class.java) { BackupCodec.portableTrackId(local.copy(id = ""), libraryId) }
        assertThrows(SourceFailure::class.java) { BackupCodec.portableTrackId(local.copy(id = "x".repeat(257)), libraryId) }
    }

    @Test fun `large valid metadata is rejected by the byte budget without constructing JSON`() {
        val text = "я".repeat(2_000)
        val tracks = List(5_000) { index ->
            val id = "video" + index.toString().padStart(6, '0')
            youtube.copy(id = "youtube:$id", sourceId = id, title = text, artist = text, album = text)
        }
        val error = assertThrows(SourceFailure::class.java) {
            BackupCodec.encode(LibraryBackup(libraryId, 1, tracks, emptyList(), emptyList()))
        }
        assertTrue(error.message!!.contains("16 МБ"))
    }

    @Test fun `byte budget accounts for escaped Unicode and supplementary characters`() {
        val special = "Трек 🌊 \"цитата\" \\ путь / \u2028"
        val copy = fixture().copy(tracks = listOf(youtube.copy(title = special), local))
        val encoded = BackupCodec.encode(copy)
        assertEquals(special, BackupCodec.decode(encoded).tracks.first().title)
    }

    @Test fun `version two roundtrips smart playlist rules without materializing their tracks`() {
        val rules = SmartRules(favorite = true, source = "yandex", query = "вечер", downloaded = true,
            unplayed = false, notPlayedDays = 7, order = SmartOrder.LEAST_PLAYED)
        val smart = SmartPlaylist("smart-1", "Мой вечер", rules.encode(), 222)
        val restored = BackupCodec.decode(BackupCodec.encode(fixture().copy(smartPlaylists = listOf(smart))))
        assertEquals(smart, restored.smartPlaylists.single())
        assertEquals(1, restored.summary.smartPlaylists)
        assertEquals(fixture().playlists.size, restored.playlists.size)
        assertEquals(rules, SmartRules.decode(restored.smartPlaylists.single().rulesJson))
    }

    @Test fun `reader retains version one compatibility and rejects malformed smart rules`() {
        val legacy = json().put("version", 1).apply { remove("smartPlaylists") }
        val restored = decode(legacy)
        assertTrue(restored.smartPlaylists.isEmpty())
        assertEquals(fixture().summary, restored.summary)
        val smart = SmartPlaylist("smart", "Список", SmartRules().encode(), 1)
        val root = JSONObject(String(BackupCodec.encode(fixture().copy(smartPlaylists = listOf(smart))), StandardCharsets.UTF_8))
        rejects(root.apply { getJSONArray("smartPlaylists").getJSONObject(0).getJSONObject("rules").put("limit", 0) })
        assertThrows(SourceFailure::class.java) {
            BackupCodec.encode(fixture().copy(smartPlaylists = listOf(smart, smart)))
        }
    }

    @Test fun `Yandex metadata exports canonical page and safe unsigned cover`() {
        val track = Track("yandex:12345", "Музыка", source = "yandex", sourceId = "12345", addedAt = 0,
            sourceUrl = "https://music.yandex.ru/album/999/track/12345?token=private",
            coverUrl = "https://avatars.yandex.net/get-music-content/image/400x400?token=private")
        val restored = BackupCodec.decode(BackupCodec.encode(LibraryBackup(libraryId, 1, listOf(track), emptyList(), emptyList())))
        assertEquals("https://music.yandex.ru/track/12345", restored.tracks.single().sourceUrl)
        assertEquals("https://avatars.yandex.net/get-music-content/image/400x400", restored.tracks.single().coverUrl)
        assertThrows(SourceFailure::class.java) {
            BackupCodec.encode(LibraryBackup(libraryId, 1, listOf(track.copy(id = "yandex:98765")), emptyList(), emptyList()))
        }
    }

    @Test fun `v3 preserves desktop play evidence without granting offline availability`() {
        val track=youtube.copy(importedPlayCount=42,importedLastPlayed=123456,importedDownloaded=true)
        val restored=BackupCodec.decode(BackupCodec.encode(LibraryBackup(libraryId,1,listOf(track),emptyList(),emptyList())))
        assertEquals(track,restored.tracks.single())
        assertEquals("",restored.tracks.single().localUri)
    }

    @Test fun `v2 legacy tracks remain readable without the new optional evidence fields`() {
        val root=json().put("version",2)
        val tracks=root.getJSONArray("tracks")
        for(index in 0 until tracks.length())for(key in listOf("importedPlayCount","importedLastPlayed","importedDownloaded"))tracks.getJSONObject(index).remove(key)
        assertEquals(fixture().summary,decode(root).summary)
    }

    @Test fun `missing object fields cannot become plausible defaults`() {
        rejects(json().apply {getJSONArray("tracks").getJSONObject(0).remove("favorite")})
        rejects(json().apply {getJSONArray("playlists").getJSONObject(0).remove("trackIds")})
        rejects(json().apply {getJSONArray("history").getJSONObject(0).remove("completed")})
    }

    @Test fun `failure messages distinguish the action format and unsupported version`() {
        val malformed=rejects("{broken").message!!
        val unsupported=rejects(json().put("version",99)).message!!
        val foreign=rejects(json().put("format","other")).message!!
        assertTrue(malformed.startsWith("Не удалось прочитать копию:"))
        assertTrue(unsupported.contains("версия"));assertTrue(foreign.contains("не копия"))
        val export=assertThrows(SourceFailure::class.java) {BackupCodec.encode(fixture().copy(tracks=List(20_001){youtube}))}
        assertTrue(export.message!!.startsWith("Не удалось сохранить копию:"))
        assertFalse(export.message!!.startsWith("Не удалось прочитать"))
    }

    @Test fun `size measurement is exact for escaped Unicode and v3 evidence`() {
        val backup=fixture().copy(tracks=listOf(youtube.copy(title="\"я🎵\\\n",importedPlayCount=123),local))
        assertEquals(BackupCodec.encode(backup).size.toLong(),BackupCodec.encodedSize(BackupCodec.portable(backup)))
    }

    @Test fun `escaped Unicode smart rule strings survive the streaming raw value reader`() {
        val rules=SmartRules(artist="песня").encode().replace("песня","\\u043f\\u0435\\u0441\\u043d\\u044f")
        val backup=fixture().copy(smartPlaylists=listOf(SmartPlaylist("smart","Test",rules,1)))
        val raw=String(BackupCodec.encode(backup),Charsets.UTF_8).replace("песня","\\u043f\\u0435\\u0441\\u043d\\u044f")
        assertEquals("песня",SmartRules.decode(BackupCodec.decode(raw.toByteArray()).smartPlaylists.single().rulesJson).artist)
    }

    /**
     * The bounded heap of the codec.
     *
     * A backup of 16.6 MB used to cost 183 MB of live heap: the org.json document model alone
     * was 86.2 MB, and the `OutOfMemoryError` that followed escaped every `catch(Exception)`.
     * The codec must therefore never build a document model, and every boundary that reads or
     * writes a document of an unknown size must turn an allocation failure into an ordinary,
     * user facing failure.
     */
    @Test fun `the codec never builds a document model and converts an allocation failure`() {
        val source=source("ru/nedotify/app/data/BackupCodec.kt")
        assertFalse("the codec must not use the org.json document model",source.contains("org.json"))
        assertFalse("the codec must not use JSONObject",source.contains("JSONObject"))
        assertFalse("the codec must not use JSONArray",source.contains("JSONArray"))
        // The conversion of an allocation failure is a behaviour, not a phrase in the source.
        val failure=assertThrows(SourceFailure::class.java) {
            allocationSafe("Не удалось прочитать копию: копия слишком велика для памяти устройства") {
                throw OutOfMemoryError()
            }
        }
        assertEquals("Не удалось прочитать копию: копия слишком велика для памяти устройства",failure.message)
    }

    /**
     * The rules of a smart playlist are bounded before they are read.
     *
     * A copy with a huge rules field must not cost its own size in the heap, and the failure
     * must speak for the boundary that read it.
     */
    @Test fun `a huge rules field is refused with the prefix of the boundary`() {
        val rules="{\"version\":1,\"artist\":\""+"я".repeat(20_000)+"\"}"
        val document=String(BackupCodec.encode(fixture()),StandardCharsets.UTF_8)
            .replace("\"smartPlaylists\":[]","\"smartPlaylists\":[{\"id\":\"smart\",\"name\":\"Правила\",\"createdAt\":1,\"rules\":$rules}]")
        val failure=assertThrows(SourceFailure::class.java) {BackupCodec.decode(document.toByteArray(StandardCharsets.UTF_8))}
        assertTrue("the failure must speak for the boundary: ${failure.message}",
            failure.message!!.startsWith("Не удалось прочитать копию:"))
    }

    /**
     * A broken rule row does not escape the prefix of the boundary.
     *
     * REG P2-15: the read and the write name their action. A smart playlist whose rules cannot
     * be read is a failed read of a copy, not a raw failure of the rule parser.
     */
    @Test fun `a broken rules row does not escape the prefix of the boundary`() {
        val broken=fixture().copy(smartPlaylists=listOf(SmartPlaylist("smart","Правила","{\"version\":2}",1)))
        val failure=assertThrows(SourceFailure::class.java) {BackupCodec.encode(broken)}
        assertTrue("the failure must speak for the boundary: ${failure.message}",
            failure.message!!.startsWith("Не удалось сохранить копию:"))
        val document=String(BackupCodec.encode(fixture()),StandardCharsets.UTF_8)
            .replace("\"smartPlaylists\":[]","\"smartPlaylists\":[{\"id\":\"smart\",\"name\":\"Правила\",\"createdAt\":1,\"rules\":{\"version\":2}}]")
        val read=assertThrows(SourceFailure::class.java) {BackupCodec.decode(document.toByteArray(StandardCharsets.UTF_8))}
        assertTrue("the failure must speak for the boundary: ${read.message}",
            read.message!!.startsWith("Не удалось прочитать копию:"))
    }

    /**
     * The size of the history is measured on its own.
     *
     * The export fits the newest sessions into the byte budget with a binary search. The
     * history is the only part of the document that grows, and the sizes of its prefixes must
     * agree with the size of the whole document.
     */
    @Test fun `the measured sizes of the history agree with the size of the document`() {
        val backup=fixture()
        val portable=BackupCodec.portable(backup)
        val prefix=BackupCodec.historyPrefixSizes(portable.history)
        assertEquals("the empty history",2L,prefix[0])
        assertEquals("the whole history",BackupCodec.historySize(portable.history),prefix[portable.history.size])
        for(count in 0..portable.history.size) {
            val document=portable.copy(history=portable.history.take(count))
            val measured=BackupCodec.encodedSize(document)
            assertEquals("the document of $count sessions",measured,
                BackupCodec.encodedSize(portable.copy(history=emptyList()))-2+prefix[count])
        }
    }

    /** The source of a file of the module, found from the working directory. */
    private fun source(path:String):String {
        var root=java.io.File("").absoluteFile
        while(root.parentFile!=null) {
            val file=java.io.File(root,"src/main/java/$path")
            if(file.isFile)return file.readText()
            root=root.parentFile
        }
        throw AssertionError("the source file $path was not found")
    }
}
