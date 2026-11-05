package ru.nedotify.app.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricsDataTest {
    @Test fun timestampsFractionsMultipleTagsAndDuplicateLinesAreNormalized() {
        val parsed=parseLrc("[ar:Исполнитель]\n[01:02.3][00:01.23]Тихий океан\n[00:01.230]Тихий океан\n[00:03]Вечер\n[00:04:001]Свет")
        assertEquals(listOf(LrcLine(1230,"Тихий океан"),LrcLine(3000,"Вечер"),LrcLine(4001,"Свет"),LrcLine(62300,"Тихий океан")),parsed)
    }
    @Test fun malformedAndOverflowingTimestampsCannotCrashPlayback() {
        val lines=parseLrc("[999999999999999999999:00]bad\n[00:60]bad\n[00:01]хорошо\nnot synced")
        assertEquals(listOf(LrcLine(1000,"хорошо")),lines)
        assertThrows(IllegalArgumentException::class.java) {parseLrc("a".repeat(256_001))}
    }
    @Test fun nullPlainLyricsAndInstrumentalHaveExplicitResults() {
        assertEquals(LyricsResult(source="LRCLIB"),lyricsResult(JSONObject("{\"plainLyrics\":null,\"syncedLyrics\":null}")))
        assertEquals(LyricsResult(plain="Музыка",instrumental=true,source="LRCLIB"),lyricsResult(JSONObject().put("plainLyrics","Музыка").put("syncedLyrics","[00:01]ghost").put("instrumental",true)))
    }
    @Test fun memoryCacheUsesMetadataKeyAndExpiresWithoutHidingNetworkErrors()=runTest {
        MockWebServer().use {server->
            var time=0L
            val client=LyricsClient(MusicNetwork(),now={time},endpoint=server.url("/get").toString())
            val track=Track("local:1","Песня","Исполнитель",source="local")
            server.enqueue(MockResponse().setBody("{\"plainLyrics\":\"Текст\"}"))
            assertEquals("Текст",client.lyrics(track).plain)
            assertEquals("Текст",client.cached(track)!!.plain)
            assertEquals("Текст",client.lyrics(track).plain);assertEquals(1,server.requestCount)
            assertNull(client.cached(track.copy(title="Другой")))
            time=86_400_001;assertNull(client.cached(track))
            server.enqueue(MockResponse().setResponseCode(503))
            try {client.lyrics(track);fail("Expected network failure")}
            catch(error:SourceFailure) {assertEquals(503,error.httpCode)}
            assertNull(client.cached(track))
        }
    }
    @Test fun notFoundHasShortNegativeCacheAndRequestContainsTrackMetadata()=runTest {
        MockWebServer().use {server->
            var time=0L
            val client=LyricsClient(MusicNetwork(),now={time},endpoint=server.url("/get").toString())
            val track=Track("local:1","Песня","Исполнитель",album="Альбом",durationMs=123_456,source="local")
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(LyricsResult(),client.lyrics(track))
            val request=server.takeRequest().requestUrl!!
            assertEquals("Песня",request.queryParameter("track_name"));assertEquals("Альбом",request.queryParameter("album_name"))
            assertEquals("123",request.queryParameter("duration"))
            assertEquals(LyricsResult(),client.cached(track));time=600_001;assertNull(client.cached(track))
        }
    }
    @Test fun explicitRetryCanRefreshPreviouslyMissingText()=runTest {
        MockWebServer().use {server->
            val client=LyricsClient(MusicNetwork(),endpoint=server.url("/get").toString())
            val track=Track("local:1","Песня",source="local")
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(LyricsResult(),client.lyrics(track))
            server.enqueue(MockResponse().setBody("{\"plainLyrics\":\"Новый текст\"}"))
            assertEquals("Новый текст",client.lyrics(track,forceRefresh=true).plain)
            assertEquals(2,server.requestCount)
        }
    }
}
