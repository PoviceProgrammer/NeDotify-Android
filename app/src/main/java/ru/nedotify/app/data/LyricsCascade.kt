package ru.nedotify.app.data

import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

data class LyricsEndpoints(
    val lrclibSearch: String = "https://lrclib.net/api/search",
    val neteaseSearch: String = "https://music.163.com/api/cloudsearch/pc",
    val neteaseLyrics: String = "https://music.163.com/api/song/lyric",
    val qqSearch: String = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp",
    val qqLyrics: String = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
    val geniusBase: String = "https://genius.com",
)

/** The same conservative identity gate is used for primary and fallback providers. */
object LyricsIdentity {
    private val versionWord = Regex("(?i)\\b(remix|live|acoustic|instrumental|slowed|sped|nightcore|edit|cover|remaster(?:ed)?)\\b")
    private val brackets = Regex("\\([^)]{0,300}\\)|\\[[^]]{0,300}]")
    private val decoration = Regex("(?i)^(?:official\\s+)?(?:audio|video|music video|lyrics?|lyric video|visuali[sz]er)(?:\\s+official)?$|^(?:HD|HQ|4K)$")
    private val credit = Regex("(?i)^\\s*(?:feat\\.?|ft\\.?)\\s+")
    private data class Title(val base: String, val version: List<String>)
    // NFKD + removing all combining marks also changes Cyrillic й to и. That both
    // weakens artist identity and stops the unknown-artist sentinel from matching.
    internal fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun title(value: String): Title {
        val versions = mutableListOf<String>()
        var base = brackets.replace(value) { match ->
            val text = match.value.substring(1, match.value.length - 1).trim()
            when {
                versionWord.containsMatchIn(text) -> { versions += normalize(text).replace("remastered", "remaster"); " " }
                decoration.matches(text) || credit.containsMatchIn(text) -> " "
                else -> match.value
            }
        }
        val suffix = base.substringAfterLast(" - ", "")
        if (suffix.isNotEmpty() && versionWord.containsMatchIn(suffix)) {
            versions += normalize(suffix).replace("remastered", "remaster")
            base = base.substringBeforeLast(" - ")
        } else if (versionWord.containsMatchIn(base)) {
            // A version without brackets remains part of the identity as well.
            versions += versionWord.findAll(base).map { normalize(it.value).replace("remastered", "remaster") }
        }
        return Title(normalize(base), versions.sorted())
    }

    private fun artists(value: String): Set<String> = value.split(Regex("(?i)\\s*(?:,|;|&|\\bfeat\\.?\\s+|\\bft\\.?\\s+|\\s+x\\s+|\\s+and\\s+|\\s+/\\s+)\\s*"))
        .map { normalize(it.removeSuffix(" - Topic")) }.filter { it.isNotBlank() && it !in setOf("неизвестный исполнитель", "unknown artist", "various artists") }.toSet()

    /** Strip an embedded credit only when it agrees with known credits, or supplies missing ones. */
    internal fun lookupTrack(track: Track): Track {
        val parts = track.title.split(Regex("\\s+[—–-]\\s+"), limit = 2)
        if (parts.size != 2 || parts.any { it.isBlank() } || versionWord.containsMatchIn(parts[1]) &&
            parts[1].length < 40 && title(parts[1]).base == normalize(parts[1])) return track
        val expected = artists(track.artist)
        val embedded = artists(parts[0])
        if (embedded.isNotEmpty() && (expected.isEmpty() || expected.intersect(embedded).isNotEmpty()))
            return track.copy(title = parts[1], artist = if (expected.isEmpty()) parts[0] else track.artist)
        return track
    }

    internal fun queryTrack(track: Track): Track = lookupTrack(track)

    private fun baseFallback(wanted: Title, offered: Title): Boolean = wanted.version.isNotEmpty() && offered.version.isEmpty() &&
        wanted.version.all { Regex("(?i)\\b(remix|slowed|sped)\\b").containsMatchIn(it) }

