package ru.nedotify.app.player

import androidx.media3.common.Player

/** Media3 event-to-checkpoint wiring, shared by the real service and its regression tests. */
internal class QueueCheckpointListener(private val checkpoint: (Boolean) -> Unit) : Player.Listener {
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) checkpoint(true)
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) checkpoint(true)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) checkpoint(false)
    }
}
