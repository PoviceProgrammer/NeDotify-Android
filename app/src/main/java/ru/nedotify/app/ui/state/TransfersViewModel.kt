package ru.nedotify.app.ui.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.nedotify.app.data.*
import ru.nedotify.app.ui.*

class TransfersViewModel(application:Application,savedState:SavedStateHandle):FeatureViewModel(application,savedState) {
    private val _backupBusy=MutableStateFlow(false)
    val backupBusy=_backupBusy.asStateFlow()
    private val _backupStatus=MutableStateFlow("")
    val backupStatus=_backupStatus.asStateFlow()
    private val _backupPreview=MutableStateFlow<BackupSummary?>(null)
    val backupPreview=_backupPreview.asStateFlow()
    private val _backupError=MutableStateFlow<String?>(null)
    val backupError=_backupError.asStateFlow()
    private val _backupNotice=MutableStateFlow("")
    val backupNotice=_backupNotice.asStateFlow()
    private var pendingBackup:BackupPreview?=null
    private val attachingLocal=mutableSetOf<String>()
    fun importUris(uris:List<Uri>)=task {
        if(uris.isEmpty())return@task
        try {
            val result=repository.importUris(uris)
            message.value="Добавлено файлов: ${result.imported}"+if(result.failed>0)". Не удалось прочитать: ${result.failed}. Проверьте доступ к файлам" else ""
        } finally {withContext(NonCancellable) {uris.forEach {releaseDocumentIfUnused(it.toString())}}}
    }
    fun attachLocalFile(trackId:String,uri:Uri) {
        if(!attachingLocal.add(trackId))return
        viewModelScope.launch {
            var previousUri=""
            try {
                previousUri=repository.dao.track(trackId)?.localUri.orEmpty()
                repository.attachLocal(trackId,uri)
                val track=repository.dao.track(trackId)
                if(track==null){message.value="Трек не найден в библиотеке";return@launch}
                val saved=repository.dao.download(trackId)?.takeIf {it.state=="complete"&&it.uri.isNotBlank()}
                // PlaybackConnection performs the controller mutation on its owning main scope.
                val replaced=app.playbackConnection.replaceTrack(track,saved)
                message.value=if(replaced>0)"Аудиофайл связан с треком, очередь обновлена" else "Аудиофайл связан с треком"
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){message.value=error.message?:"Не удалось связать аудиофайл с треком"}
            finally {attachingLocal.remove(trackId);withContext(NonCancellable) {
                releaseDocumentIfUnused(previousUri);releaseDocumentIfUnused(uri.toString())
            }}
        }
    }
    fun exportBackup(uri:Uri)=backupTask("Сохраняем резервную копию…") {
        val summary=repository.backups.export(uri)
        message.value="Копия сохранена: ${summary.tracks} треков, ${summary.playlists} плейлистов"+if(summary.droppedSessions>0)". В копию не вошли старые прослушивания: ${summary.droppedSessions}; история на устройстве сохранена" else ""
    }
    fun inspectBackup(uri:Uri)=backupTask("Проверяем резервную копию…") {
        _backupNotice.value=""
        discardPendingBackup()
        _backupPreview.value=null
        _backupError.value=null
        val backup=repository.backups.inspectPreview(uri)
        pendingBackup=backup
        _backupPreview.value=backup.summary
    }
    fun inspectDesktopBackup(uris:List<Uri>,origin:String)=backupTask("Проверяем копию библиотеки ПК…") {
        discardPendingBackup();_backupPreview.value=null;_backupNotice.value=""
        val preview=repository.desktopImport.inspectDocuments(uris,origin)
        pendingBackup=repository.backups.stageDesktopImport(preview.backup,preview.notice);_backupPreview.value=preview.backup.summary;_backupNotice.value=preview.notice
    }
    fun dismissBackupPreview() {
        if(_backupBusy.value)return
        discardPendingBackup()
        _backupPreview.value=null
        _backupError.value=null
        _backupNotice.value=""
    }
    fun restoreBackup() {
        val backup=pendingBackup?:return
        backupTask("Объединяем библиотеку…") {
            val summary=repository.backups.restore(backup)
            discardPendingBackup()
            _backupPreview.value=null
            message.value="Копия обработана: ${summary.tracks} треков, ${summary.playlists} плейлистов"
        }
    }
    private fun backupTask(status:String,block:suspend ()->Unit) {
        if(_backupBusy.value)return
        _backupBusy.value=true
        _backupStatus.value=status
        _backupError.value=null
        viewModelScope.launch {
            try {block()}
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:OutOfMemoryError){
                // An allocation failure is an `Error`, not an `Exception`: it used to escape
                // every `catch (Exception)` and killed the process.
                val text="Копия слишком велика для памяти устройства. Повторите действие с меньшей копией"
                _backupError.value=text
                message.value=text
            }
            catch(error:Exception){
                val text=error.message?:"Не удалось обработать резервную копию. Повторите действие"
                _backupError.value=text
                message.value=text
            }
            finally {_backupBusy.value=false;_backupStatus.value=""}
        }
    }
    fun scan()=task {repository.scanLocal()}
    private fun discardPendingBackup() {
        val preview=pendingBackup?:return
        pendingBackup=null
        app.ioScope.launch {repository.backups.discard(preview)}
    }
    /** Release only a grant changed by our operation, after all durable references are checked. */

    override fun onCleared() {discardPendingBackup();super.onCleared()}
}
