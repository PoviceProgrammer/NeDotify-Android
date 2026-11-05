package ru.nedotify.app.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackFocusStateTest {
    @Test fun lateGainAfterUserPauseCannotResumeOrClaimFocus() {
        val state=PlaybackFocusState()
        state.userRequest(true);state.requestResult(true);assertTrue(state.canPlay)
        state.transientLoss();assertTrue(state.requested);assertFalse(state.canPlay)
        state.userRequest(false);state.abandon();state.gain()
        assertFalse(state.canPlay);assertFalse(state.requested);assertFalse(state.hasFocus)
    }
    @Test fun transientLossResumesOnlyExistingPlayIntent() {
        val state=PlaybackFocusState()
        state.userRequest(true);state.requestResult(true);state.transientLoss()
        assertTrue(state.suspended);assertFalse(state.canPlay)
        state.gain();assertTrue(state.canPlay);assertFalse(state.suspended)
    }
    @Test fun deniedFocusDoesNotStartDecoderAndRequiresNewRequest() {
        val state=PlaybackFocusState()
        state.userRequest(true);state.requestResult(false)
        assertFalse(state.canPlay);assertFalse(state.requested);assertFalse(state.hasFocus)
        state.gain();assertFalse(state.canPlay)
        state.userRequest(true);state.requestResult(true);assertTrue(state.canPlay)
    }
    @Test fun permanentLossAndNoisyHaveNoAutomaticResume() {
        for(reason in listOf(2,3)) {
            val state=PlaybackFocusState();state.userRequest(true);state.requestResult(true)
            state.stop(reason);state.abandon();state.gain()
            assertFalse(state.canPlay);assertEquals(reason,state.reason)
        }
    }
}
