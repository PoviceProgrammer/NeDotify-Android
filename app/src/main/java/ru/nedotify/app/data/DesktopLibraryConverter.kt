package ru.nedotify.app.data

import java.security.MessageDigest
import java.time.*
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong
import org.json.JSONObject

data class DesktopImportPreview(val backup: LibraryBackup, val notice: String)

/**
 * The columns the selected desktop copy actually has.
 *
 * The importer reads the columns that exist and defaults the ones that do not, so a column that
 * is missing from the copy is a **silent loss** unless it is reported. [DesktopColumns] carries
 * the truth of the copy into the converter, which turns it into a notice line.
 */
data class DesktopColumns(
    val tracks: Set<String> = setOf(),
    val playlists: Set<String> = setOf(),
    val playlistTracks: Set<String> = setOf(),
    val history: Set<String> = setOf(),
    val smartPlaylists: Set<String> = setOf(),
) {
    companion object {
        /** Every column the importer asks for. The default means "nothing is missing". */
        val FULL = DesktopColumns(
            tracks = setOf("id", "title", "artist", "album", "duration", "source", "source_id", "source_url",
                "cover_url", "is_favorite", "added_at", "play_count", "last_played", "is_downloaded"),
            playlists = setOf("id", "name", "created_at", "is_smart", "smart_rules_json"),
            playlistTracks = setOf("playlist_id", "track_id", "position"),
            history = setOf("id", "track_id", "played_at", "duration_listened", "listening_measured", "completed"),
            smartPlaylists = setOf("id", "name", "rule_json", "created_at"),
        )
    }
}

/** Converts a selected, closed desktop SQLite snapshot, never the live Windows library. */
object DesktopLibraryConverter {
    private val trackLabels = mapOf(
        "album" to "альбом", "duration" to "длительность", "source" to "источник",
        "source_id" to "идентификатор источника", "source_url" to "ссылка на источник",
        "cover_url" to "обложка", "is_favorite" to "избранное", "added_at" to "дата добавления",
        "play_count" to "число запусков", "last_played" to "последний запуск", "is_downloaded" to "признак загрузки на ПК",
    )
    private val playlistLabels = mapOf(
        "created_at" to "дата создания", "is_smart" to "признак умного плейлиста",
        "smart_rules_json" to "правила умного плейлиста",
    )
    private val historyLabels = mapOf(
        "played_at" to "дата прослушивания", "duration_listened" to "время прослушивания",
        "listening_measured" to "отметка измеренного времени", "completed" to "признак завершения",
    )
    private val smartLabels = mapOf("rule_json" to "правила умного плейлиста", "created_at" to "дата создания")

