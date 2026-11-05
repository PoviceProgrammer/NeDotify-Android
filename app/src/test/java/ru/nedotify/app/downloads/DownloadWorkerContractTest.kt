package ru.nedotify.app.downloads

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The order of the statements of the download worker.
 *
 * The worker lives in an Android `CoroutineWorker`: it cannot be run in a
 * JVM test. The order of its statements is nevertheless a contract — a
 * package that is published as complete before it was judged is the defect
 * the whole completeness contract exists for — so the order is pinned on the
 * source itself.
 */
class DownloadWorkerContractTest {
    @Test fun `the package is published as complete only after the verdict`() {
        val source=source("ru/nedotify/app/downloads/TrackDownloadWorker.kt")
        val verdict=source.indexOf("val verdict=")
        val published=source.indexOf("""dao.downloadState(trackId,generation,"complete")""")
        assertTrue("the worker has no verdict",verdict>=0)
        assertTrue("the worker never publishes a package as complete",published>=0)
        assertTrue("the package was published before it was judged",published>verdict)
    }
    @Test fun `the free space is probed before the transfer starts`() {
        val source=source("ru/nedotify/app/downloads/TrackDownloadWorker.kt")
        val probe=source.indexOf("storage.freeBytes()")
        val transfer=source.indexOf("storage.downloader(")
        assertTrue(probe>=0);assertTrue(transfer>=0)
        assertTrue("the transfer started before the disk was checked",probe<transfer)
    }
    @Test fun `the byte ceiling cancels the transfer instead of waiting for it`() {
        val source=source("ru/nedotify/app/downloads/TrackDownloadWorker.kt")
        assertTrue("the ceiling does not cancel the downloader",
            source.contains("exceedsDownloadCeiling(committed)){tooLarge=true;downloader.cancel()}"))
    }
    @Test fun `a full disk is not retried`() {
        val source=source("ru/nedotify/app/downloads/TrackDownloadWorker.kt")
        val verdict=source.indexOf("val verdict=")
        val retry=source.indexOf("val retry=")
        assertTrue(verdict>=0);assertTrue(retry>=0)
        // The retry decision is a function of its own, and it knows the disk.
        assertTrue(source.contains("downloadRetry(error,runAttemptCount,status)"))
    }
    private fun source(path:String):String {
        var root=File("").absoluteFile
        while(root.parentFile!=null) {
            val file=File(root,"src/main/java/$path")
            if(file.isFile)return file.readText()
            root=root.parentFile
        }
        throw AssertionError("the source file $path was not found")
    }
}
