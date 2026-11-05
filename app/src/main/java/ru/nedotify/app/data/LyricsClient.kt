package ru.nedotify.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class LyricsClient(private val network:MusicNetwork,private val now:()->Long=System::currentTimeMillis,
    private val endpoint:String="https://lrclib.net/api/get",private val disk:LyricsDiskCache?=null,
    private val fallback:(suspend (Track,LyricsResult)->LyricsResult)?=null) {
    private data class Entry(val result:LyricsResult,val expiresAt:Long)
    private val lock=Mutex()
    private val cache=LinkedHashMap<String,Entry>(20,.75f,true)
    private fun key(track:Track)=listOf("lyrics-v2",track.id,track.title,track.artist,track.album,(track.durationMs/1000).toString()).joinToString("\u0000")
    suspend fun cached(track:Track):LyricsResult?=withContext(Dispatchers.IO) {lock.withLock {
        val key=key(track)
        cache[key]?.takeIf {it.expiresAt>now()}?.result?.let {return@withLock it}
        cache.remove(key)
        disk?.read(key,now())?.let {(result,expiry)->
            cache[key]=Entry(result,expiry);while(cache.size>20)cache.remove(cache.keys.first());result
        }
    }}
    suspend fun lyrics(track:Track,forceRefresh:Boolean=false):LyricsResult = withContext(Dispatchers.IO) {
        val previous=cached(track)
        if(!forceRefresh && previous!=null)return@withContext previous
        val lookup=LyricsIdentity.queryTrack(track)
        val url=endpoint.toHttpUrl().newBuilder().addQueryParameter("track_name",lookup.title)
            .addQueryParameter("artist_name",lookup.artist)
        if(lookup.album.isNotBlank())url.addQueryParameter("album_name",lookup.album)
        if(lookup.durationMs>0)url.addQueryParameter("duration",(lookup.durationMs/1000).toString())
        var primaryError:Exception?=null
        var initial=LyricsResult()
        try {
            val raw=if(fallback==null)network.get(url.build().toString()) else withTimeout(3_500) {network.get(url.build().toString())}
            val result=sourceObject(raw)
            // Malformed synced text must not discard a separately valid plain lyric.
            val plain=result.optText("plainLyrics").takeIf {it.length<=256_000}.orEmpty()
            val synced=result.optText("syncedLyrics").takeIf {it.length<=256_000}.orEmpty()
            if(result.optText("syncedLyrics").length>256_000 || result.optText("plainLyrics").length>256_000)
                primaryError=SourceFailure("Источник вернул слишком большой текст")
            val decoded=if(result.has("trackName") || result.has("artistName")) LyricsIdentity.accept(lookup,
                result.optText("trackName"),result.optText("artistName"),(result.optDouble("duration",0.0)*1000).toLong(),
                synced,plain,result.opt("instrumental")==true) ?: LyricsResult()
            else LyricsResult(plain,runCatching {parseLrc(synced)}.getOrDefault(emptyList()),result.opt("instrumental")==true)
            initial=if(decoded.hasContent())decoded.copy(source="LRCLIB") else decoded
        } catch(error:TimeoutCancellationException) {currentCoroutineContext().ensureActive();primaryError=error}
        catch(error:CancellationException) {throw error}
        catch(error:SourceFailure) {if(error.httpCode!=404)primaryError=error}
        catch(error:Exception) {primaryError=error}
        val content=try {
            if(fallback!=null && initial.lines.isEmpty() && !initial.instrumental)fallback.invoke(lookup,initial) else initial
        } catch(error:CancellationException) {throw error}
        catch(error:Exception) {if(initial.hasContent())initial else throw error}
        if(!content.hasContent() && primaryError!=null)throw primaryError
        // A refresh is an upgrade attempt. A miss must never replace an offline hit.
        if(!content.hasContent() && previous?.hasContent()==true)return@withContext previous
        lock.withLock {
            val expires=now()+if(content.hasContent())86_400_000 else 600_000
            cache[key(track)]=Entry(content,expires)
            disk?.write(key(track),content,expires)
            while(cache.size>20)cache.remove(cache.keys.first())
        }
        content
    }
}

internal fun LyricsResult.hasContent():Boolean = instrumental || lines.isNotEmpty() || plain.isNotBlank()
