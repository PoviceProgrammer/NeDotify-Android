package ru.nedotify.app.player

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Stable listener/controller identity across decoder handover; never releases the old decoder. */
@UnstableApi
internal open class SessionPlayerBridge(first:Player):ForwardingSimpleBasePlayer(first) {
    internal fun handoverTo(preparedPlayer:Player) {setPlayer(preparedPlayer)}
}
