package ru.nedotify.app.data

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

data class CatalogTrack(val provider:String,val id:String,val title:String,val artists:List<String>,
    val album:String="",val durationMs:Long=0,val coverUrl:String="",val pageUrl:String="") {
    val key:String get()="$provider:$id"
    val artist:String get()=CatalogParsers.text(artists.joinToString(", "),2048)
}
data class CatalogPlaylist(val provider:String,val id:String,val title:String,val count:Int,
    val coverUrl:String="",val pageUrl:String="") {val key:String get()="$provider:$id"}

object CatalogParsers {
    /** Refuse a truncated account list instead of reporting a successful partial import. */
    internal fun completeIds(root:JSONObject,label:String):List<String> {
        val rows=root.optJSONArray("tracks")?:throw SourceFailure("Яндекс Музыка не вернула $label")
        if(rows.length()>1000)throw SourceFailure("Список слишком большой: поддерживается до 1000 треков")
        val declared=listOf("trackCount","total","totalCount").mapNotNull {(root.opt(it) as? Number)?.toLong()}.maxOrNull()
        if(declared!=null && declared!=rows.length().toLong() || root.optBoolean("hasMore") ||
            root.optText("next").isNotBlank() || root.optText("nextCursor").isNotBlank())
            throw SourceFailure("Яндекс Музыка вернула неполный список. Повторите позже")
        return rows.objects().map {it.optText("id")}.also {ids->
            if(ids.size!=rows.length() || ids.any {!it.matches(Regex("[0-9]{1,24}"))})
                throw SourceFailure("Яндекс Музыка вернула неполный список треков")
        }
    }
    internal fun text(raw:String,limit:Int):String {
        val output=StringBuilder();var index=0
        while(index<raw.length) {
            val point=Character.codePointAt(raw,index);val count=Character.charCount(point)
            index+=count
            if(point in 0xD800..0xDFFF || Character.isISOControl(point))continue
            if(output.length+count>limit)break
            output.appendCodePoint(point)
        }
        return output.toString().trim()
    }
    fun spotifyTrack(item:JSONObject):CatalogTrack? {
        if(item.optBoolean("is_local") || item.optText("type","track")!="track")return null
        val id=item.optText("id").takeIf {it.matches(Regex("[A-Za-z0-9]{22}"))}?:return null
        val title=text(item.optText("name"),500).takeIf {it.isNotBlank()}?:return null
        val artists=item.optJSONArray("artists")?.objects().orEmpty().map {text(it.optText("name"),300)}.filter {it.isNotBlank()}.take(20)
        if(artists.isEmpty())return null
        val album=item.optJSONObject("album")
        return CatalogTrack("spotify",id,title,artists,text(album?.optText("name").orEmpty(),500),item.optLong("duration_ms").coerceIn(0,86_400_000),
            https(album?.optJSONArray("images")?.objects()?.firstOrNull()?.optText("url").orEmpty()),"https://open.spotify.com/track/$id")
    }
    fun spotifyPlaylist(item:JSONObject):CatalogPlaylist? {
        val id=item.optText("id").takeIf {it.matches(Regex("[A-Za-z0-9]{22}"))}?:return null
        return CatalogPlaylist("spotify",id,text(item.optText("name"),500).ifBlank {"Плейлист Spotify"},
            (item.optJSONObject("items")?:item.optJSONObject("tracks"))?.optInt("total")?.coerceAtLeast(0)?:0,
            https(item.optJSONArray("images")?.objects()?.firstOrNull()?.optText("url").orEmpty()),"https://open.spotify.com/playlist/$id")
    }
    fun yandexTrack(item:JSONObject):CatalogTrack? {
        if(item.optText("type","music") in setOf("podcast-episode","podcast"))return null
        val id=item.optText("id").takeIf {it.matches(Regex("[0-9]{1,24}"))}?:return null
        val rawTitle=text(item.optText("title"),500).takeIf {it.isNotBlank()}?:return null
        val version=text(item.optText("version"),100)
        val title=if(version.isBlank())rawTitle else "$rawTitle ($version)"
        val artists=item.optJSONArray("artists")?.objects().orEmpty().map {text(it.optText("name"),300)}.filter {it.isNotBlank()}.take(20)
        if(artists.isEmpty())return null
        val album=item.optJSONArray("albums")?.optJSONObject(0)
        return CatalogTrack("yandex",id,title,artists,text(album?.optText("title").orEmpty(),500),item.optLong("durationMs").coerceIn(0,86_400_000),
            yandexCover(item.optText("coverUri")),"https://music.yandex.ru/track/$id")
    }
    fun yandexPlaylist(item:JSONObject,defaultUid:String):CatalogPlaylist? {
        val kind=item.optText("kind").takeIf {it.matches(Regex("[0-9]{1,24}"))}?:return null
        val uid=item.optJSONObject("owner")?.optText("uid").orEmpty().ifBlank {defaultUid}
        if(!uid.matches(Regex("[0-9]{1,24}")))return null
        return CatalogPlaylist("yandex","$uid:$kind",text(item.optText("title"),500).ifBlank {"Плейлист Яндекс Музыки"},item.optInt("trackCount").coerceAtLeast(0),
            yandexCover(item.optJSONObject("cover")?.optText("uri").orEmpty()),"https://music.yandex.ru/users/$uid/playlists/$kind")
    }
    private fun yandexCover(raw:String):String=if(raw.isBlank())"" else https("https://"+raw.removePrefix("https://").replace("%%","400x400"))
    private fun https(raw:String):String=try {java.net.URI(raw).takeIf {it.scheme=="https" && it.host!=null && it.userInfo==null && it.rawQuery==null && it.rawFragment==null && raw.length<=2048}?.toASCIIString()?.takeIf {it.length<=2048}.orEmpty()}catch(_:Exception){""}
}