    fun convert(origin: String, trackRows: List<Map<String, Any?>>, playlistRows: List<Map<String, Any?>>,
        linkRows: List<Map<String, Any?>>, historyRows: List<Map<String, Any?>>, now: Long,
        smartRows: List<Map<String, Any?>> = emptyList(),
        columns: DesktopColumns = DesktopColumns.FULL,sourceIdentity:String?=null): DesktopImportPreview {
        val cleanOrigin = origin.trim()
        // A typed failure: the importer's error boundary keeps a `SourceFailure` and shows its
        // message. An `IllegalArgumentException` used to be swallowed into a generic message.
        if (cleanOrigin.isBlank() || cleanOrigin.length > 80 || cleanOrigin.any { it.isISOControl() })
            throw SourceFailure("Укажите имя библиотеки ПК до 80 символов")
        if (trackRows.size > 20_000 || playlistRows.size > 2_000 || linkRows.size > 100_000 ||
            historyRows.size > 100_000 || smartRows.size > 100) throw SourceFailure("В копии слишком много записей")
        val fingerprint = machineFingerprint(trackRows, historyRows,playlistRows,linkRows,smartRows)
        val stableIdentity=sourceIdentity?.takeIf {it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))}
        val libraryId = stableIdentity?.lowercase(Locale.ROOT) ?: UUID.nameUUIDFromBytes(
            ("nedotify-desktop-legacy\u0000" + cleanOrigin.lowercase(Locale.ROOT) + "\u0000" + fingerprint).toByteArray(Charsets.UTF_8)).toString()
        val tracks = linkedMapOf<String, Track>(); val ids = HashMap<String, String>(); var unresolved = 0
        var unreadableDates = 0
        trackRows.forEach { row ->
            val nativeId = row.id("id")
            if (nativeId in ids) throw SourceFailure("Повторяется ID desktop-трека")
            val source = row.text("source").lowercase(Locale.ROOT)
            val sourceId = row.text("source_id")
            val remote = when {
                source == "youtube" && sourceId.matches(Regex("[A-Za-z0-9_-]{11}")) -> "youtube"
                source == "soundcloud" && sourceId.matches(Regex("[1-9][0-9]{0,19}")) -> "soundcloud"
                source == "yandex" && sourceId.matches(Regex("[0-9]{1,24}")) -> "yandex"
                else -> "local"
            }
            if (remote == "local" && source !in setOf("local", "", "file")) unresolved++
            val localIdentity=digest(listOf(nativeId,row.text("title"),row.text("artist"),row.text("album"),row.text("duration"),row.text("added_at")).joinToString("\u0000"))
            // One unusable date must not abort the whole import: the value is lost, and the
            // loss is disclosed in the notice instead of vanishing into a default.
            val addedAt = lenientTimestamp(row["added_at"]) ?: run { unreadableDates++; 0L }
            val lastPlayed = lenientTimestamp(row["last_played"]) ?: run { unreadableDates++; 0L }
            val track = Track(if (remote == "local") "local:desktop:$localIdentity" else "$remote:$sourceId",
                row.text("title").ifBlank { "Без названия" }.safePrefix(2_048),
                row.text("artist").ifBlank { "Неизвестный исполнитель" }.safePrefix(2_048),
                row.text("album").safePrefix(2_048), seconds(row["duration"], 604_800_000), remote,
                sourceId = if (remote == "local") "" else sourceId,
                sourceUrl = if (remote == "local") "" else row.text("source_url").take(8_192),
                coverUrl = if (remote == "local") "" else row.text("cover_url").take(8_192),
                favorite = row.flag("is_favorite"), addedAt = addedAt,
                importedPlayCount=row.text("play_count").toIntOrNull()?.coerceAtLeast(0) ?: 0,
                importedLastPlayed=lastPlayed,importedDownloaded=row.flag("is_downloaded"))
            ids[nativeId] = track.id
            val previous = tracks[track.id]
            tracks[track.id] = previous?.copy(favorite = previous.favorite || track.favorite, addedAt = minOf(previous.addedAt, track.addedAt)) ?: track
        }
        val playlistIds = HashMap<String, String>()
        val playlists = playlistRows.filterNot { it.flag("is_smart") }.map { row ->
            val id = row.id("id"); val portable = "desktop:$libraryId:$id"
            if (playlistIds.put(id, portable) != null) throw SourceFailure("Повторяется ID desktop-плейлиста")
            val createdAt = lenientTimestamp(row["created_at"]) ?: run { unreadableDates++; 0L }
            BackupPlaylist(Playlist(portable, row.text("name").ifBlank { "Плейлист" }.safePrefix(120), createdAt), emptyList())
        }
        var unreadableLinks = 0
        val grouped = linkRows.sortedBy { (it["position"] as? Number)?.toLong() ?: 0L }
            .mapNotNull { row ->
                // One unusable identifier must not abort the whole import: the entry is lost,
                // and the loss is disclosed in the notice.
                val playlistId = try { row.id("playlist_id") } catch (_: SourceFailure) { unreadableLinks++; return@mapNotNull null }
                val trackId = try { row.id("track_id") } catch (_: SourceFailure) { unreadableLinks++; return@mapNotNull null }
                playlistId to trackId
            }
            .groupBy({ it.first }, { it.second })
        val nativePlaylistIds = playlistIds.entries.associate { it.value to it.key }
        val withLinks = playlists.map { entry ->
            val native = nativePlaylistIds.getValue(entry.playlist.id)
            entry.copy(trackIds = grouped[native].orEmpty().mapNotNull { ids[it] }.distinct())
        }
        var unmeasured = 0; var missing = 0; var unreadableRows = 0
        val sessions = historyRows.mapNotNull { row ->
            if (!row.flag("listening_measured")) { unmeasured++; return@mapNotNull null }
            val id = try { row.id("id") } catch (_: SourceFailure) { unreadableRows++; return@mapNotNull null }
            val trackId = try { ids[row.id("track_id")] } catch (_: SourceFailure) { unreadableRows++; return@mapNotNull null }
            if (trackId == null) { missing++; return@mapNotNull null }
            val playedAt = lenientTimestamp(row["played_at"]) ?: run { unreadableDates++; return@mapNotNull null }
            val measured = seconds(row["duration_listened"], 31_622_400_000)
            ListeningSession("desktop:$libraryId:$id", trackId, playedAt, measured, row.flag("completed"))
        }
        // One unusable rule row must not abort the whole import: the desktop skips such a row
        // (`services/smart_playlists.py`: `except (TypeError, ValueError): continue`).
        var skippedSmart = 0
        val smart = (smartRows.map { it to "smart" } + playlistRows.filter { it.flag("is_smart") }.map { it to "legacy-smart" })
            .mapNotNull { (row, kind) ->
                try {
                    val json = row.text("rule_json").ifEmpty { row.text("smart_rules_json") }
                    val rules = desktopRules(json)
                    SmartPlaylist("desktop:$libraryId:$kind:${row.id("id")}",
                        row.text("name").ifBlank { "Умный плейлист" }.safePrefix(120), rules.encode(),
                        lenientTimestamp(row["created_at"]) ?: 0L)
                } catch (_: SourceFailure) {
                    skippedSmart++
                    null
                }
            }
        val draft = LibraryBackup(libraryId, now, tracks.values.toList(), withLinks, sessions, smart)
        // Canonicalisation drops private paths/signatures, just as Android's own export does.
        val portable = try {
            BackupCodec.portable(draft)
        } catch (error: BackupFormatException) {
            throw SourceFailure("Не удалось перенести библиотеку ПК: ${error.reason}")
        }
        // Two records with the same metadata cannot be merged automatically: they are kept
        // apart and the user is told about it instead of losing one of them silently.
        val ambiguous = tracks.values.filter { it.source == "local" && BackupMerge.matchable(it) }
            .groupBy { BackupMerge.recordingKey(it) }.count { it.value.size > 1 }
        val notice = buildList {
            add("Имя источника: $cleanOrigin.")
            if(stableIdentity!=null)add("Постоянный идентификатор библиотеки ПК: $libraryId. Повторная копия обновляет ту же библиотеку.")
            else add("В старой копии нет постоянного идентификатора ПК. Использован отпечаток ${fingerprint.take(8)}. " +
                "Изменённая копия считается отдельным источником; плейлисты и история могут повториться. Для повторных переносов обновите плеер на ПК и создайте новую копию.")
            add("Аудиофайлы и Windows-пути не переносятся. Для локальных записей выберите файл заново.")
            missingColumns(columns)?.let { add(it) }
            if (unresolved > 0) add("Записи других источников перенесены как локальные метаданные: $unresolved. Для них потребуется файл или поиск аудио.")
            if (unmeasured > 0) add("Старая история без отметки измеренного времени не перенесена: $unmeasured записей.")
            if (missing > 0) add("История отсутствующих в копии треков пропущена: $missing записей.")
            if (unreadableDates > 0) add("Непрочитанные даты в копии не перенесены: $unreadableDates. Дата добавления и дата последнего запуска таких записей пустые.")
            if (unreadableRows > 0) add("Записи истории с неверным идентификатором пропущены: $unreadableRows.")
            if (unreadableLinks > 0) add("Записи плейлистов с неверным идентификатором пропущены: $unreadableLinks.")
            if (skippedSmart > 0) add("Правила умных плейлистов, которые не удалось прочитать, пропущены: $skippedSmart.")
            if (ambiguous > 0) add("Записи с одинаковым названием, исполнителем, альбомом и длительностью не объединяются автоматически: $ambiguous. " +
                "Они перенесены отдельно; лишние удалите в библиотеке.")
        }.joinToString("\n\n")
        return DesktopImportPreview(portable, notice)
    }

    /**
     * The columns the copy does not have. Their values are read as empty, so the loss has to be
     * said out loud instead of being swallowed by a default value.
     */
    private fun missingColumns(columns: DesktopColumns): String? {
        val lost = buildList {
            addAll(DesktopColumns.FULL.tracks - columns.tracks)
            addAll(DesktopColumns.FULL.playlists - columns.playlists)
            addAll(DesktopColumns.FULL.playlistTracks - columns.playlistTracks)
            addAll(DesktopColumns.FULL.history - columns.history)
            addAll(DesktopColumns.FULL.smartPlaylists - columns.smartPlaylists)
        }
        if (lost.isEmpty()) return null
        val names = lost.map { column ->
            trackLabels[column] ?: playlistLabels[column] ?: historyLabels[column] ?: smartLabels[column] ?: column
        }.distinct()
        return "В копии нет столбцов: ${names.joinToString(", ")}. Эти поля перенесены пустыми."
    }

    /**
     * Legacy copies have no source UUID. Hash all portable row content so two differing
     * copies cannot silently alias merely because their user label and native ids match.
     * This identifies a snapshot, not a machine: the notice explicitly discloses that growth
     * becomes a separate import. Current desktop copies carry portable_library_id instead.
     */
    internal fun machineFingerprint(trackRows: List<Map<String, Any?>>, historyRows: List<Map<String, Any?>>,
        playlistRows:List<Map<String,Any?>> = emptyList(),linkRows:List<Map<String,Any?>> = emptyList(),smartRows:List<Map<String,Any?>> = emptyList()): String {
        val hash=MessageDigest.getInstance("SHA-256")
        for(rows in listOf(trackRows,historyRows,playlistRows,linkRows,smartRows)) {
            // The row is read through its own keys: a sorted copy of every row would be a second
            // full materialisation of the library, and the rows are already the largest
            // allocation of an import.
            val canonical=rows.map {row->row.keys.sorted().joinToString("") {key->
                val value=row[key];"${key.length}:$key${value?.toString()?.length ?: -1}:${value ?: ""}"}}.sorted()
            for(row in canonical)hash.update("${row.length}:$row".toByteArray(Charsets.UTF_8))
            hash.update(0.toByte())
        }
        return hash.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
    }

    private fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") {"%02x".format(it.toInt() and 255)}

    fun desktopRules(json: String): SmartRules {
        if (json.length > 8_192) throw SourceFailure("Слишком большие правила умного плейлиста ПК")
        BackupCodec.requireStrictJson(json)
        val value = try { JSONObject(json) } catch (_: Exception) { throw SourceFailure("Повреждены правила умного плейлиста ПК") }
        val allowed = setOf("added_within_days", "not_played_days", "favorite", "downloaded", "unplayed", "artist", "sort")
        if (value.keys().asSequence().any { it !in allowed }) throw SourceFailure("Неподдерживаемое правило умного плейлиста ПК")
        fun flag(key: String): Boolean? = if (!value.has(key)) null else value.get(key) as? Boolean
            ?: throw SourceFailure("Неверный фильтр умного плейлиста ПК")
        fun days(key: String): Int? {
            if (!value.has(key)) return null
            val raw = value.get(key)
            if (raw !is Int || raw !in 1..3650) throw SourceFailure("Неверный период умного плейлиста ПК")
            return raw
        }
        val order = when (value.optString("sort", "newest")) {
            "newest" -> SmartOrder.RECENT; "oldest" -> SmartOrder.OLDEST; "title" -> SmartOrder.TITLE
            "most_played" -> SmartOrder.MOST_PLAYED; "least_played" -> SmartOrder.LEAST_PLAYED
            else -> throw SourceFailure("Неизвестная сортировка умного плейлиста ПК")
        }
        return SmartRules(favorite = flag("favorite"), artist = value.optString("artist", ""), addedWithinDays = days("added_within_days"),
            notPlayedDays = days("not_played_days"), downloaded = flag("downloaded"), unplayed = flag("unplayed"), order = order, limit = 500)
    }
    fun timestamp(value: Any?): Long {
        if (value == null || value.toString().isBlank()) return 0
        val numeric = (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull()
        if (numeric != null) {
            if (!numeric.isFinite() || numeric < 0) throw SourceFailure("Неверная дата в копии ПК")
            val millis = if (numeric < 100_000_000_000) numeric * 1000 else numeric
            if (millis > 253_402_300_799_999L) throw SourceFailure("Неверная дата в копии ПК")
            return millis.roundToLong()
        }
        val text = value.toString().replace(' ', 'T')
        val instant = runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: throw SourceFailure("Непонятная дата в копии ПК")
        return instant.toEpochMilli().also { if (it < 0 || it > 253_402_300_799_999L) throw SourceFailure("Неверная дата в копии ПК") }
    }
    /** The timestamp of a value that may be unusable: [timestamp] would abort the whole import. */
    private fun lenientTimestamp(value: Any?): Long? = try { timestamp(value) } catch (_: SourceFailure) { null }
    private fun seconds(value: Any?, maximum: Long): Long {
        if (value == null) return 0
        val seconds = (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull() ?: throw SourceFailure("Неверная длительность в копии ПК")
        val millis = seconds * 1000
        if (!millis.isFinite() || millis < 0 || millis > maximum) throw SourceFailure("Неверная длительность в копии ПК")
        return millis.roundToLong()
    }
    private fun Map<String, Any?>.text(key: String) = this[key]?.toString().orEmpty()
    private fun String.safePrefix(limit: Int): String = take(limit).let { value ->
        if (value.lastOrNull()?.let(Character::isHighSurrogate) == true) value.dropLast(1) else value
    }
    private fun Map<String, Any?>.id(key: String): String {
        val value = text(key)
        val number = value.toLongOrNull()
        if (number == null || number <= 0 || number.toString() != value) throw SourceFailure("Неверный ID в копии ПК")
        return value
    }
    private fun Map<String, Any?>.flag(key: String) = this[key] == true || (this[key] as? Number)?.toDouble() == 1.0 || this[key] == "1"
}
