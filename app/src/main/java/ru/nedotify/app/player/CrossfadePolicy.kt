package ru.nedotify.app.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class CrossfadeGains(val outgoing:Float,val incoming:Float)

/** Tracks ExoPlayer's automatic pause at the outgoing boundary separately from a user pause. */
internal class CrossfadeRecovery {
    var boundaryPaused=false
        private set
    fun boundaryPause() {boundaryPaused=true}
    fun cancel(canPlay:Boolean):Boolean {
        val resume=boundaryPaused && canPlay
        boundaryPaused=false
        return resume
    }
    fun reset() {boundaryPaused=false}
}

object CrossfadePolicy {
    fun gains(progress:Float):CrossfadeGains {
        val value=progress.takeIf(Float::isFinite)?.coerceIn(0f,1f)?:0f
        return CrossfadeGains(cos(value*PI/2).toFloat().coerceIn(0f,1f),sin(value*PI/2).toFloat().coerceIn(0f,1f))
    }
    /**
     * The handover happens at the end of the fade and never earlier: an outgoing deck
     * that ended early must not shorten the fade, or the incoming deck would jump in gain.
     */
    fun shouldHandOver(progress:Float):Boolean = progress>=1f
    /**
     * The gains move with the slots: the handover must not change the effective gain of
     * either deck, on any path.
     */
    fun handoverGains(outgoing:Float,incoming:Float):CrossfadeGains = CrossfadeGains(incoming,outgoing)
    fun durationMs(seconds:Int,remainingMs:Long,incomingDurationMs:Long):Long {
        if(seconds<=0 || remainingMs<600 || incomingDurationMs<1200)return 0
        // Complete before the outgoing ExoPlayer naturally advances its identical timeline.
        return minOf(seconds.coerceIn(0,12)*1000L,remainingMs-150,incomingDurationMs/2).takeIf {it>=400}?:0
    }
    fun shouldPrepare(seconds:Int,remainingMs:Long)=seconds>0 && remainingMs in 600..(seconds.coerceIn(0,12)*1000L+8000)
}
