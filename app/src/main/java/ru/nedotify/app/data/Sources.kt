package ru.nedotify.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class SourceFailure(message: String,val httpCode:Int?=null): IOException(message)
/**
 * The app's two HTTP clients.
 *
 * `http` is the metadata client: a small document that must arrive quickly,
 * so it carries a 30 s **total** call budget. `mediaHttp` is the media
 * client: the audio itself, which Media3 reads through a blocking
 * `Call.execute()`. A total budget would bound the transfer and cut a track
 * off in the middle, so the media client has none; it is bounded by its read
 * timeout, by the URL policy and by the download's own watchdog instead.
 */
class MusicNetwork(val http:OkHttpClient=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build(),
    val mediaHttp:OkHttpClient=MediaHttp.client(http)) {
    private val noRedirects by lazy {http.newBuilder().followRedirects(false).followSslRedirects(false).build()}
    private suspend fun request(request:Request,maxBytes:Int=4*1024*1024,client:OkHttpClient=http):String=suspendCancellableCoroutine { continuation ->
        val call=client.newCall(request)
        continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) {if(continuation.isActive)continuation.resumeWithException(e)}
            override fun onResponse(call:Call,response:Response) {
                val result=runCatching {response.use {
                    if(!it.isSuccessful)throw SourceFailure("Источник ответил HTTP ${it.code}",it.code)
                    val body=it.body?:throw SourceFailure("Источник вернул пустой ответ")
                    if(body.contentLength()>maxBytes)throw SourceFailure("Ответ источника слишком большой")
                    val bytes=java.io.ByteArrayOutputStream()
                    body.byteStream().use {input->val buffer=ByteArray(8192)
                        while(true) {val count=input.read(buffer);if(count<0)break
                            if(bytes.size().toLong()+count>maxBytes)throw SourceFailure("Ответ источника слишком большой")
                            bytes.write(buffer,0,count)
                        }
                    }
                    bytes.toString(Charsets.UTF_8.name())
                }}
                if(continuation.isActive)result.fold(continuation::resume,continuation::resumeWithException)
            }
        })
    }
    suspend fun get(url:String,headers:Map<String,String> = emptyMap(),maxBytes:Int=2*1024*1024):String=request(Request.Builder().url(url)
        .header("User-Agent",MediaUrlPolicy.USER_AGENT)
        .apply {headers.forEach {(key,value)->header(key,value)}}.build(),maxBytes)
    /** Used after validating a lyrics page's origin; a redirect must not escape that check. */
    suspend fun getWithoutRedirects(url:String,maxBytes:Int=2*1024*1024):String = request(Request.Builder().url(url)
        .header("User-Agent",MediaUrlPolicy.USER_AGENT).build(),maxBytes,noRedirects)
    suspend fun post(url:String,json:JSONObject):JSONObject=withContext(Dispatchers.IO) {
        // One agent for the whole provider session: the metadata call and the
        // media call must not look like two different clients.
        sourceObject(request(Request.Builder().url(url).header("User-Agent",MediaUrlPolicy.USER_AGENT)
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()))
    }
}

fun JSONArray.objects():List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private data class SearchCacheEntry(val tracks:List<Track>,val expiresAt:Long)

