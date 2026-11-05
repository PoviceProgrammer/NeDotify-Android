package ru.nedotify.app.downloads

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Downloader
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import ru.nedotify.app.MainActivity
import ru.nedotify.app.NeDotifyApp
import ru.nedotify.app.R
import ru.nedotify.app.data.MediaUrlPolicy
import ru.nedotify.app.data.SourceFailure
import ru.nedotify.app.data.mediaStatusMessage
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

@UnstableApi
class TrackDownloadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    private val app get()=applicationContext as NeDotifyApp
    private val downloads get()=app.downloads
    private val dao get()=app.repository.dao
    override suspend fun doWork():Result {
        val trackId=inputData.getString("trackId")?:return Result.failure()
        val generation=id.toString()
        return downloads.trackLock(trackId).withLock {
            val record=dao.download(trackId)
            if(record?.generation!=generation || record.state in setOf("paused","complete","removing"))return@withLock Result.success()
            val track=dao.track(trackId)?:return@withLock Result.failure()
            try {
                if(app.repository.preferences.appearance.first().offline) {
                    dao.downloadState(trackId,generation,"paused");return@withLock Result.success()
                }
                // The permit comes before the foreground. WorkManager runs up to
                // twenty workers at once: entering the foreground first would
                // let all of them hold a foreground-service quota while they
                // wait for one of the two permits.
                downloadGate(downloads.slots,{setForeground(notification(track.title))}) {
                    if(dao.downloadState(trackId,generation,"preparing")==0)throw CancellationException("Replaced")
                    val stream=app.repository.resolveForDownload(track)
                    val mime=stream.mimeType.orEmpty()
                    // The media-URL policy: the stream must be a media URL of a
                    // source the app knows. The check against the *own* source
                    // of the track happened at the resolution boundary, where
                    // the source that produced the URL is known — a fallback
                    // of another provider has its own source.
                    if(!MediaUrlPolicy.acceptsAnySource(stream.url))throw SourceFailure("Источник вернул неподдерживаемый адрес аудио")
                    if(dao.downloadSource(trackId,generation,stream.url,mime)==0)throw CancellationException("Replaced")
                    val storage=withContext(Dispatchers.IO){downloads.storage}
                    val hls=mime.equals("application/x-mpegURL",true)
                    // The manifest of an HLS stream names the segments of the
                    // package: they are the contract of its completeness.
                    val hlsManifest=if(hls)storage.verifyVod(stream.url) else null
                    if(hlsManifest!=null)withContext(Dispatchers.IO){storage.refreshManifests(trackId,stream.url)}
                    currentCoroutineContext().ensureActive()
                    if(dao.downloadState(trackId,generation,"downloading")==0)throw CancellationException("Replaced")
                    // The disk must have room. A full disk fails in exactly the
                    // same way on every attempt, so it is not a transient error.
                    val free=withContext(Dispatchers.IO){storage.freeBytes()}
                    if(free==null)throw FreeSpaceUnknownException()
                    if(!freeSpaceSufficient(free,MIN_FREE_BYTES))throw diskFullFailure()
                    // A package that was cached under the old key format is
                    // unreachable: the signature is part of the old key and
                    // it changes with every resolution.
                    val observed=DownloadStorage.observedKeys()
                    val downloader=storage.downloader(trackId,stream.url,mime,observed)
                    val committedBefore=withContext(Dispatchers.IO){storage.bytes(trackId)}
                    var declared=-1L
                    var tooLarge=false
                    var diskFull=false
                    // The watchdog reads the progress from another thread: the
                    // value must be read atomically, and a 64-bit value is not
                    // read atomically on a 32-bit runtime.
                    val lastProgress=AtomicLong(SystemClock.elapsedRealtime())
                    coroutineScope {
                        val updates=Channel<Triple<Long,Long,Float>>(Channel.CONFLATED)
                        val writer=launch {
                            var lastNotification=0L
                            for((bytes,total,percent) in updates) {
                                dao.downloadProgress(trackId,generation,bytes,total,percent)
                                if(SystemClock.elapsedRealtime()-lastNotification>2000) {
                                    setForeground(notification(track.title,percent));lastNotification=SystemClock.elapsedRealtime()
                                }
                            }
                        }
                        // The media client has no total budget, so a transfer
                        // that stops making progress is bounded here instead:
                        // the attempt is cancelled and retried, and the retry
                        // resumes the bytes that were already committed.
                        val watchdog=launch {
                            while(isActive) {
                                delay(STALL_TIMEOUT_MS)
                                if(SystemClock.elapsedRealtime()-lastProgress.get()>=STALL_TIMEOUT_MS) {
                                    downloader.cancel()
                                    throw IOException("Загрузка остановилась")
                                }
                            }
                        }
                        var lastUpdate=0L
                        try {awaitDownload(downloader,Downloader.ProgressListener {total,bytes,percent->
                            val now=SystemClock.elapsedRealtime()
                            lastProgress.set(now)
                            if(total>0)declared=total
                            // Media3 reports the bytes of the resource it is
                            // writing. A progressive download counts the bytes
                            // that are already in the cache, an HLS download
                            // counts only the new ones: the committed total is
                            // the same in both cases.
                            val committed=if(hls)committedBefore+bytes else bytes
                            if(exceedsDownloadCeiling(committed)){tooLarge=true;downloader.cancel()}
                            // The size-aware space check: the package must
                            // still fit on the volume while it is written.
                            if(declared>0 && !diskFull) {
                                val freeNow=free-(committed-committedBefore)
                                if(freeNow<declared-committed){diskFull=true;downloader.cancel()}
                            }
                            val reported=if(declared>0) (committed.toDouble()/declared*100).coerceIn(0.0,100.0).toFloat() else percent
                            if(now-lastUpdate>=750 || reported>=100){lastUpdate=now;updates.trySend(Triple(committed,total,reported))}
                        })} finally {updates.close();withContext(NonCancellable){writer.join()};watchdog.cancel()}
                    }
                    if(tooLarge)throw SourceFailure("Файл слишком большой для офлайн-загрузки")
                    if(diskFull)throw diskFullFailure()
                    currentCoroutineContext().ensureActive()
                    // The keep list holds the resources of the attempt and the
                    // resources the manifest names: a segment that was already
                    // complete was not downloaded again, and it must survive
                    // the prune.
                    val keep=HashSet(observed.keys)
                    if(hlsManifest!=null) {
                        keep.addAll(hlsManifest.manifests.map {downloadCacheKey(trackId,it)})
                        keep.addAll(hlsManifest.segments.map {downloadCacheKey(trackId,it)})
                    }
                    val size=withContext(Dispatchers.IO){storage.prune(trackId,keep);storage.bytes(trackId)}
                    if(size<=0)throw SourceFailure("Источник вернул пустой файл")
                    // The declared total is the provider's own size. A package
                    // that is shorter is not the recording and must not be
                    // published as complete. An HLS package is judged by its
                    // manifest: every segment it names must be in the cache.
                    val verdict=if(hlsManifest!=null) {
                        val expected=hlsManifest.segments.map {downloadCacheKey(trackId,it)}
                        val everyCommitted=withContext(Dispatchers.IO){expected.all {key->storage.cachedBytes(key)>0L}}
                        val resources=withContext(Dispatchers.IO){storage.keys(trackId).size}
                        hlsCompletion(hlsManifest.segments.size,resources,everyCommitted)
                    } else downloadCompletion(expectedTotal(declared),
                        withContext(Dispatchers.IO){storage.cachedBytes(downloadCacheKey(trackId,stream.url))})
                    when(verdict) {
                        is DownloadCompletion.Incomplete->throw SourceFailure(verdict.reason)
                        DownloadCompletion.Complete->Unit
                    }
                    dao.downloadProgress(trackId,generation,size,maxOf(size,declared),100f)
                    dao.downloadState(trackId,generation,"complete")
                }
                Result.success()
            } catch(cancelled:CancellationException) {
                withContext(NonCancellable){dao.downloadInterrupted(trackId,generation)}
                throw cancelled
            } catch(error:Exception) {
                val status=mediaResponseStatus(error)
                val retry=downloadRetry(error,runAttemptCount,status)
                dao.downloadState(trackId,generation,if(retry)"queued" else "failed",failureText(error,status))
                if(retry)Result.retry() else Result.failure()
            }
        }
    }
    private fun notification(title:String,percent:Float=-1f):ForegroundInfo {
        val channel="music-downloads"
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,"Загрузка музыки",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(applicationContext,channel).setSmallIcon(R.drawable.ic_nedotify)
            .setContentTitle("Скачивание музыки").setContentText(title).setContentIntent(open)
            .setProgress(100,percent.coerceIn(0f,100f).toInt(),percent<0).setOngoing(true).setOnlyAlertOnce(true).build()
        return ForegroundInfo((id.hashCode() and 0x3fffffff)+100,notification,if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }
    companion object {
        /**
         * A transfer that makes no progress for this long is a dead transfer.
         *
         * The watchdog bounds a transfer that **stops**: the media client has
         * no total budget, so nothing else would end it. It does **not** bound
         * a connection that keeps trickling bytes — such a transfer looks
         * like progress, and neither the read timeout nor the watchdog can
         * tell it apart from a slow but healthy one. That residual is
         * recorded, not claimed away.
         */
        const val STALL_TIMEOUT_MS=60_000L
    }
}

