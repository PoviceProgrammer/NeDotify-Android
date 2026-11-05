package ru.nedotify.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationLoaderTest {
    @Test fun noNetworkIsEagerlyStartedByConstructionOrModeChanges()=runTest {
        val offline=MutableStateFlow(false);val output=MutableStateFlow(DiscoveryState());var calls=0
        val loader=RecommendationLoader(backgroundScope,offline,{calls++;DiscoveryState(description="loaded")},output)
        runCurrent();offline.value=true;runCurrent();offline.value=false;runCurrent()
        assertEquals(0,calls)
        loader.request();runCurrent();assertEquals(1,calls)
        offline.value=true;runCurrent();assertEquals(2,calls)
        offline.value=true;runCurrent();assertEquals(2,calls)
    }
    @Test fun offlineToggleCancelsActiveFetchAndReloadsWithoutAHomeComposable()=runTest {
        val offline=MutableStateFlow(false);val output=MutableStateFlow(DiscoveryState());var cancelled=false
        val loader=RecommendationLoader(backgroundScope,offline,{
            if(!offline.value)try {awaitCancellation()}finally {cancelled=true}
            DiscoveryState(description="offline")
        },output)
        runCurrent();loader.request();runCurrent();assertTrue(output.value.loading)
        offline.value=true;runCurrent()
        assertTrue(cancelled);assertEquals("offline",output.value.description);assertFalse(output.value.loading)
    }
    @Test fun noncooperativeCancelledFetchCannotPublishOldResultsOrErrors()=runTest {
        val offline=MutableStateFlow(false);val output=MutableStateFlow(DiscoveryState());val release=CompletableDeferred<Unit>()
        var calls=0
        val loader=RecommendationLoader(backgroundScope,offline,{
            if(++calls==1)withContext(NonCancellable) {release.await();throw IllegalStateException("stale failure")}
            else DiscoveryState(description="fresh")
        },output)
        runCurrent();loader.request();runCurrent();offline.value=true;runCurrent()
        assertEquals("fresh",output.value.description)
        release.complete(Unit);runCurrent()
        assertEquals("fresh",output.value.description);assertNull(output.value.error)
    }
}