/** Only metadata and account library operations. Preview URLs are intentionally never consumed. */
class SpotifyCatalog(private val connections:ProviderConnections) {
    suspend fun search(query:String):List<CatalogTrack> = withContext(Dispatchers.IO) {
        if(query.isBlank())return@withContext emptyList()
        val root=connections.get("spotify","search",mapOf("q" to query.trim().take(200),"type" to "track","limit" to "10"))
        return@withContext root.optJSONObject("tracks")?.optJSONArray("items")?.objects().orEmpty().mapNotNull(CatalogParsers::spotifyTrack)
    }
    suspend fun playlists():List<CatalogPlaylist> = withContext(Dispatchers.IO) {pages("me/playlists",500).mapNotNull(CatalogParsers::spotifyPlaylist)}
    suspend fun playlistTracks(id:String):List<CatalogTrack> = withContext(Dispatchers.IO) {
        if(!id.matches(Regex("[A-Za-z0-9]{22}")))throw SourceFailure("Неверный плейлист Spotify")
        return@withContext pages("playlists/$id/items",1000).mapNotNull {row->(row.optJSONObject("item")?:row.optJSONObject("track"))?.let(CatalogParsers::spotifyTrack)}
    }
    suspend fun likedTracks():List<CatalogTrack> = withContext(Dispatchers.IO) {pages("me/tracks",1000).mapNotNull {it.optJSONObject("track")?.let(CatalogParsers::spotifyTrack)}}
    private suspend fun pages(path:String,maxItems:Int):List<JSONObject> = withContext(Dispatchers.IO) {
        val generation=connections.catalogScope("spotify")
        val result=mutableListOf<JSONObject>();var offset=0
        repeat((maxItems+49)/50) {
            coroutineContext.ensureActive()
            val page=connections.get("spotify",path,mapOf("offset" to offset.toString(),"limit" to "50"),generation)
            val items=page.optJSONArray("items")?:throw SourceFailure("Spotify не вернул список коллекции")
            val rows=items.objects()
            if(items.length()>50 || offset+items.length()>maxItems)throw SourceFailure("Spotify вернул слишком большую страницу коллекции")
            result.addAll(rows)
            if(page.isNull("next") || !page.has("next"))return@withContext result
            if(items.length()==0)throw SourceFailure("Spotify вернул пустую страницу коллекции")
            offset+=items.length()
        }
        throw SourceFailure("Коллекция Spotify превышает лимит $maxItems записей. Разделите её на несколько плейлистов")
    }
}

