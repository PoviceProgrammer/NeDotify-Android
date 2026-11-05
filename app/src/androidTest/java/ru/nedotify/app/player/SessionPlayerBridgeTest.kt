package ru.nedotify.app.player

import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No decoder/network dependency: pins the real Media3 forwarding boundary used by the service. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SessionPlayerBridgeTest {
    @Test fun handoverRetainsIncomingPositionQueueAndIntentWithoutPreparingOrSeeking() = onMain {
        val outgoing=PreparedPlayer(0,99_000)
        val incoming=PreparedPlayer(2,2_750)
        val bridge=SessionPlayerBridge(outgoing)
        val transitions=mutableListOf<String>()
        bridge.addListener(object:Player.Listener {
            override fun onMediaItemTransition(mediaItem:MediaItem?,reason:Int) {transitions+=mediaItem?.mediaId.orEmpty()}
        })
        bridge.handoverTo(incoming)
        assertEquals(3,bridge.mediaItemCount)
        assertEquals("third",bridge.currentMediaItem?.mediaId)
        assertEquals(2,bridge.currentMediaItemIndex)
        assertEquals(2_750L,bridge.currentPosition)
        assertTrue(bridge.isPlaying);assertTrue(bridge.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL,bridge.repeatMode)
        assertEquals(listOf("third"),transitions)
        assertEquals(0,incoming.prepareCalls);assertEquals(0,incoming.seekCalls)
        assertEquals(0,outgoing.releaseCalls)
        bridge.release();outgoing.release()
    }

    @Test fun existingControllerAndListenersTargetNewDecoderAfterHandover() = onMain {
        val outgoing=PreparedPlayer(0,99_000)
        val incoming=PreparedPlayer(1,3_000)
        val bridge=SessionPlayerBridge(outgoing)
        val playing=mutableListOf<Boolean>()
        bridge.addListener(object:Player.Listener {override fun onIsPlayingChanged(isPlaying:Boolean) {playing+=isPlaying}})
        bridge.handoverTo(incoming)
        bridge.pause()
        assertFalse(bridge.playWhenReady);assertFalse(bridge.isPlaying)
        assertEquals(1,incoming.pauseCalls);assertEquals(0,outgoing.pauseCalls)
        assertEquals(listOf(false),playing)
        bridge.release();outgoing.release()
    }

    private fun onMain(block:()->Unit) {InstrumentationRegistry.getInstrumentation().runOnMainSync(block)}

    private class PreparedPlayer(index:Int,position:Long):SimpleBasePlayer(Looper.getMainLooper()) {
        var seekCalls=0
        var prepareCalls=0
        var pauseCalls=0
        var releaseCalls=0
        private var value=State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf("first","second","third").map {id->
                MediaItemData.Builder(id).setMediaItem(MediaItem.Builder().setMediaId(id).build())
                    .setDurationUs(120_000_000).setIsSeekable(true).build()
            })
            .setCurrentMediaItemIndex(index).setContentPositionMs(position)
            .setPlaybackState(Player.STATE_READY).setPlayWhenReady(true,Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setShuffleModeEnabled(true).setRepeatMode(Player.REPEAT_MODE_ALL).build()
        override fun getState()=value
        override fun handleSetPlayWhenReady(playWhenReady:Boolean):ListenableFuture<*> {
            if(!playWhenReady)pauseCalls++
            value=value.buildUpon().setPlayWhenReady(playWhenReady,Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
            invalidateState();return Futures.immediateVoidFuture()
        }
        override fun handlePrepare():ListenableFuture<*> {prepareCalls++;return Futures.immediateVoidFuture()}
        override fun handleSeek(mediaItemIndex:Int,positionMs:Long,seekCommand:Int):ListenableFuture<*> {
            seekCalls++;return Futures.immediateVoidFuture()
        }
        override fun handleRelease():ListenableFuture<*> {releaseCalls++;return Futures.immediateVoidFuture()}
    }
}