class SoundCloudClient(private val network:MusicNetwork,private val scope:CoroutineScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)) {
    private val lock=Mutex()
    private val searchCache=LinkedHashMap<String,SearchCacheEntry>(20,.75f,true)
    private var clientId:String?=null
    private data class Pending(val token:Any,val task:Deferred<String>)
    private var pending:Pending?=null
    suspend fun cancelPending() {val old=lock.withLock {pending.also {pending=null}};old?.task?.cancel()}
    private suspend fun id():String {
        val task=lock.withLock {
            clientId?.let {return it}
            pending?.task?:run {
                val token=Any()
                scope.async(start=CoroutineStart.LAZY) {
                    try {
                        val value=withTimeout(12_000){scrapeId()}
                        lock.withLock {if(pending?.token===token)clientId=value}
                        value
                    } catch(_:TimeoutCancellationException){throw SourceFailure("SoundCloud не ответил вовремя. Повторите попытку")}
                    finally {withContext(NonCancellable){lock.withLock {if(pending?.token===token)pending=null}}}
                }.also {pending=Pending(token,it);it.start()}
            }
        }
        return task.await()
    }
    private suspend fun scrapeId():String {
        val html=network.get("https://soundcloud.com/")
        val scripts=Regex("https://[A-Za-z0-9.-]+\\.sndcdn\\.com/assets/[^\"' ]+\\.js").findAll(html).map { it.value }.toList().takeLast(3).reversed()
        for(url in scripts) {
            val body=network.get(url,maxBytes=8*1024*1024)
            val match=Regex("client_id[:=]\\s*\"([A-Za-z0-9]{32})\"").find(body)
            if(match!=null)return match.groupValues[1]
        }
        throw SourceFailure("SoundCloud изменил API. Попробуйте другой источник или локальный файл")
    }
    private suspend fun api(path:String,params:Map<String,String> = emptyMap()):JSONObject {
        repeat(2) { attempt ->
            val requestedId=id()
            val url=("https://api-v2.soundcloud.com/"+path).toHttpUrl().newBuilder().addQueryParameter("client_id",requestedId)
            params.forEach { (key,value)->url.addQueryParameter(key,value) }
            try { return sourceObject(network.get(url.build().toString())) }
            catch(error:SourceFailure) { if(attempt==0 && (error.message?.contains("401")==true || error.message?.contains("403")==true)) lock.withLock { if(clientId==requestedId)clientId=null } else throw error }
        }
        throw SourceFailure("SoundCloud недоступен")
    }
    suspend fun search(query:String):List<Track> = withContext(Dispatchers.IO) {
        val key=query.trim().take(200).lowercase(Locale.ROOT)
        lock.withLock {searchCache[key]?.takeIf {it.expiresAt>System.currentTimeMillis()}?.tracks}?.let {return@withContext it}
        val tracks=api("search/tracks",mapOf("q" to query.trim().take(200),"limit" to "25"))
        .optJSONArray("collection")?.objects().orEmpty().mapNotNull { item ->
            val id=item.optText("id")
            if(id.isBlank() || !item.optBoolean("streamable",false)) null else Track("soundcloud:$id",item.optText("title"),
                item.optJSONObject("user")?.optText("username")?:"SoundCloud",durationMs=item.optLong("duration"),source="soundcloud",sourceId=id,
                sourceUrl=item.optText("permalink_url"),coverUrl=item.optText("artwork_url").takeUnless { it=="null" }.orEmpty(),
                mimeType=if(item.optJSONObject("media")?.optJSONArray("transcodings")?.objects().orEmpty().any { it.optJSONObject("format")?.optText("protocol")=="progressive" }) "" else "application/x-mpegURL")
        }
        lock.withLock {
            searchCache[key]=SearchCacheEntry(tracks,System.currentTimeMillis()+300_000)
            while(searchCache.size>300)searchCache.remove(searchCache.keys.first())
        }
        tracks
    }
    suspend fun resolve(track:Track,quality:String="high"):ResolvedStream = withContext(Dispatchers.IO) {
        val data=api("tracks/${track.sourceId}")
        val transcodings=data.optJSONObject("media")?.optJSONArray("transcodings")?.objects().orEmpty()
        val transcoding=soundCloudTranscoding(transcodings,track.mimeType,quality)
        val endpoint=transcoding.getString("url").toHttpUrl().newBuilder().addQueryParameter("client_id",id()).build()
        val url=sourceObject(network.get(endpoint.toString())).optText("url")
        if(!url.startsWith("https://")) throw SourceFailure("Источник не предоставил безопасный поток")
        val hls=transcoding.optJSONObject("format")?.optText("protocol")=="hls"
        ResolvedStream(url,if(hls)"application/x-mpegURL" else null,streamExpiry(url))
    }
}

internal fun soundCloudTranscoding(items:List<JSONObject>,mimeType:String,quality:String="high"):JSONObject {
    // Media3 chooses HLS vs progressive before DataSource.open. Keep that choice valid
    // when the provider changes its list of formats between search and playback.
    val protocol=if(mimeType.equals("application/x-mpegURL",true))"hls" else "progressive"
    val compatible=items.filter {
        val preset=it.optText("preset").lowercase(Locale.ROOT)
        it.optJSONObject("format")?.optText("protocol")==protocol &&
            !preset.contains("snip") && !preset.startsWith("ctr-") && !preset.startsWith("cbc-") &&
            !it.optBoolean("snipped",false)
    }
    return (if(quality=="high")compatible.firstOrNull {it.optText("quality")=="hq"}
        else compatible.firstOrNull {it.optText("quality","sq")=="sq"})?:compatible.firstOrNull()
        ?:throw SourceFailure(soundCloudFormatFailure(items))
}

/**
 * Why no format of the track can be played.
 *
 * A track whose only formats are common-encryption ones is not a "changed
 * format": it is protected, and the app cannot decode it. The message says
 * so instead of sending the user back to the search.
 */
internal fun soundCloudFormatFailure(items:List<JSONObject>):String {
    val drm=items.isNotEmpty() && items.all {soundCloudDrmFormat(it)}
    return if(drm)"Трек защищён DRM и не может быть воспроизведён"
        else "SoundCloud изменил формат записи. Найдите трек в поиске заново"
}

/** The desktop's rule: a `ctr-`/`cbc-` protocol means common-encryption media. */
internal fun soundCloudDrmFormat(item:JSONObject):Boolean {
    val protocol=item.optJSONObject("format")?.optText("protocol")?.lowercase(Locale.ROOT).orEmpty()
    return protocol.startsWith("ctr-") || protocol.startsWith("cbc-")
}

