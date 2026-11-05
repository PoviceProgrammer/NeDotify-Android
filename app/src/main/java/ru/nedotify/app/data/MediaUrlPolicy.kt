package ru.nedotify.app.data

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Where the media policy's diagnostics go.
 *
 * The policy is a **checked** boundary: its answer — accept or refuse — is
 * the contract, and a contract must not depend on a side effect that can
 * fail. A log write is such a side effect: `android.util.Log` is not
 * implemented in a JVM unit test, and a log file can be unwritable. The
 * diagnostic is therefore an injected sink, and the policy treats a sink
 * that fails as a *missing diagnostic*, never as a failure of the refusal.
 */
fun interface MediaDiagnostics {
    /**
     * A host the policy refused.
     *
     * The **host**, never the URL: a signed URL carries a signature, and a
     * signature must not reach a log file.
     */
    fun onRefusedHost(host: String)
}

/**
 * The production sink of the media policy: the app's log.
 *
 * The level (WARN) and the tag live here, in the wiring of the app, and not
 * in the policy: the policy knows that a host was refused, and the app
 * decides what that means for whoever reads the log.
 */
object AppMediaLog : MediaDiagnostics {
    /** The tag the whole app writes under. */
    const val TAG = "NeDotify"

    override fun onRefusedHost(host: String) {
        Log.w(TAG, "Media host refused by the URL policy: $host")
    }
}

/**
 * The media-URL policy of the app.
 *
 * The streaming path and the download path read the same kind of resource: a
 * signed media URL handed out by a provider. A prefix check (`https://`) is not
 * enough for it: the URL decides which host the app connects to, and a
 * provider that is compromised — or a redirect that a provider performs — can
 * point the download at any host on the internet.
 *
 * The policy is therefore a **host allow-list per source**:
 *  * the URL must be `https`, carry no credentials and no fragment;
 *  * its host must belong to the allow-list of the source that produced it;
 *  * the check runs again on **every redirect hop**, so a permitted URL may
 *    not be used to reach a forbidden host — and a hop may not leave the
 *    source's own list either: a SoundCloud URL that redirects to a
 *    GoogleVideo host is refused, because the URL that the provider handed
 *    out was a SoundCloud one.
 *
 * The allow-list of the Yandex source is the one that already existed in
 * `YandexDownloadInfo.trustedUrl`; the two are pinned against each other by
 * `MediaUrlPolicyTest` so they cannot drift apart.
 */
interface MediaHostPolicy {
    /** The source whose allow-list holds this URL, or `null` when the URL may not be read. */
    fun sourceFor(url: HttpUrl): String?

    /** The `Referer` the source's CDN expects; `null` when the source needs none. */
    fun refererFor(source: String): String?

    /**
     * Whether this source may read this URL.
     *
     * The default is "the URL's host belongs to this source". A policy that
     * knows more may answer differently, but it may never answer *more
     * permissively* than the allow-list of the source.
     */
    fun accepts(source: String, url: HttpUrl): Boolean = sourceFor(url) == source

    /**
     * A host the policy refused.
     *
     * The **host** is reported, never the URL: a signed URL carries a
     * signature, and a signature must not reach a log file. The hook exists so
     * that a CDN that moves its hosts is visible in the log instead of
     * silently breaking every stream and every download.
     *
     * The hook is a diagnostic, never a part of the decision: a policy that
     * implements it must not let a failing diagnostic change its answer.
     */
    fun onRefusedHost(host: String) {}
}

object MediaUrlPolicy : MediaHostPolicy {
    /**
     * One agent for the whole provider session.
     *
     * The metadata request, the InnerTube request and the media request must
     * not look like three different clients: the CDNs and the provider APIs
     * answer a request differently depending on the agent, and a session that
     * changes its agent between the metadata call and the media call is a
     * session that can be refused. The agent is the one the desktop sends on
     * its YouTube and SoundCloud paths (`services/youtube_service.py`,
     * `services/soundcloud_service.py`).
     */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** A signed URL is a small document; anything longer is not one. */
    const val MAX_URL_LENGTH = 8192

    /** The media is read over the standard https port and nowhere else. */
    const val DEFAULT_HTTPS_PORT = 443

    /**
     * Where the "refused host" diagnostic goes.
     *
     * The production wiring supplies the app's log; a test supplies a
     * recorder. The default is the app's own log, so a caller that wires
     * nothing still gets the diagnostic in production.
     *
     * The policy is a process-wide singleton and the sink is read from every
     * thread that checks a URL, so the field is volatile: the sink is chosen
     * once, at startup, and is then read from the network and the download
     * threads.
     */
    @Volatile
    var diagnostics: MediaDiagnostics = AppMediaLog

