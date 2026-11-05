package ru.nedotify.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import ru.nedotify.app.data.AudioSettings

/**
 * The MediaSession always sees this one Player. Swapping its delegate preserves the prepared,
 * already audible incoming decoder, its position, queue index and exact shuffle traversal.
 * Both decoders deliberately have automatic audio focus disabled: one shared request owns audio.
 */
@UnstableApi
internal class DualDeckPlayer(
    context:Context,
    first:ExoPlayer,
    second:ExoPlayer,
    scope:CoroutineScope,
    private val elapsed:()->Long=SystemClock::elapsedRealtime,
):SessionPlayerBridge(first) {
    private val appContext=context.applicationContext
    private val decks=arrayOf(first,second)
    private var activeSlot=0
    val active:ExoPlayer get()=decks[activeSlot]
    private val standby:ExoPlayer get()=decks[1-activeSlot]
    private val effects=AudioEffectsController {applyVolumes()}
    private val manager=appContext.getSystemService(AudioManager::class.java)
    private val focusState=PlaybackFocusState()
    private var userVolume=1f
    private var unmutedVolume=1f
    private var ducked=false
    private var settings=AudioSettings()
    private var targetIndex=C.INDEX_UNSET
    private var targetId:String?=null
    private var preparedFor:String?=null
    private var blockedFor:String?=null
    private var fadeStarted=0L
    private var fadeDuration=0L
    private val transitionRecovery=CrossfadeRecovery()
    private var outgoingGain=1f
    private var incomingGain=0f
    private var closed=false
    var isHandover=false
        private set
    var onHandover:(String)->Unit={}
    /** A real user action: it supersedes the running restore/autoplay/refresh requests. */
    var onUserInteraction:()->Unit={}
    /** The playback environment changed or another controller paused: a queued play must not resume. */
    var onPlaybackInterrupted:()->Unit={}
    /** The gate refused the play: the controller must be able to tell "refused" from "succeeded". */
    var onPlaybackRefused:(String)->Unit={}
    var onQueueSet:(String,Int)->Unit={_,_->}
    /**
     * The gate's decision for the play that is executed now: the refusal reason,
     * or null when the play may start. The gate consumes the play, so the
     * decision must be asked exactly once per play.
     */
    var playRefusal:(Boolean)->String?={null}
    var canStartPlayback:()->Boolean={true}
    var onFocusDenied:()->Unit={}
    private val focusRequest=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({change->audioFocusChanged(change)},Handler(Looper.getMainLooper()))
        .build()
    private val noisyReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action==AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                pauseForReason(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        }
    }
    private val tick:Job
    private val polling=MutableStateFlow(false)
    private fun needsPolling()=!closed && (fadeDuration>0 ||
        (focusState.canPlay && settings.crossfadeSeconds>0 && active.isPlaying))

    init {
        decks.forEachIndexed {slot,deck->
            deck.addListener(object:Player.Listener {
                override fun onEvents(player:Player,events:Player.Events) {polling.value=needsPolling()}
                override fun onAudioSessionIdChanged(audioSessionId:Int) {
                    effects.session(slot,audioSessionId,slot==activeSlot);applyVolumes()
                }
                override fun onMediaItemTransition(mediaItem:MediaItem?,reason:Int) {
                    if(slot==activeSlot && !isHandover) {cancelTransition();blockedFor=null}
                }
                override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int) {
                    if(slot==activeSlot && !playWhenReady && fadeDuration>0 &&
                        reason==Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)transitionRecovery.boundaryPause()
                }
                override fun onPlaybackStateChanged(playbackState:Int) {
                    if(slot==activeSlot && playbackState==Player.STATE_ENDED && fadeDuration==0L && !settings.autoplay)
                        finishIfEnded()
                }
                override fun onPlayerError(error:PlaybackException) {
                    if(slot!=activeSlot)blockedFor=currentKey()
                    cancelTransition()
                }
                override fun onTimelineChanged(timeline:Timeline,reason:Int) {
                    if(slot==activeSlot && reason==Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !isHandover) {
                        cancelTransition()
                        if(timeline.isEmpty)pauseForReason(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                    }
                }
            })
        }
        ContextCompat.registerReceiver(appContext,noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),ContextCompat.RECEIVER_NOT_EXPORTED)
        tick=scope.launch {
            polling.collectLatest {enabled->if(enabled) {
                while(isActive && needsPolling()) {
                    delay(if(fadeDuration>0)40L else 200L)
                    if(!closed)advanceTransition()
                }
                polling.value=needsPolling()
            }}
        }
    }

    fun configure(value:AudioSettings) {
        val normalized=value.normalized()
        if(normalized.crossfadeSeconds!=settings.crossfadeSeconds)cancelTransition()
        settings=normalized;effects.configure(normalized);applyVolumes()
        if(!normalized.autoplay)finishIfEnded()
        polling.value=needsPolling()
    }

    /** Also used before a service-owned offline replacement or autoplay queue append. */
    fun cancelTransition() {
        if(closed)return
        val resumeBoundary=transitionRecovery.cancel(focusState.canPlay)
        fadeDuration=0;fadeStarted=0;targetIndex=C.INDEX_UNSET;targetId=null;preparedFor=null
        outgoingGain=1f;incomingGain=0f
        active.pauseAtEndOfMediaItems=false
        standby.pause();standby.stop()
        // The standby keeps its playlist so the next boundary can reuse it instead of
        // rebuilding the whole queue; a stale playlist is detected before it is reused.
        if(settings.crossfadeSeconds==0)standby.clearMediaItems()
        // A failed incoming decoder at the last instant must not strand the outgoing deck
        // in ExoPlayer's automatic pause-at-boundary state. User/focus pauses clear intent first.
        if(resumeBoundary)active.play()
        effects.crossfade(false);applyVolumes()
    }

    fun finishIfEnded() {
        // The queue ended by itself: that is not an environment interruption, so
        // it must not invalidate a play the user asked for (R7). A play that
        // belongs to a new selection still starts the new queue.
        if(!closed && active.playbackState==Player.STATE_ENDED)
            pauseForReason(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,false)
    }

    private fun currentKey()="${active.currentMediaItemIndex}:${active.currentMediaItem?.mediaId}"

    private fun advanceTransition() {
        val current=active
        if(fadeDuration>0) {
            // A stalled or failed target must never fade a healthy outgoing track to silence.
            if(!focusState.canPlay || !standby.isPlaying || standby.playbackState!=Player.STATE_READY ||
                current.playerError!=null ||
                (!current.isPlaying && !transitionRecovery.boundaryPaused && current.playbackState!=Player.STATE_ENDED) ||
                (current.playbackState!=Player.STATE_READY && current.playbackState!=Player.STATE_ENDED)) {
                blockedFor=currentKey();cancelTransition();return
            }
            val progress=((elapsed()-fadeStarted).toFloat()/fadeDuration).coerceIn(0f,1f)
            val gains=CrossfadePolicy.gains(progress)
            outgoingGain=gains.outgoing;incomingGain=gains.incoming;applyVolumes()
            // The fade always completes before the handover: an outgoing deck that ended
            // early must not shorten it, or the incoming deck would jump in gain.
            if(CrossfadePolicy.shouldHandOver(progress))handover()
            return
        }
        if(settings.crossfadeSeconds==0 || !focusState.canPlay || !current.isPlaying ||
            current.isCurrentMediaItemLive || current.isPlayingAd || current.duration==C.TIME_UNSET) {
            if(targetIndex!=C.INDEX_UNSET)cancelTransition()
            return
        }
        val remaining=((current.duration-current.currentPosition)/current.playbackParameters.speed).toLong()
        if(blockedFor==currentKey() || !CrossfadePolicy.shouldPrepare(settings.crossfadeSeconds,remaining))return
        val next=if(current.repeatMode==Player.REPEAT_MODE_ONE)current.currentMediaItemIndex else current.nextMediaItemIndex
        if(next==C.INDEX_UNSET)return
        if(targetIndex==C.INDEX_UNSET) {
            preparedFor=currentKey();targetIndex=next;targetId=current.getMediaItemAt(next).mediaId
            standby.volume=0f
            // The standby already holds this playlist: move its cursor instead of
            // rebuilding the whole queue at every boundary.
            val reuse=standbyCarriesSameQueue(current)
            if(!reuse) {
                val items=(0 until current.mediaItemCount).map(current::getMediaItemAt)
                standby.setMediaItems(items,next,0L)
            }
            // The shuffle mode must be set before the order: enabling it randomises the order.
            standby.shuffleModeEnabled=current.shuffleModeEnabled
            if(!standbyCarriesSameOrder(current))standby.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(shuffleTraversal(current),0L))
            standby.repeatMode=current.repeatMode
            standby.playbackParameters=current.playbackParameters
            standby.trackSelectionParameters=current.trackSelectionParameters
            if(reuse)standby.seekTo(next,0L)
            standby.pauseAtEndOfMediaItems=true;standby.prepare()
        }
        if(preparedFor!=currentKey() || targetIndex!=next || targetId!=current.getMediaItemAt(next).mediaId) {
            cancelTransition();return
        }
        if(remaining>settings.crossfadeSeconds*1000L || standby.playbackState!=Player.STATE_READY ||
            standby.playerError!=null || standby.isCurrentMediaItemLive || standby.duration==C.TIME_UNSET)return
        val targetDuration=(standby.duration/standby.playbackParameters.speed).toLong()
        val duration=CrossfadePolicy.durationMs(settings.crossfadeSeconds,remaining,targetDuration)
        if(duration==0L)return
        // Prevent an automatic transition racing the final fade tick. The new delegate will
        // resume its normal queue traversal at handover without seeking or preparing it again.
        current.pauseAtEndOfMediaItems=true
        fadeDuration=duration;fadeStarted=elapsed();standby.play();effects.crossfade(true)
    }

    /**
     * The standby may only be reused when it provably holds the same queue: the same
     * items, in the same order, with the same sources, the same shuffle traversal and the
     * same repeat mode. Anything else falls back to a full install.
     */
    private fun standbyCarriesSameQueue(current:ExoPlayer):Boolean {
        if(standby.mediaItemCount!=current.mediaItemCount)return false
        if(standby.shuffleModeEnabled!=current.shuffleModeEnabled)return false
        if(standby.repeatMode!=current.repeatMode)return false
        for(index in 0 until current.mediaItemCount) {
            val source=current.getMediaItemAt(index);val held=standby.getMediaItemAt(index)
            if(source.mediaId!=held.mediaId)return false
            if(source.localConfiguration?.uri!=held.localConfiguration?.uri)return false
            if(source.localConfiguration?.tag!=held.localConfiguration?.tag)return false
            if(source.localConfiguration?.mimeType!=held.localConfiguration?.mimeType)return false
            if(source.mediaMetadata.title!=held.mediaMetadata.title)return false
        }
        return true
    }

    /** The two decks must traverse the queue in the same order, item by item. */
    private fun standbyCarriesSameOrder(current:ExoPlayer):Boolean {
        var mine=standby.shuffleOrder.firstIndex;var theirs=current.shuffleOrder.firstIndex
        var guard=0
        while(mine!=C.INDEX_UNSET && theirs!=C.INDEX_UNSET && guard++<=current.mediaItemCount) {
            if(mine!=theirs)return false
            mine=standby.shuffleOrder.getNextIndex(mine);theirs=current.shuffleOrder.getNextIndex(theirs)
        }
        return mine==C.INDEX_UNSET && theirs==C.INDEX_UNSET
    }

    private fun handover() {
        val incoming=standby
        if(incoming.playbackState!=Player.STATE_READY || !incoming.isPlaying || incoming.currentMediaItem?.mediaId!=targetId) {
            blockedFor=currentKey();cancelTransition();return
        }
        val outgoing=active
        isHandover=true
        try {
            // Exactly one history session boundary; overlap is attributed to the displayed
            // outgoing item until here, never to both decoders simultaneously.
            onHandover(incoming.currentMediaItem!!.mediaId)
            // The gains move with the slots: neither deck's effective gain changes here.
            val moved=CrossfadePolicy.handoverGains(outgoingGain,incomingGain)
            outgoingGain=moved.outgoing;incomingGain=moved.incoming
            activeSlot=1-activeSlot
            fadeDuration=0;fadeStarted=0
            transitionRecovery.reset()
            targetIndex=C.INDEX_UNSET;targetId=null;preparedFor=null;blockedFor=null
            incoming.pauseAtEndOfMediaItems=false
            effects.active(activeSlot);effects.crossfade(false);applyVolumes()
            handoverTo(incoming)
            // The outgoing deck keeps its playlist: it becomes the next standby and can be
            // reused instead of rebuilt.
            outgoing.pause();outgoing.stop();outgoing.pauseAtEndOfMediaItems=false
        } finally {isHandover=false}
    }

    private fun applyVolumes() {
        // The equaliser headroom follows the platform effect state, never a published
        // UI snapshot that may be stale.
        val gain=audioOutputGain(userVolume,settings,effects.eqControlled,ducked)
        active.volume=(gain*outgoingGain).coerceIn(0f,1f)
        standby.volume=(gain*incomingGain).coerceIn(0f,1f)
    }

    override fun getState():State = super.getState().buildUpon()
        .setVolume(userVolume).setUnmuteVolume(unmutedVolume)
        .setPlayWhenReady(focusState.requested,focusState.reason)
        .setPlaybackSuppressionReason(if(focusState.suspended && focusState.requested)
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS else Player.PLAYBACK_SUPPRESSION_REASON_NONE).build()

    override fun handleSetPlayWhenReady(playWhenReady:Boolean):ListenableFuture<*> {
        val refusal=playRefusal(playWhenReady)
        if(refusal!=null) {
            // The gate is closed. The controller must be able to tell a refusal from a
            // success, so the command fails and the session reports the reason.
            invalidateState()
            onPlaybackRefused(refusal)
            return Futures.immediateFailedFuture<Any>(PlaybackRefusedException(refusal))
        }
        focusState.userRequest(playWhenReady)
        onUserInteraction()
        // A pause is a competing interaction: a play that is already queued must not undo it.
        if(!playWhenReady)onPlaybackInterrupted()
        cancelTransition()
        if(playWhenReady) {
            if(!focusState.hasFocus) {
                val granted=runCatching {manager.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED}.getOrDefault(false)
                focusState.requestResult(granted)
                if(!granted)onFocusDenied()
            }
            active.playWhenReady=focusState.canPlay
        } else {active.pause();abandonFocus()}
        invalidateState();return Futures.immediateVoidFuture()
    }

    private fun audioFocusChanged(change:Int) {
        if(closed)return
        when(change) {
            AudioManager.AUDIOFOCUS_GAIN->{focusState.gain();ducked=false;active.playWhenReady=focusState.canPlay;applyVolumes()}
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK->{ducked=true;applyVolumes()}
            // The environment changed, not the user's intent: the queued play must not
            // resume, but the running restore/autoplay/refresh requests stay valid.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT->{focusState.transientLoss();onPlaybackInterrupted();cancelTransition();active.pause()}
            AudioManager.AUDIOFOCUS_LOSS->pauseForReason(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        }
        invalidateState()
    }

    private fun pauseForReason(reason:Int,invalidateQueuedPlay:Boolean=true) {
        focusState.stop(reason)
        // Only an environment interruption invalidates a play that is already
        // queued: a pause that comes from the queue itself does not.
        if(invalidateQueuedPlay)onPlaybackInterrupted()
        cancelTransition();active.pause();abandonFocus();invalidateState()
    }
    private fun abandonFocus() {manager.abandonAudioFocusRequest(focusRequest);focusState.abandon();ducked=false;applyVolumes()}
    /** A transport or queue command: it never cancels a pending queue selection. */
    private fun command() {onUserInteraction();cancelTransition();blockedFor=null}

    override fun handleSetVolume(volume:Float,volumeOperationType:Int):ListenableFuture<*> {
        when(volumeOperationType) {
            C.VOLUME_OPERATION_TYPE_MUTE->{if(userVolume>0f)unmutedVolume=userVolume;userVolume=0f}
            C.VOLUME_OPERATION_TYPE_UNMUTE->userVolume=unmutedVolume
            else->{userVolume=volume.coerceIn(0f,1f);if(userVolume>0f)unmutedVolume=userVolume}
        }
        applyVolumes();invalidateState();return Futures.immediateVoidFuture()
    }
    override fun handlePrepare():ListenableFuture<*> {command();return super.handlePrepare()}
    override fun handleStop():ListenableFuture<*> {
        focusState.stop(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        command();onPlaybackInterrupted();abandonFocus();return super.handleStop()
    }
    override fun handleSeek(mediaItemIndex:Int,positionMs:Long,seekCommand:Int):ListenableFuture<*> {
        command();return super.handleSeek(mediaItemIndex,positionMs,seekCommand)
    }
    override fun handleSetMediaItems(mediaItems:List<MediaItem>,startIndex:Int,startPositionMs:Long):ListenableFuture<*> {
        command();onQueueSet(mediaItems.getOrNull(startIndex)?.mediaId.orEmpty(),startIndex)
        val future=super.handleSetMediaItems(mediaItems,startIndex,startPositionMs)
        // A closed gate means the play was refused: the queue is installed, but paused.
        if(installPaused(active.playWhenReady,canStartPlayback()))active.playWhenReady=false
        return future
    }
    override fun handleAddMediaItems(index:Int,mediaItems:List<MediaItem>):ListenableFuture<*> {
        command();return super.handleAddMediaItems(index,mediaItems)
    }
    override fun handleMoveMediaItems(fromIndex:Int,toIndex:Int,newIndex:Int):ListenableFuture<*> {
        command();return super.handleMoveMediaItems(fromIndex,toIndex,newIndex)
    }
    override fun handleReplaceMediaItems(fromIndex:Int,toIndex:Int,mediaItems:List<MediaItem>):ListenableFuture<*> {
        command();return super.handleReplaceMediaItems(fromIndex,toIndex,mediaItems)
    }
    override fun handleRemoveMediaItems(fromIndex:Int,toIndex:Int):ListenableFuture<*> {
        command();return super.handleRemoveMediaItems(fromIndex,toIndex)
    }
    override fun handleSetRepeatMode(repeatMode:Int):ListenableFuture<*> {command();return super.handleSetRepeatMode(repeatMode)}
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled:Boolean):ListenableFuture<*> {command();return super.handleSetShuffleModeEnabled(shuffleModeEnabled)}
    override fun handleSetPlaybackParameters(playbackParameters:PlaybackParameters):ListenableFuture<*> {command();return super.handleSetPlaybackParameters(playbackParameters)}
    override fun handleSetTrackSelectionParameters(trackSelectionParameters:TrackSelectionParameters):ListenableFuture<*> {command();return super.handleSetTrackSelectionParameters(trackSelectionParameters)}
    override fun handleSetAudioAttributes(audioAttributes:AudioAttributes,handleAudioFocus:Boolean):ListenableFuture<*> {
        command();standby.setAudioAttributes(audioAttributes,false);return super.handleSetAudioAttributes(audioAttributes,false)
    }
    override fun handleRelease():ListenableFuture<*> {
        if(!closed) {
            focusState.stop(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);cancelTransition();closed=true;tick.cancel()
            manager.abandonAudioFocusRequest(focusRequest)
            appContext.unregisterReceiver(noisyReceiver);effects.close();decks.forEach(ExoPlayer::release)
        }
        return Futures.immediateVoidFuture()
    }

    companion object {
        fun shuffleTraversal(player:ExoPlayer):IntArray {
            val result=mutableListOf<Int>();val order=player.shuffleOrder;var index=order.firstIndex
            while(index!=C.INDEX_UNSET && result.size<player.mediaItemCount) {result+=index;index=order.getNextIndex(index)}
            return result.toIntArray()
        }
    }
}
