package ru.nedotify.app.data

import java.util.Locale
import org.json.JSONObject

enum class SmartOrder { RECENT, OLDEST, TITLE, ARTIST, MOST_PLAYED, LEAST_PLAYED }

/** All enabled filters are combined with AND; no executable SQL is stored in a rule. */
data class SmartRules(
    val favorite: Boolean? = null,
    val source: String? = null,
    val artist: String = "",
    val query: String = "",
    val minListenedMs: Long = 0,
    val addedWithinDays: Int? = null,
    val limit: Int = 100,
    val order: SmartOrder = SmartOrder.RECENT,
    val downloaded: Boolean? = null,
    val unplayed: Boolean? = null,
    val notPlayedDays: Int? = null,
) {
    fun requireFilter():SmartRules = checked().also {
        if(it.favorite==null && it.source==null && it.artist.isBlank() && it.query.isBlank() && it.minListenedMs==0L &&
            it.addedWithinDays==null && it.downloaded==null && it.unplayed==null && it.notPlayedDays==null)
            invalidRules("выберите хотя бы одно условие")
    }
    fun checked(): SmartRules {
        if (source != null && source !in setOf("local", "soundcloud", "youtube", "yandex")) invalidRules("неизвестный источник")
        if (artist.length > 200 || query.length > 200 || (artist + query).any { it < ' ' || it == '\u007f' })
            invalidRules("слишком длинный или недопустимый текст")
        for(text in listOf(artist,query)) {
            var index=0
            while(index<text.length) {
                val point=Character.codePointAt(text,index);index+=Character.charCount(point)
                if(point in 0xD800..0xDFFF)invalidRules("недопустимый Unicode в тексте")
            }
        }
        if (minListenedMs !in 0..31_622_400_000L || limit !in 1..1_000 ||
            (addedWithinDays != null && addedWithinDays !in 1..36_500) ||
            (notPlayedDays != null && notPlayedDays !in 1..36_500)) invalidRules("значение вне допустимых границ")
        return copy(artist = artist.trim(), query = query.trim())
    }

    fun encode(): String = checked().let { rules ->
        JSONObject().put("version", 1).put("favorite", rules.favorite ?: JSONObject.NULL)
            .put("source", rules.source ?: JSONObject.NULL).put("artist", rules.artist).put("query", rules.query)
            .put("minListenedMs", rules.minListenedMs).put("addedWithinDays", rules.addedWithinDays ?: JSONObject.NULL)
            .put("limit", rules.limit).put("order", rules.order.name).put("downloaded", rules.downloaded ?: JSONObject.NULL)
            .put("unplayed", rules.unplayed ?: JSONObject.NULL).put("notPlayedDays", rules.notPlayedDays ?: JSONObject.NULL).toString()
    }

    companion object {
        fun decode(json: String): SmartRules {
            if (json.length > 4_096) invalidRules("слишком большой набор правил")
            try {
                BackupCodec.requireStrictJson(json)
                val value = JSONObject(json)
                val keys = setOf("version", "favorite", "source", "artist", "query", "minListenedMs", "addedWithinDays", "limit", "order",
                    "downloaded", "unplayed", "notPlayedDays")
                val originalKeys = keys - setOf("downloaded", "unplayed", "notPlayedDays")
                if (value.keys().asSequence().any { it !in keys } || originalKeys.any { !value.has(it) }) invalidRules("неверные поля")
                fun integer(key: String): Long {
                    val item = value.get(key)
                    if (item !is Int && item !is Long) invalidRules("$key должно быть целым числом")
                    return (item as Number).toLong()
                }
                fun text(key: String): String = value.get(key) as? String ?: invalidRules("$key должно быть строкой")
                if (integer("version") != 1L) invalidRules("версия правил не поддерживается")
                fun boolean(key:String):Boolean? = if(!value.has(key) || value.isNull(key)) null else value.get(key) as? Boolean
                    ?: invalidRules("$key должно быть логическим значением")
                val favorite = boolean("favorite")
                val days = if (value.isNull("addedWithinDays")) null else integer("addedWithinDays").also {
                    if (it !in 1..36_500L) invalidRules("недопустимое число дней")
                }.toInt()
                val limit = integer("limit").also { if (it !in 1..1_000L) invalidRules("недопустимый размер списка") }.toInt()
                val notPlayed = if (!value.has("notPlayedDays") || value.isNull("notPlayedDays")) null else integer("notPlayedDays").also {
                    if (it !in 1..36_500L) invalidRules("недопустимое число дней")
                }.toInt()
                return SmartRules(favorite, if (value.isNull("source")) null else text("source"), text("artist"), text("query"),
                    integer("minListenedMs"), days, limit, SmartOrder.valueOf(text("order")), boolean("downloaded"),
                    boolean("unplayed"), notPlayed).checked()
            } catch (error: SourceFailure) { throw error }
            catch (_: Exception) { invalidRules("повреждённый JSON") }
        }
    }
}

private fun invalidRules(reason: String): Nothing = throw SourceFailure("Неверные правила умного плейлиста: $reason")

object SmartPlaylistEngine {
    fun select(candidates: List<DiscoveryCandidate>, rules: SmartRules, now: Long): List<Track> {
        val filter = rules.checked()
        fun String.key() = lowercase(Locale.ROOT)
        val artist = filter.artist.key()
        val query = filter.query.key()
        val cutoff = filter.addedWithinDays?.let { now - it * 86_400_000L }
        val lastPlayedCutoff = filter.notPlayedDays?.let { now - it * 86_400_000L }
        val rows = candidates.asSequence().filter { row ->
            val track = row.track
            (filter.favorite == null || track.favorite == filter.favorite) &&
                (filter.source == null || track.source == filter.source) &&
                (artist.isEmpty() || artist in track.artist.key()) &&
                (query.isEmpty() || listOf(track.title, track.artist, track.album).any { query in it.key() }) &&
                row.listenedMs >= filter.minListenedMs &&
                (filter.downloaded == null || (row.downloaded || track.importedDownloaded) == filter.downloaded) &&
                (filter.unplayed == null || (row.playCount == 0) == filter.unplayed) &&
                (lastPlayedCutoff == null || row.lastPlayed == 0L || row.lastPlayed <= lastPlayedCutoff) &&
                (cutoff == null || track.addedAt in cutoff..now)
        }
        val comparator = when (filter.order) {
            SmartOrder.RECENT -> compareByDescending<DiscoveryCandidate> { it.track.addedAt }
            SmartOrder.OLDEST -> compareBy { it.track.addedAt }
            SmartOrder.TITLE -> compareBy { it.track.title.key() }
            SmartOrder.ARTIST -> compareBy<DiscoveryCandidate> { it.track.artist.key() }.thenBy { it.track.title.key() }
            SmartOrder.MOST_PLAYED -> compareByDescending { it.listenedMs }
            SmartOrder.LEAST_PLAYED -> compareBy { it.listenedMs }
        }.thenBy { it.track.id }
        return rows.sortedWith(comparator).take(filter.limit).map { it.track }.toList()
    }
}
