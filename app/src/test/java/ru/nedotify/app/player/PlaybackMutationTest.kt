package ru.nedotify.app.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackMutationTest {
    @Test fun autoplayCannotAppendOldResultsAfterQueueReplacementDuringDatabasePreparation()=runTest {
        val lookup=CompletableDeferred<List<String>>()
        var queue=listOf("old");var interaction=1
        val requestVersion=interaction;val requestedQueue=queue.toList()
        var applied:Boolean?=null
        val job=launch {
            applied=guardedPlaybackMutation({lookup.await()},
                {interaction==requestVersion && queue==requestedQueue}, {queue=queue+it})
        }
        runCurrent()
        queue=listOf("new-user-selection");interaction++
        lookup.complete(listOf("old-flow-result"));job.join()
        assertEquals(listOf("new-user-selection"),queue);assertEquals(false,applied)
    }

    @Test fun pauseThenResumeStillInvalidatesAnEarlierAutoplayRequestForTheSameIds()=runTest {
        val preparation=CompletableDeferred<String>()
        var interaction=0;var playing=true;var appended=false
        val version=interaction
        val job=launch {guardedPlaybackMutation({preparation.await()},
            {interaction==version && playing},{appended=true})}
        runCurrent()
        playing=false;interaction++
        playing=true;interaction++
        preparation.complete("item");job.join()
        assertFalse(appended)
    }

    @Test fun staleOfflineLookupCannotPauseTheNewlySelectedLocalTrack()=runTest {
        val completion=CompletableDeferred<Boolean>()
        var id="remote";var interaction=0;var playing=true
        val wanted=id;val version=interaction
        val job=launch {guardedPlaybackMutation({completion.await()},
            {id==wanted && interaction==version},{downloaded->if(!downloaded)playing=false})}
        runCurrent()
        id="local";interaction++
        completion.complete(false);job.join()
        assertTrue(playing);assertEquals("local",id)
    }

    @Test fun autoplayDoesNotMutateTheQueueOrCancelRecoveryIfErrorOrBufferingBeginsDuringLookup()=runTest {
        for(interruption in listOf("error","buffering")) {
            val preparation=CompletableDeferred<List<String>>()
            var playing=true;var error=false;val ended=false
            var queue=listOf("current");var recoveryCancelled=false
            val job=launch {guardedPlaybackMutation({preparation.await()},
                {!error && (playing || ended)},
                {queue=queue+it;recoveryCancelled=true})}
            runCurrent()
            if(interruption=="error")error=true else playing=false
            preparation.complete(listOf("flow"));job.join()
            assertEquals(interruption,listOf("current"),queue)
            assertFalse(interruption,recoveryCancelled)
        }
    }

    @Test fun currentRequestAppliesExactlyOnceAndCancelledCallerNeverMutatesPlayer()=runTest {
        var applications=0
        assertTrue(guardedPlaybackMutation({42},{true},{assertEquals(42,it);applications++}))
        assertEquals(1,applications)
        val preparation=CompletableDeferred<Int>()
        val job=launch {guardedPlaybackMutation({preparation.await()},{true},{applications++})}
        runCurrent();job.cancel();preparation.complete(1);job.join()
        assertEquals(1,applications)
        var prepared=false
        assertFalse(guardedPlaybackMutation({prepared=true;0},{false},{applications++}))
        assertFalse(prepared)
    }
}
