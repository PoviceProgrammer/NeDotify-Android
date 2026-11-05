package ru.nedotify.app.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SoundCloudSingleFlightTest {
    @Test fun `cancelling one search does not cancel shared client id discovery`()=runBlocking {
        val started=CountDownLatch(1);val release=CountDownLatch(1);val scripts=AtomicInteger()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val client=SoundCloudClient(network {request->when(request.url.host) {
            "soundcloud.com"->"<script src=\"https://a-v2.sndcdn.com/assets/main.js\"></script>"
            "a-v2.sndcdn.com"->{scripts.incrementAndGet();started.countDown();check(release.await(5,TimeUnit.SECONDS));"client_id:\"abcdefghijklmnopqrstuvwxyz123456\""}
            else->"""{"collection":[]}"""
        }},scope)
        try {
            withTimeout(8000) {
                val a=async {client.search("first")}
                val b=async {client.search("second")}
                assertTrue(withContext(Dispatchers.IO){started.await(5,TimeUnit.SECONDS)})
                a.cancelAndJoin();release.countDown()
                assertTrue(b.await().isEmpty());assertEquals(1,scripts.get())
            }
        } finally {release.countDown();scope.cancel()}
    }
    @Test fun `unauthorized client id is refreshed once and shared id is reused afterwards`()=runBlocking {
        val scripts=AtomicInteger();val requests=AtomicInteger()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val client=SoundCloudClient(network {request->when(request.url.host) {
            "soundcloud.com"->"<script src=\"https://a-v2.sndcdn.com/assets/main.js\"></script>"
            "a-v2.sndcdn.com"->{scripts.incrementAndGet();"client_id:\"abcdefghijklmnopqrstuvwxyz123456\""}
            else->{if(requests.incrementAndGet()==1)throw SourceFailure("Источник ответил HTTP 403");"""{"collection":[]}"""}
        }},scope)
        try {
            withTimeout(8000){client.search("first");client.search("second")}
            assertEquals(2,scripts.get());assertEquals(3,requests.get())
        } finally {scope.cancel()}
    }
    private fun network(body:(Request)->String)=MusicNetwork(OkHttpClient.Builder().addInterceptor {chain->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body(chain.request()).toResponseBody("application/json".toMediaType())).build()
    }.build())
}