    /**
     * The hosts a source may use.
     *
     * A rule that starts with `*.` matches the subdomains of the host; every
     * other rule matches that host exactly. The Yandex rules are the ones of
     * `YandexDownloadInfo.trustedUrl`; the two are pinned against each other
     * by `MediaUrlPolicyTest` so they cannot drift apart.
     *
     * The SoundCloud list is the desktop's own list (`core/proxy.py`:
     * `soundcloud.com`, `sndcdn.com`, `soundcloud.cloud`, `scdn.co`) narrowed
     * to the hosts that actually carry media, plus the hosts the app's own
     * probe has observed (`android/review/PROVIDER_PROBE.json`:
     * `playback.media-streaming.soundcloud.cloud` for the HLS manifest and
     * its segments, `a-v2.sndcdn.com` for the client-id asset). The list is
     * deliberately **wider** than what the app has seen: a host that is
     * missing is a broken playback, and a host that is present but unused is
     * no worse than the host the desktop already trusts.
     */
    private val allowedHosts: Map<String, List<String>> = mapOf(
        "soundcloud" to listOf(
            "sndcdn.com", "*.sndcdn.com",
            "scdn.co", "*.scdn.co",
            "soundcloud.cloud", "*.soundcloud.cloud",
            "media-streaming.soundcloud.cloud", "*.media-streaming.soundcloud.cloud"
        ),
        "youtube" to listOf("googlevideo.com", "*.googlevideo.com"),
        "yandex" to listOf("storage.mds.yandex.net", "*.storage.yandex.net", "music.yandex.net", "*.music.yandex.net")
    )

    private val referers: Map<String, String> = mapOf(
        "soundcloud" to "https://soundcloud.com/",
        "youtube" to "https://music.youtube.com/",
        "yandex" to "https://music.yandex.ru/"
    )

    /** The hosts a source may use. */
    fun hostsFor(source: String): List<String> = allowedHosts[source].orEmpty()

    /** The source that owns a host, or `null` when no source may use it. */
    fun sourceForHost(host: String): String? {
        val name = host.lowercase(Locale.ROOT)
        return allowedHosts.entries.firstOrNull { (_, rules) -> hostAllowed(name, rules) }?.key
    }

    /** Whether the host matches one of the rules of a source. */
    fun hostAllowed(host: String, source: String): Boolean =
        hostAllowed(host.lowercase(Locale.ROOT), hostsFor(source))

    private fun hostAllowed(host: String, rules: List<String>): Boolean = rules.any { rule ->
        if (rule.startsWith("*.")) host.endsWith(rule.substring(1)) else host == rule
    }

    override fun sourceFor(url: HttpUrl): String? {
        if (!url.isHttps) return null
        if (url.port != DEFAULT_HTTPS_PORT) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (!url.fragment.isNullOrEmpty()) return null
        if (url.toString().length > MAX_URL_LENGTH) return null
        return sourceForHost(url.host)
    }

    override fun refererFor(source: String): String? = referers[source]

    override fun accepts(source: String, url: HttpUrl): Boolean =
        sourceFor(url) == source && hostAllowed(url.host, source)

    override fun onRefusedHost(host: String) {
        // The host, never the URL: a signed URL carries a signature.
        reportRefusedHost(host)
    }

    /**
     * The diagnostic, reported so that it can never abort the refusal.
     *
     * The refusal is the contract of the policy; the diagnostic is a courtesy
     * towards whoever reads the log. A sink that fails — an `android.util.Log`
     * that is not implemented in a JVM unit test, a log file that cannot be
     * written — must neither turn a refused host into a crash of the media
     * path nor hide the `SourceFailure` the user is waiting for.
     */
    private fun reportRefusedHost(host: String) {
        try {
            diagnostics.onRefusedHost(host)
        } catch (diagnosticFailure: Exception) {
            // Deliberately contained: the diagnostic is lost, the refusal
            // stands. There is nowhere else to report it — the sink that just
            // failed is the reporter. An `Error` is not contained: a fatal JVM
            // condition is not a diagnostic that failed, it is a process that
            // is going down.
        }
    }

    /** True when this source may read this URL. */
    fun accepts(source: String, url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return accepts(source, parsed)
    }

    /**
     * Whether any source may read this URL.
     *
     * The check of a *specific* source belongs to the resolution boundary,
     * where the source that produced the URL is known. This is the weaker
     * check of a boundary that does not know it: the URL must still be a
     * media URL of a source the app knows.
     */
    fun acceptsAnySource(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return sourceFor(parsed) != null
    }

    /**
     * The URL, or a `SourceFailure` the user can act on. The message never
     * contains the URL itself: it can carry a signature.
     */
    fun requireAccepted(source: String, url: String): String {
        val parsed = url.toHttpUrlOrNull()
            ?: throw SourceFailure("Источник вернул неверный адрес аудио")
        if (!accepts(source, parsed)) {
            onRefusedHost(parsed.host)
            throw SourceFailure("Источник вернул неподдерживаемый адрес аудио")
        }
        return url
    }
}
