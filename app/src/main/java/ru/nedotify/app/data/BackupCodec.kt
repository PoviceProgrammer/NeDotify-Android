package ru.nedotify.app.data

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * A codec failure that still carries its bare reason, so that the caller can add the prefix that
 * fits its own context ("Не удалось сохранить копию", "Не удалось перенести библиотеку ПК", …).
 */
class BackupFormatException(val reason: String) : SourceFailure(reason)

data class BackupPlaylist(val playlist: Playlist, val trackIds: List<String>)
data class BackupSummary(val tracks: Int, val favorites: Int, val playlists: Int, val sessions: Int, val localTracks: Int,
    val smartPlaylists: Int = 0, val droppedSessions: Int = 0)
data class LibraryBackup(
    val libraryId: String,
    val exportedAt: Long,
    val tracks: List<Track>,
    val playlists: List<BackupPlaylist>,
    val history: List<ListeningSession>,
    val smartPlaylists: List<SmartPlaylist> = emptyList(),
) {
    val summary: BackupSummary get() = BackupSummary(tracks.size, tracks.count { it.favorite },
        playlists.size, history.size, tracks.count { it.source == "local" }, smartPlaylists.size)
}

/**
 * Portable metadata only. Device grants, playback URLs and download files never enter this format.
 *
 * The codec is **streaming in both directions**: neither the writer nor the reader ever holds a
 * JSON document object model. A document inside the format's own caps used to cost roughly ten
 * times its own size in live heap (a 16.6 MB document measured 183 MB, and `OutOfMemoryError` is
 * an `Error`, so it escaped every `catch (Exception)` and killed the process). The reader now
 * builds the domain objects straight from the byte array, and the writer appends straight to the
 * output stream, so the peak cost is the document plus the model — never a DOM.
 */
object BackupCodec {
    /** The byte ceiling of one backup document. */
    const val MAX_BYTES = 16 * 1024 * 1024
    /** The track ceiling of one backup. This is a documented product limit of the format. */
    const val MAX_TRACKS = 20_000
    /** The playlist ceiling of one backup. This is a documented product limit of the format. */
    const val MAX_PLAYLISTS = 2_000
    /** The playlist-membership ceiling of one backup. */
    const val MAX_LINKS = 100_000
    /** The listening-session ceiling of one backup. */
    const val MAX_SESSIONS = 100_000
    /** The ceiling of the rules of one smart playlist, in bytes of the document. */
    private const val MAX_RULES_BYTES = 8_192
    private const val MAX_TIMESTAMP = 253_402_300_799_999L
    private const val MAX_DURATION = 604_800_000L
    private const val MAX_LISTENED = 31_622_400_000L
    private const val FORMAT = "nedotify-library"
    private const val VERSION = 3L
    private val portableId = Regex("portable-local:[0-9a-f]{64}")
    private val youtubeId = Regex("[A-Za-z0-9_-]{11}")
    private val soundCloudId = Regex("[1-9][0-9]{0,19}")
    private val yandexId = Regex("[0-9]{1,24}")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private val rootKeys = setOf("format", "version", "libraryId", "exportedAt", "tracks", "playlists", "history", "smartPlaylists")
    private val trackKeys = setOf("id", "title", "artist", "album", "durationMs", "source", "sourceId", "sourceUrl",
        "coverUrl", "favorite", "addedAt", "mimeType", "importedPlayCount", "importedLastPlayed", "importedDownloaded")
    private val originalTrackKeys=trackKeys-setOf("importedPlayCount","importedLastPlayed","importedDownloaded")
    private val playlistKeys = setOf("id", "name", "createdAt", "trackIds")
    private val sessionKeys = setOf("id", "trackId", "startedAt", "listenedMs", "completed")
    private val smartKeys = setOf("id", "name", "createdAt", "rules")