/** Ports the API used by desktop's installed yandex_music client; not an official public Music SDK. */
class YandexCatalog(private val connections:ProviderConnections) {
    fun playable(track:CatalogTrack):Track {
        if(track.provider!="yandex" || !track.id.matches(Regex("[0-9]{1,24}")))throw SourceFailure("Неверный трек Яндекс Музыки")
        return Track(track.key,track.title,track.artist,track.album,track.durationMs,"yandex",track.id,track.pageUrl,coverUrl=track.coverUrl)
    }
    suspend fun resolve(id:String):ResolvedStream {
        if(!id.matches(Regex("[0-9]{1,24}")))throw SourceFailure("Неверный трек Яндекс Музыки")
        val info=connections.get("yandex","tracks/$id/download-info",YandexDownloadInfo.parameters(id,System.currentTimeMillis()))
        val rows=info.optJSONArray("result")?.objects().orEmpty()
        val selected=YandexDownloadInfo.select(rows)
        // This public download-info document does not receive an OAuth header.
        val document=connections.network.text(okhttp3.Request.Builder().url(selected.url).build(),64*1024)
        return ResolvedStream(YandexDownloadInfo.directLink(document),"audio/mpeg",System.currentTimeMillis()+45_000L)
    }
    suspend fun search(query:String):List<CatalogTrack> = withContext(Dispatchers.IO) {
        if(query.isBlank())return@withContext emptyList()
        val result=connections.get("yandex","search",mapOf("text" to query.trim().take(200),"type" to "track","page" to "0","nocorrect" to "false")).optJSONObject("result")
            ?:throw SourceFailure("Яндекс Музыка не вернула результаты поиска")
        return@withContext result.optJSONObject("tracks")?.optJSONArray("results")?.objects().orEmpty().take(30).mapNotNull(CatalogParsers::yandexTrack)
    }
    suspend fun playlists():List<CatalogPlaylist> = withContext(Dispatchers.IO) {
        val generation=connections.catalogScope("yandex")
        val uid=connections.userId()
        val rows=connections.get("yandex","users/$uid/playlists/list",expectedGeneration=generation).optJSONArray("result")?.objects()?:throw SourceFailure("Яндекс Музыка не вернула плейлисты")
        if(rows.size>500)throw SourceFailure("Слишком много плейлистов Яндекс Музыки: поддерживается до 500")
        return@withContext rows.mapNotNull {CatalogParsers.yandexPlaylist(it,uid)}
    }
    suspend fun playlistTracks(id:String):List<CatalogTrack> = withContext(Dispatchers.IO) {
        if(!id.matches(Regex("[0-9]{1,24}:[0-9]{1,24}")))throw SourceFailure("Неверный плейлист Яндекс Музыки")
        val (uid,kind)=id.split(':')
        val generation=connections.catalogScope("yandex")
        val result=connections.get("yandex","users/$uid/playlists/$kind",expectedGeneration=generation).optJSONObject("result")?:throw SourceFailure("Яндекс Музыка не вернула плейлист")
        if(result.optInt("trackCount")>1000)throw SourceFailure("Плейлист слишком большой: поддерживается до 1000 треков")
        val rows=result.optJSONArray("tracks")?.objects()?:throw SourceFailure("Яндекс Музыка не вернула список треков")
        if(rows.size>1000)throw SourceFailure("Плейлист слишком большой: поддерживается до 1000 треков")
        val existing=rows.mapNotNull {it.optJSONObject("track")?.let(CatalogParsers::yandexTrack)}.associateBy {it.id}
        val ids=rows.mapNotNull {it.optText("id").takeIf {id->id.matches(Regex("[0-9]{1,24}"))}}
        if(ids.size!=rows.size)throw SourceFailure("Яндекс Музыка вернула неполный список треков")
        if(result.optInt("trackCount")>rows.size)throw SourceFailure("Яндекс Музыка вернула неполный плейлист. Повторите позже")
        val missing=ids.filter {it !in existing}
        val resolved=existing+fetchTracks(missing,generation).associateBy {it.id}
        if(ids.any {it !in resolved})throw SourceFailure("Яндекс Музыка не вернула данные всех треков плейлиста")
        return@withContext ids.mapNotNull(resolved::get)
    }
    suspend fun likedTracks():List<CatalogTrack> = withContext(Dispatchers.IO) {
        val generation=connections.catalogScope("yandex")
        val uid=connections.userId()
        val root=connections.get("yandex","users/$uid/likes/tracks",expectedGeneration=generation).optJSONObject("result")?.optJSONObject("library")?:throw SourceFailure("Яндекс Музыка не вернула избранное")
        val ids=CatalogParsers.completeIds(root,"избранное")
        val byId=fetchTracks(ids,generation).associateBy {it.id}
        if(ids.any {it !in byId})throw SourceFailure("Яндекс Музыка не вернула данные всех любимых треков. Повторите позже")
        return@withContext ids.mapNotNull(byId::get)
    }
    private suspend fun fetchTracks(ids:List<String>,generation:Long):List<CatalogTrack> = withContext(Dispatchers.IO) {
        val output=mutableListOf<CatalogTrack>()
        ids.distinct().chunked(50).forEach {batch->
            coroutineContext.ensureActive()
            val rows=connections.postYandex("tracks",mapOf("track-ids" to batch.joinToString(","),"with-positions" to "false"),generation).optJSONArray("result")?:throw SourceFailure("Яндекс Музыка не вернула данные треков")
            output+=rows.objects().mapNotNull(CatalogParsers::yandexTrack)
        }
        return@withContext output
    }
}
