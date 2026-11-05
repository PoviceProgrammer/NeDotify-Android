package ru.nedotify.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** UI retains only counts and a capability for an app-private staged file. */
data class BackupPreview(val token:String,val summary:BackupSummary,val notice:String="")

/** SAF documents are user-owned; no private library files are moved or deleted. */
class LibraryBackupStorage(private val context:Context,private val dao:LibraryDao,
    private val preferences:Preferences,private val withFence:suspend (suspend ()->Unit)->Unit = { it() }) {
    private val previews=File(context.cacheDir,"backup-previews").apply {mkdirs()}
    suspend fun export(uri:Uri):BackupSummary=withContext(Dispatchers.IO) {
        requireDocument(uri)
        val snapshot=dao.exportSnapshot()
        val links=snapshot.links.groupBy {it.playlistId}
        val backup=LibraryBackup(preferences.libraryId(),System.currentTimeMillis(),snapshot.tracks,
            snapshot.playlists.map {BackupPlaylist(it,links[it.id].orEmpty().map {link->link.trackId})},snapshot.history,snapshot.smartPlaylists)
        val prepared=BackupExport.prepare(backup,snapshot.totalSessions)
        val bytes=BackupCodec.encode(prepared.backup)
        ensureActive()
        context.contentResolver.openOutputStream(uri,"wt")?.use {output->
            output.write(bytes);output.flush()
        } ?:throw SourceFailure("Не удалось сохранить резервную копию")
        prepared.summary
    }

    suspend fun stage(backup:LibraryBackup,notice:String="",failurePrefix:String="Не удалось сохранить копию"):BackupPreview=withContext(Dispatchers.IO) {
        val bytes=BackupCodec.encode(backup,failurePrefix)
        ensureActive()
        val token=UUID.randomUUID().toString()
        val file=previewFile(token)
        try {file.writeBytes(bytes);ensureActive();sweepPreviews(file);BackupPreview(token,backup.summary,notice)}
        catch(error:Throwable) {file.delete();throw error}
    }

    /** The staging of a transfer of a desktop library: its failures speak about that transfer. */
    suspend fun stageDesktopImport(backup:LibraryBackup,notice:String="")=
        stage(backup,notice,"Не удалось перенести библиотеку ПК")

    suspend fun inspectPreview(uri:Uri):BackupPreview=withContext(Dispatchers.IO) {
        requireDocument(uri)
        val token=UUID.randomUUID().toString();val file=previewFile(token)
        try {
            copyBackup(uri,file)
            val summary=BackupCodec.decode(file.readBytes()).summary
            ensureActive();sweepPreviews(file);BackupPreview(token,summary)
        } catch(error:Throwable) {file.delete();throw error}
    }

    suspend fun discard(preview:BackupPreview)=withContext(Dispatchers.IO) {previewFile(preview.token).delete();Unit}

    suspend fun restore(preview:BackupPreview):BackupSummary=withContext(Dispatchers.IO) {
        val file=previewFile(preview.token)
        if(!file.isFile || file.length()>BackupCodec.MAX_BYTES)throw SourceFailure("Предпросмотр устарел. Выберите копию заново")
        val backup=BackupCodec.decode(file.readBytes())
        ensureActive()
        applyRestore(backup)
        file.delete()
        backup.summary
    }

    suspend fun inspect(uri:Uri):LibraryBackup=withContext(Dispatchers.IO) {
        requireDocument(uri)
        val file=previewFile(UUID.randomUUID().toString())
        try {copyBackup(uri,file);ensureActive();BackupCodec.decode(file.readBytes())} finally {file.delete()}
    }

    suspend fun restore(backup:LibraryBackup):BackupSummary=withContext(Dispatchers.IO) {
        // Revalidate at the storage boundary as well as during preview; no partial merge.
        val validated=BackupCodec.portable(backup)
        ensureActive()
        applyRestore(validated)
        validated.summary
    }

    /**
     * The restore shares the fence with the checkpoint and the removal of a track.
     *
     * A restore and a deletion must not interleave: the one that lands last decides whether the
     * track is in the library. The fence also keeps the restore out of the way of a checkpoint
     * that is in flight, so a listening session is never written for a row of a half-merged
     * library.
     */
    private suspend fun applyRestore(backup:LibraryBackup)=allocationSafe(
        "Не удалось восстановить библиотеку: копия слишком велика для памяти устройства") {
        withFence { dao.restoreBackup(backup,preferences.libraryId()) }
    }

    private fun requireDocument(uri:Uri) {
        if(uri.scheme!="content")throw SourceFailure("Выберите документ через системное окно")
    }

    private suspend fun copyBackup(uri:Uri,file:File) {
        context.contentResolver.openInputStream(uri)?.use {input->file.outputStream().use {output->
            val buffer=ByteArray(32768);var total=0L
            while(true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count=input.read(buffer);if(count<0)break
                total+=count
                if(total>BackupCodec.MAX_BYTES)throw SourceFailure("Не удалось прочитать копию: размер копии превышает 16 МБ")
                output.write(buffer,0,count)
            }
        }} ?:throw SourceFailure("Не удалось прочитать резервную копию")
    }

    private fun previewFile(token:String):File {
        if(!token.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            throw SourceFailure("Предпросмотр устарел. Выберите копию заново")
        return File(previews,"$token.json")
    }

    /** Preview files can outlive an Activity or a cancelled return; keep their disk use bounded. */
    private fun sweepPreviews(current:File) = synchronized(previews) {
        val expiration=System.currentTimeMillis()-24*60*60*1000L
        val previous=previews.listFiles().orEmpty().filter {it!=current && it.name.matches(Regex("[0-9a-f-]{36}\\.json"))}
            .sortedByDescending {it.lastModified()}
        for((index,file) in previous.withIndex())if(index>=3 || file.lastModified()<expiration)file.delete()
    }
}