    fun portableTrackId(track: Track, libraryId: String): String {
        if (track.source != "local") return track.id
        checkedText(track.id, 256, false)
        if (portableId.matches(track.id)) return track.id
        requireLibraryId(libraryId, ::invalidWrite)
        val digest = MessageDigest.getInstance("SHA-256").digest((libraryId.lowercase(Locale.ROOT) + '\u0000' + track.id)
            .toByteArray(StandardCharsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 255
            hex[index * 2] = "0123456789abcdef"[value ushr 4]
            hex[index * 2 + 1] = "0123456789abcdef"[value and 15]
        }
        return "portable-local:" + String(hex)
    }

    /**
     * The portable form of a backup: private paths, signed URLs and device identities are removed
     * and the local track identities are rewritten into their portable form. The result is what the
     * format actually carries, so its size can be measured before anything is written.
     */
    fun portable(backup: LibraryBackup): LibraryBackup {
        requireLibraryId(backup.libraryId, ::invalidWrite)
        checkCounts(backup, ::invalidWrite)
        // Validate raw fields before hashing IDs or parsing URLs. Dropping a private URL
        // must not first let a corrupt multi-megabyte field allocate parser buffers.
        backup.tracks.forEach { track ->
            checkedText(track.id, 256, false, ::invalidWrite); checkedText(track.title, 2_048, false, ::invalidWrite)
            checkedText(track.artist, 2_048, empty = true, fail = ::invalidWrite)
            checkedText(track.album, 2_048, empty = true, fail = ::invalidWrite)
            checkedText(track.source, 32, false, ::invalidWrite); checkedText(track.sourceId, 128, empty = true, fail = ::invalidWrite)
            checkedText(track.sourceUrl, 8_192, empty = true, fail = ::invalidWrite)
            checkedText(track.coverUrl, 8_192, empty = true, fail = ::invalidWrite)
            checkedText(track.mimeType, 128, empty = true, fail = ::invalidWrite)
            if (track.localUri.length > 16_384) invalidWrite("слишком длинный адрес локального файла")
        }
        val remap = backup.tracks.associate { it.id to portableTrackId(it, backup.libraryId) }
        if (remap.size != backup.tracks.size) invalidWrite("повторяется идентификатор трека")
        val portable = backup.copy(
            libraryId = backup.libraryId.lowercase(Locale.ROOT),
            tracks = backup.tracks.map { track -> track.copy(
                id = remap.getValue(track.id),
                sourceId = if (track.source == "local") "" else track.sourceId,
                sourceUrl = canonicalPage(track), localUri = "",
                coverUrl = safeCover(track.coverUrl, track.source),
            ) },
            playlists = backup.playlists.map { entry -> entry.copy(trackIds = entry.trackIds.map { id ->
                remap[id] ?: invalidWrite("плейлист ссылается на отсутствующий трек")
            }) },
            history = backup.history.map { session -> session.copy(trackId = remap[session.trackId]
                ?: invalidWrite("история ссылается на отсутствующий трек")) },
        )
        validate(portable, ::invalidWrite)
        return portable
    }

    /** The exact number of bytes [encode] would produce for this backup. */
    fun encodedSize(backup: LibraryBackup): Long {
        val size = JsonSize()
        size.obj(8)
        size.field("format"); size.text(FORMAT)
        size.field("version"); size.number(VERSION)
        size.field("libraryId"); size.text(backup.libraryId)
        size.field("exportedAt"); size.number(backup.exportedAt)
        size.field("tracks"); size.array(backup.tracks.size)
        backup.tracks.forEach { track ->
            size.obj(15)
            size.field("id"); size.text(track.id)
            size.field("title"); size.text(track.title)
            size.field("artist"); size.text(track.artist)
            size.field("album"); size.text(track.album)
            size.field("durationMs"); size.number(track.durationMs)
            size.field("source"); size.text(track.source)
            size.field("sourceId"); size.text(track.sourceId)
            size.field("sourceUrl"); size.text(track.sourceUrl)
            size.field("coverUrl"); size.text(track.coverUrl)
            size.field("favorite"); size.boolean(track.favorite)
            size.field("addedAt"); size.number(track.addedAt)
            size.field("mimeType"); size.text(track.mimeType)
            size.field("importedPlayCount"); size.number(track.importedPlayCount.toLong())
            size.field("importedLastPlayed"); size.number(track.importedLastPlayed)
            size.field("importedDownloaded"); size.boolean(track.importedDownloaded)
        }
        size.field("playlists"); size.array(backup.playlists.size)
        backup.playlists.forEach { entry ->
            size.obj(4)
            size.field("id"); size.text(entry.playlist.id)
            size.field("name"); size.text(entry.playlist.name)
            size.field("createdAt"); size.number(entry.playlist.createdAt)
            size.field("trackIds"); size.array(entry.trackIds.size)
            entry.trackIds.forEach(size::text)
        }
        size.field("history"); size.array(backup.history.size)
        backup.history.forEach { session -> size.session(session) }
        size.field("smartPlaylists"); size.array(backup.smartPlaylists.size)
        backup.smartPlaylists.forEach { playlist ->
            size.obj(4)
            size.field("id"); size.text(playlist.id)
            size.field("name"); size.text(playlist.name)
            size.field("createdAt"); size.number(playlist.createdAt)
            size.field("rules"); size.json(rules(playlist.rulesJson, ::invalidWrite).encode())
        }
        return size.bytes
    }

    /**
     * The size of the history array alone.
     *
     * The export fits the newest sessions into the byte budget. Measuring the whole document
     * again for every step of that search costs one pass over every track and every playlist per
     * step; the history is the only part that grows, so it is measured on its own.
     */
    internal fun historySize(sessions: List<ListeningSession>): Long {
        val size = JsonSize()
        size.array(sessions.size)
        sessions.forEach { session -> size.session(session) }
        return size.bytes
    }

    /**
     * The size of the history array of the first *k* sessions, for every *k*.
     *
     * The export fits the newest sessions into the byte budget with a binary search. The
     * history is measured once, in one pass, and the search reads the sizes from here.
     */
    internal fun historyPrefixSizes(sessions: List<ListeningSession>): LongArray {
        val size = JsonSize()
        val prefix = LongArray(sessions.size + 1)
        prefix[0] = 2 // "[]"
        var total = 0L
        sessions.forEachIndexed { index, session ->
            val before = size.bytes
            size.session(session)
            total += size.bytes - before
            prefix[index + 1] = (index + 2).toLong() + total
        }
        return prefix
    }

    fun encode(backup:LibraryBackup,failurePrefix:String="Не удалось сохранить копию"):ByteArray =
        allocationSafe("$failurePrefix: копия слишком велика для памяти устройства") {
            try { encodeDocument(backup,failurePrefix) }
            catch(error:BackupFormatException) {throw SourceFailure("$failurePrefix: ${error.reason}")}
        }

    private fun encodeDocument(backup: LibraryBackup, failurePrefix: String): ByteArray {
        val portable = try {
            portable(backup)
        } catch (error: BackupFormatException) {
            throw SourceFailure("$failurePrefix: ${error.reason}")
        }
        val size = encodedSize(portable)
        if (size > MAX_BYTES) invalidWrite("размер копии превышает 16 МБ")
        val out = SizedStream(size.toInt())
        val writer = JsonWriter(out)
        writer.objStart()
        writer.key("format"); writer.text(FORMAT)
        writer.key("version"); writer.number(VERSION)
        writer.key("libraryId"); writer.text(portable.libraryId)
        writer.key("exportedAt"); writer.number(portable.exportedAt)
        writer.key("tracks"); writer.arrStart()
        portable.tracks.forEach { track ->
            writer.objStart()
            writer.key("id"); writer.text(track.id)
            writer.key("title"); writer.text(track.title)
            writer.key("artist"); writer.text(track.artist)
            writer.key("album"); writer.text(track.album)
            writer.key("durationMs"); writer.number(track.durationMs)
            writer.key("source"); writer.text(track.source)
            writer.key("sourceId"); writer.text(track.sourceId)
            writer.key("sourceUrl"); writer.text(track.sourceUrl)
            writer.key("coverUrl"); writer.text(track.coverUrl)
            writer.key("favorite"); writer.bool(track.favorite)
            writer.key("addedAt"); writer.number(track.addedAt)
            writer.key("mimeType"); writer.text(track.mimeType)
            writer.key("importedPlayCount"); writer.number(track.importedPlayCount.toLong())
            writer.key("importedLastPlayed"); writer.number(track.importedLastPlayed)
            writer.key("importedDownloaded"); writer.bool(track.importedDownloaded)
            writer.objEnd()
        }
        writer.arrEnd()
        writer.key("playlists"); writer.arrStart()
        portable.playlists.forEach { entry ->
            writer.objStart()
            writer.key("id"); writer.text(entry.playlist.id)
            writer.key("name"); writer.text(entry.playlist.name)
            writer.key("createdAt"); writer.number(entry.playlist.createdAt)
            writer.key("trackIds"); writer.arrStart()
            entry.trackIds.forEach(writer::text)
            writer.arrEnd()
            writer.objEnd()
        }
        writer.arrEnd()
        writer.key("history"); writer.arrStart()
        portable.history.forEach { session ->
            writer.objStart()
            writer.key("id"); writer.text(session.id)
            writer.key("trackId"); writer.text(session.trackId)
            writer.key("startedAt"); writer.number(session.startedAt)
            writer.key("listenedMs"); writer.number(session.listenedMs)
            writer.key("completed"); writer.bool(session.completed)
            writer.objEnd()
        }
        writer.arrEnd()
        writer.key("smartPlaylists"); writer.arrStart()
        portable.smartPlaylists.forEach { playlist ->
            writer.objStart()
            writer.key("id"); writer.text(playlist.id)
            writer.key("name"); writer.text(playlist.name)
            writer.key("createdAt"); writer.number(playlist.createdAt)
            writer.key("rules"); writer.raw(rules(playlist.rulesJson, ::invalidWrite).encode())
            writer.objEnd()
        }
        writer.arrEnd()
        writer.objEnd()
        return out.bytes()
    }

    /** The rules of a smart playlist, with the failure of the codec's own prefix. */
    private fun rules(json:String,fail:(String)->Nothing):SmartRules =
        try { SmartRules.decode(json) } catch (_:SourceFailure) { fail("неверные правила умного плейлиста") }

    fun decode(bytes: ByteArray): LibraryBackup {
        if (bytes.size > MAX_BYTES)
            throw SourceFailure("Не удалось прочитать копию: размер копии превышает 16 МБ")
        return allocationSafe("Не удалось прочитать копию: копия слишком велика для памяти устройства") {
            try {
                decodeDocument(bytes)
            } catch (error: BackupFormatException) {
                throw SourceFailure("Не удалось прочитать копию: ${error.reason}")
            } catch (error: SourceFailure) {
                throw error
            } catch (_: Exception) {
                throw SourceFailure("Не удалось прочитать копию: файл повреждён или содержит недопустимые данные")
            }
        }
    }

    private fun decodeDocument(bytes: ByteArray): LibraryBackup {
        val reader = JsonReader(bytes)
        reader.skipBom()
        reader.skipWhitespace()
        reader.expect('{'.code)
        val seen = HashSet<String>()
        var format = ""
        var version = 0L
        var libraryId = ""
        var exportedAt = 0L
        val tracks = ArrayList<Track>()
        val playlists = ArrayList<BackupPlaylist>()
        val history = ArrayList<ListeningSession>()
        val smart = ArrayList<SmartPlaylist>()
        var links = 0
        reader.eachMember(rootKeys, seen) { key ->
            when (key) {
                "format" -> format = reader.readString(40)
                "version" -> version = reader.readLong()
                "libraryId" -> libraryId = reader.readString(36)
                "exportedAt" -> exportedAt = reader.readLong()
                "tracks" -> reader.readArray(MAX_TRACKS) { tracks.add(reader.readTrack()) }
                "playlists" -> reader.readArray(MAX_PLAYLISTS) {
                    val entry = reader.readPlaylist()
                    links += entry.trackIds.size
                    if (links > MAX_LINKS) invalidRead("в копии слишком много записей плейлистов")
                    playlists.add(entry)
                }
                "history" -> reader.readArray(MAX_SESSIONS) { history.add(reader.readSession()) }
                "smartPlaylists" -> reader.readArray(MAX_PLAYLISTS) { smart.add(reader.readSmartPlaylist()) }
            }
        }
        if (reader.hasTrailingData()) invalidRead("лишние данные после JSON")
        if (format != FORMAT) invalidRead("это не копия библиотеки NeDotify")
        if (version !in 1..VERSION) invalidRead("версия копии пока не поддерживается")
        val expected = if (version == 1L) rootKeys - "smartPlaylists" else rootKeys
        if (seen != expected) invalidRead("неверный набор полей в копии")
        return LibraryBackup(libraryId.lowercase(Locale.ROOT), exportedAt,
            tracks, playlists, history, smart).also { validate(it, ::invalidRead) }
    }

    private fun checkCounts(backup: LibraryBackup, fail: (String) -> Nothing) {
        if (backup.tracks.size > MAX_TRACKS)
            fail("в копии больше $MAX_TRACKS треков — это предел формата копии")
        if (backup.playlists.size + backup.smartPlaylists.size > MAX_PLAYLISTS)
            fail("в копии больше $MAX_PLAYLISTS плейлистов — это предел формата копии")
        if (backup.history.size > MAX_SESSIONS)
            fail("в копии больше $MAX_SESSIONS записей истории — это предел формата копии")
        if (backup.playlists.sumOf { it.trackIds.size.toLong() } > MAX_LINKS)
            fail("в копии слишком много записей плейлистов")
    }

    /** Account for JSON punctuation, escaped strings and UTF-8 before allocating anything. */
    private class JsonSize {
        var bytes = 0L
            private set
        private fun add(count: Int) { bytes += count }
        fun obj(fields: Int) = add(2 + (fields - 1).coerceAtLeast(0))
        fun array(items: Int) = add(2 + (items - 1).coerceAtLeast(0))
        fun field(name: String) { text(name); add(1) }
        fun number(value: Long) = add(value.toString().length)
        fun boolean(value: Boolean) = add(if (value) 4 else 5)
        fun json(value: String) = add(value.toByteArray(StandardCharsets.UTF_8).size)
        fun session(session: ListeningSession) {
            obj(5)
            field("id"); text(session.id)
            field("trackId"); text(session.trackId)
            field("startedAt"); number(session.startedAt)
            field("listenedMs"); number(session.listenedMs)
            field("completed"); boolean(session.completed)
        }
        fun text(value: String) {
            add(2)
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                add(when {
                    char == '"' || char == '\\' -> 2
                    char == '\b' || char == '\u000C' || char == '\n' || char == '\r' || char == '\t' -> 2
                    char < ' ' -> 6
                    char <= '\u007F' -> 1
                    char <= '\u07FF' -> 2
                    Character.isHighSurrogate(char) -> { index++; 4 }
                    else -> 3
                })
            }
        }
    }