    private fun similar(left: String, right: String): Boolean {
        if (left == right) return true
        val size = max(left.length, right.length)
        if (size == 0 || left.isBlank() || right.isBlank() || size > 512 || abs(left.length - right.length) > size / 10) return false
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { i, char ->
            val current = IntArray(right.length + 1); current[0] = i + 1
            right.forEachIndexed { j, other -> current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (char == other) 0 else 1) }
            previous = current
        }
        return previous.last().toDouble() / size <= .10
    }

    fun accept(track: Track, offeredTitle: String, offeredArtist: String, durationMs: Long,
        synced: String, plain: String, instrumental: Boolean = false): LyricsResult? {
        if (offeredTitle.length > 2_048 || offeredArtist.length > 2_048 || synced.length > 256_000 || plain.length > 256_000) return null
        val lookup = lookupTrack(track)
        val wanted = title(lookup.title); val offered = title(lookupTrack(lookup.copy(title = offeredTitle, artist = offeredArtist)).title)
        val base = baseFallback(wanted, offered)
        if (wanted.base.isBlank() || wanted.base.length > 512 || offered.base.length > 512 ||
            (wanted.version != offered.version && !base) || !similar(wanted.base, offered.base)) return null
        val expectedArtists = artists(lookup.artist); val actualArtists = artists(offeredArtist)
        val matchingDuration = track.durationMs > 0 && durationMs > 0 &&
            abs(track.durationMs - durationMs) <= max(2_000L, track.durationMs / 100)
        if (actualArtists.isEmpty() || (expectedArtists.isNotEmpty() && actualArtists.intersect(expectedArtists).isEmpty())) return null
        if (expectedArtists.isEmpty() && (wanted.base != offered.base || !matchingDuration)) return null
        if (instrumental) return if (base || wanted.version.any { !it.contains("instrumental") }) null else LyricsResult(instrumental = true)
        val text = plain.trim().ifBlank { stripLrc(synced) }
        if (text.isBlank()) return null
        val lines = if (matchingDuration && !base) runCatching { parseLrc(synced) }.getOrDefault(emptyList()) else emptyList()
        return LyricsResult(text, lines)
    }

    internal fun stripLrc(value: String): String = value.replace(Regex("\\[[^]\\r\\n]{0,300}]"), "").trim()
}

