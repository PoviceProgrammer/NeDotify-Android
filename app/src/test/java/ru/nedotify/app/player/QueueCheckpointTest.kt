package ru.nedotify.app.player

import org.junit.Assert.*
import org.junit.Test
import ru.nedotify.app.data.QueueSnapshot

/**
 * The queue checkpoint: the identity is saved at once, the position on a much
 * slower cadence. The old guard compared the whole payload, so the position
 * made it change on every tick and the settings file was rewritten ~12 times a
 * minute (REG P2-01).
 */
class QueueCheckpointTest {
    private fun snapshot(position:Long,ids:List<String> = listOf("a","b")) =
        QueueSnapshot(ids,0,"a",0,false,listOf(0,1),position)

    @Test fun `a changed queue is saved at once`() {
        val checkpoint=QueueCheckpoint(30_000L)
        assertTrue(checkpoint.shouldWrite(snapshot(0L)))
        assertTrue(checkpoint.shouldWrite(snapshot(0L,listOf("a","c"))))
    }
    @Test fun `the position is saved on the slow cadence only`() {
        val checkpoint=QueueCheckpoint(30_000L)
        assertTrue(checkpoint.shouldWrite(snapshot(0L)))
        // The checkpoint runs every 5 s: the position moves by less than the
        // step, so nothing is written.
        var position=0L
        repeat(5) {position+=5_000L;assertFalse(checkpoint.shouldWrite(snapshot(position)))}
        // Half a minute later the position is written.
        position+=5_000L
        assertTrue(checkpoint.shouldWrite(snapshot(position)))
    }
    @Test fun `a playing track writes the settings file at most twice a minute`() {
        val checkpoint=QueueCheckpoint(30_000L)
        var writes=0
        var position=0L
        repeat(12) { // one minute of five-second checkpoints
            position+=5_000L
            if(checkpoint.shouldWrite(snapshot(position)))writes++
        }
        assertTrue("the settings file was rewritten $writes times a minute",writes<=2)
    }
    @Test fun `the final position is always saved`() {
        val checkpoint=QueueCheckpoint(30_000L)
        assertTrue(checkpoint.shouldWrite(snapshot(0L)))
        assertTrue(checkpoint.shouldWrite(snapshot(1_000L),force=true))
    }
    @Test fun `the position of a changed queue is saved with it`() {
        val checkpoint=QueueCheckpoint(30_000L)
        assertTrue(checkpoint.shouldWrite(snapshot(0L)))
        // A new queue: the identity changed, so the position goes with it.
        assertTrue(checkpoint.shouldWrite(snapshot(120_000L,listOf("x"))))
    }
}
