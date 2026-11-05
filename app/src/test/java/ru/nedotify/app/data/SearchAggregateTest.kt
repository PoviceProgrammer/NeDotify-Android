package ru.nedotify.app.data

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchAggregateTest {
    private fun track(id:String,artist:String="Артист",album:String="Альбом")=Track(id,id,artist,album,source="local",addedAt=1)

    @Test fun sourcesStartInParallelAndPartialSuccessSurvivesAnErrorAndTimeout()=runTest {
        val started=mutableSetOf<Int>()
        val result=aggregateSearch(listOf(
            {started+=1;delay(50);listOf(track("a"))},
            {started+=2;delay(25);throw SourceFailure("Источник недоступен")},
            {started+=3;delay(1_000);listOf(track("slow"))}
        ),providerTimeoutMs=100)
        assertEquals(setOf(1,2,3),started);assertEquals(listOf("a"),result.map {it.id})
        assertEquals(100L,testScheduler.currentTime)
    }

    @Test fun successfulEmptySearchIsNotReportedAsAnErrorAndAllFailuresAreActionable()=runTest {
        assertTrue(aggregateSearch(listOf({emptyList()},{emptyList()})).isEmpty())
        assertTrue(aggregateSearch(emptyList()).isEmpty())
        try {aggregateSearch(listOf({throw SourceFailure("secret URL")},{delay(999);emptyList()}),providerTimeoutMs=10);fail("All providers failed")}
        catch(error:SourceFailure) {assertTrue(error.message!!.contains("на устройстве"));assertFalse(error.message!!.contains("secret URL"))}
    }
    @Test fun emptyLibraryDoesNotHideUnavailableOnlineSearch()=runTest {
        try {aggregateSearch(listOf({emptyList()},{throw SourceFailure("blocked SC")},{throw SourceFailure("blocked YT")}));fail("Incomplete search must be explicit")}
        catch(error:SourceFailure) {assertTrue(error.message!!.contains("не во всех источниках"))}
    }

    @Test fun parentCancellationEscapesInsteadOfBeingConvertedToAProviderFailure()=runTest {
        try {withTimeout(50){aggregateSearch(listOf({delay(1_000);listOf(track("late"))}),providerTimeoutMs=2_000)};fail("Caller cancelled")}
        catch(_:TimeoutCancellationException) { /* Real parent cancellation. */ }
        assertEquals(50L,testScheduler.currentTime)
    }

    @Test fun deduplicationKeepsProviderOrderAndBoundsTheCombinedList()=runTest {
        val result=aggregateSearch(listOf({listOf(track("a"),track("b"))},{listOf(track("b"),track("c"))}),maxResults=2)
        assertEquals(listOf("a","b"),result.map {it.id})
    }

    @Test fun groupsUseOnlyActualReturnedMetadataAndKeepSameNamedAlbumsByDifferentArtistsSeparate() {
        val a=track("a","Мой Артист","Океан")
        val b=track("b","мой артист","океан")
        val c=track("c","Другой Артист","Океан")
        val unknown=track("unknown","Неизвестный исполнитель","")
        val noAlbum=track("none","Мой Артист","Неизвестный альбом")
        val artists=SearchResultGroups.artists(listOf(a,b,c,unknown,noAlbum,a))
        assertEquals(2,artists.size)
        assertEquals(listOf("a","b","none"),artists.single {it.key=="мой артист"}.tracks.map {it.id})
        val albums=SearchResultGroups.albums(listOf(a,b,c,unknown,noAlbum))
        assertEquals(2,albums.size)
        assertNotEquals(albums[0].key,albums[1].key)
        assertEquals(setOf("a","b","c"),albums.flatMap {it.tracks}.map {it.id}.toSet())
    }
}
