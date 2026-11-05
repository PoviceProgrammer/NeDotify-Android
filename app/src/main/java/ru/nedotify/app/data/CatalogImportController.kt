package ru.nedotify.app.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class CatalogImportState(val provider:String="",val title:String="",val importing:Boolean=false,
    val paused:Boolean=false,val completed:Int=0,val total:Int=0,val unavailable:Int=0,
    val error:String?=null,val message:String?=null,val errorCode:Int?=null)

/**
 * The import cannot go on: the service is unreachable, the account is gone or the
 * app is offline. It is a [SourceFailure], so every caller that already handles a
 * provider failure keeps working, but the import controller can tell it apart
 * from a single track that has no playable recording.
 */
class CatalogImportBlocked(message:String,code:Int?=null):SourceFailure(message,code)

internal data class CatalogImportCheckpoint(val playlistId:String,val provider:String,val title:String,
    val items:List<CatalogTrack>,val next:Int=0,val accepted:Set<String> = emptySet(),val unavailable:Int=0,
    val createdAt:Long=System.currentTimeMillis()) {
    fun state(importing:Boolean=false,error:String?=null,message:String?=null,errorCode:Int?=null)=CatalogImportState(provider,title,
        importing,!importing,accepted.size,items.size,unavailable,error,message,errorCode)
}

internal interface CatalogImportJournal {
    fun read():CatalogImportCheckpoint?
    fun write(checkpoint:CatalogImportCheckpoint)
    fun clear()
}

/**
 * Application-owned work with an idempotent Room commit before each durable checkpoint.
 *
 * The request outlives the screen that started it: the journal keeps the position
 * of the import, so a new process continues where the previous one stopped.
 * The items are resolved with a **bounded** concurrency and are committed in the
 * order of the list, one durable checkpoint per committed item.
 */