/** Best verified plain text survives later source failures. Cancellation is never swallowed. */
class LyricsCascade(private val network: MusicNetwork, private val endpoints: LyricsEndpoints = LyricsEndpoints(),
    private val providerTimeoutMs: Long = 8_000) {
    suspend fun lookup(track: Track, initial: LyricsResult): LyricsResult = withContext(Dispatchers.IO) {
        if (initial.instrumental || initial.lines.isNotEmpty()) return@withContext initial
        var best = initial
        var failure: Exception? = null
        // One deadline for the whole cascade: the providers run concurrently under
        // it, so a slow provider cannot spend the budget twice.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(providerTimeoutMs.coerceIn(100, 8_000))
        data class Offered(val value: LyricsResult? = null, val error: Exception? = null, val done: Boolean = false)
        supervisorScope {
            val offers = Channel<Offered>(Channel.UNLIMITED)
            val providers: List<Pair<String, suspend (Track, (LyricsResult?) -> Unit) -> Unit>> = listOf(
                "LRCLIB" to ::lrclib, "NetEase" to ::netease, "QQ Music" to ::qq, "Genius" to ::genius)
            val jobs = providers.map { (name, provider) -> launch {
                try {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw SourceFailure("Источник текста не ответил вовремя")
                    withTimeout(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)) {
                        provider(LyricsIdentity.queryTrack(track)) { value -> offers.trySend(Offered(value?.copy(source = name))) }
                    }
                } catch (error: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive(); offers.trySend(Offered(error = SourceFailure("Источник текста не ответил вовремя")))
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { offers.trySend(Offered(error = error)) }
                finally { offers.trySend(Offered(done = true)) }
            } }
            var remaining = jobs.size
            while (remaining > 0) {
                val item = offers.receive()
                if (item.done) remaining--
                if (item.error != null) failure = failure ?: item.error
                val value = item.value
                if (value != null && (value.instrumental || value.lines.isNotEmpty() || best.plain.isBlank())) best = value
                if (best.instrumental || best.lines.isNotEmpty()) { jobs.forEach { it.cancel() }; break }
            }
            offers.close()
        }
        if (best.plain.isBlank() && best.lines.isEmpty() && !best.instrumental && failure != null)
            throw SourceFailure("Не удалось получить текст из источников. Проверьте подключение и повторите поиск", (failure as? SourceFailure)?.httpCode)
        best
    }

    private fun query(track: Track): String = "${track.artist} ${track.title}".trim().take(2_000)
    private fun url(endpoint: String, vararg parameters: Pair<String, String>): String = endpoint.toHttpUrl().newBuilder().apply {
        parameters.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build().toString()
    private suspend fun read(url: String, referer: String? = null): String = network.get(url,
        if (referer == null) emptyMap() else mapOf("Referer" to referer), 2 * 1024 * 1024)

    private suspend fun lrclib(track: Track, offer: (LyricsResult?) -> Unit) {
        val candidates = sourceArray(read(url(endpoints.lrclibSearch, "q" to query(track))))
        for (item in candidates.objects().take(50)) {
            val result = LyricsIdentity.accept(track, text(item,"trackName"), text(item,"artistName"),
                seconds(item.opt("duration")), text(item,"syncedLyrics"), text(item,"plainLyrics"), item.opt("instrumental") == true)
            offer(result)
            if (result?.instrumental == true || result?.lines?.isNotEmpty() == true) return
        }
    }

    private suspend fun netease(track: Track, offer: (LyricsResult?) -> Unit) {
        val root = sourceObject(read(url(endpoints.neteaseSearch, "type" to "1", "offset" to "0", "limit" to "12", "s" to query(track)), "https://music.163.com/"))
        if (root.optInt("code", 200) != 200) return
        val candidates = root.optJSONObject("result")?.optJSONArray("songs")?.objects().orEmpty().take(12)
        var verified = 0
        for (song in candidates) {
            val title = song.optText("name")
            val artists = people(song.optJSONArray("ar") ?: song.optJSONArray("artists"))
            val duration = millis(song.opt("dt") ?: song.opt("duration"))
            if (LyricsIdentity.accept(track, title, artists, duration, "", "candidate") == null) continue
            val id = song.opt("id")?.toString().orEmpty()
            if (!id.matches(Regex("[1-9][0-9]{0,19}"))) continue
            if (++verified > 4) return
            val result = sourceObject(read(url(endpoints.neteaseLyrics, "id" to id, "lv" to "1", "kv" to "1", "tv" to "-1"), "https://music.163.com/"))
            val synced = result.optJSONObject("lrc")?.let { text(it,"lyric") }.orEmpty()
            val accepted = LyricsIdentity.accept(track, title, artists, duration, synced, LyricsIdentity.stripLrc(synced))
            offer(accepted)
            if (accepted?.lines?.isNotEmpty() == true) return
        }
    }

    private suspend fun qq(track: Track, offer: (LyricsResult?) -> Unit) {
        val root = sourceObject(read(url(endpoints.qqSearch, "w" to query(track), "format" to "json", "p" to "1", "n" to "12"), "https://y.qq.com/"))
        val candidates = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")?.objects().orEmpty().take(12)
        var verified = 0
        for (song in candidates) {
            val title = song.optText("songname"); val artists = people(song.optJSONArray("singer"))
            val duration = seconds(song.opt("interval"))
            if (LyricsIdentity.accept(track, title, artists, duration, "", "candidate") == null) continue
            val id = song.optText("songmid")
            if (!id.matches(Regex("[A-Za-z0-9]{1,64}"))) continue
            if (++verified > 4) return
            val rootLyrics = sourceObject(read(url(endpoints.qqLyrics, "songmid" to id, "format" to "json", "nobase64" to "1"), "https://y.qq.com/"))
            val synced = decodeEntities(text(rootLyrics,"lyric"))
            val accepted = LyricsIdentity.accept(track, title, artists, duration, synced, LyricsIdentity.stripLrc(synced))
            offer(accepted)
            if (accepted?.lines?.isNotEmpty() == true) return
        }
    }

    private suspend fun genius(track: Track, offer: (LyricsResult?) -> Unit) {
        val root = sourceObject(read(url(endpoints.geniusBase.trimEnd('/') + "/api/search/multi", "per_page" to "5", "q" to query(track))))
        val sections = root.optJSONObject("response")?.optJSONArray("sections")?.objects().orEmpty().take(8)
        var verified = 0
        for (section in sections) for (hit in section.optJSONArray("hits")?.objects().orEmpty().take(10)) {
            val song = hit.optJSONObject("result") ?: continue
            val artists = listOfNotNull(song.optJSONObject("primary_artist")).plus(song.optJSONArray("featured_artists")?.objects().orEmpty())
                .joinToString(", ") { it.optText("name") }
            val title = song.optText("title")
            if (LyricsIdentity.accept(track, title, artists, 0, "", "candidate") == null) continue
            val address = safeGeniusPage(song.optText("url")) ?: continue
            if (++verified > 4) return
            val plain = geniusText(network.getWithoutRedirects(address))
            val accepted = LyricsIdentity.accept(track, title, artists, 0, "", plain)
            offer(accepted)
            if (accepted != null) return
        }
    }

    private fun safeGeniusPage(value: String): String? = runCatching {
        val uri = URI(value); val base = URI(endpoints.geniusBase)
        val official = base.scheme == "https" && base.host == "genius.com" && base.port == -1
        val allowed = if (official) uri.scheme == "https" && uri.host in setOf("genius.com", "www.genius.com") && uri.port == -1
            else uri.scheme == base.scheme && uri.host == base.host && uri.port == base.port
        if (allowed && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.matches(Regex("/[A-Za-z0-9%_-]{1,500}-lyrics"))) value else null
    }.getOrNull()

    private fun people(array: JSONArray?): String = array?.objects()?.take(20)?.joinToString(", ") { it.optText("name") }.orEmpty()
    private fun text(value:JSONObject,key:String):String = (value.opt(key) as? String).orEmpty()
    private fun seconds(value: Any?): Long = (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..604_800.0 }?.times(1000)?.toLong() ?: 0
    private fun millis(value: Any?): Long = (value as? Number)?.toLong()?.takeIf { it in 0..604_800_000L } ?: 0

    companion object {
        internal fun geniusText(page: String): String {
            if (page.length > 2 * 1024 * 1024) return ""
            val containers = Regex("<div\\b[^>]*\\bdata-lyrics-container\\s*=\\s*[\"']true[\"'][^>]*>", RegexOption.IGNORE_CASE)
            val div = Regex("</?div\\b[^>]*>", RegexOption.IGNORE_CASE)
            val parts = mutableListOf<String>()
            for (start in containers.findAll(page).take(30)) {
                var depth = 1; var end = -1
                for (tag in div.findAll(page, start.range.last + 1)) {
                    depth += if (tag.value.startsWith("</")) -1 else 1
                    if (depth == 0) { end = tag.range.first; break }
                }
                if (end < 0) continue
                val body = page.substring(start.range.last + 1, end)
                    .replace(Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                    .replace(Regex("<br\\s*/?>|</?(?:p|div)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("<[^>]*>"), "")
                parts += decodeEntities(body).trim()
                if (parts.sumOf { it.length } > 256_000) return ""
            }
            return parts.joinToString("\n").trim()
        }

        internal fun decodeEntities(value: String): String {
            val named = mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ",
                "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”")
            return Regex("&(#x[0-9A-Fa-f]{1,6}|#[0-9]{1,7}|[A-Za-z]{2,10});").replace(value) { match ->
                val entity = match.groupValues[1]
                val number = when { entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16); entity.startsWith('#') -> entity.drop(1).toIntOrNull(); else -> null }
                if (number != null && number in 1..0x10FFFF && number !in 0xD800..0xDFFF) String(Character.toChars(number))
                else named[entity] ?: match.value
            }
        }
    }
}