@UnstableApi
class RemoveDownloadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val app=applicationContext as NeDotifyApp
        val trackId=inputData.getString("trackId")?:return Result.failure()
        return app.downloads.trackLock(trackId).withLock {
            if(app.repository.dao.download(trackId)?.generation!=id.toString())return@withLock Result.success()
            try {
                withContext(Dispatchers.IO){app.downloads.storage.remove(trackId)}
                app.repository.dao.deleteDownload(trackId,id.toString());Result.success()
            } catch(_:IOException){
                if(runAttemptCount<3)Result.retry() else {
                    app.repository.dao.downloadState(trackId,id.toString(),"remove_failed","Не удалось удалить файлы. Повторите удаление")
                    Result.failure()
                }
            }
        }
    }
}

fun failureText(error:Exception,status:Int?):String=when {
    foregroundStartRestricted(error)->"Android ограничил фоновую загрузку. Откройте приложение и повторите загрузку позже"
    isDiskFull(error)->DISK_FULL_MESSAGE
    error is FreeSpaceUnknownException->error.message.orEmpty()
    error is SourceFailure->error.message.orEmpty().take(240)
    status==401 || status==403->mediaStatusMessage(status)?:"Ссылка истекла или источник ограничил доступ. Повторите загрузку"
    error is java.io.FileNotFoundException->"Не удалось сохранить музыку. Проверьте свободное место"
    error is IOException->"Соединение прервано. Загрузку можно продолжить"
    else->"Не удалось скачать трек. Повторите попытку"
}

internal fun foregroundStartRestricted(error:Throwable):Boolean =
    generateSequence(error) {it.cause}.take(12).any {
        it.javaClass.simpleName in setOf("ForegroundServiceStartNotAllowedException",
            "ForegroundServiceTypeNotAllowedException", "StartForegroundCalledOnStoppedServiceException") ||
            (it is IllegalStateException && it.message.orEmpty().contains("foreground",ignoreCase=true))
    }