    private fun validate(backup: LibraryBackup, fail: (String) -> Nothing) {
        checkCounts(backup, fail)
        requireLibraryId(backup.libraryId, fail)
        bounded(backup.exportedAt, MAX_TIMESTAMP, fail)
        val ids = HashSet<String>()
        backup.tracks.forEach { track ->
            checkedText(track.id, 256, false, fail)
            if (!ids.add(track.id)) fail("повторяется идентификатор трека")
            checkedText(track.title, 2_048, false, fail); checkedText(track.artist, 2_048, empty = true, fail = fail)
            checkedText(track.album, 2_048, empty = true, fail = fail)
            checkedText(track.mimeType, 128, empty = true, fail = fail)
            bounded(track.durationMs, MAX_DURATION, fail); bounded(track.addedAt, MAX_TIMESTAMP, fail)
            bounded(track.importedPlayCount.toLong(),Int.MAX_VALUE.toLong(),fail)
            bounded(track.importedLastPlayed,MAX_TIMESTAMP,fail)
            if (track.localUri.isNotEmpty()) fail("копия содержит путь к локальному файлу")
            when (track.source) {
                "local" -> if (!portableId.matches(track.id) || track.sourceId.isNotEmpty() || track.sourceUrl.isNotEmpty() || track.coverUrl.isNotEmpty())
                    fail("неверная переносимая локальная запись")
                "youtube" -> if (!youtubeId.matches(track.sourceId) || track.id != "youtube:${track.sourceId}") fail("неверный идентификатор YouTube")
                "soundcloud" -> if (!soundCloudId.matches(track.sourceId) || track.id != "soundcloud:${track.sourceId}") fail("неверный идентификатор SoundCloud")
                "yandex" -> if (!yandexId.matches(track.sourceId) || track.id != "yandex:${track.sourceId}") fail("неверный идентификатор Яндекс Музыки")
                else -> fail("неизвестный источник музыки")
            }
            if (track.sourceUrl != canonicalPage(track)) fail("небезопасная ссылка на источник")
            if (track.coverUrl != safeCover(track.coverUrl, track.source)) fail("небезопасная ссылка на обложку")
        }
        val playlistIds = HashSet<String>()
        backup.playlists.forEach { entry ->
            checkedText(entry.playlist.id, 256, false, fail); checkedText(entry.playlist.name, 2_048, false, fail)
            bounded(entry.playlist.createdAt, MAX_TIMESTAMP, fail)
            if (!playlistIds.add(entry.playlist.id)) fail("повторяется идентификатор плейлиста")
            if (entry.trackIds.size > MAX_TRACKS || entry.trackIds.toSet().size != entry.trackIds.size) fail("в плейлисте повторяется трек")
            if (entry.trackIds.any { it !in ids }) fail("плейлист ссылается на отсутствующий трек")
        }
        val smartIds = HashSet<String>()
        backup.smartPlaylists.forEach { playlist ->
            checkedText(playlist.id, 256, false, fail); checkedText(playlist.name, 2_048, false, fail)
            bounded(playlist.createdAt, MAX_TIMESTAMP, fail)
            if (!smartIds.add(playlist.id)) fail("повторяется идентификатор умного плейлиста")
            // The rules speak for themselves; the failure must speak for the codec as well
            // (REG P2-15): a broken rule row may not escape the prefix of the boundary.
            rules(playlist.rulesJson, fail)
        }
        val sessionIds = HashSet<String>()
        backup.history.forEach { session ->
            checkedText(session.id, 256, false, fail)
            if (!sessionIds.add(session.id)) fail("повторяется идентификатор прослушивания")
            if (session.trackId !in ids) fail("история ссылается на отсутствующий трек")
            bounded(session.startedAt, MAX_TIMESTAMP, fail); bounded(session.listenedMs, MAX_LISTENED, fail)
        }
    }

