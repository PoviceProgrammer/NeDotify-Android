package ru.nedotify.app.downloads

import ru.nedotify.app.data.mediaCacheKey
import ru.nedotify.app.data.mediaIdentity
import ru.nedotify.app.data.mediaNamespace

/**
 * The identity of a download package.
 *
 * The key must survive a re-resolution: a provider signs every URL afresh,
 * and a key that contains the signature turns every retry into a download
 * from zero. The identity therefore drops the signature and keeps the
 * resource itself — the same rule the streaming cache uses, so both caches
 * agree on what "the same bytes" means.
 */
fun downloadPrefix(trackId: String): String = mediaNamespace(trackId)

/** The cache key of a download package. */
fun downloadCacheKey(trackId: String, url: String): String = mediaCacheKey(trackId, url)

/**
 * The key a package had before the signed *path* was normalised.
 *
 * The old format kept the signature and the timestamp of the signed path in
 * the key. The signature changes with **every** resolution, so a package of
 * the old format cannot be found by a new resolution any more: the legacy
 * key of the new URL is a different key. The key is kept here as the
 * definition of the old format — the packages of that format are orphaned
 * and the user downloads them again.
 */
fun legacyDownloadCacheKey(trackId: String, url: String): String =
    downloadPrefix(trackId) + mediaIdentity(url, normaliseSignedPath = false)

/**
 * Whether another attempt may be made.
 *
 * A full disk is not a transient error: retrying it would fail in exactly
 * the same way until the attempt budget is spent.
 */
fun downloadCanRetry(attempt: Int, status: Int?, diskFull: Boolean = false): Boolean =
    !diskFull && attempt < 3 && (status == null || status in listOf(401, 403, 408, 429) || status >= 500)
