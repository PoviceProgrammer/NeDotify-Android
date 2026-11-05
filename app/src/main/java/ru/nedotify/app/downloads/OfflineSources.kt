package ru.nedotify.app.downloads

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

/** Completed packages have a physically network-free read path, including HLS keys/segments. */
@UnstableApi
class OfflineSources(private val online:MediaSource.Factory,private val downloads:Downloads):MediaSource.Factory by online {
    override fun createMediaSource(item:MediaItem):MediaSource {
        val asset=item.localConfiguration?.tag as? OfflineAsset?:return online.createMediaSource(item)
        val cached=downloads.storage.factory(asset.id,false)
        val local=item.buildUpon().setUri(asset.uri).setMimeType(asset.mime.ifBlank {null}).build()
        return DefaultMediaSourceFactory(cached).createMediaSource(local)
    }
}