    private fun canonicalPage(track: Track): String = when (track.source) {
        "youtube" -> if (youtubeId.matches(track.sourceId)) "https://music.youtube.com/watch?v=${track.sourceId}" else ""
        "yandex" -> if (yandexId.matches(track.sourceId)) "https://music.yandex.ru/track/${track.sourceId}" else ""
        "soundcloud" -> runCatching {
            val uri = URI(track.sourceUrl)
            val path = uri.rawPath.orEmpty()
            if (uri.scheme.equals("https", true) && uri.host.equals("soundcloud.com", true) && uri.rawUserInfo == null && uri.port == -1 &&
                path.length <= 2_000 && Regex("/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+").matches(path) &&
                path.substringBeforeLast('/').lowercase(Locale.ROOT) !in setOf("/tracks", "/search", "/discover", "/settings"))
                "https://soundcloud.com$path" else ""
        }.getOrDefault("")
        else -> ""
    }

    private fun safeCover(value: String, source: String): String {
        if (value.isBlank() || source == "local") return ""
        return runCatching {
            val uri = URI(value)
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val approved = when (source) {
                "soundcloud" -> host == "i1.sndcdn.com" || host == "i2.sndcdn.com"
                "youtube" -> host in setOf("i.ytimg.com", "img.youtube.com", "yt3.ggpht.com", "lh3.googleusercontent.com")
                "yandex" -> host in setOf("avatars.yandex.net", "avatars.mds.yandex.net")
                else -> false
            }
            val path = uri.rawPath.orEmpty()
            if (!approved || !uri.scheme.equals("https", true) || uri.rawUserInfo != null || uri.port != -1 || path.isBlank() || path.length > 2_000 ||
                Regex("(?i)(videoplayback|\\.(m3u8|mp3|m4a|mp4|aac|ts)(/|$))").containsMatchIn(path)) ""
            else "https://$host$path"
        }.getOrDefault("")
    }

