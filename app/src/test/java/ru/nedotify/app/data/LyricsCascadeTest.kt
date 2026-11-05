package ru.nedotify.app.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricsCascadeTest {
    private val track=Track("local:test","Тихий океан","Мой Артист",durationMs=180_000,source="local",addedAt=0)
    private fun endpoints(server:MockWebServer)=LyricsEndpoints(server.url("/lrclib").toString(),server.url("/netease/search").toString(),
        server.url("/netease/lyric").toString(),server.url("/qq/search").toString(),server.url("/qq/lyric").toString(),server.url("/").toString().trimEnd('/'))
    private fun json(body:Any)=MockResponse().setHeader("Content-Type","application/json").setBody(body.toString())
    private fun lrclib(title:String=track.title,artist:String=track.artist,duration:Int=180,synced:String="",plain:String="")=JSONObject()
        .put("trackName",title).put("artistName",artist).put("duration",duration).put("syncedLyrics",synced).put("plainLyrics",plain)
    private fun netease(id:Int,duration:Int=180_000)=JSONObject().put("id",id).put("name",track.title).put("dt",duration)
        .put("ar",JSONArray().put(JSONObject().put("name",track.artist)))
    private fun fallback(path:String):MockResponse=when(path){
        "/lrclib"->json(JSONArray())
        "/netease/search"->json(JSONObject().put("result",JSONObject().put("songs",JSONArray())))
        "/qq/search"->json(JSONObject().put("data",JSONObject().put("song",JSONObject().put("list",JSONArray()))))
        else->json(JSONObject())
    }

    @Test fun identityRejectsWrongArtistVersionAndRemixerButKeepsVerifiedPlainOnDurationMismatch() {
        fun accept(wanted:Track=track,title:String=track.title,artist:String=track.artist,duration:Long=180_000)=
            LyricsIdentity.accept(wanted,title,artist,duration,"[00:01.00]Строка","Строка")
        assertNull(accept(artist="Другой исполнитель"))
        assertNull(accept(title="Совсем другая песня"))
        assertNull(accept(title="${track.title} (Live)"))
        assertNull(accept(wanted=track.copy(title="${track.title} (Tiesto Remix)"),title="${track.title} (Other Remix)"))
        assertNotNull(accept(title="${track.title} (Official Audio)"))
        assertTrue(accept(duration=200_000)!!.lines.isEmpty())
        assertEquals("Строка",accept(duration=200_000)!!.plain)
        assertTrue(accept(duration=0)!!.lines.isEmpty())
        assertEquals(1,accept()!!.lines.size)
        assertNull(accept(wanted=track.copy(artist="Неизвестный исполнитель"),duration=0))
        assertNotNull(accept(wanted=track.copy(artist="Неизвестный исполнитель")))
        assertNull(accept(wanted=track.copy(artist="Май"),artist="Маи"))
        assertEquals("мой артист",LyricsIdentity.normalize("Мой Артист"))
        assertNull(LyricsIdentity.accept(track.copy(title="${track.title} (Remix)"),"${track.title} (Remix)",track.artist,180_000,"","",true))
    }

    @Test fun lrclibSearchCanUpgradePlainAndWrongCandidatesCannotBecomeKaraoke()=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
                "/lrclib"->json(JSONArray().put(lrclib(artist="Другой",synced="[00:01]Чужая"))
                    .put(lrclib(synced="[00:01]Нужная",plain="Нужная")))
                else->fallback(request.requestUrl!!.encodedPath)
            }}
            server.start()
            val result=LyricsCascade(MusicNetwork(),endpoints(server)).lookup(track,LyricsResult("Старый текст"))
            assertEquals("Нужная",result.plain);assertEquals(LrcLine(1000,"Нужная"),result.lines.single())
            assertEquals("LRCLIB",result.source)
        }
    }

    @Test fun neteaseTriesAnotherVerifiedEditionWhenFirstHasNoLyrics()=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                val url=request.requestUrl!!
                return when(url.encodedPath){
                    "/netease/search"->json(JSONObject().put("code",200).put("result",JSONObject().put("songs",JSONArray().put(netease(1)).put(netease(2)))))
                    "/netease/lyric"->json(JSONObject().put("lrc",JSONObject().put("lyric",if(url.queryParameter("id")=="2")"[00:02.50]Настоящий текст" else "")))
                    else->fallback(url.encodedPath)
                }
            }}
            server.start()
            val result=LyricsCascade(MusicNetwork(),endpoints(server)).lookup(track,LyricsResult())
            assertEquals("Настоящий текст",result.plain);assertEquals(2500L,result.lines.single().timeMs)
            val requests=List(server.requestCount){server.takeRequest()}
            assertEquals(listOf("1","2"),requests.filter{it.requestUrl!!.encodedPath=="/netease/lyric"}.map{it.requestUrl!!.queryParameter("id")})
            assertTrue(requests.filter{it.requestUrl!!.encodedPath.startsWith("/netease/")}.all{it.getHeader("Referer")=="https://music.163.com/"})
        }
    }

    @Test fun neteaseFetchesAtMostFourVerifiedCandidates()=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
                "/netease/search"->json(JSONObject().put("result",JSONObject().put("songs",JSONArray((1..12).map{netease(it)}))))
                "/netease/lyric"->json(JSONObject())
                else->fallback(request.requestUrl!!.encodedPath)
            }}
            server.start()
            LyricsCascade(MusicNetwork(),endpoints(server)).lookup(track,LyricsResult())
            val requests=List(server.requestCount){server.takeRequest()}
            assertEquals(4,requests.count{it.requestUrl!!.encodedPath=="/netease/lyric"})
        }
    }

    @Test fun qqDecodesHtmlEncodedLrcAndUsesSecondsForDuration()=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
                "/qq/search"->json(JSONObject().put("data",JSONObject().put("song",JSONObject().put("list",JSONArray().put(
                    JSONObject().put("songname",track.title).put("interval",180).put("songmid","Ab123").put("singer",JSONArray().put(JSONObject().put("name",track.artist))))))))
                "/qq/lyric"->json(JSONObject().put("lyric","&#91;00:03.00&#93;Ты &amp; я"))
                else->fallback(request.requestUrl!!.encodedPath)
            }}
            server.start()
            val result=LyricsCascade(MusicNetwork(),endpoints(server)).lookup(track,LyricsResult())
            assertEquals("Ты & я",result.plain);assertEquals(3000L,result.lines.single().timeMs)
        }
    }

    @Test fun geniusReadsOnlyVerifiedTrustedPagesAndAlwaysReturnsPlainText()=runBlocking {
        MockWebServer().use {server->
            fun song(url:String)=JSONObject().put("result",JSONObject().put("title",track.title).put("primary_artist",JSONObject().put("name",track.artist)).put("url",url))
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
                "/api/search/multi"->json(JSONObject().put("response",JSONObject().put("sections",JSONArray().put(JSONObject().put("hits",JSONArray()
                    .put(song("https://evil.example/track-lyrics")).put(song(server.url("/song-lyrics").toString())))))))
                "/song-lyrics"->MockResponse().setBody("<div data-lyrics-container=\"true\"><span>Ты &amp; я</span><br/>Вторая &#x1F30A;<div>Третья</div></div><aside>Не текст</aside>")
                else->fallback(request.requestUrl!!.encodedPath)
            }}
            server.start()
            val result=LyricsCascade(MusicNetwork(),endpoints(server)).lookup(track,LyricsResult())
            assertEquals("Ты & я\nВторая 🌊\nТретья",result.plain);assertTrue(result.lines.isEmpty())
            assertEquals(5,server.requestCount)
        }
    }

    @Test fun aPlainHitSurvivesLaterMalformedResponsesAndTimeouts()=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
                "/lrclib"->json(JSONArray().put(lrclib(duration=0,synced="[00:01]Слова")))
                "/netease/search"->MockResponse().setBody("invalid JSON")
                "/qq/search"->MockResponse().setBody("{}").setHeadersDelay(250,TimeUnit.MILLISECONDS)
                else->MockResponse().setResponseCode(503)
            }}
            server.start()
            val result=LyricsCascade(MusicNetwork(),endpoints(server),100).lookup(track,LyricsResult())
            assertEquals("Слова",result.plain);assertTrue(result.lines.isEmpty())
        }
    }

    @Test fun externalCancellationEscapesAndInstrumentalSkipsAllNetworkCalls()=runBlocking {
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setBody("[]").setHeadersDelay(2,TimeUnit.SECONDS)}
            server.start()
            val cascade=LyricsCascade(MusicNetwork(),endpoints(server))
            assertEquals(LyricsResult(instrumental=true),cascade.lookup(track,LyricsResult(instrumental=true)))
            assertEquals(0,server.requestCount)
            try {withTimeout(100){cascade.lookup(track,LyricsResult("Сохранённый текст"))};fail("Caller cancellation must escape")}
            catch(_:TimeoutCancellationException) { /* Caller owns cancellation. */ }
        }
    }

    @Test fun htmlParserIgnoresUnrelatedMarkupAndBoundsUntrustedInput() {
        assertEquals("",LyricsCascade.geniusText("<div>Unrelated</div>"))
        assertEquals("",LyricsCascade.geniusText("x".repeat(2*1024*1024+1)))
        assertEquals("Первый\nВторой",LyricsCascade.geniusText("<div data-lyrics-container='true'>Первый<script>secret()</script><br>Второй</div>"))
        assertEquals("А & 🌊",LyricsCascade.decodeEntities("&#1040; &amp; &#x1F30A;"))
    }
}
