package ru.nedotify.app.downloads

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * The order of the two steps of a download: the slot first, the foreground
 * second. A worker that waits for its slot must not hold a foreground
 * quota while it waits.
 */
class DownloadGateTest {
    @Test fun `the foreground is not entered while the worker waits for its slot`()=runBlocking {
        val slots=Semaphore(1)
        slots.acquire()
        var foregrounded=false
        val worker=launch {downloadGate(slots,{foregrounded=true}){}}
        delay(150)
        assertFalse("the worker entered the foreground while it waited for its slot",foregrounded)
        slots.release()
        worker.join()
        assertTrue(foregrounded)
    }
    @Test fun `the block runs inside the slot`()=runBlocking {
        val slots=Semaphore(1)
        var inside=false
        slots.acquire()
        val worker=launch {downloadGate(slots,{inside=true}){inside=true}}
        delay(100)
        assertFalse(inside)
        slots.release()
        worker.join()
        assertTrue(inside)
    }
}
