package ru.nedotify.app.player

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.nedotify.app.data.QueueSnapshot

/** Actual delegate → stable bridge → registered checkpoint listener. No app data is read/written. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class QueueCheckpointListenerWiringTest {
    @Test fun realSeekCallbackCrossesTheStableBridgeAndForcesSubmission() = withDeck { fixture ->
        onMain { fixture.deck.seekTo(0, 2_500L) }
        idle()
        onMain { assertTrue("Seek must submit the sub-interval position", fixture.forced.contains(2_500L)) }
    }

    @Test fun realUserPauseCallbackCrossesTheStableBridgeAndForcesSubmission() = withDeck { fixture ->
        onMain {
            // An idle delegate can carry play intent without prepare/network/audio.
            // This isolates callback forwarding from device audio-focus policy.
            fixture.deck.active.seekTo(0, 1_200L)
            fixture.deck.active.playWhenReady = true
        }
        idle()
        onMain { fixture.forced.clear(); fixture.deck.active.pause() }
        idle()
        onMain { assertTrue("Pause must submit the sub-interval position", fixture.forced.contains(1_200L)) }
    }

    private class Fixture(val scope: CoroutineScope, val deck: DualDeckPlayer) {
        val forced = mutableListOf<Long>()
    }

    private fun withDeck(block: (Fixture) -> Unit) {
        var fixture: Fixture? = null
        onMain {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val deck = DualDeckPlayer(context, ExoPlayer.Builder(context).build(), ExoPlayer.Builder(context).build(), scope)
            fixture = Fixture(scope, deck)
            val checkpoint = QueueCheckpoint(30_000L)
            deck.addListener(QueueCheckpointListener { force ->
                val active = deck.active
                val snapshot = QueueSnapshot(listOf("a"), 0, "a", 0, false, listOf(0), active.currentPosition)
                if (checkpoint.shouldWrite(snapshot, force) && force) fixture!!.forced += snapshot.positionMs
            })
            // Never prepare: the synthetic URI is not opened and no library is touched.
            deck.setMediaItems(listOf(MediaItem.Builder().setMediaId("a").setUri("https://example.invalid/a.mp3").build()), 0, 0L)
        }
        try { idle(); block(requireNotNull(fixture)) }
        finally { onMain { fixture?.let { it.deck.release(); it.scope.cancel() } } }
    }

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
