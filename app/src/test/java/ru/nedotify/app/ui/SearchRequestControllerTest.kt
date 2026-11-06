package ru.nedotify.app.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import ru.nedotify.app.data.Track
import ru.nedotify.app.ui.state.SearchRequestController

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRequestControllerTest {
    private fun track(id:String)=Track(id,"Track $id",source="local")

    @Test fun restoredParametersWaitForTheSearchRouteBeforeFetchingAndResultsAreReloaded()=runTest {
        val saved=SavedStateHandle(mapOf("query" to " ocean ","source" to "local","category" to "artists","search_group" to "artist:ocean"))
        val calls=mutableListOf<Pair<String,String>>()
        val first=SearchRequestController(backgroundScope,saved,{query,source,_->calls+=query to source;listOf(track("first"))})
        runCurrent()
        assertTrue(calls.isEmpty());assertTrue(first.state.value.tracks.isEmpty())
        assertEquals("artists",first.category.value);assertEquals("artist:ocean",first.selectedGroupKey.value)
        first.onActive(true);runCurrent();advanceTimeBy(350);runCurrent()
        assertEquals(listOf("ocean" to "local"),calls)
        assertEquals("first",first.state.value.tracks.single().id)
        first.onActive(false)

        // Simulate recreation using only the SavedStateHandle primitive values.
        val restored=SavedStateHandle(saved.keys().associateWith {saved.get<Any?>(it)})
        val second=SearchRequestController(backgroundScope,restored,{query,source,_->calls+=query to source;listOf(track("reloaded"))})
        runCurrent()
        assertEquals(1,calls.size);assertTrue(second.state.value.tracks.isEmpty())
        second.onActive(true);runCurrent();advanceTimeBy(350);runCurrent()
        assertEquals(2,calls.size);assertEquals("reloaded",second.state.value.tracks.single().id)
        assertEquals(" ocean ",second.query.value);assertEquals("local",second.source.value)
        assertEquals("artists",second.category.value);assertEquals("artist:ocean",second.selectedGroupKey.value)
        assertEquals(setOf("query","source","category","search_group"),restored.keys())
    }

    @Test fun typingCancelsThePreviousDebounceAndOnlyFetchesTheLastTrimmedQuery()=runTest {
        val calls=mutableListOf<String>()
        val requests=SearchRequestController(backgroundScope,SavedStateHandle(),{query,_,_->calls+=query;emptyList()})
        runCurrent();requests.onActive(true);requests.setQuery("first");runCurrent()
        advanceTimeBy(200);runCurrent();assertTrue(calls.isEmpty())
        requests.setQuery("  latest  ");runCurrent()
        advanceTimeBy(349);runCurrent();assertTrue(calls.isEmpty())
        advanceTimeBy(1);runCurrent()
        assertEquals(listOf("latest"),calls)
        assertFalse(requests.state.value.busy)
    }

    @Test fun anOldNonCooperativeResponseCannotReplaceResultsOfTheNewQuery()=runTest {
        val release=CompletableDeferred<Unit>()
        val requests=SearchRequestController(backgroundScope,SavedStateHandle(),{query,_,_->
            if(query=="old")withContext(NonCancellable) {release.await()}
            listOf(track(query))
        },debounceMs=0)
        try {
            runCurrent();requests.submit("old","all");runCurrent()
            requests.submit("selected","local");runCurrent()
            assertEquals("selected",requests.state.value.tracks.single().id)
            release.complete(Unit);runCurrent()
            assertEquals("selected",requests.state.value.tracks.single().id)
            assertNull(requests.state.value.error);assertFalse(requests.state.value.busy)
        } finally {release.complete(Unit)}
    }

    @Test fun aLateCancelledFailureCannotOverwriteFreshResultsOrTheirPartialWarning()=runTest {
        val release=CompletableDeferred<Unit>()
        val requests=SearchRequestController(backgroundScope,SavedStateHandle(),{query,_,partial->
            if(query=="old")withContext(NonCancellable) {release.await();throw IllegalStateException("stale failure")}
            partial("One source is unavailable");listOf(track(query))
        },debounceMs=0)
        try {
            runCurrent();requests.submit("old","all");runCurrent()
            requests.submit("new","all");runCurrent()
            release.complete(Unit);runCurrent()
            assertEquals("new",requests.state.value.tracks.single().id)
            assertEquals("One source is unavailable",requests.state.value.warning)
            assertNull(requests.state.value.error)
        } finally {release.complete(Unit)}
    }

    @Test fun closingTheRouteCancelsItsBusyStateAndLateResponseWithoutStartingAnotherSearch()=runTest {
        val release=CompletableDeferred<Unit>();var calls=0
        val requests=SearchRequestController(backgroundScope,SavedStateHandle(),{_,_,_->
            calls++
            if(calls==1)withContext(NonCancellable) {release.await()}
            listOf(track("response-$calls"))
        },debounceMs=0)
        try {
            runCurrent();requests.submit("query","all");runCurrent();assertTrue(requests.state.value.busy)
            requests.onActive(false);assertFalse(requests.state.value.busy)
            release.complete(Unit);runCurrent()
            assertEquals(1,calls);assertTrue(requests.state.value.tracks.isEmpty())
            requests.onActive(true);runCurrent()
            assertEquals(2,calls);assertEquals("response-2",requests.state.value.tracks.single().id)
        } finally {release.complete(Unit)}
    }

    @Test fun goingOfflineCancelsRemoteWorkAndKeepsOnlyLocalSearchResults()=runTest {
        val offline=MutableStateFlow(false);val release=CompletableDeferred<Unit>();val calls=mutableListOf<String>()
        val requests=SearchRequestController(backgroundScope,SavedStateHandle(),{_,source,_->
            calls+=source
            if(source!="local")withContext(NonCancellable) {release.await()}
            listOf(track(source))
        },offline,debounceMs=0)
        try {
            runCurrent();requests.submit("query","youtube");runCurrent()
            offline.value=true;runCurrent()
            assertEquals("local",requests.source.value)
            assertEquals("local",requests.state.value.tracks.single().id)
            requests.setSource("youtube");runCurrent();assertEquals(listOf("youtube","local"),calls)
            release.complete(Unit);runCurrent()
            assertEquals("local",requests.state.value.tracks.single().id)
        } finally {release.complete(Unit)}
    }

    @Test fun aCurrentFailureClearsOldRowsAndCanBeRetriedWithoutLosingParameters()=runTest {
        var fail=false
        val requests=SearchRequestController(backgroundScope,SavedStateHandle(),{query,_,partial->
            if(fail)throw IllegalStateException("source unavailable")
            partial("Partial result");listOf(track(query))
        },debounceMs=0)
        runCurrent();requests.submit("query","soundcloud");runCurrent()
        assertEquals("Partial result",requests.state.value.warning)
        fail=true;requests.submit("query","soundcloud");runCurrent()
        assertEquals("source unavailable",requests.state.value.error)
        assertNull(requests.state.value.warning);assertTrue(requests.state.value.tracks.isEmpty())
        assertEquals("query",requests.query.value);assertEquals("soundcloud",requests.source.value)
        fail=false;requests.submit(requests.query.value,requests.source.value);runCurrent()
        assertEquals("query",requests.state.value.tracks.single().id);assertNull(requests.state.value.error)
    }

    @Test fun onlySmallValidatedParametersAreSavedWhileInactiveAndChangingContextDropsTheGroup()=runTest {
        val saved=SavedStateHandle();var calls=0
        val requests=SearchRequestController(backgroundScope,saved,{_,_,_->calls++;emptyList()})
        runCurrent();requests.setQuery("q".repeat(1000));requests.setSource("youtube")
        requests.setCategory("artists");requests.selectGroup("g".repeat(1000));runCurrent()
        assertEquals(0,calls);assertEquals(200,requests.query.value.length);assertEquals(500,requests.selectedGroupKey.value.length)
        requests.setSource("not-a-provider");requests.setCategory("not-a-category")
        assertEquals("youtube",requests.source.value);assertEquals("artists",requests.category.value)
        requests.setQuery("different");assertEquals("",requests.selectedGroupKey.value)
        assertTrue(saved.keys().all {saved.get<Any?>(it) is String})
        assertTrue(saved.keys().none {it.contains("result",ignoreCase=true)||it.contains("track",ignoreCase=true)||it.contains("token",ignoreCase=true)})
    }
}
