package ru.nedotify.app.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPreparationTest {
    @Test fun clearingOrPausingWhileDatabaseFinishesCannotResurrectPlayback()=runTest {
        val ready=CompletableDeferred<Unit>();val started=mutableListOf<String>();val errors=mutableListOf<Exception>()
        val requests=PlaybackPreparation<String>(this)
        requests.submit({withContext(NonCancellable) {ready.await()};"old"},started::add,errors::add)
        runCurrent();requests.cancel();ready.complete(Unit);runCurrent()
        assertTrue(started.isEmpty());assertTrue(errors.isEmpty())
    }
    @Test fun selectingAnotherTrackWinsOverLatePreparationOfThePreviousTrack()=runTest {
        val ready=CompletableDeferred<Unit>();val played=mutableListOf<String>();val errors=mutableListOf<Exception>()
        val requests=PlaybackPreparation<String>(this)
        requests.submit({withContext(NonCancellable) {ready.await()};"previous"},played::add,errors::add);runCurrent()
        requests.submit({"selected"},played::add,errors::add);runCurrent()
        ready.complete(Unit);runCurrent()
        assertEquals(listOf("selected"),played);assertTrue(errors.isEmpty())
    }
    @Test fun cancelledOldFailureCannotReplaceTheNewSelectionWithAnError()=runTest {
        val ready=CompletableDeferred<Unit>();val errors=mutableListOf<Exception>();var played=""
        val requests=PlaybackPreparation<String>(this)
        requests.submit({withContext(NonCancellable) {ready.await();throw IllegalStateException("stale")}}, {played=it},errors::add);runCurrent()
        requests.submit({"new"},{played=it},errors::add);runCurrent();ready.complete(Unit);runCurrent()
        assertEquals("new",played);assertTrue(errors.isEmpty())
    }
    @Test fun currentFailureIsReportedAndNextSelectionCanPlay()=runTest {
        val errors=mutableListOf<Exception>();var played="";val requests=PlaybackPreparation<String>(this)
        requests.submit({throw IllegalStateException("missing")},{played=it},errors::add);runCurrent()
        assertEquals("missing",errors.single().message)
        requests.submit({"available"},{played=it},errors::add);runCurrent()
        assertEquals("available",played);assertEquals(1,errors.size)
    }
    @Test fun cancellingCatalogMatchClearsBusyImmediatelyAndLateMatchNeverWritesQueue()=runTest {
        val matched=CompletableDeferred<Unit>();var busy=true;var writes=0;var plays=0;var completions=0
        val requests=PlaybackPreparation<String>(this)
        requests.submit(load={
            withContext(NonCancellable) {matched.await()}
            currentCoroutineContext().ensureActive()
            writes++;"matched"
        },onReady={plays++},onFailure={fail("Cancelled catalog lookup reported an error")},
            onComplete={busy=false;completions++})
        runCurrent();requests.cancel()
        assertFalse(busy);assertEquals(1,completions)
        matched.complete(Unit);runCurrent()
        assertEquals(0,writes);assertEquals(0,plays);assertEquals(1,completions)
    }
    @Test fun lateCatalogCompletionCannotClearNewRequestsBusyIndicator()=runTest {
        val old=CompletableDeferred<Unit>();val selected=CompletableDeferred<Unit>();var busy="";val played=mutableListOf<String>()
        val requests=PlaybackPreparation<String>(this)
        requests.submit({withContext(NonCancellable) {old.await()};"old"},played::add,{fail()},onComplete={busy=""})
        runCurrent()
        requests.submit({selected.await();"new"},played::add,{fail()},onComplete={busy=""})
        busy="new";runCurrent();old.complete(Unit);runCurrent()
        assertEquals("new",busy);assertTrue(played.isEmpty())
        selected.complete(Unit);runCurrent()
        assertEquals("",busy);assertEquals(listOf("new"),played)
    }
    @Test fun successAndFailureEachCompleteOnceAndLaterCancelDoesNotRepeatCleanup()=runTest {
        var completions=0;val requests=PlaybackPreparation<String>(this)
        requests.submit({"ready"},{},{fail()},onComplete={completions++});runCurrent();requests.cancel()
        assertEquals(1,completions)
        requests.submit({throw IllegalStateException("missing")},{fail()},{},onComplete={completions++});runCurrent();requests.cancel()
        assertEquals(2,completions)
    }
    /**
     * The preparation writes the track into the library. A delete that overtakes
     * that write would be undone by it, so the delete waits for the cancelled
     * preparation to finish.
     */
    @Test fun theCancelledPreparationReleasesTheLibraryBeforeTheDeleteRuns()=runTest {
        val order=mutableListOf<String>()
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val requests=PlaybackPreparation<String>(this)
        requests.submit(load={
            started.complete(Unit)
            withContext(NonCancellable) {release.await()}
            order+="write";"ready"
        },onReady={fail("A cancelled preparation must not start the playback")},onFailure={fail()})
        runCurrent();started.await()
        val deleting=launch {requests.cancelAndAwait();order+="delete"}
        runCurrent()
        // The write is still in flight: the delete must not overtake it.
        assertEquals(emptyList<String>(),order)
        release.complete(Unit);deleting.join();runCurrent()
        assertEquals(listOf("write","delete"),order)
    }
}
