package ru.nedotify.app.downloads

import androidx.media3.exoplayer.offline.Downloader
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadCancellationTest {
    @Test fun `cancel waits for the old downloader to stop before releasing the track`()=runBlocking {
        val started=CountDownLatch(1);val exited=AtomicBoolean();val cancelled=AtomicBoolean()
        val downloader=object:Downloader {
            override fun download(progressListener:Downloader.ProgressListener?) {
                started.countDown()
                try {CountDownLatch(1).await(8,TimeUnit.SECONDS)} finally {exited.set(true)}
            }
            override fun cancel(){cancelled.set(true)}
            override fun remove(){}
        }
        withTimeout(5000) {
            val job=launch {awaitDownload(downloader,Downloader.ProgressListener {_,_,_->})}
            assertTrue(withContext(Dispatchers.IO){started.await(3,TimeUnit.SECONDS)})
            job.cancelAndJoin()
            assertTrue(exited.get());assertTrue(cancelled.get());assertTrue(job.isCancelled)
        }
    }
}
