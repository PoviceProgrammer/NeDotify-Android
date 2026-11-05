package ru.nedotify.app.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class A6RegressionTest {
    private val song=Track("local:a","Океан","Мой Артист",durationMs=180_000,source="local")
    private fun lyric(track:Track=song,title:String=song.title,artist:String=song.artist)=
        LyricsIdentity.accept(track,title,artist,180_000,"[00:01]Слова","Слова")

    @Test fun embeddedArtistCreditsAreStrippedOnlyWhenTheySupplyOrAgreeWithIdentity() {
        assertNotNull(lyric(song.copy(title="Мой Артист - Океан (Official Audio)")))
        assertNotNull(lyric(song.copy(title="Мой Артист — Океан",artist="Неизвестный исполнитель")))
        assertNull(lyric(song.copy(title="Другой Артист - Океан")))
        assertNull(lyric(song.copy(title="Океан - Live")))
    }
    @Test fun anySharedCreditedArtistMatchesButUnrelatedArtistsDoNot() {
        assertNotNull(lyric(song.copy(artist="Мой Артист feat. Гость")))
        assertNotNull(lyric(artist="Мой Артист, Гость"))
        assertNull(lyric(artist="Гость"))
    }
    @Test fun anEmbeddedPrefixIsStrippedOnlyWhenItNamesTheArtist() {
        // The prefix is the artist: it is stripped and the bare song matches.
        assertNotNull(lyric(song.copy(title="Мой Артист - Океан"),title="Океан",artist="Мой Артист"))
        assertNotNull(lyric(song.copy(title="AC/DC - Back in Black",artist="AC/DC"),title="Back in Black",artist="AC/DC"))
        // The prefix is not the artist: the title keeps it and no bare song matches.
        assertNull(lyric(song.copy(title="AC/DC - Back in Black"),title="Океан",artist="Мой Артист"))
        assertNull(lyric(song.copy(title="Океан - Part 2"),title="Океан",artist="Мой Артист"))
    }
    @Test fun limitedBaseVersionFallbackIsAlwaysPlainAndCannotInventKaraokeOrInstrumental() {
        for(version in listOf("Remix","Slowed","Sped Up")) {
            val wanted=song.copy(title="Океан ($version)")
            assertEquals("Слова",lyric(wanted)!!.plain)
            assertTrue(lyric(wanted)!!.lines.isEmpty())
            assertNull(LyricsIdentity.accept(wanted,"Океан",song.artist,180_000,"","",true))
        }
        for(version in listOf("Live","Remastered","Edit","Acoustic","Instrumental","Nightcore"))
            assertNull("Wrong base fallback for $version",lyric(song.copy(title="Океан ($version)")))
        assertNull(lyric(song.copy(title="Океан (Tiesto Remix)"),title="Океан (Other Remix)"))
    }
    @Test fun oversizedPrimaryLyricsStillConsultFallbackAndKeepValidPlain()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody(JSONObject().put("plainLyrics","Сохранённый текст")
                .put("syncedLyrics","x".repeat(256_001)).toString()))
            val client=LyricsClient(MusicNetwork(),endpoint=server.url("/get").toString(),fallback={_,initial->
                assertEquals("Сохранённый текст",initial.plain);initial
            })
            assertEquals("Сохранённый текст",client.lyrics(song).plain)
        }
    }
    @Test fun primaryMalformedSchemaStillConsultsFallback()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("malformed"))
            val client=LyricsClient(MusicNetwork(),endpoint=server.url("/get").toString(),fallback={_,_->LyricsResult("Из другого сервиса",source="NetEase")})
            assertEquals("NetEase",client.lyrics(song).source)
        }
    }
    @Test fun forcedMissNeverReplacesGoodOfflineLyrics()=runBlocking {
        MockWebServer().use {server->
            val client=LyricsClient(MusicNetwork(),endpoint=server.url("/get").toString())
            server.enqueue(MockResponse().setBody("{\"plainLyrics\":\"Хороший текст\"}"))
            val hit=client.lyrics(song)
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(hit,client.lyrics(song,forceRefresh=true))
            assertEquals(hit,client.cached(song))
        }
    }
    @Test fun forcedFailureKeepsCacheAndStillReportsUnavailability()=runBlocking {
        MockWebServer().use {server->
            val client=LyricsClient(MusicNetwork(),endpoint=server.url("/get").toString())
            server.enqueue(MockResponse().setBody("{\"plainLyrics\":\"Хороший текст\"}"))
            val hit=client.lyrics(song)
            server.enqueue(MockResponse().setResponseCode(503))
            try {client.lyrics(song,true);fail("Failure must be reported")}catch(error:SourceFailure){assertEquals(503,error.httpCode)}
            assertEquals(hit,client.cached(song))
        }
    }
    @Test fun providerLookupsStartTogetherUnderOneBudgetAndFailuresAreNotNoMatch()=runBlocking {
        MockWebServer().use {server->
            val barrier=CountDownLatch(4);val concurrent=AtomicBoolean(true)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                barrier.countDown()
                if(!barrier.await(2,TimeUnit.SECONDS))concurrent.set(false)
                return MockResponse().setResponseCode(503)
            }}
            val base=server.url("/").toString().trimEnd('/')
            val endpoints=LyricsEndpoints("$base/lrclib","$base/netease","$base/nlyrics","$base/qq","$base/qlyrics",base)
            try {LyricsCascade(MusicNetwork(),endpoints).lookup(song,LyricsResult());fail("Unavailable sources must not be a miss")}
            catch(error:SourceFailure){assertTrue(error.message!!.contains("подключение"))}
            assertTrue("All four providers must start before any response",concurrent.get())
            assertEquals(4,server.requestCount)
        }
    }
    @Test fun theWholeLyricsCascadeStaysInsideOneBudgetWhenAProviderHangs()=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=
                if(request.requestUrl!!.encodedPath=="/lrclib")MockResponse().setBody("[]")
                else MockResponse().setBody("{}").setHeadersDelay(1,TimeUnit.SECONDS)}
            val base=server.url("/").toString().trimEnd('/')
            val endpoints=LyricsEndpoints("$base/lrclib","$base/netease","$base/nlyrics","$base/qq","$base/qlyrics",base)
            val started=System.nanoTime()
            try {LyricsCascade(MusicNetwork(),endpoints,300).lookup(song,LyricsResult());fail("A hanging provider must be reported")}
            catch(error:SourceFailure){}
            val elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
            assertTrue("The cascade must stay inside its budget, took $elapsed ms",elapsed<3_000)
        }
    }
    @Test fun aGeniusRedirectCannotEscapeValidatedPageOrigin()=runBlocking {
        MockWebServer().use {trusted->MockWebServer().use {untrusted->
            val songHit=JSONObject().put("title",song.title).put("primary_artist",JSONObject().put("name",song.artist))
                .put("url",trusted.url("/song-lyrics").toString())
            trusted.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath) {
                "/api/search/multi"->MockResponse().setBody(JSONObject().put("response",JSONObject().put("sections",JSONArray().put(
                    JSONObject().put("hits",JSONArray().put(JSONObject().put("result",songHit)))))).toString())
                "/song-lyrics"->MockResponse().setResponseCode(302).setHeader("Location",untrusted.url("/exfiltrate"))
                "/lrclib"->MockResponse().setBody("[]")
                else->MockResponse().setBody("{}")
            }}
            val base=trusted.url("/").toString().trimEnd('/')
            try {LyricsCascade(MusicNetwork(),LyricsEndpoints("$base/lrclib","$base/netease","$base/nlyrics","$base/qq","$base/qlyrics",base))
                .lookup(song,LyricsResult());fail("Redirect must be reported unavailable")}
            catch(_:SourceFailure){}
            assertEquals(0,untrusted.requestCount)
        }}
    }
    @Test fun localResultsSurviveOnlineFailuresWithVisiblePartialWarningAndNoNetworkDeadline()=runTest {
        var warning:String?=null
        val result=aggregateSearch(listOf({delay(500);listOf(song)},{throw SourceFailure("offline")}),
            providerTimeoutMs=10,timeoutExemptIndices=setOf(0),onPartialFailure={warning=it})
        assertEquals(listOf(song),result);assertNotNull(warning);assertEquals(500L,testScheduler.currentTime)
    }
    @Test fun matcherRetriesPlayableCandidatesAndKeepsCatalogMetadata()=runTest {
        val metadata=CatalogTrack("spotify","s","Океан",listOf("Мой Артист"),"Каталог",180_000,"https://image.test/catalog")
        val first=Track("youtube:abcdefghijk","Мой Артист - Океан","Мой Артист",durationMs=180_000,source="youtube",sourceId="abcdefghijk")
        val second=first.copy(id="youtube:123456789ab",sourceId="123456789ab",durationMs=181_000)
        val checked=mutableListOf<String>()
        val result=CatalogMatcher.resolve(metadata,listOf({listOf(first,second)})) {track->
            checked+=track.id;if(track.id==first.id)throw SourceFailure("Unavailable recording")
        }
        assertEquals(listOf(first.id,second.id),checked)
        assertEquals(second.id,result.id);assertEquals(metadata.title,result.title)
        assertEquals("Каталог",result.album);assertEquals(metadata.coverUrl,result.coverUrl)
    }
    @Test fun classifiedRateLimitStopsCatalogMatchingWithoutBeingFlattened()=runTest {
        var next=false
        try {CatalogMatcher.resolve(CatalogTrack("spotify","s","Океан",listOf("Мой Артист")),listOf(
            {throw SourceFailure("Сервис ограничил запросы",429)},{next=true;emptyList()}));fail("Rate limit must escape")}
        catch(error:SourceFailure){assertEquals(429,error.httpCode);assertTrue(error.message!!.contains("ограничил"))}
        assertFalse(next)
    }
    @Test fun explicitJsonNullHasIdenticalTextSemanticsAndCannotBecomeCatalogTitle() {
        val root=JSONObject("{\"title\":null,\"name\":null,\"id\":123,\"literal\":\"null\"}")
        assertEquals("",root.optText("title"));assertEquals("123",root.optText("id"));assertEquals("null",root.optText("literal"))
        root.put("artists",JSONArray().put(JSONObject().put("name","Artist")))
        assertNull(CatalogParsers.yandexTrack(root))
        root.put("title","Песня").put("version",JSONObject.NULL)
        assertEquals("Песня",CatalogParsers.yandexTrack(root)!!.title)
    }
    @Test fun theJsonReaderKeepsAMissingKeyAndAJsonNullApartAndNeverProducesTheStringNull() {
        val root=JSONObject("{\"present\":null,\"literal\":\"null\",\"number\":7}")
        // A JSON null is not the text "null": the per-field default is kept.
        assertEquals("",root.optText("present"))
        assertEquals("default",root.optText("present","default"))
        // A missing key keeps the per-field default as well and never throws:
        // the platform readers differ (the org.json jar throws, AOSP returns null),
        // the one reader of this app does not.
        assertEquals("",root.optText("missing"))
        assertEquals("default",root.optText("missing","default"))
        // A real value is never rewritten.
        assertEquals("null",root.optText("literal"))
        assertEquals("7",root.optText("number"))
    }
    @Test fun truncatedOrMalformedLikedTrackListsCannotAppearSuccessful() {
        val root=JSONObject().put("tracks",JSONArray().put(JSONObject().put("id",123)))
        assertEquals(listOf("123"),CatalogParsers.completeIds(root,"избранное"))
        for(extra in listOf("total" to 2,"trackCount" to 2,"hasMore" to true,"nextCursor" to "cursor")) {
            val bad=JSONObject(root.toString()).put(extra.first,extra.second)
            assertThrows(SourceFailure::class.java){CatalogParsers.completeIds(bad,"избранное")}
        }
        assertThrows(SourceFailure::class.java){CatalogParsers.completeIds(JSONObject(),"избранное")}
    }
    @Test fun neutralSmartRulesAreRejectedAndImportedDownloadEvidenceDoesNotEnableOfflinePlayback() {
        assertThrows(SourceFailure::class.java){SmartRules().requireFilter()}
        assertEquals(true,SmartRules(favorite=true).requireFilter().favorite)
        val imported=song.copy(localUri="",source="soundcloud",importedDownloaded=true)
        val row=DiscoveryCandidate(imported)
        assertEquals(listOf(imported),SmartPlaylistEngine.select(listOf(row),SmartRules(downloaded=true),System.currentTimeMillis()))
        assertTrue(DiscoveryRanking.select(listOf(row),emptySet(),20,System.currentTimeMillis(),true).isEmpty())
        assertEquals(artistKey("Ёлка"),artistKey(" елка "))
    }
    @Test fun repeatedHomeRequestsAreIdempotentAndExplicitRefreshStillLoads()=runTest {
        var calls=0;val output=MutableStateFlow(DiscoveryState())
        val loader=RecommendationLoader(backgroundScope,MutableStateFlow(false),{calls++;DiscoveryState()},output)
        runCurrent();loader.request();runCurrent();loader.request();runCurrent()
        assertEquals(1,calls);loader.request(force=true);runCurrent();assertEquals(2,calls)
    }
    @Test fun invalidationBurstIsConflatedBeforeTheExpensiveDatabaseAggregation()=runTest {
        val changes=MutableSharedFlow<Int>(extraBufferCapacity=20);var queries=0
        backgroundScope.launch {limitedDiscoveryRefresh(changes,30_000){++queries}.collect()}
        runCurrent();changes.emit(1);runCurrent();assertEquals(1,queries)
        repeat(5){changes.emit(it);runCurrent()};assertEquals(1,queries)
        advanceTimeBy(30_000);runCurrent();assertEquals(2,queries)
    }
}
