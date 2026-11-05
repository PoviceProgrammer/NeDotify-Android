package ru.nedotify.app.downloads

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * The contract of a finished package: the bytes must be the whole recording,
 * the disk must have room, and a full disk must not be retried.
 */
class DownloadPolicyTest {
    @Test fun `a package that is shorter than the declared total is not complete`() {
        val verdict=downloadCompletion(5_000_000,4_000_000)
        assertTrue(verdict is DownloadCompletion.Incomplete)
        assertEquals("Файл загружен не полностью",(verdict as DownloadCompletion.Incomplete).reason)
    }
    @Test fun `a package that reached the declared total is complete`() {
        assertEquals(DownloadCompletion.Complete,downloadCompletion(5_000_000,5_000_000))
        assertEquals(DownloadCompletion.Complete,downloadCompletion(5_000_000,6_000_000))
    }
    @Test fun `a package without a declared total still needs the size floor`() {
        assertTrue(downloadCompletion(null,1_024) is DownloadCompletion.Incomplete)
        assertTrue(downloadCompletion(null,1_024) is DownloadCompletion.Incomplete)
        assertEquals(DownloadCompletion.Complete,downloadCompletion(null,MIN_DOWNLOAD_BYTES))
    }
    @Test fun `a declared total of zero is no declaration at all`() {
        assertEquals(DownloadCompletion.Complete,downloadCompletion(0,MIN_DOWNLOAD_BYTES))
        assertEquals(DownloadCompletion.Complete,downloadCompletion(-1,MIN_DOWNLOAD_BYTES))
    }
    @Test fun `the declared total is the size of the whole file`() {
        // Media3 reports the total content length of the resource
        // (`CacheWriter.getLength()` is `endPosition - dataSpec.position`, and
        // the downloader builds its request with the position 0). The bytes
        // that were committed before the attempt are already counted in it.
        assertEquals(5_000_000L,expectedTotal(5_000_000))
        assertNull(expectedTotal(0))
        assertNull(expectedTotal(-1))
    }
    @Test fun `an hls package must hold every segment of its manifest`() {
        // 20 segments and the manifest itself: 21 resources.
        assertEquals(DownloadCompletion.Complete,hlsCompletion(20,21,true))
        // Three of the twenty segments: the package is truncated.
        val truncated=hlsCompletion(20,4,true)
        assertTrue(truncated is DownloadCompletion.Incomplete)
        assertEquals("Файл загружен не полностью",(truncated as DownloadCompletion.Incomplete).reason)
        // The resources are there, but one of them was never committed.
        assertTrue(hlsCompletion(20,21,false) is DownloadCompletion.Incomplete)
        // A manifest without segments is not a recording.
        assertTrue(hlsCompletion(0,1,true) is DownloadCompletion.Incomplete)
    }
    @Test fun `a full disk is not a transient error`() {
        assertTrue(isDiskFull(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(isDiskFull(IOException("outer",IOException("ENOSPC"))))
        assertTrue(isDiskFull(IOException("No space left on device")))
        assertFalse(isDiskFull(IOException("Connection reset")))
    }
    @Test fun `the app's own disk full verdict is recognised`() {
        // The verdict of the app is a type, not a text: a message that only
        // happens to say "no space" is not a verdict.
        assertTrue(isDiskFull(diskFullFailure()))
        assertTrue(isDiskFull(IOException("outer",diskFullFailure())))
        assertFalse(isDiskFull(IOException(DISK_FULL_MESSAGE)))
    }
    @Test fun `a full disk is never retried and says what to do`() {
        assertFalse(downloadCanRetry(0,null,diskFull=true))
        assertFalse(downloadCanRetry(0,503,diskFull=true))
        assertTrue(downloadCanRetry(0,null))
        assertEquals(DISK_FULL_MESSAGE,failureText(IOException("ENOSPC"),null))
        assertEquals(DISK_FULL_MESSAGE,failureText(diskFullFailure(),null))
    }
    @Test fun `the worker does not retry a full disk`() {
        assertFalse(downloadRetry(diskFullFailure(),0,null))
        assertFalse(downloadRetry(IOException("ENOSPC"),0,null))
        // An expired signature is retried: a new attempt resolves a new one.
        assertTrue(downloadRetry(ru.nedotify.app.data.SourceFailure("expired",401),0,401))
        // A plain I/O error is retried while the budget lasts.
        assertTrue(downloadRetry(IOException("reset"),0,null))
        assertFalse(downloadRetry(IOException("reset"),3,null))
        // A failure that is neither is not retried.
        assertFalse(downloadRetry(IllegalStateException("other"),0,null))
    }
    @Test fun `the free space probe refuses a download that cannot fit`() {
        assertTrue(freeSpaceSufficient(MIN_FREE_BYTES,MIN_FREE_BYTES))
        assertFalse(freeSpaceSufficient(MIN_FREE_BYTES-1,MIN_FREE_BYTES))
        // A volume whose free space cannot be read fails closed: a download
        // that fills the disk takes the whole app down with it.
        assertFalse(freeSpaceSufficient(null,MIN_FREE_BYTES))
    }
    @Test fun `the ceiling stops a package that is too large`() {
        assertFalse(exceedsDownloadCeiling(MAX_DOWNLOAD_BYTES))
        assertTrue(exceedsDownloadCeiling(MAX_DOWNLOAD_BYTES+1))
    }
    @Test fun `the verdict of a stream that cannot be stored matches its text`() {
        assertTrue(streamVerdictMessage(StreamVerdict.DrmProtected).contains("DRM"))
        assertFalse(streamVerdictMessage(StreamVerdict.DrmProtected).contains("лиценз"))
        assertTrue(streamVerdictMessage(StreamVerdict.Live).contains("Прямые эфиры"))
        assertTrue(streamVerdictMessage(StreamVerdict.Empty).contains("аудиофрагмент"))
    }
    @Test fun `the legacy key of a package is still found under the old format`() {
        val yandex="https://storage.mds.yandex.net/get-mp3/signature/1700000000/x/xx/track.mp3"
        assertNotEquals(downloadCacheKey("track",yandex),legacyDownloadCacheKey("track",yandex))
        assertTrue(legacyDownloadCacheKey("track",yandex).startsWith(downloadPrefix("track")))
    }
}
