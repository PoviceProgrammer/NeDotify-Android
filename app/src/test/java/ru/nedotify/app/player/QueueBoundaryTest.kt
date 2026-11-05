package ru.nedotify.app.player

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

/**
 * The explicit queue boundary (P3-05): only the transition the *set* caused is
 * a boundary. A set whose start item is the item that already plays produces
 * no transition, so the armed boundary survives it; a later, unrelated
 * transition to the same item must not be attributed to the set.
 */
class QueueBoundaryTest {
    @Test fun `the transition the set caused is the boundary`() {
        assertTrue(isExplicitQueueBoundary(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,"a",0,"a",0))
    }
    @Test fun `an unrelated transition to the same item is not a boundary`() {
        // A seek or an automatic advance to the item the boundary names: the
        // listen must not be split.
        assertFalse(isExplicitQueueBoundary(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,"a",0,"a",0))
        assertFalse(isExplicitQueueBoundary(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,"a",0,"a",0))
        assertFalse(isExplicitQueueBoundary(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,"a",0,"a",0))
    }
    @Test fun `another item is not the boundary`() {
        assertFalse(isExplicitQueueBoundary(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,"b",0,"a",0))
        assertFalse(isExplicitQueueBoundary(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,"a",1,"a",0))
    }
    @Test fun `an unarmed boundary is never a boundary`() {
        assertFalse(isExplicitQueueBoundary(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,"a",0,null,-1))
    }
}
