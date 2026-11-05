package ru.nedotify.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SourceDeadlineTest {
    @Test fun ownDeadlineIsActionableButParentDeadlineRemainsCancellation()=runTest {
        try {sourceDeadline(50,"Источник не ответил") {delay(1_000)};fail("Own deadline")}
        catch(error:SourceFailure) {assertEquals("Источник не ответил",error.message)}
        try {withTimeout(10) {sourceDeadline(500,"Do not replace parent cancellation") {delay(1_000)}};fail("Parent deadline")}
        catch(_:TimeoutCancellationException) {}
    }
    @Test fun explicitUserCancellationDoesNotBecomeRetryableNetworkError()=runTest {
        val started=CompletableDeferred<Unit>();var failed=false
        val job=launch {
            try {sourceDeadline(500,"network") {started.complete(Unit);awaitCancellation()}}
            catch(_:SourceFailure) {failed=true}
        }
        started.await();job.cancelAndJoin()
        assertTrue(job.isCancelled);assertFalse(failed)
    }
    @Test fun successfulValueAndProviderFailureKeepTheirIdentity()=runTest {
        assertEquals("stream",sourceDeadline(50,"timeout") {"stream"})
        val failure=SourceFailure("Denied",403)
        try {sourceDeadline(50,"timeout") {throw failure};fail("Provider failure")}
        catch(error:SourceFailure) {assertSame(failure,error)}
    }
}
