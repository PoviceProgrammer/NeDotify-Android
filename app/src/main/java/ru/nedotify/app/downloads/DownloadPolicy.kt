package ru.nedotify.app.downloads

import java.io.IOException

/**
 * Why a stream cannot be stored for offline playback.
 *
 * The verdict and its text belong together: the old message claimed that a
 * protected stream "requires a separate offline licence", which is false —
 * the app has no way to obtain one, and the stream is simply not storable.
 */
enum class StreamVerdict {
    /** A live stream has no end: it can never become a finished track. */
    Live,

    /** The playlist declares no audio fragments. */
    Empty,

    /** The stream is encrypted with a protection the app cannot decode. */
    DrmProtected
}

/** The user-facing text of a verdict. */
fun streamVerdictMessage(verdict: StreamVerdict): String = when (verdict) {
    StreamVerdict.Live -> "Прямые эфиры нельзя сохранить как завершённый трек"
    StreamVerdict.Empty -> "В записи нет аудиофрагментов"
    StreamVerdict.DrmProtected -> "Поток защищён DRM. Его нельзя сохранить для офлайн-воспроизведения"
}

/** The verdict of a package that must not be published as complete. */
sealed interface DownloadCompletion {
    /** The bytes are the whole recording. */
    data object Complete : DownloadCompletion

    /** The bytes are not the whole recording; the text says why. */
    data class Incomplete(val reason: String) : DownloadCompletion
}

/** The largest package the app stores. A recording beyond that is refused. */
const val MAX_DOWNLOAD_BYTES: Long = 512L * 1024 * 1024

/** The smallest file that can be a recording. Anything shorter is a fragment. */
const val MIN_DOWNLOAD_BYTES: Long = 32L * 1024

/** The free space a download needs before it may start. */
const val MIN_FREE_BYTES: Long = 64L * 1024 * 1024

/**
 * Whether the received bytes are the whole recording.
 *
 * The provider declares a total; the download may deliver less. A declared
 * total that is not reached means the package is truncated, and a package
 * that is published as complete is then played as if it ended — the user
 * hears a track that stops in the middle. A total that the provider did not
 * declare leaves only the size floor.
 */
fun downloadCompletion(declaredTotal: Long?, received: Long, minimumBytes: Long = MIN_DOWNLOAD_BYTES): DownloadCompletion {
    if (received < minimumBytes) return DownloadCompletion.Incomplete("Источник вернул слишком короткий файл")
    if (declaredTotal != null && declaredTotal > 0 && received < declaredTotal)
        return DownloadCompletion.Incomplete("Файл загружен не полностью")
    return DownloadCompletion.Complete
}

/**
 * The verdict of an HLS package.
 *
 * The manifest is the contract of an HLS package: the offline playback reads
 * the manifest out of the cache and then every segment the manifest names.
 * A package whose segments are not all in the cache therefore breaks **in the
 * middle of the track** — the offline path has no upstream to fall back to,
 * and the missing segment is a cache miss.
 *
 * Two checks, because they fail for different reasons:
 *  * the **count** check — the package must hold at least one resource per
 *    segment of the manifest (plus the manifest itself). It cannot be fooled
 *    by a resource that was opened but not committed, and it does not depend
 *    on how a segment URL is spelled.
 *  * the **precise** check — every segment of the manifest must be in the
 *    cache under the key the downloader used.
 *
 * @param expectedSegments how many segments the manifest names.
 * @param cachedResources how many resources of the package are in the cache.
 * @param everyExpectedCommitted whether every segment of the manifest is
 *   committed to the cache.
 */
fun hlsCompletion(expectedSegments: Int, cachedResources: Int, everyExpectedCommitted: Boolean): DownloadCompletion {
    if (expectedSegments <= 0) return DownloadCompletion.Incomplete("В записи нет аудиофрагментов")
    // The manifest itself is one of the resources of the package.
    if (cachedResources < expectedSegments + 1) return DownloadCompletion.Incomplete("Файл загружен не полностью")
    if (!everyExpectedCommitted) return DownloadCompletion.Incomplete("Файл загружен не полностью")
    return DownloadCompletion.Complete
}

