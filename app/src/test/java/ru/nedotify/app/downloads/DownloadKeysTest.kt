package ru.nedotify.app.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadKeysTest {
    @Test fun `download namespaces cannot escape their directory or collide by title`() {
        assertEquals(65,downloadPrefix("../Очень длинное название/CON").length)
        assertTrue(downloadPrefix("id").matches(Regex("[a-f0-9]{64}\\|")))
        assertNotEquals(downloadPrefix("source:1"),downloadPrefix("source:2"))
    }
    @Test fun `signed SoundCloud renewal retains byte identity`() {
        val first="https://playback.media-streaming.soundcloud.cloud/asset/aac/data001.m4s?Policy=old&Signature=old&Key-Pair-Id=a"
        val next="https://playback.media-streaming.soundcloud.cloud/asset/aac/data001.m4s?Policy=new&Signature=new&Key-Pair-Id=b"
        assertEquals(downloadCacheKey("track",first),downloadCacheKey("track",next))
        assertNotEquals(downloadCacheKey("track",first),downloadCacheKey("other",next))
        assertNotEquals(downloadCacheKey("track",first),downloadCacheKey("track",next.replace("data001","data002")))
    }
    @Test fun `codec content version and length are never collapsed by authentication normalization`() {
        val url="https://rr1.googlevideo.com/videoplayback?id=a&itag=140&lmt=10&clen=123&expire=100&sig=a"
        assertEquals(downloadCacheKey("t",url),downloadCacheKey("t",url.replace("expire=100&sig=a","expire=200&sig=b")))
        for((from,to) in listOf("itag=140" to "itag=251","lmt=10" to "lmt=20","clen=123" to "clen=456","id=a" to "id=b"))
            assertNotEquals(downloadCacheKey("t",url),downloadCacheKey("t",url.replace(from,to)))
    }
    @Test fun `unknown hosts keep all query parameters`() {
        assertNotEquals(downloadCacheKey("t","https://unknown.example/a?expire=1"),downloadCacheKey("t","https://unknown.example/a?expire=2"))
        assertNotEquals(downloadCacheKey("t","https://evil-sndcdn.com/a?sig=1"),downloadCacheKey("t","https://evil-sndcdn.com/a?sig=2"))
    }
    @Test fun `automatic retry has a finite budget and does not retry missing recordings`() {
        assertTrue(downloadCanRetry(0,403));assertTrue(downloadCanRetry(2,503));assertTrue(downloadCanRetry(0,null))
        assertFalse(downloadCanRetry(3,403));assertFalse(downloadCanRetry(0,404));assertFalse(downloadCanRetry(0,400))
    }
}
