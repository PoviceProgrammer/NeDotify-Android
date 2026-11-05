package ru.nedotify.app.player

import android.view.KeyEvent

/**
 * The key codes Media3 1.11.1 turns into a play or a pause
 * (`MediaSessionImpl.applyMediaButtonKeyEvent`): the headset hook, the
 * play/pause key, the play key and the pause key.
 *
 * A media button that carries one of them reaches the player directly and
 * never goes through `onPlayerCommandRequest`, so such a play is a **fresh**
 * request: the gate must not refuse it.
 */
internal fun isFreshPlayKeyCode(keyCode:Int):Boolean =
    keyCode==KeyEvent.KEYCODE_HEADSETHOOK||keyCode==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||
    keyCode==KeyEvent.KEYCODE_MEDIA_PLAY||keyCode==KeyEvent.KEYCODE_MEDIA_PAUSE