/**
 * Whether the device has room for the download.
 *
 * The probe happens before the transfer: a full disk turns every retry into
 * the same failure, so it must not be treated as a transient error. The
 * probe **fails closed**: a volume whose free space cannot be read is a
 * volume the app may not fill, because a download that fills the disk takes
 * the whole app down with it.
 */
fun freeSpaceSufficient(availableBytes: Long?, requiredBytes: Long): Boolean =
    availableBytes != null && availableBytes >= requiredBytes

/**
 * The size the provider declared for the whole file.
 *
 * Media3 reports the **total** content length of the resource, not the
 * number of bytes that are still missing: `CacheWriter.getLength()` is
 * `endPosition - dataSpec.position`, and the progressive downloader builds
 * its request with the position 0. The bytes that were committed before the
 * attempt are therefore already counted in the declared total, and adding
 * them again would report a file that is larger than it is.
 *
 * A server that ignores the range does not change this: Media3's HTTP data
 * source skips the prefix it already has and reports the remaining length,
 * so the end position stays the size of the whole file.
 *
 * @return the expected size, or `null` when the provider declared none.
 */
fun expectedTotal(declared: Long): Long? = if (declared <= 0) null else declared

/**
 * The failure of a disk that is full.
 *
 * The failure carries its own type: a message that only *says* "no space
 * left" is not a verdict the app can recognise, and an unrecognised
 * `IOException` is a transient error that the retry policy would repeat
 * until the attempt budget is spent.
 */
class DiskFullException(val reason: String) : IOException(reason)

/** The failure of a volume whose free space cannot be read. */
class FreeSpaceUnknownException : IOException("Не удалось проверить свободное место. Повторите загрузку")

/**
 * Whether the failure is "the disk is full".
 *
 * `ENOSPC` is an `IOException`, so without this check the retry policy would
 * treat a full disk as a transient error and retry it until the attempt
 * budget is spent. The app's **own** verdict is recognised by its type: a
 * text that happens to contain the words of the message is not a verdict.
 */
fun isDiskFull(error: Throwable): Boolean {
    val chain = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
    if (chain.any { it is DiskFullException }) return true
    return chain.any { cause ->
        val message = cause.message?.lowercase().orEmpty()
        message.contains("enospc") || message.contains("no space left on device") ||
            message.contains("not enough space") || message.contains("disk full")
    }
}

/** The text of a full disk. It is final: the user has to free space. */
const val DISK_FULL_MESSAGE = "Не удалось сохранить музыку. Проверьте свободное место"

/** The failure of a disk that is full. */
fun diskFullFailure(): IOException = DiskFullException(DISK_FULL_MESSAGE)

/**
 * Whether the package is larger than the app stores.
 *
 * The ceiling is checked against the **committed** bytes, so a package that
 * is already too large is cancelled instead of being written to the end.
 */
fun exceedsDownloadCeiling(committedBytes: Long): Boolean = committedBytes > MAX_DOWNLOAD_BYTES

/**
 * Whether the worker may try again.
 *
 * The decision is one function, so that it can be pinned: a full disk is
 * never retried, a source failure that carries a status (an expired
 * signature) is retried, and a plain I/O error is retried while the attempt
 * budget lasts.
 */
fun downloadRetry(error: Throwable, attempt: Int, status: Int?): Boolean = when {
    isDiskFull(error)->false
    // A source failure that carries a status is the signed URL expiring: a
    // new attempt resolves a new one.
    error is ru.nedotify.app.data.SourceFailure->status!=null && downloadCanRetry(attempt,status)
    error is IOException->downloadCanRetry(attempt,status)
    else->false
}
