package ru.nedotify.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import android.util.AtomicFile

object LocalAudioMetadata {
    fun read(context:Context,uri:Uri,fallback:Track):Track {
        val metadata=MediaMetadataRetriever()
        return try {
            metadata.setDataSource(context,uri)
            fun text(key:Int)=localMetadataText(metadata.extractMetadata(key)).takeIf {it.isNotBlank()}
            val duration=text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf {it in 1..604_800_000}
            val mime=text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            val artwork=runCatching {metadata.embeddedPicture?.takeIf {it.size<=2*1024*1024}?.let {bytes->
                val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it.toInt() and 255)}
                val folder=File(context.filesDir,"artwork").apply {mkdirs()}
                val image=File(folder,"$hash.img")
                saveArtwork(image,bytes)
                image.toURI().toString()
            }}.getOrNull().orEmpty()
            fallback.copy(title=text(MediaMetadataRetriever.METADATA_KEY_TITLE)?:fallback.title,
                artist=text(MediaMetadataRetriever.METADATA_KEY_ARTIST)?:fallback.artist,
                album=text(MediaMetadataRetriever.METADATA_KEY_ALBUM)?:fallback.album,
                durationMs=duration?:fallback.durationMs,mimeType=mime.takeIf {it.startsWith("audio/")}?:fallback.mimeType,
                coverUrl=artwork.ifEmpty {fallback.coverUrl})
        } catch(_:Exception) {fallback}
        finally {runCatching {metadata.release()}}
    }
    @Synchronized internal fun saveArtwork(image:File,bytes:ByteArray) {
        if(image.isFile && image.length()==bytes.size.toLong())return
        // A stopped process must not leave an existing partial file which future
        // scans mistake for a valid cached cover. AtomicFile preserves the old one.
        val target=AtomicFile(image)
        var stream:java.io.FileOutputStream?=null
        try {stream=target.startWrite();stream.write(bytes);target.finishWrite(stream)}
        catch(error:Exception){target.failWrite(stream);throw error}
    }
}
