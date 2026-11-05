package ru.nedotify.app.player

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import ru.nedotify.app.data.OfflineDownload
import ru.nedotify.app.data.Track
import ru.nedotify.app.downloads.OfflineAsset

/**
 * The data of one queue item, before it becomes a MediaItem.
 *
 * A MediaItem without a URI makes DefaultMediaSourceFactory throw
 * (Preconditions.checkNotNull(mediaItem.localConfiguration)), so every item that
 * enters the live queue is built here — the service and the view model share it.
 */
data class QueueItemData(
    val mediaId:String,
    val uri:String,
    val mimeType:String?,
    val tag:OfflineAsset?,
    val title:String,
    val artist:String,
    val album:String,
    val artwork:String,
    val durationMs:Long=0,
)

/**
 * The item of a track: the downloaded file when the download is **complete**, the
 * track's own file when it has one, otherwise the resolver's scheme.
 *
 * The factory is the single choke point of the live queue, so it owns the rule
 * that only a finished download may become the offline item: a download in the
 * `preparing` state already carries a URI, and playing it would fail.
 */
fun queueItemData(track:Track,saved:OfflineDownload?=null):QueueItemData {
    val asset=saved?.takeIf {it.state=="complete"&&it.uri.isNotBlank()}
        ?.let {OfflineAsset(track.id,it.uri,it.mimeType,it.generation)}
    val uri=track.localUri.takeIf {it.isNotBlank()}?:queueUri(track.id,asset!=null)
    return QueueItemData(track.id,uri,(asset?.mime?:track.mimeType).takeIf {it.isNotBlank()},asset,
        track.title,track.artist,track.album,track.coverUrl,track.durationMs)
}

/** The scheme the resolver understands; the track id is the last path segment. */
internal fun queueUri(trackId:String,offline:Boolean):String =
    (if(offline)"nedotify-offline" else "nedotify")+"://track/"+encodePathSegment(trackId)

/** Percent-encoding of one path segment, matching android.net.Uri.encode. */
internal fun encodePathSegment(value:String):String = buildString {
    for(byte in value.toByteArray(Charsets.UTF_8)) {
        val code=byte.toInt() and 0xFF
        val char=code.toChar()
        if(code<0x80 && (char.isLetterOrDigit()||char in "-_.!~*'()"))append(char)
        else append('%').append("%02X".format(code))
    }
}

/** The live queue item of a track. It always carries a URI, the offline tag and the metadata. */
fun trackMediaItem(track:Track,saved:OfflineDownload?=null):MediaItem = queueItemData(track,saved).toMediaItem()

private fun QueueItemData.toMediaItem():MediaItem = MediaItem.Builder()
    .setMediaId(mediaId).setUri(uri.toUri()).setTag(tag).setMimeType(mimeType)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setDurationMs(durationMs.takeIf {it>0})
        .setArtworkUri(artwork.takeIf {it.isNotBlank()}?.toUri()).build()).build()
