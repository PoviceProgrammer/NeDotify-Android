package ru.nedotify.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.nedotify.app.data.OfflineDownload
import ru.nedotify.app.data.Track

/**
 * The wiring of the playback gate: the parts that need a real player.
 *
 * NOT EXECUTED in the repair environment (no device/emulator, and APK packaging
 * is forbidden). It must be run on a device or an emulator.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackGateWiringTest {
    private val context:Context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aRefusedPlayFailsTheCommandAndReportsTheReason() = onMain {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val deck=DualDeckPlayer(context,ExoPlayer.Builder(context).build(),ExoPlayer.Builder(context).build(),scope)
        var refusal:String?=null
        deck.playRefusal={PLAYBACK_REFUSED}
        deck.onPlaybackRefused={refusal=it}
        deck.setPlayWhenReady(true)
        assertEquals(PLAYBACK_REFUSED,refusal)
        // The play did not start: the player is still paused.
        assertFalse(deck.playWhenReady)
        deck.release();scope.cancel()
    }

    @Test fun aFreshPlayIsNotRefused() = onMain {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val deck=DualDeckPlayer(context,ExoPlayer.Builder(context).build(),ExoPlayer.Builder(context).build(),scope)
        var refusal:String?=null
        deck.playRefusal={null}
        deck.onPlaybackRefused={refusal=it}
        deck.setPlayWhenReady(true)
        assertNull(refusal)
        deck.release();scope.cancel()
    }

    @Test fun aClosedGateInstallsTheQueuePaused() = onMain {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val deck=DualDeckPlayer(context,ExoPlayer.Builder(context).build(),ExoPlayer.Builder(context).build(),scope)
        deck.canStartPlayback={false}
        val item=MediaItem.Builder().setMediaId("a").setUri("https://example.invalid/a.mp3").build()
        deck.setMediaItems(listOf(item),0,0L)
        assertFalse(deck.playWhenReady)
        deck.release();scope.cancel()
    }

    @Test fun theLiveQueueItemAlwaysCarriesAUri() {
        val track=Track("soundcloud:1","Название","Исполнитель","Альбом",1000L,"soundcloud")
        val plain=trackMediaItem(track)
        assertNotNull(plain.localConfiguration)
        assertNotNull(plain.localConfiguration!!.uri)
        val saved=OfflineDownload(trackId="soundcloud:1",generation="g",state="complete",uri="content://media/1")
        val offline=trackMediaItem(track,saved)
        assertNotNull(offline.localConfiguration)
        assertNotNull(offline.localConfiguration!!.uri)
    }

    private fun onMain(block:()->Unit) {InstrumentationRegistry.getInstrumentation().runOnMainSync(block)}
}
