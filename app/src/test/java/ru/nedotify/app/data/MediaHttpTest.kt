package ru.nedotify.app.data

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/**
 * The media client: no total call budget, a per-hop URL policy and the
 * headers the CDNs expect. Every test here fails when the media client is
 * replaced by the metadata client or when the redirect policy is removed.
 */
class MediaHttpTest {
    /**
     * A policy over the local test servers.
     *
     * Every server is a host of **one** source, and the sources are
     * different: a hop from one source to another must be refused, and a
     * test that cannot express that difference cannot see the defect.
     */
    private class LocalPolicy(private val hosts: List<Pair<HttpUrl, String>>) : MediaHostPolicy {
        override fun sourceFor(url: HttpUrl): String? =
            hosts.firstOrNull { it.first.host == url.host && it.first.port == url.port }?.second
        override fun refererFor(source: String): String? = when (source) {
            "soundcloud" -> "https://soundcloud.com/"
            "youtube" -> "https://music.youtube.com/"
            else -> null
        }
    }
    private fun client(vararg hosts: Pair<HttpUrl, String>, base: OkHttpClient = OkHttpClient()): OkHttpClient =
        MediaHttp.client(base, LocalPolicy(hosts.toList()), connectTimeoutSeconds = 5, readTimeoutSeconds = 5)

