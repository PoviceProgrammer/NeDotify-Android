package ru.nedotify.app.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LocalArtworkTest {
    @Test fun partialCoverLeftByAnInterruptedScanIsReplacedWithCompleteBytes() {
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"artwork-test-${UUID.randomUUID()}").apply {check(mkdirs())}
        try {
            val file=File(directory,"cover.img").apply {writeBytes(byteArrayOf(1,2))}
            val complete=ByteArray(4096){(it%251).toByte()}
            LocalAudioMetadata.saveArtwork(file,complete)
            assertArrayEquals(complete,file.readBytes())
            LocalAudioMetadata.saveArtwork(file,complete)
            assertArrayEquals(complete,file.readBytes())
            assertEquals(listOf("cover.img"),directory.listFiles()!!.map {it.name})
        } finally {directory.deleteRecursively()}
    }
}
