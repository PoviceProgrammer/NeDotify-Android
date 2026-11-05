package ru.nedotify.app.player

import androidx.media3.common.Player

/**
 * Whether the transition is the explicit queue boundary the user asked for.
 *
 * Only the transition the *set* caused is a boundary. A set whose start item is
 * the item that already plays produces no transition at all, so the armed
 * boundary would survive it; a later, unrelated transition to the same item
 * must not be attributed to the set, or the listen would be split (P3-05).
 */
internal fun isExplicitQueueBoundary(reason:Int,itemId:String?,index:Int,
    boundaryId:String?,boundaryIndex:Int):Boolean =
    reason==Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED&&boundaryId!=null&&
    itemId==boundaryId&&index==boundaryIndex
