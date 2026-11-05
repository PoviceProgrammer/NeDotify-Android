package ru.nedotify.app.data

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * The identity of a media resource.
 *
 * A media URL is *signed*: the signature, the expiry and sometimes the media
 * id live in the query or in the path, and they change on every resolution.
 * The cache key must therefore not be the URL itself — otherwise every
 * re-resolution orphans the bytes that were already fetched, and a download
 * that has to be retried starts from zero instead of resuming.
 *
 * The identity is the URL without its signature:
 *  * the authentication parameters of a known CDN are dropped;
 *  * the signature and the timestamp of a signed *path* are replaced by a
 *    placeholder (the Yandex CDN puts them there);
 *  * everything else — the host, the path, the media id, the codec, the
 *    content length, the last modification — stays part of the identity, so a
 *    different recording can never be served from another track's bytes.
 */
private val authQueryKeys = setOf(
    "expires", "expire", "policy", "signature", "key-pair-id", "sig", "lsig", "spc", "sparams", "lsparams"
)

/** The hosts whose authentication parameters are dropped from the identity. */
private val signedQueryHosts = listOf("sndcdn.com", "*.sndcdn.com", "scdn.co", "*.scdn.co",
    "soundcloud.cloud", "*.soundcloud.cloud", "media-streaming.soundcloud.cloud",
    "*.media-streaming.soundcloud.cloud", "googlevideo.com", "*.googlevideo.com")

/** The Yandex CDN signs the *path*: `/get-mp3/<signature>/<timestamp>/<file>`. */
private val signedPath = Regex("^(/get-mp3/)([^/]+)/(\\d+)(/.*)?$")

/** The namespace of one track: a download package belongs to exactly one track. */
fun mediaNamespace(trackId: String): String = MessageDigest.getInstance("SHA-256")
    .digest(trackId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) } + "|"

private fun matchesAnyRule(host: String, rules: List<String>) = rules.any { rule ->
    if (rule.startsWith("*.")) host.endsWith(rule.substring(1)) else host == rule
}

/**
 * The identity of the URL, without its signature.
 *
 * @param normaliseSignedPath whether a signed *path* is normalised. The
 *   download cache reads the un-normalised identity as a fallback for the
 *   packages that were cached before the identity was normalised.
 */
fun mediaIdentity(url: String, normaliseSignedPath: Boolean = true): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val host = uri.host.orEmpty().lowercase(Locale.ROOT)
    val known = matchesAnyRule(host, signedQueryHosts)
    val query = uri.rawQuery?.split('&')
        ?.filterNot { known && it.substringBefore('=').lowercase(Locale.ROOT) in authQueryKeys }
        ?.joinToString("&").orEmpty()
    val path = uri.rawPath.orEmpty()
    val normalised = if (normaliseSignedPath && MediaUrlPolicy.hostAllowed(host, "yandex"))
        signedPath.matchEntire(path)?.let {
            it.groupValues[1] + "{signature}/{timestamp}" + it.groupValues[4]
        } ?: path else path
    val authority = uri.rawAuthority.orEmpty().substringAfterLast('@')
    val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
    return scheme + "://" + authority + normalised + (if (query.isBlank()) "" else "?$query")
}

/**
 * The cache key of one media resource.
 *
 * @param trackId the track the bytes belong to, or `null` for a resource
 *   that is not owned by a track (an HLS segment of a resolved playlist).
 */
fun mediaCacheKey(trackId: String?, url: String): String =
    (trackId?.let { mediaNamespace(it) } ?: "") + mediaIdentity(url)

/** The prefix that marks the manifest of an HLS stream. */
const val HLS_MANIFEST_KEY_PREFIX = "hls:"

/**
 * The cache key of the streaming path.
 *
 * @param identity the identity the resolver put into the data spec: the
 *   track that owns the resource, or the track plus the manifest marker.
 * @param url the URL the resource is read from.
 */
fun streamingCacheKey(identity: String?, url: String): String = when {
    // The manifest of an HLS stream carries the signatures of its segments:
    // it must be fetched again with every resolution, or the segments it
    // names are refused. The segments themselves keep a stable key, so their
    // bytes survive the new manifest.
    identity != null && identity.startsWith(HLS_MANIFEST_KEY_PREFIX) -> url
    // The media itself: the track that owns it and the resource.
    identity != null -> mediaCacheKey(identity, url)
    // A segment of a manifest: the resource alone.
    else -> mediaCacheKey(null, url)
}
