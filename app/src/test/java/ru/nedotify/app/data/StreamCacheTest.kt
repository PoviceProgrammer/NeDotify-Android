package ru.nedotify.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamCacheTest {
    @Test fun providerLogoutInvalidatesEveryAccountAndQualityWithoutDiscardingOtherProviders()=runTest {
        var calls=0
        val cache=StreamCache(backgroundScope,now={0}) {key->calls++;ResolvedStream("$key/$calls",expiresAt=100)}
        val public=cache.get("soundcloud:1|high")
        val old=cache.get("yandex:1|high|yandex:42:1")
        cache.get("yandex:1|standard|yandex:42:1")
        cache.get("yandex:1|high|yandex:43:3")
        cache.invalidateMatching {it.startsWith("yandex:")}
        assertEquals(public,cache.get("soundcloud:1|high"))
        assertNotEquals(old,cache.get("yandex:1|high|yandex:42:1"))
        assertEquals(5,calls)
    }
    @Test fun revokedProviderFlightCannotRepopulateCacheAfterReturningLate()=runTest {
        val ready=CompletableDeferred<Unit>();var calls=0
        val cache=StreamCache(backgroundScope,now={0}) {
            val call=++calls
            if(call==1)withContext(NonCancellable) {ready.await()}
            ResolvedStream("stream-$call",expiresAt=100)
        }
        val old=async {cache.get("yandex:1|high|old")};runCurrent()
        cache.invalidateMatching {it.startsWith("yandex:")}
        assertEquals("stream-2",cache.get("yandex:1|high|new").url)
        ready.complete(Unit);runCurrent();assertTrue(old.isCancelled)
        assertEquals("stream-2",cache.get("yandex:1|high|new").url)
        assertEquals("stream-3",cache.get("yandex:1|high|old").url)
    }
    @Test fun `late completion of invalidated flight cannot remove or overwrite replacement`()=runTest {
        val oldReady=CompletableDeferred<Unit>();val newReady=CompletableDeferred<Unit>();var calls=0
        val cache=StreamCache(backgroundScope,now={0}) {
            val call=++calls
            if(call==1)withContext(NonCancellable){oldReady.await()} else newReady.await()
            ResolvedStream("stream-$call",expiresAt=100)
        }
        val old=async {cache.get("a")};runCurrent();cache.invalidate("a")
        val fresh=async {cache.get("a")};runCurrent();assertEquals(2,calls)
        oldReady.complete(Unit);runCurrent()
        val waiter=async {cache.get("a")};runCurrent();assertEquals(2,calls)
        newReady.complete(Unit);assertEquals("stream-2",fresh.await().url);assertEquals("stream-2",waiter.await().url)
        assertTrue(old.isCancelled);assertEquals("stream-2",cache.get("a").url)
    }
    @Test fun `offline cancellation permits an immediate new request`()=runTest {
        val ready=CompletableDeferred<Unit>();var calls=0
        val cache=StreamCache(backgroundScope,now={0}) {if(++calls==1)ready.await();ResolvedStream("fresh",expiresAt=100)}
        val a=async {cache.get("a")};runCurrent();cache.cancelAll()
        assertEquals("fresh",cache.get("a").url);assertEquals(2,calls);assertTrue(a.isCancelled)
    }
    @Test fun `concurrent requests share resolution and a cancelled waiter cannot cancel others`()=runTest {
        val ready=CompletableDeferred<Unit>()
        var calls=0
        val cache=StreamCache(backgroundScope,now={0}) {calls++;ready.await();ResolvedStream("https://cdn/audio",expiresAt=100)}
        val a=async {cache.get("a")};val b=async {cache.get("a")}
        runCurrent();assertEquals(1,calls)
        a.cancel();ready.complete(Unit)
        assertEquals("https://cdn/audio",b.await().url)
        assertEquals("https://cdn/audio",cache.get("a").url);assertEquals(1,calls)
    }
    @Test fun `expired and invalidated entries are resolved again`()=runTest {
        var now=0L;var calls=0
        val cache=StreamCache(backgroundScope,now={now}){calls++;ResolvedStream("https://cdn/$calls",expiresAt=now+100)}
        assertEquals("https://cdn/1",cache.get("a").url)
        now=100;assertEquals("https://cdn/2",cache.get("a").url)
        cache.invalidate("a");assertEquals("https://cdn/3",cache.get("a").url)
    }
    @Test fun `failure is not retained as a permanent inflight entry`()=runTest {
        var calls=0
        val supervisor=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val cache=StreamCache(supervisor,now={0}) {if(++calls==1)throw SourceFailure("temporary");ResolvedStream("ok",expiresAt=100)}
        try {cache.get("a");fail("Expected error")}catch(_:SourceFailure){}
        assertEquals("ok",cache.get("a").url);assertEquals(2,calls);supervisor.cancel()
    }
    @Test fun `the last waiter that leaves releases the flight and its slot`()=runTest {
        var calls=0
        var jobCancelled=false
        val cache=StreamCache(backgroundScope,now={0}) {
            calls++
            if(calls==1) {try {awaitCancellation()} catch(cancelled:CancellationException){jobCancelled=true;throw cancelled}}
            ResolvedStream("fresh",expiresAt=100)
        }
        val waiter=async {cache.get("a")}
        runCurrent();assertEquals(1,calls)
        // The waiter is gone: the flight must not keep the slot occupied and
        // the job must not stay behind.
        waiter.cancelAndJoin();runCurrent()
        assertTrue(jobCancelled)
        assertEquals("fresh",cache.get("a").url);assertEquals(2,calls)
    }
    @Test fun `a flight that still has a waiter survives the loss of another one`()=runTest {
        val ready=CompletableDeferred<Unit>();var calls=0
        val cache=StreamCache(backgroundScope,now={0}) {calls++;ready.await();ResolvedStream("shared",expiresAt=100)}
        val first=async {cache.get("a")};val second=async {cache.get("a")}
        runCurrent();assertEquals(1,calls)
        first.cancelAndJoin()
        ready.complete(Unit)
        assertEquals("shared",second.await().url);assertEquals(1,calls)
    }
}
