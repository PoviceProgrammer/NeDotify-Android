package ru.nedotify.app.downloads

import android.content.Context
import android.os.StatFs
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.nedotify.app.data.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What one attempt of a download has observed.
 *
 * The record belongs to the **attempt**, not to the process: a key of an
 * earlier attempt of the same track must not keep the bytes of an earlier
 * package alive, and it must not be counted for the package that is being
 * judged. `DownloadStorage.observedKeys()` returns a **new** record for every
 * call.
 */
class ObservedKeys {
    /** The cache keys the attempt used. */
    val keys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The URLs the attempt opened. */
    val urls: MutableSet<String> = ConcurrentHashMap.newKeySet()
}

/**
 * The resources an HLS manifest names.
 *
 * The manifest is the contract of an HLS package: the offline playback reads
 * the manifest out of the cache and then every segment the manifest names.
 * A package whose segments are not all in the cache breaks **in the middle
 * of the track**, because the offline path has no upstream to fall back to.
 *
 * @param manifests the URLs of the manifests themselves.
 * @param segments the URLs of the audio segments, resolved the way Media3
 *   resolves them (`UriUtil.resolveToUri` against the manifest's base URI).
 *   Two segments of two variants may share a URL: the list holds the
 *   **distinct** resources of the package, because the package holds
 *   resources, not names.
 */
data class HlsPackage(val manifests: List<String>, val segments: List<String>)