class CatalogImportController internal constructor(private val scope:CoroutineScope,private val journal:CatalogImportJournal,
    private val resolve:suspend(CatalogTrack)->Track,
    private val commit:suspend(CatalogImportCheckpoint,Track)->Unit,
    private val itemTimeoutMs:Long=20_000,private val runBudgetMs:Long=DEFAULT_RUN_BUDGET_MS,
    private val concurrency:Int=DEFAULT_CONCURRENCY,
    private val blocker:suspend(String)->String? = { null }) {
    private val output=MutableStateFlow(CatalogImportState())
    val state:StateFlow<CatalogImportState> = output.asStateFlow()
    fun clearMessage() {output.value=output.value.copy(message=null)}
    private val lock=Mutex()
    private var checkpoint:CatalogImportCheckpoint?=null
    private var job:Job?=null
    private val initialized=scope.async {
        try {
            checkpoint=journal.read()
            checkpoint?.let {output.value=it.state(message="Незавершённый импорт сохранён. Можно продолжить.")}
        } catch(error:Exception) {output.value=CatalogImportState(error="Не удалось прочитать сохранённый импорт. Начните новый список")}
    }

    /**
     * Start a new request. A refusal is reported in the state instead of thrown:
     * the caller is a button handler, and an exception there is a crash.
     */
    @Synchronized fun start(provider:String,title:String,items:List<CatalogTrack>) {
        if(job?.isActive==true){output.value=output.value.copy(error="Импорт уже выполняется. Приостановите или завершите его перед новым списком",errorCode=null);return}
        if(checkpoint!=null){output.value=output.value.copy(error="Сначала продолжите или завершите сохранённый импорт",errorCode=null);return}
        if(provider !in setOf("spotify","yandex") || items.isEmpty() || items.any {it.provider!=provider}){
            output.value=output.value.copy(provider=provider,error="Выберите список треков одного сервиса",errorCode=null);return}
        // The cap is 500 items: 1000 is the worst-case number of provider *searches*
        // (500 items times two searchers), not a number of items.
        val bounded=items.take(MAX_ITEMS)
        val notice=if(items.size>MAX_ITEMS)"Обработаны первые $MAX_ITEMS записей" else null
        enqueue({
            CatalogImportCheckpoint(UUID.randomUUID().toString(),provider,title.trim().take(120).ifBlank {"Импорт"},bounded)
                .also {journal.write(it);checkpoint=it}
        },notice)
    }

    @Synchronized fun resume() {
        if(checkpoint==null){output.value=output.value.copy(error="Нет сохранённого импорта",errorCode=null);return}
        enqueue({checkpoint ?: throw SourceFailure("Нет сохранённого импорта")})
    }

    @Synchronized fun pause() {job?.cancel()}

    @Synchronized fun discard() {
        job?.cancel()
        scope.launch {
            initialized.await()
            lock.withLock {
                try {
                    val finished=checkpoint
                    journal.clear();checkpoint=null
                    output.value=CatalogImportState(provider=finished?.provider.orEmpty(),title=finished?.title.orEmpty(),
                        message="Импорт завершён. Уже добавленные треки сохранены.")
                }
                catch(error:Exception) {output.value=output.value.copy(error="Не удалось завершить импорт. Повторите действие")}
            }
        }
    }

    private fun enqueue(select:()->CatalogImportCheckpoint,notice:String?=null) {
        if(job?.isActive==true) {output.value=output.value.copy(error="Импорт уже выполняется. Приостановите или завершите его перед новым списком",errorCode=null);return}
        job=scope.launch(start=CoroutineStart.LAZY) {
            initialized.await()
            lock.withLock {
                try {
                    val current=select()
                    output.value=current.state(importing=true,message=notice)
                    val run=withTimeout(runBudgetMs){importItems(current)}
                    if(run.blockedReason!=null) {
                        // The environment blocks the request; it stays resumable.
                        output.value=run.checkpoint.state(error=run.blockedReason)
                    } else {
                        journal.clear();checkpoint=null
                        val done=run.checkpoint
                        output.value=done.state(message="Импортировано ${done.accepted.size} треков; недоступно: ${done.unavailable}"+
                            (notice?.let {"; $it"}?:"")).copy(paused=false,error=null,errorCode=null)
                    }
                } catch(error:TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    output.value=checkpoint?.state(message="Импорт приостановлен по лимиту времени. Можно продолжить.")?:output.value
                } catch(error:CancellationException) {
                    // The run budget arrives as a cancellation of the inner scope.
                    if(error.cause is TimeoutCancellationException) {
                        output.value=checkpoint?.state(message="Импорт приостановлен по лимиту времени. Можно продолжить.")?:output.value
                    } else {
                        output.value=checkpoint?.state(message="Импорт приостановлен. Уже добавленные треки сохранены.")?:output.value
                        throw error
                    }
                } catch(error:Exception) {
                    val code=(error as? SourceFailure)?.httpCode
                    output.value=checkpoint?.state(error=error.message?:"Не удалось продолжить импорт",errorCode=code)
                        ?:output.value.copy(importing=false,error=error.message?:"Не удалось начать импорт",errorCode=code)
                }
            }
        }.also {it.start()}
    }

    /**
     * The items are resolved with a bounded concurrency and committed in the order
     * of the list. A commit is durable before the checkpoint advances, so an
     * interrupted import never adds the same recording twice.
     */
    private suspend fun importItems(start:CatalogImportCheckpoint):ImportRun = coroutineScope {
        var current=start
        var launched=start.next
        var blockedReason:String?=null
        val inFlight=LinkedHashMap<Int,Deferred<Track?>>()
        // The environment is checked before the first item as well: an offline
        // import must not add anything before it pauses.
        val initial=blocker(start.provider)
        if(initial!=null)return@coroutineScope ImportRun(start,initial)
        while(current.next<start.items.size) {
            while(inFlight.size<concurrency.coerceIn(1,MAX_CONCURRENCY) && launched<start.items.size) {
                val index=launched++
                inFlight[index]=async {resolveItem(start.items[index])}
            }
            val index=current.next
            val pending=inFlight[index]?:break
            val ready=pending.await()
            inFlight.remove(index)
            if(ready!=null)commit(current,ready)
            current=current.copy(next=index+1,accepted=if(ready==null)current.accepted else current.accepted+ready.id,
                unavailable=current.unavailable+if(ready==null)1 else 0)
            journal.write(current);checkpoint=current
            output.value=current.state(importing=true)
            // The environment can change while the request runs: offline mode or a
            // closed session pauses it instead of burning the rest of the list.
            val reason=blocker(start.provider)
            if(reason!=null){blockedReason=reason;inFlight.values.forEach {it.cancel()};break}
        }
        ImportRun(current,blockedReason)
    }

    /**
     * One item. A classified provider failure (an expired session, a rate limit)
     * stops the whole request and keeps its reason; every other failure marks
     * this single item as unavailable.
     */
    private suspend fun resolveItem(item:CatalogTrack):Track?=try {
        withTimeout(itemTimeoutMs){resolve(item)}
    } catch(error:TimeoutCancellationException) {
        currentCoroutineContext().ensureActive();null
    } catch(error:CancellationException) {throw error}
    catch(error:CatalogImportBlocked) {throw error}
    catch(error:SourceFailure) {if(error.httpCode in setOf(401,403,429))throw error;null}
    catch(_:Exception) {null}

    private data class ImportRun(val checkpoint:CatalogImportCheckpoint,val blockedReason:String?=null)

    companion object {
        /** 500 items: 1000 is the worst-case number of provider searches, not items. */
        const val MAX_ITEMS=500
        /** The provider work of one import is bounded, like the aggregate search. */
        const val DEFAULT_CONCURRENCY=4
        private const val MAX_CONCURRENCY=8
        /** 500 items at four at a time and 20 s each is at most 41 min 40 s. */
        const val DEFAULT_RUN_BUDGET_MS=2_700_000L
        private var instance:CatalogImportController?=null
        @Synchronized fun forRepository(repository:LibraryRepository):CatalogImportController = instance?:run {
            CatalogImportController(CoroutineScope(SupervisorJob()+Dispatchers.IO),
                FileCatalogImportJournal(File(repository.context.filesDir,"catalog-import-v1.json")),
                resolve={item->
                    if(repository.preferences.appearance.first().offline)throw CatalogImportBlocked("Включён офлайн-режим. Импорт приостановлен")
                    val track=if(item.provider=="yandex")repository.providers.yandex.playable(item)
                    else CatalogMatcher.resolve(item,listOf(repository.soundCloud::search,repository.youtube::search)) {repository.resolve(it);Unit}
                    if(item.provider=="yandex")repository.resolve(track)
                    track
                },commit={request,track->repository.dao.importPlaylist(Playlist(request.playlistId,request.title,request.createdAt),listOf(track))},
                blocker={provider->
                    if(repository.preferences.appearance.first().offline)"Включён офлайн-режим. Импорт приостановлен"
                    else if(provider=="yandex" && !repository.providers.status.value.yandexConnected)"Подключите Яндекс Музыку в настройках. Импорт приостановлен"
                    else null
                })
                .also {instance=it}
        }
    }
}

