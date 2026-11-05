package ru.nedotify.app.player

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

/**
 * The media button and the notification's play button never reach
 * `onPlayerCommandRequest`: they call the player directly. Such a play is a
 * fresh request and the gate must never refuse it.
 */
class MediaButtonPlayTest {
    @Test fun `the keys that produce a play are fresh requests`() {
        assertTrue(isFreshPlayKeyCode(KeyEvent.KEYCODE_HEADSETHOOK))
        assertTrue(isFreshPlayKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(isFreshPlayKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(isFreshPlayKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE))
    }
    @Test fun `the navigation keys are not play requests`() {
        assertFalse(isFreshPlayKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertFalse(isFreshPlayKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertFalse(isFreshPlayKeyCode(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertFalse(isFreshPlayKeyCode(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertFalse(isFreshPlayKeyCode(KeyEvent.KEYCODE_MEDIA_STOP))
        assertFalse(isFreshPlayKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
