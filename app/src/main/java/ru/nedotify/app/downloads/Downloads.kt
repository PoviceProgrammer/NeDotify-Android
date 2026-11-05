package ru.nedotify.app.downloads

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.work.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import ru.nedotify.app.data.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class Downloads(private val context:Context,val repository:LibraryRepository,private val scope:CoroutineScope,
    database:DatabaseProvider) {
    val storage by lazy {DownloadStorage(context,repository.network,database)}
    private val work get()=WorkManager.getInstance(context)
    private val commands=Mutex()
    private val trackLocks=ConcurrentHashMap<String,Mutex>()
    val slots=Semaphore(2)
    fun trackLock(id:String)=trackLocks.getOrPut(id){Mutex()}
    private val dao get()=repository.dao
    val errors=MutableSharedFlow<String>(extraBufferCapacity=1)
    init {
        scope.launch {guard {reconcile()}}
        scope.launch {repository.preferences.appearance.map {it.offline}.distinctUntilChanged().collect {if(it)guard {pauseAll()}}}
    }
    private suspend fun guard(action:suspend ()->Unit) {
        try {action()}
        catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){android.util.Log.e("NeDotify","Download state recovery failed",error);errors.emit("Не удалось восстановить загрузки. Откройте библиотеку и повторите действие")}
    }
    private fun request(record:OfflineDownload):OneTimeWorkRequest {
        val builder=if(record.state=="removing")OneTimeWorkRequestBuilder<RemoveDownloadWorker>() else OneTimeWorkRequestBuilder<TrackDownloadWorker>()
        builder.setId(UUID.fromString(record.generation)).setInputData(workDataOf("trackId" to record.trackId))
            .addTag(TAG).addTag(name(record.trackId)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,java.util.concurrent.TimeUnit.SECONDS)
        if(record.state!="removing")builder.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
        return builder.build()
    }
    private suspend fun schedule(record:OfflineDownload) {work.enqueueUniqueWork(name(record.trackId),ExistingWorkPolicy.REPLACE,request(record)).result.awaitResult()}
    suspend fun enqueue(track:Track)=commands.withLock {
        if(repository.preferences.appearance.first().offline)throw SourceFailure("Отключите офлайн-режим, чтобы скачать трек")
        if(track.localUri.isNotBlank())throw SourceFailure("Этот файл уже находится на устройстве")
        val old=dao.download(track.id)
        if(old?.state in setOf("queued","preparing","downloading","complete","removing","remove_failed"))return@withLock
        // The write of the track row shares the fence with the removal of a track:
        // a delete that lands during the enqueue cannot be undone by this write.
        repository.remember(track)
        val record=OfflineDownload(track.id,UUID.randomUUID().toString(),bytes=old?.bytes?:0)
        try {dao.putDownload(record)}
        catch(error:android.database.sqlite.SQLiteException) {
            // The track was deleted while the download was queued: the row of the download
            // would have no track to belong to. A raw constraint error is not a user message.
            throw SourceFailure("Трек удалён из библиотеки").also { it.initCause(error) }
        }
        schedule(record)
    }
    suspend fun pause(id:String)=commands.withLock {pauseLocked(id)}
    private suspend fun pauseLocked(id:String) {
        val old=dao.download(id)?:return
        if(old.state !in setOf("queued","preparing","downloading"))return
        // Fence late progress/completion of the cancelled worker immediately.
        dao.putDownload(old.copy(generation=UUID.randomUUID().toString(),state="paused",updatedAt=System.currentTimeMillis()))
        work.cancelUniqueWork(name(id)).result.awaitResult()
    }
    suspend fun pauseAll()=commands.withLock {dao.allDownloads().forEach {pauseLocked(it.trackId)}}
    suspend fun resume(id:String) {val track=dao.track(id)?:throw SourceFailure("Трек отсутствует");enqueue(track)}
    suspend fun remove(id:String)=commands.withLock {
        val old=dao.download(id)?:return@withLock
        val record=old.copy(generation=UUID.randomUUID().toString(),state="removing",error="",updatedAt=System.currentTimeMillis())
        dao.putDownload(record);schedule(record)
    }
    /** Library deletion waits for the worker and cached bytes before removing its row. */
    suspend fun removeNow(id:String)=commands.withLock {
        val old=dao.download(id)
        if(old!=null)dao.putDownload(old.copy(generation=UUID.randomUUID().toString(),state="removing"))
        work.cancelUniqueWork(name(id)).result.awaitResult()
        trackLock(id).withLock {
            try {
                withContext(Dispatchers.IO){storage.remove(id)}
                dao.download(id)?.let {dao.deleteDownload(id,it.generation)}
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception) {
                dao.download(id)?.let {dao.downloadState(id,it.generation,"remove_failed","Не удалось удалить файлы. Повторите удаление")}
                throw SourceFailure("Не удалось удалить файлы трека. Повторите удаление").also {it.initCause(error)}
            }
        }
    }
    /** Recover even the crash window between the Room transaction and WorkManager enqueue. */
    suspend fun reconcile()=commands.withLock {
        for(record in dao.allDownloads()) {
            if(record.state !in setOf("queued","preparing","downloading","removing"))continue
            val info=try {work.getWorkInfoById(UUID.fromString(record.generation)).awaitResult()}
                catch(cancelled:CancellationException){throw cancelled}
                catch(_:IllegalArgumentException){null}
            if(info==null) {
                dao.downloadInterrupted(record.trackId,record.generation)
                schedule(record.copy(state=if(record.state=="removing")"removing" else "queued"))
            } else if(info.state.isFinished) {
                if(record.state=="removing")dao.downloadState(record.trackId,record.generation,"remove_failed","Удаление прервано. Повторите удаление")
                else dao.downloadState(record.trackId,record.generation,"failed","Загрузка прервана. Нажмите «Повторить»")
            }
        }
    }
    companion object {
        const val TAG="nedotify-downloads"
        fun name(id:String)="download-"+downloadPrefix(id).removeSuffix("|")
    }
}

suspend fun <T> ListenableFuture<T>.awaitResult():T=suspendCancellableCoroutine {continuation->
    addListener({try {if(continuation.isActive)continuation.resume(get())}
        catch(error:ExecutionException){if(continuation.isActive)continuation.resumeWithException(error.cause?:error)}
        catch(error:Exception){if(continuation.isActive)continuation.resumeWithException(error)}},Runnable::run)
    continuation.invokeOnCancellation {cancel(false)}
}
