package ru.nedotify.app.data

import okhttp3.Call
import okhttp3.Connection
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The media-URL policy is the boundary between "a provider may hand the app
 * a URL" and "the app connects to a host". Every test here fails when the
 * allow-list is removed, widened or replaced by a prefix check.
 */
class MediaUrlPolicyTest {
    @Test fun `each source may only use its own hosts`() {
        assertTrue(MediaUrlPolicy.accepts("soundcloud", "https://cf-media.sndcdn.com/a.mp3"))
        assertTrue(MediaUrlPolicy.accepts("soundcloud", "https://playback.media-streaming.soundcloud.cloud/a/playlist.m3u8"))
        assertTrue(MediaUrlPolicy.accepts("youtube", "https://rr1---sn-abc.googlevideo.com/videoplayback?id=1"))
        assertTrue(MediaUrlPolicy.accepts("yandex", "https://storage.mds.yandex.net/get-mp3/a/b/c"))
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "https://rr1---sn-abc.googlevideo.com/videoplayback?id=1"))
        assertFalse(MediaUrlPolicy.accepts("youtube", "https://cf-media.sndcdn.com/a.mp3"))
        assertFalse(MediaUrlPolicy.accepts("yandex", "https://cf-media.sndcdn.com/a.mp3"))
    }
    @Test fun `the hosts the app has observed are allowed for their own source`() {
        // android/review/PROVIDER_PROBE.json — the hosts of the app's own live
        // probe. The HLS manifest and its segments live on the first host, the
        // client-id asset on the second.
        assertTrue(MediaUrlPolicy.accepts("soundcloud",
            "https://playback.media-streaming.soundcloud.cloud/PpUAe84RbCDt/aac_160k/2ea9ab96-0200-415f-8e83-e85bf4a98db7/playlist.m3u8"))
        assertTrue(MediaUrlPolicy.accepts("soundcloud",
            "https://playback.media-streaming.soundcloud.cloud/PpUAe84RbCDt/aac_160k/2ea9ab96-0200-415f-8e83-e85bf4a98db7/data000.m4s"))
        assertTrue(MediaUrlPolicy.accepts("soundcloud", "https://a-v2.sndcdn.com/assets/55-d39036ca.js"))
        // The metadata path is not a media path: it must stay unreachable.
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "https://api-v2.soundcloud.com/search/tracks"))
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "https://soundcloud.com/"))
        assertFalse(MediaUrlPolicy.accepts("youtube", "https://music.youtube.com/youtubei/v1/player"))
    }
    @Test fun `the list is at least the list of the desktop`() {
        // core/proxy.py: the desktop trusts these hosts for the SoundCloud
        // session. A host that is missing from the app is a broken playback.
        for (host in listOf("cf-media.sndcdn.com", "a-v2.sndcdn.com", "i1.scdn.co",
            "playback.media-streaming.soundcloud.cloud", "media.soundcloud.cloud"))
            assertTrue("the desktop trusts $host", MediaUrlPolicy.accepts("soundcloud", "https://$host/a"))
        // The media host of YouTube is the one of the desktop.
        assertTrue(MediaUrlPolicy.accepts("youtube", "https://rr1---sn-abc.googlevideo.com/videoplayback?id=1"))
        assertTrue(MediaUrlPolicy.accepts("youtube", "https://redirector.googlevideo.com/videoplayback?id=1"))
    }
    @Test fun `a host that only looks like an allowed host is refused`() {
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "https://evil-sndcdn.com/a.mp3"))
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "https://sndcdn.com.evil.test/a.mp3"))
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "https://scdn.co.evil.test/a.mp3"))
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "https://soundcloud.cloud.evil.test/a.mp3"))
        assertFalse(MediaUrlPolicy.accepts("youtube", "https://googlevideo.com.evil.test/a"))
        assertFalse(MediaUrlPolicy.accepts("yandex", "https://storage.yandex.net.evil.test/a"))
    }
    @Test fun `only a plain https url without credentials is a media url`() {
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "http://cf-media.sndcdn.com/a.mp3"))
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "https://user:pass@cf-media.sndcdn.com/a.mp3"))
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "https://cf-media.sndcdn.com:8443/a.mp3"))
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "not a url"))
        assertFalse(MediaUrlPolicy.accepts("soundcloud", "https://cf-media.sndcdn.com/a.mp3#fragment"))
    }
    @Test fun `a refused url is a source failure and never reaches the host`() {
        val failure=runCatching { MediaUrlPolicy.requireAccepted("soundcloud", "https://evil.test/a.mp3") }.exceptionOrNull()
        assertTrue(failure is SourceFailure)
        assertEquals("Источник вернул неподдерживаемый адрес аудио",failure?.message)
        // The message never carries the URL: it can hold a signature.
        assertFalse(failure?.message.orEmpty().contains("evil.test"))
        assertEquals("https://cf-media.sndcdn.com/a.mp3",MediaUrlPolicy.requireAccepted("soundcloud", "https://cf-media.sndcdn.com/a.mp3"))
    }
    @Test fun `the refused host reaches the diagnostic sink and never the url`() {
        // The sink is what the operator reads when a CDN moves its hosts. It
        // must receive the host — the URL carries a signature.
        val recorded=mutableListOf<String>()
        withDiagnostics(MediaDiagnostics{host->recorded+=host}) {
            val failure=runCatching {
                MediaUrlPolicy.requireAccepted("soundcloud", "https://evil.test/a.mp3?signature=s3cr3t")
            }.exceptionOrNull()
            assertTrue(failure is SourceFailure)
            assertEquals("Источник вернул неподдерживаемый адрес аудио",failure?.message)
            assertEquals(listOf("evil.test"),recorded)
            // The sink never sees the URL: a signed URL carries a signature.
            assertFalse(recorded.any{it.contains("signature")||it.contains("s3cr3t")})
        }
    }
    @Test fun `a failing diagnostic cannot abort the refusal`() {
        // The refusal is the contract; the diagnostic is a courtesy. A sink
        // that throws — an android.util.Log that is not implemented in a JVM
        // test, a log file that cannot be written — must not replace the
        // SourceFailure the user is waiting for.
        withDiagnostics(MediaDiagnostics{throw IllegalStateException("the log is not available")}) {
            val failure=runCatching { MediaUrlPolicy.requireAccepted("soundcloud", "https://evil.test/a.mp3") }.exceptionOrNull()
            assertTrue("the refusal must survive a failing diagnostic",failure is SourceFailure)
            assertEquals("Источник вернул неподдерживаемый адрес аудио",failure?.message)
        }
    }
    @Test fun `the client path refuses the host without contacting it and reports the host`() {
        // The same contract on the path that actually holds the connection:
        // the interceptor must refuse before the request leaves, and the
        // diagnostic must carry the host and not the URL.
        val recorded=mutableListOf<String>()
        withDiagnostics(MediaDiagnostics{host->recorded+=host}) {
            val request=Request.Builder().url("https://evil.test/a.mp3?signature=s3cr3t").build()
            val failure=runCatching { MediaPolicyInterceptor(MediaUrlPolicy).intercept(NeverProceedChain(request)) }.exceptionOrNull()
            assertTrue(failure is SourceFailure)
            assertEquals("Источник вернул неподдерживаемый адрес аудио",failure?.message)
            assertEquals(listOf("evil.test"),recorded)
            assertFalse(recorded.any{it.contains("signature")||it.contains("s3cr3t")})
        }
    }
    @Test fun `the yandex allow list is the one that already existed`() {
        val hosts=listOf("storage.mds.yandex.net","storage.yandex.net","music.yandex.net","cdn.music.yandex.net",
            "evil.test","sndcdn.com","storage.yandex.net.evil.test")
        for(host in hosts) {
            val url="https://$host/get-mp3/signature/1700000000/file.mp3"
            val legacy=runCatching { YandexDownloadInfo.trustedUrl(url).toString() }.isSuccess
            assertEquals("the two allow lists disagree about $host",legacy,MediaUrlPolicy.accepts("yandex",url))
        }
    }
    @Test fun `the referer of a source is the one its cdn expects`() {
        assertEquals("https://soundcloud.com/",MediaUrlPolicy.refererFor("soundcloud"))
        assertEquals("https://music.youtube.com/",MediaUrlPolicy.refererFor("youtube"))
        assertEquals("https://music.yandex.ru/",MediaUrlPolicy.refererFor("yandex"))
        assertNull(MediaUrlPolicy.refererFor("unknown"))
    }
    @Test fun `the host of a url decides which source may read it`() {
        assertEquals("soundcloud",MediaUrlPolicy.sourceFor("https://cf-media.sndcdn.com/a".toHttpUrl()))
        assertEquals("soundcloud",MediaUrlPolicy.sourceFor("https://i1.scdn.co/a".toHttpUrl()))
        assertEquals("youtube",MediaUrlPolicy.sourceFor("https://rr1---sn-abc.googlevideo.com/a".toHttpUrl()))
        assertEquals("yandex",MediaUrlPolicy.sourceFor("https://storage.mds.yandex.net/a".toHttpUrl()))
        assertNull(MediaUrlPolicy.sourceFor("https://evil.test/a".toHttpUrl()))
    }
    @Test fun `a host belongs to exactly one source`() {
        // The redirect chain stays inside one source: the source of a host
        // must not be ambiguous, or a hop could change its source.
        for (source in listOf("soundcloud", "youtube", "yandex"))
            for (rule in MediaUrlPolicy.hostsFor(source)) {
                val host = if (rule.startsWith("*.")) "x" + rule.substring(1) else rule
                assertEquals("the host $host has two owners", source, MediaUrlPolicy.sourceForHost(host))
            }
    }
    @Test fun `a policy can be replaced for a test without touching the production one`() {
        val permissive=object:MediaHostPolicy{
            override fun sourceFor(url:HttpUrl):String?=if(url.host=="127.0.0.1")"test" else null
            override fun refererFor(source:String):String?=null
        }
        assertEquals("test",permissive.sourceFor("https://127.0.0.1:1/a".toHttpUrl()))
        assertNull(permissive.sourceFor("https://evil.test/a".toHttpUrl()))
    }
    /**
     * Runs the block with a diagnostic sink of the test's own and puts the
     * app's sink back afterwards: the policy is a process-wide singleton and
     * the next test must not inherit this sink.
     */
    private fun withDiagnostics(sink:MediaDiagnostics,block:()->Unit) {
        val previous=MediaUrlPolicy.diagnostics
        MediaUrlPolicy.diagnostics=sink
        try { block() } finally { MediaUrlPolicy.diagnostics=previous }
    }
    /** A chain that fails the test when the refused request is allowed to leave. */
    private class NeverProceedChain(private val request:Request):Interceptor.Chain {
        override fun request():Request=request
        override fun proceed(request:Request):Response=throw AssertionError("the refused host was contacted")
        override fun connection():Connection?=null
        override fun call():Call=throw AssertionError("the refused host was contacted")
        override fun connectTimeoutMillis():Int=0
        override fun withConnectTimeout(timeout:Int,unit:TimeUnit):Interceptor.Chain=this
        override fun readTimeoutMillis():Int=0
        override fun withReadTimeout(timeout:Int,unit:TimeUnit):Interceptor.Chain=this
        override fun writeTimeoutMillis():Int=0
        override fun withWriteTimeout(timeout:Int,unit:TimeUnit):Interceptor.Chain=this
    }
}
