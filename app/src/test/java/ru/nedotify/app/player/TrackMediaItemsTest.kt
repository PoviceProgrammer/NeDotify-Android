package ru.nedotify.app.player

import org.junit.Assert.*
import org.junit.Test
import ru.nedotify.app.data.OfflineDownload
import ru.nedotify.app.data.Track
import ru.nedotify.app.downloads.OfflineAsset

class TrackMediaItemsTest {
    private fun track(id:String="soundcloud:1",localUri:String="",mime:String="") =
        Track(id,"Название","Исполнитель","Альбом",1000L,"soundcloud",localUri=localUri,mimeType=mime)

    @Test fun `an online item always carries a resolvable uri`() {
        val item=queueItemData(track())
        assertEquals("soundcloud:1",item.mediaId)
        assertEquals("nedotify://track/soundcloud%3A1",item.uri)
        assertNull(item.tag)
        assertEquals("Название",item.title);assertEquals("Исполнитель",item.artist)
    }
    @Test fun `a completed download becomes the offline item with its tag`() {
        val saved=OfflineDownload(trackId="soundcloud:1",generation="g",state="complete",
            uri="content://media/1",mimeType="audio/mpeg")
        val item=queueItemData(track(),saved)
        assertEquals("nedotify-offline://track/soundcloud%3A1",item.uri)
        assertEquals(OfflineAsset("soundcloud:1","content://media/1","audio/mpeg","g"),item.tag)
        assertEquals("audio/mpeg",item.mimeType)
    }
    @Test fun `the track's own file wins over the resolver`() {
        val item=queueItemData(track(localUri="content://media/9"))
        assertEquals("content://media/9",item.uri)
        assertNull(item.tag)
    }
    @Test fun `an unfinished download never becomes the offline item`() {
        val saved=OfflineDownload(trackId="soundcloud:1",generation="g",state="failed",uri="")
        val item=queueItemData(track(),saved)
        assertEquals("nedotify://track/soundcloud%3A1",item.uri)
        assertNull(item.tag)
    }
    @Test fun `a download that is not complete never becomes the offline item`() {
        // A download in the `preparing` state already carries a URI: playing it
        // would fail. Only a finished download may become the offline item.
        for(state in listOf("queued","preparing","paused","failed")) {
            val saved=OfflineDownload(trackId="soundcloud:1",generation="g",state=state,uri="content://media/1")
            val item=queueItemData(track(),saved)
            assertEquals(state,"nedotify://track/soundcloud%3A1",item.uri)
            assertNull(state,item.tag)
        }
    }
    @Test fun `the live queue item always carries a uri`() {
        // A MediaItem without a URI makes the source factory throw: the factory
        // is the only place a live item is built, so it must never be empty.
        for(saved in listOf(null,OfflineDownload(trackId="soundcloud:1",generation="g",state="complete",uri="content://media/1"))) {
            assertTrue(queueItemData(track(),saved).uri.isNotBlank())
        }
        assertTrue(queueItemData(track(localUri="content://media/9")).uri.isNotBlank())
    }
    @Test fun `the path segment is percent encoded`() {
        assertEquals("a%2Fb",encodePathSegment("a/b"))
        assertEquals("a-b_c.d",encodePathSegment("a-b_c.d"))
        assertEquals("%D0%B0",encodePathSegment("а"))
        assertEquals("nedotify://track/local%3A42",queueUri("local:42",false))
    }
}
