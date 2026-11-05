package ru.nedotify.app.player

import org.junit.Assert.*
import org.junit.Test

/**
 * A main thread that is not the Android one.
 *
 * The controller never talks to `android.os.Looper` directly any more: it asks the
 * injected seam. The fake records what was posted so a test can run it the way the
 * main looper would.
 */
private class FakeMainThread(private val current:Boolean=true):MainThread {
    private val queued=mutableListOf<() -> Unit>()
    var posts=0
        private set
    override fun post(block:()->Unit) {posts++;queued+=block}
    override fun isCurrent():Boolean = current
    /** Runs everything that was posted, as the main looper would. */
    fun runPosted() {val pending=queued.toList();queued.clear();pending.forEach {it()}}
}

class AudioRuntimeTest {
    @Test fun `closing the effects clears the crossfade hint`() {
        val effects=AudioEffectsController(main=FakeMainThread())
        effects.crossfade(true)
        assertTrue(effects.crossfadeActive)
        assertTrue(AudioRuntime.status.value.crossfadeActive)
        effects.close()
        assertFalse(effects.crossfadeActive)
        assertFalse(AudioRuntime.status.value.crossfadeActive)
    }
    @Test fun `the equaliser control state is cached, not read on every tick`() {
        // `hasControl()` is a binder call into audioflinger and the volume is
        // applied on every crossfade tick: the state must be cached.
        var reads=0
        val state=CachedControlState {reads++;true}
        state.refresh()
        assertTrue(state.value);assertEquals(1,reads)
        repeat(100){state.value}
        assertEquals(1,reads) // the per-tick read never reaches the platform
        state.refresh()
        assertEquals(2,reads);assertTrue(state.value)
    }
    @Test fun `a failing platform read leaves the effect uncontrolled`() {
        val state=CachedControlState {throw IllegalStateException("no session")}
        state.refresh()
        assertFalse(state.value)
    }
    @Test fun `without an audio session there is no platform equaliser`() {
        val effects=AudioEffectsController(main=FakeMainThread())
        try { assertFalse(effects.eqControlled) } finally { effects.close() }
    }
    /**
     * The production default is the real Android main looper.
     *
     * A JVM unit test has no platform: `android.os.Looper` is a stub that throws
     * "not mocked". The default seam therefore cannot answer, and the call fails in
     * the platform rather than in the guard — which pins both the default and the
     * fact that the guard is still armed on the production path.
     */
    @Test fun `the default seam really asks the Android main looper`() {
        val effects=AudioEffectsController()
        val error=assertThrows(RuntimeException::class.java) {effects.crossfade(true)}
        val message=error.message?:""
        assertTrue("expected the platform looper to be consulted, got: $message",
            message.contains("android.os.Looper")&&message.contains("not mocked"))
    }
    /** The main-thread guard still rejects a call that arrives from another thread. */
    @Test fun `the main-thread guard rejects a call that is not on the main thread`() {
        val effects=AudioEffectsController(main=FakeMainThread(current=false))
        assertThrows(IllegalStateException::class.java) {effects.crossfade(true)}
        assertFalse(effects.crossfadeActive)
        // The published snapshot carries the same guard.
        assertThrows(IllegalStateException::class.java) {
            AudioRuntime.publish(AudioRuntimeStatus(),FakeMainThread(current=false))
        }
    }
    /**
     * The control-status callback of a platform effect arrives on a binder thread:
     * the work is posted through the seam instead of running on the caller's thread.
     */
    @Test fun `the control-status callback is posted through the seam`() {
        val main=FakeMainThread()
        val effects=AudioEffectsController(main=main)
        var applied=0
        effects.onControlStatus {applied++}
        assertEquals(0,applied) // the work is queued for the main thread
        assertEquals(1,main.posts)
        main.runPosted()
        assertEquals(1,applied)
    }
}