@UnstableApi
class DownloadStorage(private val context:Context,private val network:MusicNetwork,database:DatabaseProvider) {
    // Permanent app files, no LRU and no overlap with the disposable streaming cache.
    // The packages are the user's music: an evictor would delete them.
    val cache=SimpleCache(File(context.filesDir,"offline-media"),NoOpCacheEvictor(),database)
    /**
     * The data source of a package.
     *
     * The key factory is **pure**: it computes the key of a resource and
     * nothing else. It is called for every segment of an HLS stream, on the
     * loader thread, and a cache read there is a disk read in the middle of
     * a download.
     */
    fun factory(id:String,online:Boolean,observed:ObservedKeys?=null):CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setCacheKeyFactory {spec->
            val url=spec.uri.toString()
            observed?.urls?.add(url)
            downloadCacheKey(id,url).also {observed?.keys?.add(it)}
        }
        .setUpstreamDataSourceFactory(if(online)OkHttpDataSource.Factory(network.mediaHttp) else null)
        .setFlags(if(online)CacheDataSource.FLAG_BLOCK_ON_CACHE else 0)
    /** The bytes of one resource of the package. */
    fun cachedBytes(key:String):Long=cache.getCachedBytes(key,0,C.LENGTH_UNSET.toLong())
    /**
     * The free space of the volume the packages are stored on.
     *
     * `null` when the volume cannot be read: the probe then **fails closed**
     * — a download that fills the disk takes the whole app down with it, and
     * a probe that pretends there is room is exactly the wrong default.
     */
    fun freeBytes():Long?=runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull()
    /**
     * The manifest of the stream must be a media resource of a known source.
     *
     * The manifest is read through the media client, so the same host
     * allow-list applies to it: a provider cannot point the download at a
     * manifest on a host of its own choosing.
     *
     * The manifest also names the segments of the package: they are returned
     * so that the completeness of the package can be judged **before** it is
     * published as a finished track.
     */
    suspend fun verifyVod(url:String):HlsPackage=withContext(Dispatchers.IO) {
        withTimeout(45_000) {
            suspend fun parse(uri:String):HlsPlaylist {
                val parsed=uri.toHttpUrlOrNull()
                if(parsed==null || MediaUrlPolicy.sourceFor(parsed)==null)
                    throw SourceFailure("Источник вернул неподдерживаемый адрес аудио")
                // The general metadata client reads unrestricted response bodies;
                // a manifest must be bounded before its parser allocates lines.
                val text=ProviderHttp(network.mediaHttp).text(okhttp3.Request.Builder().url(uri).build(),2*1024*1024)
                return HlsPlaylistParser().parse(uri.toUri(),ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            }
            fun validate(playlist:HlsPlaylist) {
                if(playlist !is HlsMediaPlaylist || !playlist.hasEndTag)throw SourceFailure(streamVerdictMessage(StreamVerdict.Live))
                if(playlist.segments.isEmpty())throw SourceFailure(streamVerdictMessage(StreamVerdict.Empty))
                if(playlist.protectionSchemes!=null)throw SourceFailure(streamVerdictMessage(StreamVerdict.DrmProtected))
            }
            val root=parse(url)
            val manifests=mutableListOf(url)
            val segments=mutableListOf<String>()
            if(root is HlsMultivariantPlaylist) {
                if(root.mediaPlaylistUrls.isEmpty())throw SourceFailure("В записи нет аудиопотоков")
                if(root.mediaPlaylistUrls.size>16)throw SourceFailure("Слишком много вариантов HLS для загрузки")
                for(uri in root.mediaPlaylistUrls) {
                    val media=parse(uri.toString())
                    validate(media)
                    manifests+=uri.toString()
                    segments+=segmentUrls(media)
                }
            } else {
                validate(root)
                segments+=segmentUrls(root)
            }
            HlsPackage(manifests,segments.distinct())
        }
    }
    /**
     * The URLs of the segments of a media playlist.
     *
     * The resolution is the one Media3 uses (`UriUtil.resolveToUri` against
     * the playlist's base URI), so that the key of a segment is the key the
     * downloader used for it.
     */
    private fun segmentUrls(playlist:HlsPlaylist):List<String> {
        if(playlist !is HlsMediaPlaylist)return emptyList()
        val base=playlist.baseUri
        return playlist.segments.map {UriUtil.resolveToUri(base,it.url).toString()}
    }
    fun downloader(id:String,url:String,mime:String,observed:ObservedKeys):Downloader {
        val item=MediaItem.Builder().setUri(url).setMimeType(mime.ifBlank {null}).build()
        val factory=factory(id,true,observed)
        return if(mime.equals("application/x-mpegURL",true))HlsDownloader.Factory(factory).create(item)
            else ProgressiveDownloader(item,factory,Runnable::run)
    }
    /** Refresh cached manifests before resume: old segment signatures must not survive a new URL. */
    fun refreshManifests(id:String,root:String) {
        val rootKey=downloadCacheKey(id,root)
        for(key in keys(id)) {
            val first=cache.getCachedSpans(key).firstOrNull {it.position==0L}
            val manifest=first?.file?.let {file->runCatching {
                file.inputStream().use {input->val marker=ByteArray(7);input.read(marker)==7 && String(marker,Charsets.US_ASCII)=="#EXTM3U"}
            }.getOrDefault(false)}?:false
            if(key==rootKey || manifest)cache.removeResource(key)
        }
    }
    fun keys(id:String):List<String> {val prefix=downloadPrefix(id);return cache.keys.filter {it.startsWith(prefix)}}
    fun bytes(id:String)=keys(id).sumOf {key->cache.getCachedSpans(key).sumOf {it.length}}
    fun remove(id:String) {keys(id).forEach(cache::removeResource)}
    /**
     * Remove the resources of the track that are not part of the package.
     *
     * The keep list is the attempt's own resources **and** the resources the
     * manifest names. A segment that the downloader found already complete is
     * not downloaded again and is therefore not in the attempt's list — it is
     * named by the manifest, and it must survive the prune, or the package
     * loses a segment that the manifest still names.
     */
    fun prune(id:String,keep:Set<String>) {keys(id).filterNot {it in keep}.forEach(cache::removeResource)}
    companion object {
        /**
         * The record of one attempt.
         *
         * A **new** record for every call: the record of an attempt must not
         * be shared with the next one, or the bytes of an earlier package
         * would be counted for the package that is being judged.
         */
        fun observedKeys():ObservedKeys=ObservedKeys()
    }
}

/** Cancellation must stop the downloader before the next generation touches the same namespace. */
@UnstableApi
suspend fun awaitDownload(downloader:Downloader,progress:Downloader.ProgressListener)=coroutineScope {
    val running=async(Dispatchers.IO){runInterruptible {downloader.download(progress)}}
    try {running.await();ensureActive()}
    finally {downloader.cancel();withContext(NonCancellable){running.join()}}
}

data class OfflineAsset(val id:String,val uri:String,val mime:String,val generation:String)