internal class FileCatalogImportJournal(private val file:File):CatalogImportJournal {
    override fun read():CatalogImportCheckpoint? {
        if(!file.exists())return null
        if(file.length()>4*1024*1024)throw SourceFailure("Сохранённый импорт слишком большой")
        val root=sourceObject(file.readText(Charsets.UTF_8))
        if(root.getInt("version")!=1)throw SourceFailure("Версия сохранённого импорта не поддерживается")
        val items=root.getJSONArray("items").objects().map {row->CatalogTrack(row.optText("provider"),row.optText("id"),row.optText("title"),
            row.getJSONArray("artists").let {values->(0 until values.length()).map {values.getString(it)}},row.optText("album"),
            row.getLong("durationMs"),row.optText("coverUrl"),row.optText("pageUrl"))}
        val next=root.getInt("next")
        if(items.isEmpty() || items.size>CatalogImportController.MAX_ITEMS || next !in 0..items.size)throw SourceFailure("Повреждён сохранённый импорт")
        val accepted=root.getJSONArray("accepted").let {values->(0 until values.length()).map {values.getString(it)}.toSet()}
        return CatalogImportCheckpoint(root.getString("playlistId"),root.getString("provider"),root.getString("title"),items,next,accepted,
            root.getInt("unavailable"),root.getLong("createdAt"))
    }
    override fun write(checkpoint:CatalogImportCheckpoint) {
        val root=JSONObject().put("version",1).put("playlistId",checkpoint.playlistId).put("provider",checkpoint.provider)
            .put("title",checkpoint.title).put("next",checkpoint.next).put("accepted",JSONArray(checkpoint.accepted.toList()))
            .put("unavailable",checkpoint.unavailable).put("createdAt",checkpoint.createdAt).put("items",JSONArray().also {rows->
                checkpoint.items.forEach {item->rows.put(JSONObject().put("provider",item.provider).put("id",item.id).put("title",item.title)
                    .put("artists",JSONArray(item.artists)).put("album",item.album).put("durationMs",item.durationMs)
                    .put("coverUrl",item.coverUrl).put("pageUrl",item.pageUrl))}
            })
        val bytes=root.toString().toByteArray(Charsets.UTF_8)
        if(bytes.size>4*1024*1024)throw SourceFailure("Список слишком большой для сохранения")
        val temporary=File(file.parentFile,file.name+".tmp")
        try {
            FileOutputStream(temporary).use {it.write(bytes);it.channel.force(true)}
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {temporary.delete()}
    }
    override fun clear() {if(file.exists() && !file.delete())throw SourceFailure("Не удалось завершить сохранённый импорт")}
}
