package ru.nedotify.app.ui

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.*
import org.junit.Test

class NowPlayingQueueTest {
    private fun media(id:String,title:String,artist:String="")=MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build()).build()

    @Test fun `an unchanged row keeps its instance`() {
        val old=QueueRow("a","Title","Artist","")
        assertSame(old,queueRow(media("a","Title","Artist"),old))
    }
    @Test fun `a changed row is a new instance`() {
        val old=QueueRow("a","Title","Artist","")
        assertNotSame(old,queueRow(media("a","Other","Artist"),old))
        assertNotSame(old,queueRow(media("b","Title","Artist"),old))
        assertNotSame(old,queueRow(media("a","Title","Other"),old))
    }
    @Test fun `an unchanged queue keeps its list, so the mirror comparison stays cheap`() {
        val rows=listOf(QueueRow("a","Title","Artist",""),QueueRow("b","Other","Artist2",""))
        val rebuilt=rows.map {queueRow(media(it.id,it.title,it.artist),it)}
        assertEquals(rows,rebuilt)
        assertSame(rows[0],rebuilt[0]);assertSame(rows[1],rebuilt[1])
        // The whole list is equal, so the state flow does not emit a new value.
        assertEquals(NowPlaying(id="a",queue=rows),NowPlaying(id="a",queue=rebuilt))
    }
    @Test fun `the row still reads as the pair the player screen expects`() {
        val row=QueueRow("a","Title","Artist","art")
        assertEquals("a",row.first);assertEquals("Title",row.second)
    }
    @Test fun `duration is carried by the queue mirror and changing it invalidates only its row`() {
        val item=media("a","Title").buildUpon().setMediaMetadata(MediaMetadata.Builder().setTitle("Title").setDurationMs(180_000L).build()).build()
        val row=queueRow(item,null)
        assertEquals(180_000L,row.durationMs)
        assertSame(row,queueRow(item,row))
        assertNotSame(row,queueRow(media("a","Title"),row))
    }
}
