package ru.nedotify.app.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.nedotify.app.data.QueueSnapshot

/** Drives the same Media3 listener registered by PlaybackService, with real checkpoint policy. */
class QueueCheckpointListenerTest {
    private class Fixture {
        var position = 0L
        val writes = mutableListOf<Long>()
        private val policy = QueueCheckpoint(30_000L)
        val listener = QueueCheckpointListener { force -> save(force) }
        init { save(false) }
        private fun save(force: Boolean) {
            val snapshot = QueueSnapshot(listOf("a"), 0, "a", 0, false, listOf(0), position)
            if (policy.shouldWrite(snapshot, force)) writes += snapshot.positionMs
        }
    }

    @Test fun userPausePersistsItsSubIntervalPositionBeforeServiceDestruction() {
        val fixture = Fixture()
        fixture.position = 1_200L
        fixture.listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals(listOf(0L, 1_200L), fixture.writes)
    }

    @Test fun userSeekPersistsItsSubIntervalPositionWithoutWaitingForPlaybackTick() {
        val fixture = Fixture()
        fixture.position = 2_500L
        fixture.listener.onPositionDiscontinuity(position(0L), position(2_500L), Player.DISCONTINUITY_REASON_SEEK)
        assertEquals(listOf(0L, 2_500L), fixture.writes)
    }

    @Test fun focusInterruptionAndPlayRequestDoNotForcePeriodicPositionWrites() {
        val fixture = Fixture()
        fixture.position = 1_200L
        fixture.listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        fixture.listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals(listOf(0L), fixture.writes)
    }

    @Suppress("DEPRECATION")
    private fun position(value: Long) = Player.PositionInfo(null, 0, null, 0, value, value, -1, -1)
}
