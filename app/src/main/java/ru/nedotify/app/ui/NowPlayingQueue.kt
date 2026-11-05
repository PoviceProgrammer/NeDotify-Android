package ru.nedotify.app.ui

import androidx.media3.common.MediaItem

/**
 * The row of a queue item, reusing the previous row when nothing changed: the
 * now-playing mirror's equality check then compares instances, not strings.
 */
internal fun queueRow(media:MediaItem,old:QueueRow?):QueueRow {
    val metadata=media.mediaMetadata
    val title=metadata.title?.toString().orEmpty()
    val artist=metadata.artist?.toString().orEmpty()
    val artwork=metadata.artworkUri?.toString().orEmpty()
    val duration=metadata.durationMs?.coerceAtLeast(0)?:0
    return if(old!=null&&old.id==media.mediaId&&old.title==title&&old.artist==artist&&old.artwork==artwork&&old.durationMs==duration)old
        else QueueRow(media.mediaId,title,artist,artwork,duration)
}
