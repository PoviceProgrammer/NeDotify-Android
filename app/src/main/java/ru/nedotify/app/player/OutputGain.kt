package ru.nedotify.app.player

import ru.nedotify.app.data.AudioSettings
import ru.nedotify.app.data.equalizerHeadroom

/** An enabled-but-uncontrolled EQ must not attenuate the output; regained control needs headroom. */
internal fun audioOutputGain(volume:Float,settings:AudioSettings,eqControlled:Boolean,ducked:Boolean):Float =
    (volume.takeIf(Float::isFinite)?.coerceIn(0f,1f)?:0f) *
        (if(eqControlled)equalizerHeadroom(settings) else 1f) * (if(ducked).2f else 1f)