/** Experimental internal API: playability is checked; no anonymous playback guarantee. */
class InnerTubeClient(private val network:MusicNetwork) {
    private val lock=Mutex()
    private val searchCache=LinkedHashMap<String,SearchCacheEntry>(20,.75f,true)
    private fun context()=JSONObject().put("client",JSONObject().put("clientName","ANDROID_MUSIC")
        .put("clientVersion","7.27.52").put("androidSdkVersion",30).put("hl","ru").put("gl","US"))
    suspend fun search(query:String):List<Track> = withContext(Dispatchers.IO) {
        val key=query.trim().take(200).lowercase(Locale.ROOT)
        lock.withLock {searchCache[key]?.takeIf {it.expiresAt>System.currentTimeMillis()}?.tracks}?.let {return@withContext it}
        val searchContext=JSONObject().put("client",JSONObject().put("clientName","WEB_REMIX")
            .put("clientVersion","1.20261014.01.00").put("hl","ru").put("gl","US"))
        val data=network.post("https://music.youtube.com/youtubei/v1/search",JSONObject().put("context",searchContext).put("query",query))
        val tracks=linkedMapOf<String,Track>()
        var visited=0
        fun visit(value:Any?,depth:Int=0) {
            if(depth>40 || visited++>50_000)return
            when(value) {
                is JSONObject -> {
                    val renderer=value.optJSONObject("musicResponsiveListItemRenderer")
                    if(renderer!=null) {
                        val id=renderer.optJSONObject("playlistItemData")?.optText("videoId")?.takeIf {it.isNotBlank()}
                            ?:renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optText("videoId").orEmpty()
                        val columns=renderer.optJSONArray("flexColumns")?.objects().orEmpty()
                        fun runs(index:Int)=columns.getOrNull(index)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")?.optJSONArray("runs")?.objects().orEmpty()
                        val title=runs(0).joinToString("") {it.optText("text")}
                        val artist=youtubeArtist(runs(1))
                        val thumbs=renderer.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.objects()
                        if(id.matches(Regex("[A-Za-z0-9_-]{11}")) && title.isNotBlank()) tracks[id]=Track("youtube:$id",title,artist,source="youtube",sourceId=id,
                            sourceUrl="https://music.youtube.com/watch?v=$id",coverUrl=thumbs?.lastOrNull()?.optText("url").orEmpty())
                    }
                    value.keys().forEach { visit(value.opt(it),depth+1) }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it),depth+1) }
            }
        }
        visit(data)
        tracks.values.take(30).also {result->lock.withLock {
            searchCache[key]=SearchCacheEntry(result,System.currentTimeMillis()+300_000)
            while(searchCache.size>300)searchCache.remove(searchCache.keys.first())
        }}
    }
    suspend fun resolve(track:Track,quality:String="high"):ResolvedStream = withContext(Dispatchers.IO) {
        val data=network.post("https://music.youtube.com/youtubei/v1/player",JSONObject().put("context",context()).put("videoId",track.sourceId))
        val status=data.optJSONObject("playabilityStatus")
        if(status?.optText("status")!="OK") throw SourceFailure("YouTube: ${status?.optText("reason")?.take(160)?:"запись недоступна"}")
        val formats=data.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")?.objects().orEmpty()
            .filter { it.optText("mimeType").startsWith("audio/") && it.optText("url").startsWith("https://") &&
                it.optJSONArray("drmFamilies")?.length().let {count->count==null || count==0} }
        fun bitrate(value:JSONObject)=value.optInt("averageBitrate",value.optInt("bitrate",128_000)).coerceAtLeast(1)
        val format=when(quality) {
            "dataSaver"->formats.minByOrNull(::bitrate)
            "standard"->formats.minByOrNull {kotlin.math.abs(bitrate(it)-128_000)}
            else->formats.maxByOrNull(::bitrate)
        }?:throw SourceFailure("YouTube не предоставил прямой поток. Выберите другой источник")
        val url=format.getString("url")
        ResolvedStream(url,format.optText("mimeType").substringBefore(';'),streamExpiry(url))
    }
}

internal fun youtubeArtist(runs:List<JSONObject>):String {
    val artists=runs.indices.filter {index->
        val browse=runs[index].optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        browse?.optText("browseId")?.startsWith("UC")==true || browse?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")?.optText("pageType")=="MUSIC_PAGE_TYPE_ARTIST"
    }
    val selected=if(artists.isNotEmpty())runs.subList(artists.first(),artists.last()+1) else {
        val divider=runs.indexOfFirst {it.optText("text").trim() in setOf("•","·")}
        if(divider==1 && runs.first().optText("text").lowercase(Locale.ROOT) in setOf("композиция","песня","видео","song","video"))runs.drop(2) else runs
    }
    return selected.joinToString("") {it.optText("text")}.substringBefore(" • ").trim().ifBlank {"Неизвестный исполнитель"}
}