    @Test fun `the media request carries a stable agent and the referer of its source`() {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("audio"))
            val client=client(server.url("/media") to "soundcloud")
            val response=client.newCall(Request.Builder().url(server.url("/media")).build()).execute()
            response.use { assertEquals("audio",it.body?.string()) }
            val recorded=server.takeRequest()
            assertEquals(MediaUrlPolicy.USER_AGENT,recorded.getHeader("User-Agent"))
            assertEquals("https://soundcloud.com/",recorded.getHeader("Referer"))
        } finally {server.shutdown()}
    }
    @Test fun `a redirect to a foreign host is refused and never fetched`() {
        val server=MockWebServer();server.start()
        val other=MockWebServer();other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",other.url("/elsewhere").toString()))
            val client=client(server.url("/media") to "soundcloud")
            val failure=runCatching {
                client.newCall(Request.Builder().url(server.url("/media")).build()).execute().use { it.body?.string() }
            }.exceptionOrNull()
            assertTrue(failure is SourceFailure)
            assertEquals(1,server.requestCount)
            assertEquals(0,other.requestCount)
        } finally {server.shutdown();other.shutdown()}
    }
    @Test fun `a redirect to another source is refused even when that source may use the host`() {
        val server=MockWebServer();server.start()
        val other=MockWebServer();other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",other.url("/elsewhere").toString()))
            // Both hosts are allowed — but they belong to different sources.
            val client=client(server.url("/media") to "soundcloud", other.url("/") to "youtube")
            val failure=runCatching {
                client.newCall(Request.Builder().url(server.url("/media")).build()).execute().use { it.body?.string() }
            }.exceptionOrNull()
            assertTrue("the hop left the source of the first url",failure is SourceFailure)
            assertEquals(0,other.requestCount)
        } finally {server.shutdown();other.shutdown()}
    }
    @Test fun `a redirect inside the allowed host is followed`() {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/target"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("followed"))
            val client=client(server.url("/media") to "soundcloud")
            val body=client.newCall(Request.Builder().url(server.url("/media")).build()).execute().use { it.body?.string() }
            assertEquals("followed",body)
            assertEquals(2,server.requestCount)
            val first=server.takeRequest()
            val second=server.takeRequest()
            assertEquals("/media",first.path)
            assertEquals("/target",second.path)
            // The second hop is validated and headed as well.
            assertEquals(MediaUrlPolicy.USER_AGENT,second.getHeader("User-Agent"))
            assertEquals("https://soundcloud.com/",second.getHeader("Referer"))
        } finally {server.shutdown()}
    }
    @Test fun `a hop inside the same source keeps the referer of the source`() {
        val server=MockWebServer();server.start()
        val other=MockWebServer();other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",other.url("/elsewhere").toString()))
            other.enqueue(MockResponse().setResponseCode(200).setBody("followed"))
            // The second server belongs to the same source: the hop is allowed.
            val client=client(server.url("/media") to "soundcloud", other.url("/") to "soundcloud")
            val body=client.newCall(Request.Builder().url(server.url("/media")).build()).execute().use { it.body?.string() }
            assertEquals("followed",body)
            val hop=other.takeRequest()
            assertEquals("https://soundcloud.com/",hop.getHeader("Referer"))
        } finally {server.shutdown();other.shutdown()}
    }
    @Test fun `a hop to another connection drops the authorization header`() {
        val server=MockWebServer();server.start()
        val other=MockWebServer();other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",other.url("/elsewhere").toString()))
            other.enqueue(MockResponse().setResponseCode(200).setBody("followed"))
            val client=client(server.url("/media") to "soundcloud", other.url("/") to "soundcloud")
            val request=Request.Builder().url(server.url("/media")).header("Authorization","Bearer secret").build()
            client.newCall(request).execute().use { it.body?.string() }
            assertEquals("Bearer secret",server.takeRequest().getHeader("Authorization"))
            assertNull("the credential travelled to another host",other.takeRequest().getHeader("Authorization"))
        } finally {server.shutdown();other.shutdown()}
    }
    @Test fun `a hop inside the same connection keeps the authorization header`() {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/target"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("followed"))
            val client=client(server.url("/media") to "soundcloud")
            val request=Request.Builder().url(server.url("/media")).header("Authorization","Bearer secret").build()
            client.newCall(request).execute().use { it.body?.string() }
            assertEquals("Bearer secret",server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer secret",server.takeRequest().getHeader("Authorization"))
        } finally {server.shutdown()}
    }
    @Test fun `a redirect loop is stopped instead of followed forever`() {
        val server=MockWebServer();server.start()
        try {
            repeat(10){server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/loop"))}
            val client=client(server.url("/media") to "soundcloud")
            val failure=runCatching {
                client.newCall(Request.Builder().url(server.url("/media")).build()).execute().use { it.body?.string() }
            }.exceptionOrNull()
            assertTrue(failure is SourceFailure)
            assertTrue(server.requestCount<=MediaHttp.MAX_REDIRECTS+1)
        } finally {server.shutdown()}
    }
    @Test fun `an expired signature is a classified failure and not a generic error`() {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            val client=client(server.url("/media") to "soundcloud")
            val failure=runCatching {
                client.newCall(Request.Builder().url(server.url("/media")).build()).execute()
            }.exceptionOrNull()
            assertTrue(failure is SourceFailure)
            assertEquals(401,(failure as SourceFailure).httpCode)
            assertEquals(mediaStatusMessage(401),failure.message)
        } finally {server.shutdown()}
    }
    @Test fun `a refused host is a classified failure`() {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403))
            val client=client(server.url("/media") to "soundcloud")
            val failure=runCatching {
                client.newCall(Request.Builder().url(server.url("/media")).build()).execute()
            }.exceptionOrNull()
            assertTrue(failure is SourceFailure)
            assertEquals(403,(failure as SourceFailure).httpCode)
        } finally {server.shutdown()}
    }
    @Test fun `a url outside the allow list never reaches the network`() {
        val server=MockWebServer();server.start()
        val other=MockWebServer();other.start()
        try {
            val client=client(server.url("/media") to "soundcloud")
            val failure=runCatching {
                client.newCall(Request.Builder().url(other.url("/media")).build()).execute()
            }.exceptionOrNull()
            assertTrue(failure is SourceFailure)
            assertEquals(0,other.requestCount)
        } finally {server.shutdown();other.shutdown()}
    }
    @Test fun `the media client has no total budget and the metadata client keeps its own`() {
        val network=MusicNetwork()
        assertEquals(0,network.mediaHttp.callTimeoutMillis)
        assertEquals(30_000,network.http.callTimeoutMillis)
        assertEquals(20_000,network.mediaHttp.readTimeoutMillis)
        assertEquals(20_000,network.http.readTimeoutMillis)
        assertFalse(network.mediaHttp.followRedirects)
        assertFalse(network.mediaHttp.followSslRedirects)
    }
    @Test fun `the media client is not the metadata client`() {
        val network=MusicNetwork()
        assertNotSame(network.http,network.mediaHttp)
        assertEquals(12_000,network.mediaHttp.connectTimeoutMillis)
    }
    @Test fun `the provider session speaks with one agent`() = kotlinx.coroutines.runBlocking {
        // The metadata request, the InnerTube request and the media request
        // must not look like three different clients: a session that changes
        // its agent between the metadata call and the media call is a session
        // that can be refused.
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val network=MusicNetwork(OkHttpClient())
            network.get(server.url("/x").toString())
            assertEquals(MediaUrlPolicy.USER_AGENT,server.takeRequest().getHeader("User-Agent"))
        } finally {server.shutdown()}
    }
}
