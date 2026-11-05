package ru.nedotify.app.player

import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.*
import org.junit.Test

/**
 * The gate's contract:
 *
 *  * a play that was **enqueued before** an interruption is stale and is refused;
 *  * a play that is **enqueued after** it, or that never went through the
 *    enqueue callback (the media button, the notification), is fresh and is
 *    never refused;
 *  * an interruption never destroys the pending selection (REG P2-12).
 */
class PendingSelectionGateTest {
    @Test fun `a play that was enqueued before an interruption is refused`() {
        val gate=PendingSelectionGate()
        gate.begin(SettableFuture.create<String>())
        gate.expectQueuedPlay()          // the controller enqueued the play
        assertTrue(gate.interruptPlay()) // the environment stopped playback
        assertFalse(gate.canStartPlayback) // the queue must be installed paused
        assertTrue(gate.evaluatePlay(playWhenReady=true)) // the queued play is stale
    }
    @Test fun `an interruption after the set resolved still invalidates the queued play`() {
        // The window the old gate left open: the set future is done, the play is
        // still queued behind it. The gate must not depend on the future.
        val gate=PendingSelectionGate()
        val future=SettableFuture.create<String>()
        gate.begin(future);gate.expectQueuedPlay()
        future.set("ready");gate.complete(future)
        assertTrue(gate.interruptPlay())
        assertTrue(gate.evaluatePlay(playWhenReady=true))
    }
    @Test fun `a fresh play from the media button is never refused`() {
        val gate=PendingSelectionGate()
        gate.expectFreshPlay()
        assertFalse(gate.evaluatePlay(playWhenReady=true))
    }
    @Test fun `a fresh play does not consume the stale queued command`() {
        val gate=PendingSelectionGate()
        gate.begin(SettableFuture.create<String>())
        gate.expectQueuedPlay()
        gate.interruptPlay()
        gate.expectFreshPlay()
        assertFalse(gate.evaluatePlay(playWhenReady=true)) // the media button's play
        assertTrue(gate.evaluatePlay(playWhenReady=true))  // the stale queued play
    }
    @Test fun `the refusal is not sticky - the next play is judged on its own`() {
        val gate=PendingSelectionGate()
        gate.expectQueuedPlay();gate.interruptPlay()
        assertTrue(gate.evaluatePlay(playWhenReady=true))
        assertFalse(gate.evaluatePlay(playWhenReady=true))
    }
    @Test fun `a command enqueued after the interruption is allowed`() {
        val gate=PendingSelectionGate()
        gate.expectQueuedPlay();gate.interruptPlay()
        gate.expectQueuedPlay()
        // The commands are executed in the order they were enqueued: the stale
        // one first (refused), the fresh one after it (allowed).
        assertTrue(gate.evaluatePlay(playWhenReady=true))
        assertFalse(gate.evaluatePlay(playWhenReady=true))
    }
    @Test fun `a pause is never refused`() {
        val gate=PendingSelectionGate()
        gate.expectQueuedPlay();gate.interruptPlay()
        assertFalse(gate.evaluatePlay(playWhenReady=false))
    }
    @Test fun `an interruption without an outstanding command does not refuse the next play`() {
        val gate=PendingSelectionGate()
        assertFalse(gate.interruptPlay())
        assertTrue(gate.canStartPlayback)
        assertFalse(gate.evaluatePlay(playWhenReady=true))
    }
    @Test fun `an interruption never destroys the pending selection`() {
        val gate=PendingSelectionGate()
        val future=SettableFuture.create<String>()
        gate.begin(future)
        gate.expectQueuedPlay()
        gate.interruptPlay()
        assertFalse(future.isCancelled)
    }
    @Test fun `a newer selection supersedes the older pending one`() {
        val gate=PendingSelectionGate()
        val older=SettableFuture.create<String>();gate.begin(older)
        val newer=SettableFuture.create<String>();gate.begin(newer)
        assertTrue(older.isCancelled);assertFalse(newer.isCancelled)
        gate.complete(older) // a stale callback cannot clear the newer request
        assertFalse(newer.isCancelled)
    }
    @Test fun `a new selection re-arms the gate`() {
        val gate=PendingSelectionGate()
        gate.expectQueuedPlay();gate.interruptPlay()
        assertFalse(gate.canStartPlayback)
        gate.begin(SettableFuture.create<String>())
        assertTrue(gate.canStartPlayback)
        assertFalse(gate.evaluatePlay(playWhenReady=true))
    }
    @Test fun `the queue is installed paused only while a stale command is outstanding`() {
        val gate=PendingSelectionGate()
        gate.begin(SettableFuture.create<String>())
        gate.expectQueuedPlay()
        assertTrue(gate.canStartPlayback) // nothing went stale yet
        gate.interruptPlay()
        assertFalse(gate.canStartPlayback)
        assertTrue(gate.evaluatePlay(playWhenReady=true)) // the queued play is consumed
        assertTrue(gate.canStartPlayback)
    }
    @Test fun `a play with no known origin is allowed`() {
        val gate=PendingSelectionGate()
        assertFalse(gate.evaluatePlay(playWhenReady=true))
    }
    @Test fun `the commands of a disconnected controller are abandoned`() {
        val gate=PendingSelectionGate()
        gate.expectQueuedPlay()
        gate.abandonCommands()
        // The queued command will never be executed: the next play is fresh.
        assertFalse(gate.evaluatePlay(playWhenReady=true))
        assertTrue(gate.canStartPlayback)
    }
}
