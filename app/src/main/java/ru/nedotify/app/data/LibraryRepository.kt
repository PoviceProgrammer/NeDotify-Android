package ru.nedotify.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.UUID

class LibraryRepository(val context:Context,val dao:LibraryDao,val preferences:Preferences,val network:MusicNetwork) {
    val audioSettings=AudioSettingsStore(context)
    val providers=ProviderConnections(context,network.http)
    val desktopImport=DesktopLibraryImport(context)
    val soundCloud=SoundCloudClient(network)
    val youtube=InnerTubeClient(network)
    val lyrics=LyricsClient(network,disk=LyricsDiskCache(java.io.File(context.filesDir,"lyrics")),fallback=LyricsCascade(network)::lookup)
    private val resolverScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val transientQueue=java.util.concurrent.ConcurrentHashMap<String,Track>()
    private val libraryWrites by lazy {LibraryWrites(::track,dao::rememberSession,{id->
        invalidate(id);transientQueue.remove(id);dao.deleteTrack(id)
    },dao::remember)}
    /** The restore shares the fence with the checkpoint and the removal of a track. */
    val backups by lazy {LibraryBackupStorage(context,dao,preferences){ block -> libraryWrites.guarded(block) }}
    private val streams=StreamCache(resolverScope) {key ->
        val parts=key.split('|',limit=4);val id=parts[0];val quality=parts.getOrElse(1){"high"}
        val track=track(id)?:throw SourceFailure("Трек отсутствует в очереди")
        resolveRemote(track,quality,parts.getOrNull(2)?.takeIf {it.isNotBlank()})
    }
    private suspend fun resolveRemote(track:Track,quality:String="high",expectedScope:String?=null,
        budgetMillis:Long=RESOLUTION_BUDGET_MS,deadlineMessage:String=RESOLUTION_DEADLINE_MESSAGE,
        fallbackCandidates:List<Track> = emptyList()):ResolvedStream {
        val scope=if(track.source=="yandex")providers.yandexScope() else null
        if(expectedScope!=null && scope!=expectedScope)throw SourceFailure("Аккаунт изменился. Повторите воспроизведение")
        val result=onlineSource({preferences.appearance.first().offline}) {
            // One wall-clock budget for the whole cascade: the attempts do not
            // each get a timeout of their own.
            val cascade=ResolutionCascade(budgetMillis)
            cascade.resolve(resolutionAttempts(track,quality,deadlineMessage,fallbackCandidates))
        }
        if(scope!=null && providers.yandexScope()!=scope)throw SourceFailure("Аккаунт изменился. Повторите воспроизведение")
        return result
    }
    /**
     * The providers that are tried, in order, under one budget.
     *
     * The identity gate decides whether a second provider may serve the
     * track. A candidate that the caller already owns is admitted by the
     * gate; otherwise the second attempt **finds** one the way the desktop
     * finds it (`core/playback_recovery.py`): the other providers are
     * searched and the app's own matcher decides whether a candidate is the
     * same recording. After successful resolution the verified identity is
     * persisted in Room, then checked again against current metadata on reuse.
     *
     * The fallback is resolved with **its own** source: its URL lives on its
     * provider's hosts, and the media-URL policy is checked against that
     * source.
     */
    private fun resolutionAttempts(track:Track,quality:String,deadlineMessage:String,
        candidates:List<Track>):List<suspend (Long)->ResolvedStream?> {
        val attempts=mutableListOf<suspend (Long)->ResolvedStream?>()
        attempts.add {left->resolveWith(track,track.source,quality,deadlineMessage,left)}
        attempts.add {left->
            val fallback=fallbackCandidate(track,candidates,left) ?: return@add null
            val stream=resolveWith(fallback,fallback.source,quality,deadlineMessage,left)
            // Only a successfully resolved, matched recording becomes durable identity.
            // A storage failure must not discard audio we have already resolved.
            ProviderMapping.verified(track,fallback)?.let { mapping ->
                try { dao.putProviderMapping(mapping) } catch(cancelled:CancellationException) { throw cancelled } catch(_:Exception) { }
            }
            stream
        }
        return attempts
    }
    /**
     * The candidate that may serve [track], or `null`.
     *
     * A candidate the caller already owns is admitted by the gate at once.
     * Otherwise the other providers are searched — the way the desktop's
     * recovery does it — and the gate decides whether a candidate is the
     * *same recording*. The search is bounded: it may not eat the whole
     * budget of the cascade, and a search that fails is not an error of the
     * resolution: the cascade simply ends with the first attempt's error.
     */
    private suspend fun fallbackCandidate(track:Track,supplied:List<Track>,remaining:Long):Track? {
        val query="${track.artist} ${track.title}".take(200)
        val remembered=dao.providerMappings(track.id).map {it.candidate()}
        return ResolutionIdentity.findFallback(track,supplied+remembered,remaining,FALLBACK_SEARCH_BUDGET_MS) {source->
            when(source) {
                "youtube"->youtube.search(query)
                "soundcloud"->soundCloud.search(query)
                else->emptyList()
            }
        }
    }
    private suspend fun resolveWith(track:Track,source:String,quality:String,deadlineMessage:String,
        remaining:Long):ResolvedStream=
        withinBudget(remaining,deadlineMessage) {
            val stream=when(source) {
                "soundcloud"->soundCloud.resolve(track,quality)
                "youtube"->youtube.resolve(track,quality)
                "yandex"->providers.yandex.resolve(track.sourceId)
                else->throw SourceFailure("Неизвестный источник")
            }
            // The media-URL policy: a stream may only live on a host of the
            // source that produced it. A prefix check would let a provider
            // point the playback or the download at any host on the internet.
            MediaUrlPolicy.requireAccepted(source,stream.url)
            stream
        }
    /**
     * A download needs a fresh signature, but must never invalidate a playing track's flight.
     *
     * The signed URL is only useful while it is alive. The resolution budget
     * is shorter than the shortest TTL the providers hand out, and a URL that
     * is about to expire is refused instead of being downloaded: the retry
     * resolves a new one, and the cache identity is stable, so the retry
     * resumes the bytes that were already committed.
     */
    suspend fun resolveForDownload(track:Track):ResolvedStream {
        if(preferences.appearance.first().offline)throw SourceFailure("Включён офлайн-режим")
        val stream=resolveRemote(track,audioSettings.settings.first().quality,
            budgetMillis=DOWNLOAD_RESOLUTION_BUDGET_MS,deadlineMessage=DOWNLOAD_DEADLINE_MESSAGE)
        if(stream.expiresAt>0 && stream.expiresAt<=System.currentTimeMillis()+EXPIRY_MARGIN_MS)
            throw SourceFailure("Ссылка на аудио истекла. Повторите загрузку")
        return stream
    }
    suspend fun invalidate(id:String) {streams.invalidateMatching {it.startsWith("$id|")}}
    suspend fun logoutProvider(provider:String) {
        providers.logout(provider)
        if(provider=="yandex")streams.invalidateMatching {it.startsWith("yandex:")}
    }
    suspend fun offline(value:Boolean) {preferences.offline(value);if(value){streams.cancelAll();soundCloud.cancelPending()}}
    suspend fun search(query:String,source:String,onPartialFailure:(suspend (String)->Unit)?=null):List<Track> = withContext(Dispatchers.IO) search@ {
        val cleanQuery=query.trim()
        if(cleanQuery.isEmpty() || cleanQuery.length>200 || cleanQuery.any {it.isISOControl()})
            throw SourceFailure("Укажите поисковый запрос до 200 символов")
        if(source !in setOf("all","local","soundcloud","youtube"))throw SourceFailure("Неизвестный источник поиска")
        if(source=="local")return@search withContext(Dispatchers.IO) {
            // SQLite LIKE is ASCII-only for case folding; keep Cyrillic matching correct.
            val matches=mutableListOf<String>();var offset=0
            while(matches.size<100) {
                ensureActive()
                val page=dao.searchPage(500,offset)
                matches+=page.filter {it.title.contains(cleanQuery,true)||it.artist.contains(cleanQuery,true)||it.album.contains(cleanQuery,true)}.map {it.id}
                if(page.size<500)break
                offset+=page.size
            }
            val found=dao.tracks(matches.take(100)).associateBy {it.id}
            matches.take(100).mapNotNull {found[it]}
        }
        if(preferences.appearance.first().offline)throw SourceFailure("Включён офлайн-режим. Доступна локальная библиотека")
        if(source=="all")return@search aggregateSearch(listOf({search(cleanQuery,"local")},{soundCloud.search(cleanQuery)},{youtube.search(cleanQuery)}),
            onPartialFailure=onPartialFailure,timeoutExemptIndices=setOf(0))
        if(source=="youtube")youtube.search(cleanQuery) else soundCloud.search(cleanQuery)
    }
    suspend fun resolve(track:Track,force:Boolean=false):ResolvedStream {
        if(track.localUri.isNotBlank())return ResolvedStream(track.localUri)
        if(track.source=="local")throw SourceFailure("Укажите аудиофайл заново через меню трека")
        if(preferences.appearance.first().offline)throw SourceFailure("Трек недоступен без сети")
        return sourceDeadline(RESOLUTION_BUDGET_MS,"Источник не ответил вовремя. Повторите попытку") {
            val scope=if(track.source=="yandex")providers.yandexScope() else null
            // The format belongs in the in-memory key: a track whose mimeType changed must
            // not keep the resolution of the old format until the process restarts.
            val key="${track.id}|${audioSettings.settings.first().quality}|${scope.orEmpty()}|${track.mimeType}"
            if(force)streams.invalidate(key)
            val result=streams.get(key)
            if(scope!=null && providers.yandexScope()!=scope)throw SourceFailure("Аккаунт изменился. Повторите воспроизведение")
            result
        }
    }
    suspend fun importUris(uris:List<Uri>)=withContext(Dispatchers.IO) {
        importLocalBatch(uris) { uri ->
            val id="document:"+UUID.nameUUIDFromBytes(uri.toString().toByteArray())
            var name:String?=null
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst())name=it.getString(0) }
            ensureActive()
            val base=Track(id,localImportTitle(name,uri.lastPathSegment),source="local",localUri=uri.toString(),mimeType=context.contentResolver.getType(uri).orEmpty())
            // The user chose this file again: the track is theirs, the tombstone of an earlier
            // deletion does not apply any more.
            DeletedTracks.restore(id)
            libraryWrites.refresh(listOf(LocalAudioMetadata.read(context,uri,base))) { rows -> dao.refresh(rows) }
        }
    }
    suspend fun scanLocal()=withContext(Dispatchers.IO) {
        val collection=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(collection,arrayOf(MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM,MediaStore.Audio.Media.DURATION,MediaStore.Audio.Media.ALBUM_ID,MediaStore.Audio.Media.MIME_TYPE),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",null,null)?.use { cursor ->
            val batch=mutableListOf<Track>()
            while(cursor.moveToNext()) {
                ensureActive()
                val id="local:${cursor.getLong(0)}"
                batch+=Track(id,localMetadataText(cursor.getString(1)).ifBlank {"Без названия"},localMetadataText(cursor.getString(2)).ifBlank {"Неизвестный исполнитель"},
                    localMetadataText(cursor.getString(3)),cursor.getLong(4).coerceIn(0,604_800_000),"local",localUri=ContentUris.withAppendedId(collection,cursor.getLong(0)).toString(),
                    coverUrl=ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"),cursor.getLong(5)).toString(),mimeType=cursor.getString(6).orEmpty())
                if(batch.size>=250){libraryWrites.refresh(batch.toList()){rows->dao.refresh(rows)};batch.clear()}
            }
            if(batch.isNotEmpty())libraryWrites.refresh(batch){rows->dao.refresh(rows)}
        }
    }
    /** A write of a track row shares the fence with the checkpoint and the removal. */
    suspend fun remember(track:Track) = libraryWrites.remember(track)
    suspend fun deleteTrack(id:String) = libraryWrites.delete(id)
    /** Resolve the current row under the same fence as deletion; stale checkpoints cannot resurrect it. */
    suspend fun recordSession(session:ListeningSession)=libraryWrites.record(session)
    fun stageQueue(items:List<Track>,append:Boolean=false) = synchronized(transientQueue) {
        if(!append)transientQueue.clear()
        items.forEach {transientQueue[it.id]=it}
    }
    suspend fun track(id:String):Track?=dao.track(id) ?: transientQueue[id]
    suspend fun flowCandidates(excluding:Set<String>,limit:Int):List<Track> = DiscoveryEngine(this).selectFlow(excluding,limit)
    suspend fun attachLocal(trackId:String,uri:Uri)=withContext(Dispatchers.IO) {
        if(uri.scheme!="content")throw SourceFailure("Выберите файл в системном окне")
        val resolver=context.contentResolver
        val mime=resolver.getType(uri).orEmpty()
        if(mime.isNotBlank() && !mime.startsWith("audio/") && mime!="application/ogg")
            throw SourceFailure("Выберите аудиофайл")
        resolver.openAssetFileDescriptor(uri,"r")?.use { /* Check the grant before storing it. */ }
            ?:throw SourceFailure("Не удалось открыть аудиофайл")
        if(dao.attachLocal(trackId,uri.toString(),mime)!=1)throw SourceFailure("Локальный трек не найден")
    }
    suspend fun createPlaylist(name:String) { require(name.isNotBlank());dao.putPlaylist(Playlist(UUID.randomUUID().toString(),name.trim().take(120))) }
    suspend fun queueTracks(ids:List<String>):Map<String,Track> {
        val stored=ids.distinct().chunked(900).flatMap {dao.tracks(it)}.associateBy {it.id}
        return ids.distinct().mapNotNull {id->(stored[id] ?: transientQueue[id])?.let {id to it}}.toMap()
    }
    companion object {
        /** The wall-clock budget of one streaming resolution. */
        const val RESOLUTION_BUDGET_MS=15_000L
        /**
         * The budget of a download resolution. It is shorter than the 30 s
         * budget of the metadata client, because the signed URL the providers
         * hand out is alive for about a minute: a resolution that took longer
         * would return a URL that is already dying.
         */
        const val DOWNLOAD_RESOLUTION_BUDGET_MS=30_000L
        /** A URL that expires within this margin is not worth downloading. */
        const val EXPIRY_MARGIN_MS=5_000L
        const val RESOLUTION_DEADLINE_MESSAGE="Источник не ответил вовремя. Повторите попытку"
        const val DOWNLOAD_DEADLINE_MESSAGE="Источник не ответил вовремя. Повторите загрузку"
        /**
         * The budget of the search that finds a cross-provider fallback.
         *
         * The search is a means, not the goal: it may not use the whole
         * budget of the cascade, or a provider that is down would keep the
         * user waiting for the fallback's own resolution.
         */
        const val FALLBACK_SEARCH_BUDGET_MS=6_000L
    }
}
