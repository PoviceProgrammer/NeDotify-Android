package ru.nedotify.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SourceContractTest {
    @Test fun `LRC supports multiple timestamps and fractional precision`() {
        val result=parseLrc("[ar:Artist]\n[01:02.3][02:03.45]Привет\n[00:01.007]Начало\n[01:70.00]bad")
        assertEquals(listOf(1007L,62300L,123450L),result.map {it.timeMs})
        assertEquals(listOf("Начало","Привет","Привет"),result.map {it.text})
    }
    @Test fun `expiration includes safety margin and ignores malformed query`() {
        assertEquals(1_940_000L,streamExpiry("https://host/a?expire=2000",100))
        assertEquals(2_940_000L,streamExpiry("https://host/a?Expires=3000",100))
        assertEquals(840_100L,streamExpiry("https://host/a?expire=bad",100))
        assertEquals(840_100L,streamExpiry("not a URI",100))
    }
    @Test fun `SoundCloud rejects protected transcodings and selects progressive`()=runBlocking {
        val calls=AtomicInteger()
        val network=network { request ->
            when(request.url.host) {
                "soundcloud.com"->"<script src=\"https://a-v2.sndcdn.com/assets/player.js\"></script>"
                "a-v2.sndcdn.com"->{calls.incrementAndGet();"client_id:\"abcdefghijklmnopqrstuvwxyz123456\""}
                "api-v2.soundcloud.com"->"""{"media":{"transcodings":[{"url":"https://transcode.test/protected","format":{"protocol":"ctr-hls"}},{"url":"https://transcode.test/play","format":{"protocol":"progressive"}}]}}"""
                "transcode.test"->{assertEquals("/play",request.url.encodedPath);"""{"url":"https://cdn.test/audio.mp3?Expires=2000000000"}"""}
                else->error("Unexpected request")
            }
        }
        val client=SoundCloudClient(network)
        val result=client.resolve(Track("soundcloud:1","Track",source="soundcloud",sourceId="1"))
        assertTrue(result.url.startsWith("https://cdn.test/"));assertNull(result.mimeType)
        client.resolve(Track("soundcloud:2","Track",source="soundcloud",sourceId="2"))
        assertEquals(1,calls.get())
    }
    @Test fun `YouTube playability failure is explicit and does not produce fake stream`()=runBlocking {
        val client=InnerTubeClient(network {"""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}}"""})
        try {client.resolve(Track("youtube:abcdefghijk","Track",source="youtube",sourceId="abcdefghijk"));fail("Must fail")}
        catch(error:SourceFailure){assertTrue(error.message!!.contains("Sign in"))}
    }
    private fun network(body:(Request)->String)=MusicNetwork(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body(chain.request()).toResponseBody("application/json".toMediaType())).build()
    }.build())
}
