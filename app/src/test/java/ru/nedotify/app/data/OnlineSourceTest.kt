package ru.nedotify.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineSourceTest {
    @Test fun offlineEnabledDuringAsyncSetupPreventsTheLateFlightFromOpeningHttp()=runTest {
        val setup=CompletableDeferred<Unit>();var offline=false;var requests=0
        val job=async {
            setup.await() // e.g. audio quality / secure account scope before the flight is registered
            runCatching {onlineSource({offline}) {requests++;"url"}}
        }
        runCurrent();offline=true;setup.complete(Unit);runCurrent()
        assertTrue(job.await().exceptionOrNull() is SourceFailure);assertEquals(0,requests)
    }
    @Test fun responseThatArrivesAfterOfflineCannotBePublishedOrCached()=runTest {
        val response=CompletableDeferred<String>();var offline=false;var cached:String?=null
        val job=async {runCatching {cached=onlineSource({offline}) {response.await()}}}
        runCurrent();offline=true;response.complete("signed-url");runCurrent()
        assertTrue(job.await().exceptionOrNull() is SourceFailure);assertNull(cached)
    }
    @Test fun cancellationRemainsCancellationInsteadOfAnOfflineError()=runTest {
        val response=CompletableDeferred<Unit>();var checks=0
        val job=launch {
            onlineSource({checks++;false}) {withContext(NonCancellable) {response.await()};"late"}
            fail("Cancelled response escaped")
        }
        runCurrent();job.cancel();response.complete(Unit);runCurrent()
        assertTrue(job.isCancelled);assertEquals(1,checks)
    }
}
