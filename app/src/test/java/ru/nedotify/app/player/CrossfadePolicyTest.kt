package ru.nedotify.app.player

import org.junit.Assert.*
import org.junit.Test

class CrossfadePolicyTest {
    @Test fun equalPowerGainsPreservePowerAndEndpoints() {
        assertEquals(CrossfadeGains(1f,0f),CrossfadePolicy.gains(0f))
        for(step in 0..100) {
            val gain=CrossfadePolicy.gains(step/100f)
            assertEquals(1f,gain.outgoing*gain.outgoing+gain.incoming*gain.incoming,.00001f)
        }
        assertEquals(1f,CrossfadePolicy.gains(1f).incoming,.00001f)
        assertTrue(CrossfadePolicy.gains(1f).outgoing<.00001f)
    }
    @Test fun disabledShortAndUnknownTracksDoNotFade() {
        assertEquals(0L,CrossfadePolicy.durationMs(0,10_000,60_000))
        assertEquals(0L,CrossfadePolicy.durationMs(5,500,60_000))
        assertEquals(0L,CrossfadePolicy.durationMs(5,5_000,-1))
        assertEquals(0L,CrossfadePolicy.durationMs(5,5_000,900))
    }
    @Test fun finishesBeforeOutgoingNaturalTransitionAndBeforeHalfIncoming() {
        assertEquals(4_850L,CrossfadePolicy.durationMs(12,5_000,60_000))
        assertEquals(1_000L,CrossfadePolicy.durationMs(12,5_000,2_000))
        assertEquals(12_000L,CrossfadePolicy.durationMs(99,60_000,60_000))
    }
    @Test fun preparationOnlyOccursInBoundedWindowNearEnd() {
        assertFalse(CrossfadePolicy.shouldPrepare(0,1_000))
        assertFalse(CrossfadePolicy.shouldPrepare(6,30_000))
        assertTrue(CrossfadePolicy.shouldPrepare(6,13_000))
        assertFalse(CrossfadePolicy.shouldPrepare(6,599))
    }
    @Test fun nonFiniteProgressCannotPropagateIntoVolume() {
        assertEquals(CrossfadeGains(1f,0f),CrossfadePolicy.gains(Float.NaN))
        assertEquals(CrossfadeGains(1f,0f),CrossfadePolicy.gains(Float.POSITIVE_INFINITY))
    }
    @Test fun stalledIncomingAtOutgoingBoundaryRecoversOnlyOnce() {
        val recovery=CrossfadeRecovery();recovery.boundaryPause()
        assertTrue(recovery.cancel(canPlay=true))
        assertFalse(recovery.boundaryPaused);assertFalse(recovery.cancel(canPlay=true))
    }
    @Test fun userPauseOrFocusLossPreventsBoundaryFallbackFromResuming() {
        val recovery=CrossfadeRecovery();val focus=PlaybackFocusState()
        focus.userRequest(true);focus.requestResult(true);recovery.boundaryPause()
        focus.userRequest(false);assertFalse(recovery.cancel(focus.canPlay))
        focus.userRequest(true);focus.requestResult(true);recovery.boundaryPause()
        focus.transientLoss();assertFalse(recovery.cancel(focus.canPlay))
        focus.gain();assertFalse(recovery.cancel(focus.canPlay))
    }
    @Test fun normalCancellationAndCompletedHandoverNeverResumeOutgoingDeck() {
        val recovery=CrossfadeRecovery()
        assertFalse(recovery.cancel(canPlay=true))
        recovery.boundaryPause();recovery.reset()
        assertFalse(recovery.cancel(canPlay=true))
    }
    @Test fun theHandoverHappensOnlyWhenTheFadeHasCompleted() {
        // An outgoing deck that ended early must not shorten the fade: the incoming deck
        // would jump in gain.
        assertFalse(CrossfadePolicy.shouldHandOver(0f))
        assertFalse(CrossfadePolicy.shouldHandOver(.99f))
        assertTrue(CrossfadePolicy.shouldHandOver(1f))
        assertTrue(CrossfadePolicy.shouldHandOver(1.4f))
    }
    @Test fun theHandoverKeepsTheEffectiveGainOfBothDecks() {
        // The gains move with the slots, so neither deck's effective gain changes.
        val finished=CrossfadePolicy.gains(1f)
        val moved=CrossfadePolicy.handoverGains(finished.outgoing,finished.incoming)
        assertEquals(finished.incoming,moved.outgoing,.00001f)
        assertEquals(finished.outgoing,moved.incoming,.00001f)
        val half=CrossfadePolicy.gains(.5f)
        val movedHalf=CrossfadePolicy.handoverGains(half.outgoing,half.incoming)
        assertEquals(half.incoming,movedHalf.outgoing,.00001f)
        assertEquals(half.outgoing,movedHalf.incoming,.00001f)
    }
}
