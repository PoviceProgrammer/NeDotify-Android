package ru.nedotify.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/**
 * The cover-art client: explicit timeouts and a hard cap on the size of the
 * response. Without them the cover path runs on a stock client whose
 * connect and read timeouts are ten seconds and whose call timeout is
 * unbounded.
 */
class ImageHttpTest {
    @Test fun `the cover client has the bounds of the app`() {
        val client=ImageHttp.client()
        assertEquals(10_000,client.connectTimeoutMillis)
        assertEquals(15_000,client.readTimeoutMillis)
        assertEquals(30_000,client.callTimeoutMillis)
    }
    @Test fun `an image that declares more than the cap is refused`() {
        val server=MockWebServer();server.start()
        try {
            val payload=ByteArray(2048){'a'.code.toByte()}
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))
            val client=OkHttpClient().newBuilder().addInterceptor(ResponseSizeLimitInterceptor(1024)).build()
            val failure=runCatching {
                client.newCall(Request.Builder().url(server.url("/cover.png")).build()).execute()
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertTrue(failure!!.message!!.contains("larger than"))
        } finally {server.shutdown()}
    }
    @Test fun `a body that exceeds the cap while it is read is refused`() {
        val server=MockWebServer();server.start()
        try {
            val payload=ByteArray(64*1024){'a'.code.toByte()}
            // A chunked answer declares no length: the cap has to be enforced
            // while the body is read.
            server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(okio.Buffer().write(payload),1024))
            val client=OkHttpClient().newBuilder().addInterceptor(ResponseSizeLimitInterceptor(1024)).build()
            val failure=runCatching {
                client.newCall(Request.Builder().url(server.url("/cover.png")).build()).execute().use { it.body?.bytes() }
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertTrue(failure!!.message!!.contains("larger than"))
        } finally {server.shutdown()}
    }
}