    private fun requireLibraryId(value: String, fail: (String) -> Nothing) {
        if (!uuid.matches(value) || runCatching { UUID.fromString(value) }.isFailure) fail("неверный идентификатор библиотеки")
    }
    private fun bounded(value: Long, maximum: Long, fail: (String) -> Nothing) {
        if (value !in 0..maximum) fail("число выходит за допустимые границы")
    }
    private fun checkedText(value: String, max: Int, empty: Boolean = true, fail: (String) -> Nothing = ::invalidRead) {
        if (value.length > max || (!empty && value.isBlank()) || value.any { it == '\u0000' || it in '\u0001'..'\u0008' || it in '\u000B'..'\u001F' })
            fail("недопустимый текст в копии")
        // A lone surrogate cannot round-trip as UTF-8 and must not silently become '?'.
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (Character.isHighSurrogate(char)) {
                if (index >= value.length || !Character.isLowSurrogate(value[index++])) fail("недопустимый текст в копии")
            } else if (Character.isLowSurrogate(char)) fail("недопустимый текст в копии")
        }
    }

    private fun invalidRead(reason: String): Nothing = throw BackupFormatException(reason)
    private fun invalidWrite(reason: String): Nothing = throw BackupFormatException(reason)
    private fun invalidDocument(reason: String): Nothing = throw BackupFormatException("Некорректный JSON: $reason")

    internal fun requireStrictJson(text: String) { JsonShape(text).validate() }

    /** Small strict grammar check; recursive calls are bounded before entering each container. */
    private class JsonShape(private val text: String) {
        private var position = 0
        fun validate() {
            whitespace(); if (position >= text.length || text[position] != '{') invalidDocument("ожидается объект JSON")
            value(0); whitespace(); if (position != text.length) invalidDocument("лишние данные после JSON")
        }
        private fun value(depth: Int) {
            whitespace()
            when (text.getOrNull(position)) {
                '{' -> container(depth, true)
                '[' -> container(depth, false)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> invalidDocument("повреждённый JSON")
            }
        }
        private fun container(depth: Int, objectValue: Boolean) {
            if (depth >= 32) invalidDocument("слишком глубокая вложенность JSON")
            position++; whitespace()
            val close = if (objectValue) '}' else ']'
            if (take(close)) return
            val keys = if (objectValue) HashSet<String>() else null
            while (true) {
                if (objectValue) {
                    if (text.getOrNull(position) != '"') invalidDocument("ключ JSON должен быть строкой")
                    val key = string()
                    if (!keys!!.add(key)) invalidDocument("повторяется поле JSON")
                    whitespace(); if (!take(':')) invalidDocument("повреждённый JSON")
                }
                value(depth + 1); whitespace()
                if (take(close)) return
                if (!take(',')) invalidDocument("повреждённый JSON")
                whitespace()
            }
        }
        private fun string(): String {
            position++
            val result = StringBuilder()
            while (position < text.length) {
                val char = text[position++]
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> {
                        val escaped = text.getOrNull(position++) ?: invalidDocument("незаконченная строка JSON")
                        result.append(when (escaped) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                            'u' -> {
                                if (position + 4 > text.length) invalidDocument("неверный Unicode в JSON")
                                val hex = text.substring(position, position + 4)
                                if (hex.any { it.digitToIntOrNull(16) == null }) invalidDocument("неверный Unicode в JSON")
                                position += 4; hex.toInt(16).toChar()
                            }
                            else -> invalidDocument("неверная escape-последовательность JSON")
                        })
                    }
                    char < ' ' -> invalidDocument("управляющий символ в JSON")
                    else -> result.append(char)
                }
            }
            invalidDocument("незаконченная строка JSON")
        }
        private fun literal(value: String) {
            if (!text.startsWith(value, position)) invalidDocument("повреждённый JSON")
            position += value.length
        }
        private fun number() {
            take('-')
            if (!take('0')) digits()
            if (take('.')) digits()
            if (take('e') || take('E')) { if (!take('+')) take('-'); digits() }
        }
        private fun digits() {
            val start = position
            while (true) {
                val next = text.getOrNull(position) ?: break
                if (next !in '0'..'9') break
                position++
            }
            if (position == start) invalidDocument("неверное число JSON")
        }
        private fun take(char: Char): Boolean = if (text.getOrNull(position) == char) { position++; true } else false
        private fun whitespace() { while (text.getOrNull(position) in listOf(' ', '\r', '\n', '\t')) position++ }
    }

    /**
     * The output of the writer, without the second copy of the document.
     *
     * `ByteArrayOutputStream.toByteArray()` copies the whole buffer. The document is measured
     * exactly before it is written, so the buffer **is** the document and the copy is pure
     * overhead: the export holds the model and the document, not three copies of the document.
     */
    private class SizedStream(size:Int):ByteArrayOutputStream(size) {
        fun bytes():ByteArray = if(count==buf.size) buf else buf.copyOf(count)
    }

    /**
     * A minimal streaming JSON writer. It never builds a document object model: the output is the
     * only allocation, so the peak heap of an export is the output plus the model.
     */
    private class JsonWriter(private val out: ByteArrayOutputStream) {        private val containers = java.util.ArrayDeque<Boolean>()
        private var afterKey = false

        fun objStart() { separate(); out.write('{'.code); containers.addLast(false) }
        fun objEnd() { out.write('}'.code); containers.removeLast() }
        fun arrStart() { separate(); out.write('['.code); containers.addLast(false) }
        fun arrEnd() { out.write(']'.code); containers.removeLast() }
        fun key(name: String) { separate(); string(name); out.write(':'.code); afterKey = true }
        fun text(value: String) { separate(); string(value) }
        fun number(value: Long) { separate(); out.write(value.toString().toByteArray(StandardCharsets.UTF_8)) }
        fun bool(value: Boolean) {
            separate()
            out.write(if (value) "true".toByteArray(StandardCharsets.UTF_8) else "false".toByteArray(StandardCharsets.UTF_8))
        }
        /** Writes an already validated JSON value verbatim (used for the smart playlist rules). */
        fun raw(value: String) { separate(); out.write(value.toByteArray(StandardCharsets.UTF_8)) }

        private fun separate() {
            if (afterKey) { afterKey = false; return }
            if (containers.isNotEmpty()) {
                if (containers.peekLast()==true) out.write(','.code)
                containers.pollLast(); containers.addLast(true)
            }
        }

        private fun string(value: String) {
            out.write('"'.code)
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                when {
                    char == '"' -> out.write("\\\"".toByteArray(StandardCharsets.UTF_8))
                    char == '\\' -> out.write("\\\\".toByteArray(StandardCharsets.UTF_8))
                    char == '\b' -> out.write("\\b".toByteArray(StandardCharsets.UTF_8))
                    char == '\u000C' -> out.write("\\f".toByteArray(StandardCharsets.UTF_8))
                    char == '\n' -> out.write("\\n".toByteArray(StandardCharsets.UTF_8))
                    char == '\r' -> out.write("\\r".toByteArray(StandardCharsets.UTF_8))
                    char == '\t' -> out.write("\\t".toByteArray(StandardCharsets.UTF_8))
                    char < ' ' -> out.write(hexEscape(char))
                    else -> {
                        val code = if (Character.isHighSurrogate(char)) {
                            if (index >= value.length || !Character.isLowSurrogate(value[index]))
                                invalidWrite("недопустимый текст в копии")
                            index++
                            Character.toCodePoint(char, value[index - 1])
                        } else if (Character.isLowSurrogate(char)) {
                            invalidWrite("недопустимый текст в копии")
                        } else char.code
                        codePoint(out, code)
                    }
                }
            }
            out.write('"'.code)
        }

        private fun hexEscape(char: Char): ByteArray {
            val hex = "0123456789abcdef"
            val value = char.code
            return byteArrayOf('\\'.code.toByte(), 'u'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(),
                hex[value shr 4].code.toByte(), hex[value and 15].code.toByte())
        }

        private fun codePoint(out: ByteArrayOutputStream, code: Int) {
            when {
                code < 0x80 -> out.write(code)
                code < 0x800 -> {
                    out.write(0xC0 or (code shr 6))
                    out.write(0x80 or (code and 0x3F))
                }
                code < 0x10000 -> {
                    out.write(0xE0 or (code shr 12))
                    out.write(0x80 or ((code shr 6) and 0x3F))
                    out.write(0x80 or (code and 0x3F))
                }
                else -> {
                    out.write(0xF0 or (code shr 18))
                    out.write(0x80 or ((code shr 12) and 0x3F))
                    out.write(0x80 or ((code shr 6) and 0x3F))
                    out.write(0x80 or (code and 0x3F))
                }
            }
        }
    }

    /**
     * A strict streaming JSON reader over the raw bytes. It never materialises a document object
     * model: every value is turned into a domain object as soon as it is read, so the peak heap of
     * an import is the document plus the model.
     */
    private class JsonReader(private val bytes: ByteArray) {
        private var pos = 0

        fun skipBom() {
            if (bytes.size >= 3 && byteAt(0) == 0xEF && byteAt(1) == 0xBB && byteAt(2) == 0xBF) pos = 3
        }

        fun skipWhitespace() {
            while (pos < bytes.size) {
                val b = byteAt(pos)
                if (b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D) pos++ else break
            }
        }

        /** True when the document carries bytes after the root value. */
        fun hasTrailingData(): Boolean {
            skipWhitespace()
            return pos < bytes.size
        }

        fun expect(value: Int) {
            skipWhitespace()
            if (next() != value) invalidRead("повреждённый JSON")
        }

        /** Reads the members of an object; every member name must be known and appear once. */
        fun eachMember(allowed: Set<String>, seen: HashSet<String>, body: (String) -> Unit) {
            skipWhitespace()
            if (peek() == ','.code) invalidRead("повреждённый JSON")
            if (peek() == '}'.code) { pos++; return }
            while (true) {
                skipWhitespace()
                if (peek() != '"'.code) invalidRead("повреждённый JSON")
                val key = readString(64)
                if (key !in allowed) invalidRead("неверный набор полей в копии")
                if (!seen.add(key)) invalidRead("повторяется поле JSON")
                skipWhitespace(); expect(':'.code)
                body(key)
                skipWhitespace()
                if (peek() == ','.code) { pos++; continue }
                if (peek() == '}'.code) { pos++; return }
                invalidRead("повреждённый JSON")
            }
        }

        fun readArray(maximum: Int, body: () -> Unit) {
            expect('['.code)
            skipWhitespace()
            if (peek() == ']'.code) { pos++; return }
            var count = 0
            while (true) {
                count++
                if (count > maximum) invalidRead("в копии слишком много записей")
                body()
                skipWhitespace()
                if (peek() == ','.code) { pos++; continue }
                if (peek() == ']'.code) { pos++; return }
                invalidRead("повреждённый JSON")
            }
        }

        fun readString(maximum: Int): String {
            skipWhitespace()
            if (next() != '"'.code) invalidRead("ожидается строка JSON")
            val out = StringBuilder()
            while (true) {
                val b = peek()
                when {
                    b < 0 -> invalidRead("файл повреждён")
                    b == '"'.code -> { pos++; return out.toString() }
                    b == '\\'.code -> { pos++; escape(out) }
                    b < 0x20 -> invalidRead("управляющий символ в JSON")
                    else -> codePoint(out)
                }
                if (out.length > maximum) invalidRead("слишком длинный текст в копии")
            }
        }

        fun readLong(): Long {
            skipWhitespace()
            var negative = false
            if (peek() == '-'.code) { negative = true; pos++ }
            val start = pos
            var value = 0L
            var digits = 0
            while (true) {
                val b = peek()
                if (b < '0'.code || b > '9'.code) break
                pos++; digits++
                if (digits > 18) invalidRead("число выходит за допустимые границы")
                value = value * 10 + (b - '0'.code)
            }
            if (digits == 0) invalidRead("неверное число JSON")
            if (digits > 1 && byteAt(start) == '0'.code) invalidRead("неверное число JSON")
            val trailing = peek()
            if (trailing == '.'.code || trailing == 'e'.code || trailing == 'E'.code)
                invalidRead("поле должно быть целым числом")
            return if (negative) -value else value
        }

        fun readBoolean(): Boolean {
            skipWhitespace()
            if (peek() == 't'.code) { literal("true"); return true }
            if (peek() == 'f'.code) { literal("false"); return false }
            invalidRead("поле должно быть логическим")
        }

        /** Reads one already validated JSON value verbatim (used for the smart playlist rules). */
        fun readRaw(maximum:Int): String {
            val start = pos
            skipValue(0)
            // The value is bounded before it is materialised: a copy with a huge rules field
            // must not cost its own size in the heap.
            if (pos - start > maximum) invalidRead("слишком большой набор правил в копии")
            return decodeUtf8(bytes, start, pos)
        }

        fun readTrack(): Track {
            expect('{'.code)
            var id = ""; var title = ""; var artist = ""; var album = ""
            var duration = 0L; var source = ""; var sourceId = ""; var sourceUrl = ""
            var coverUrl = ""; var favorite = false; var addedAt = 0L; var mimeType = ""
            var playCount=0;var lastPlayed=0L;var downloaded=false
            val seen=HashSet<String>()
            eachMember(trackKeys, seen) { key ->
                when (key) {
                    "id" -> id = readString(256)
                    "title" -> title = readString(2_048)
                    "artist" -> artist = readString(2_048)
                    "album" -> album = readString(2_048)
                    "durationMs" -> duration = readLong()
                    "source" -> source = readString(32)
                    "sourceId" -> sourceId = readString(128)
                    "sourceUrl" -> sourceUrl = readString(2_048)
                    "coverUrl" -> coverUrl = readString(2_048)
                    "favorite" -> favorite = readBoolean()
                    "addedAt" -> addedAt = readLong()
                    "mimeType" -> mimeType = readString(128)
                    "importedPlayCount" -> playCount=readLong().also {if(it !in 0..Int.MAX_VALUE.toLong())invalidRead("неверное число воспроизведений")}.toInt()
                    "importedLastPlayed" -> lastPlayed=readLong()
                    "importedDownloaded" -> downloaded=readBoolean()
                }
            }
            if(seen!=trackKeys && seen!=originalTrackKeys)invalidRead("неверный набор полей трека в копии")
            return Track(id = id, title = title, artist = artist, album = album, durationMs = duration,
                source = source, sourceId = sourceId, sourceUrl = sourceUrl, coverUrl = coverUrl,
                favorite = favorite, addedAt = addedAt, mimeType = mimeType,
                importedPlayCount=playCount,importedLastPlayed=lastPlayed,importedDownloaded=downloaded)
        }

        fun readPlaylist(): BackupPlaylist {
            expect('{'.code)
            var id = ""; var name = ""; var createdAt = 0L
            val ids = ArrayList<String>()
            val seen=HashSet<String>()
            eachMember(playlistKeys, seen) { key ->
                when (key) {
                    "id" -> id = readString(256)
                    "name" -> name = readString(2_048)
                    "createdAt" -> createdAt = readLong()
                    "trackIds" -> readArray(MAX_TRACKS) { ids.add(readString(256)) }
                }
            }
            if(seen!=playlistKeys)invalidRead("неверный набор полей плейлиста в копии")
            return BackupPlaylist(Playlist(id, name, createdAt), ids)
        }

        fun readSession(): ListeningSession {
            expect('{'.code)
            var id = ""; var trackId = ""; var startedAt = 0L; var listened = 0L; var completed = false
            val seen=HashSet<String>()
            eachMember(sessionKeys, seen) { key ->
                when (key) {
                    "id" -> id = readString(256)
                    "trackId" -> trackId = readString(256)
                    "startedAt" -> startedAt = readLong()
                    "listenedMs" -> listened = readLong()
                    "completed" -> completed = readBoolean()
                }
            }
            if(seen!=sessionKeys)invalidRead("неверный набор полей истории в копии")
            return ListeningSession(id, trackId, startedAt, listened, completed)
        }

        fun readSmartPlaylist(): SmartPlaylist {
            expect('{'.code)
            var id = ""; var name = ""; var createdAt = 0L; var raw = ""
            val seen=HashSet<String>()
            eachMember(smartKeys, seen) { key ->
                when (key) {
                    "id" -> id = readString(256)
                    "name" -> name = readString(2_048)
                    "createdAt" -> createdAt = readLong()
                    "rules" -> raw = readRaw(MAX_RULES_BYTES)
                }
            }
            if(seen!=smartKeys)invalidRead("неверный набор полей умного плейлиста в копии")
            return SmartPlaylist(id, name, rules(raw, ::invalidRead).encode(), createdAt)
        }

        private fun escape(out: StringBuilder) {
            when (val value = next()) {
                '"'.code -> out.append('"')
                '\\'.code -> out.append('\\')
                '/'.code -> out.append('/')
                'b'.code -> out.append('\b')
                'f'.code -> out.append('\u000C')
                'n'.code -> out.append('\n')
                'r'.code -> out.append('\r')
                't'.code -> out.append('\t')
                'u'.code -> {
                    val first = hex4()
                    if (first in 0xD800..0xDBFF) {
                        if (peek() != '\\'.code) invalidRead("неверный Unicode в JSON")
                        pos++
                        if (next() != 'u'.code) invalidRead("неверный Unicode в JSON")
                        val second = hex4()
                        if (second !in 0xDC00..0xDFFF) invalidRead("неверный Unicode в JSON")
                        out.append(first.toChar()); out.append(second.toChar())
                    } else if (first in 0xDC00..0xDFFF) {
                        invalidRead("неверный Unicode в JSON")
                    } else out.append(first.toChar())
                }
                else -> invalidRead("неверная escape-последовательность JSON")
            }
        }

        private fun hex4(): Int {
            var value = 0
            repeat(4) {
                val b = next()
                val digit = when {
                    b in '0'.code..'9'.code -> b - '0'.code
                    b in 'a'.code..'f'.code -> b - 'a'.code + 10
                    b in 'A'.code..'F'.code -> b - 'A'.code + 10
                    else -> invalidRead("неверный Unicode в JSON")
                }
                value = value * 16 + digit
            }
            return value
        }

        private fun codePoint(out: StringBuilder) {
            val first = next()
            when {
                first < 0x80 -> out.append(first.toChar())
                first in 0xC2..0xDF -> out.append((((first and 0x1F) shl 6) or continuation()).toChar())
                first in 0xE0..0xEF -> {
                    val code = ((first and 0x0F) shl 12) or (continuation() shl 6) or continuation()
                    if (code < 0x800 || code in 0xD800..0xDFFF) invalidRead("неверный UTF-8 в копии")
                    out.append(code.toChar())
                }
                first in 0xF0..0xF4 -> {
                    val code = ((first and 0x07) shl 18) or (continuation() shl 12) or (continuation() shl 6) or continuation()
                    if (code < 0x10000 || code > 0x10FFFF) invalidRead("неверный UTF-8 в копии")
                    out.appendCodePoint(code)
                }
                else -> invalidRead("неверный UTF-8 в копии")
            }
        }

        private fun continuation(): Int {
            val b = next()
            if (b < 0x80 || b > 0xBF) invalidRead("неверный UTF-8 в копии")
            return b and 0x3F
        }

        private fun skipValue(depth: Int) {
            if (depth > 32) invalidRead("слишком глубокая вложенность JSON")
            skipWhitespace()
            when (peek()) {
                '"'.code -> { pos++; skipString() }
                '{'.code -> {
                    pos++; skipWhitespace()
                    if (peek() == '}'.code) { pos++; return }
                    while (true) {
                        skipWhitespace()
                        if (peek() != '"'.code) invalidRead("повреждённый JSON")
                        pos++; skipString()
                        skipWhitespace()
                        if (peek() != ':'.code) invalidRead("повреждённый JSON")
                        pos++; skipValue(depth + 1); skipWhitespace()
                        if (peek() == ','.code) { pos++; continue }
                        if (peek() == '}'.code) { pos++; return }
                        invalidRead("повреждённый JSON")
                    }
                }
                '['.code -> {
                    pos++; skipWhitespace()
                    if (peek() == ']'.code) { pos++; return }
                    while (true) {
                        skipValue(depth + 1); skipWhitespace()
                        if (peek() == ','.code) { pos++; continue }
                        if (peek() == ']'.code) { pos++; return }
                        invalidRead("повреждённый JSON")
                    }
                }
                't'.code -> literal("true")
                'f'.code -> literal("false")
                'n'.code -> literal("null")
                else -> readLong()
            }
        }

        private fun skipString() {
            while (true) {
                val b = next()
                when {
                    b == '"'.code -> return
                    b == '\\'.code -> {
                        when (next()) {
                            '"'.code, '\\'.code, '/'.code, 'b'.code, 'f'.code, 'n'.code, 'r'.code, 't'.code -> Unit
                            'u'.code -> {
                                val first=hex4()
                                if(first in 0xD800..0xDBFF) {
                                    if(next()!='\\'.code || next()!='u'.code || hex4() !in 0xDC00..0xDFFF)
                                        invalidRead("неверный Unicode в JSON")
                                } else if(first in 0xDC00..0xDFFF)invalidRead("неверный Unicode в JSON")
                            }
                            else -> invalidRead("неверная escape-последовательность JSON")
                        }
                    }
                    b < 0x20 -> invalidRead("управляющий символ в JSON")
                    else -> if (b >= 0x80) { pos--; utf8Sequence() }
                }
            }
        }

        /** Consumes one UTF-8 sequence without decoding it; validates that it is well formed. */
        private fun utf8Sequence() {
            val first = next()
            when {
                first < 0x80 -> Unit
                first in 0xC2..0xDF -> continuation()
                first in 0xE0..0xEF -> {
                    val code = ((first and 0x0F) shl 12) or (continuation() shl 6) or continuation()
                    if (code < 0x800 || code in 0xD800..0xDFFF) invalidRead("неверный UTF-8 в копии")
                }
                first in 0xF0..0xF4 -> {
                    val code = ((first and 0x07) shl 18) or (continuation() shl 12) or (continuation() shl 6) or continuation()
                    if (code < 0x10000 || code > 0x10FFFF) invalidRead("неверный UTF-8 в копии")
                }
                else -> invalidRead("неверный UTF-8 в копии")
            }
        }

        private fun literal(value: String) {
            for (char in value) if (next() != char.code) invalidRead("повреждённый JSON")
        }

        private fun byteAt(index: Int): Int = bytes[index].toInt() and 0xFF
        private fun peek(): Int = if (pos < bytes.size) byteAt(pos) else -1
        private fun next(): Int {
            if (pos >= bytes.size) invalidRead("файл повреждён")
            val b = byteAt(pos); pos++; return b
        }
    }

    /** Decodes a byte range as strict UTF-8. */
    private fun decodeUtf8(bytes: ByteArray, start: Int, end: Int): String {
        val out = StringBuilder(end - start)
        var index = start
        while (index < end) {
            val first = bytes[index].toInt() and 0xFF
            when {
                first < 0x80 -> { out.append(first.toChar()); index++ }
                first in 0xC2..0xDF -> {
                    val second = continuationAt(bytes, index + 1, end)
                    out.append((((first and 0x1F) shl 6) or second).toChar()); index += 2
                }
                first in 0xE0..0xEF -> {
                    val second = continuationAt(bytes, index + 1, end)
                    val third = continuationAt(bytes, index + 2, end)
                    val code = ((first and 0x0F) shl 12) or (second shl 6) or third
                    if (code < 0x800 || code in 0xD800..0xDFFF) invalidRead("неверный UTF-8 в копии")
                    out.append(code.toChar()); index += 3
                }
                first in 0xF0..0xF4 -> {
                    val second = continuationAt(bytes, index + 1, end)
                    val third = continuationAt(bytes, index + 2, end)
                    val fourth = continuationAt(bytes, index + 3, end)
                    val code = ((first and 0x07) shl 18) or (second shl 12) or (third shl 6) or fourth
                    if (code < 0x10000 || code > 0x10FFFF) invalidRead("неверный UTF-8 в копии")
                    out.appendCodePoint(code); index += 4
                }
                else -> invalidRead("неверный UTF-8 в копии")
            }
        }
        return out.toString()
    }

    private fun continuationAt(bytes: ByteArray, index: Int, end: Int): Int {
        if (index >= end) invalidRead("неверный UTF-8 в копии")
        val b = bytes[index].toInt() and 0xFF
        if (b < 0x80 || b > 0xBF) invalidRead("неверный UTF-8 в копии")
        return b and 0x3F
    }
}
