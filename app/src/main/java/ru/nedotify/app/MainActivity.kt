package ru.nedotify.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.provider.Settings
import ru.nedotify.app.data.AuthPkce
import ru.nedotify.app.ui.*
import ru.nedotify.app.ui.state.*

/**
 * The OAuth callback that was already delivered.
 *
 * The marker lives in a ViewModel on purpose: it survives a configuration
 * change - the same callback must not be delivered twice - and it does not
 * survive a process death - the callback must be delivered again, or the login
 * it belongs to is lost.
 */
class OAuthDeliveryViewModel:ViewModel() {
    private var delivered:String?=null
    /** Whether the callback must be delivered now. */
    fun take(uri:String):Boolean=if(delivered==uri)false else {delivered=uri;true}
}

class MainActivity:ComponentActivity() {
    private val home:HomeViewModel by viewModels()
    private val library:LibraryViewModel by viewModels()
    private val search:SearchViewModel by viewModels()
    private val downloads:DownloadsViewModel by viewModels()
    private val settings:SettingsViewModel by viewModels()
    private val profile:ProfileViewModel by viewModels()
    private val lyrics:LyricsViewModel by viewModels()
    private val connections:ConnectionsViewModel by viewModels()
    private val transfers:TransfersViewModel by viewModels()
    private val events get()=(application as NeDotifyApp).uiEvents
    private val playback get()=(application as NeDotifyApp).playbackConnection
    private val delivery:OAuthDeliveryViewModel by viewModels()
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);readOAuth(intent)}
    /**
     * The OAuth callback of an intent.
     *
     * The callback is read on **every** creation of the Activity: after a
     * process death the intent is delivered again, and the login it carries
     * must still be finishable. The marker of the delivery lives in a
     * ViewModel, so a rotation does not deliver the same callback twice, while
     * a process death does.
     */
    private fun readOAuth(intent:Intent) {
        val uri=intent.data?:return
        if(!AuthPkce.isRedirect(uri.toString()))return
        if(delivery.take(uri.toString()))connections.handleSpotifyRedirect(uri.toString())
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readOAuth(intent)
        setContent {
            LaunchedEffect(events) {events.openExternalUrl.collect {url->
                val uri=url.toUri()
                if(uri.scheme=="https" && uri.host=="accounts.spotify.com")try {startActivity(Intent(Intent.ACTION_VIEW,uri))}
                catch(_:Exception) {events.message.value="Не удалось открыть браузер для входа"}
            }}
            val appearance by settings.appearance.collectAsStateWithLifecycle()
            val dark=appearance.mode=="dark" || appearance.mode=="system" && isSystemInDarkTheme()
            SideEffect {
                val bars=if(dark)SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle=bars,navigationBarStyle=bars)
            }
            val documents=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                val accepted=uris.filter { uri ->
                    try {contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);true}
                    catch(_:SecurityException){false}
                }
                if(accepted.size!=uris.size)events.message.value="Часть файлов нельзя сохранить. Выберите их через другое хранилище"
                transfers.importUris(accepted)
            }
            var backupPickerOpen by rememberSaveable {mutableStateOf(false)}
            val exportBackup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
                backupPickerOpen=false
                uri?.let(transfers::exportBackup)
            }
            val importBackup=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
                backupPickerOpen=false
                uri?.let(transfers::inspectBackup)
            }
            var desktopOrigin by rememberSaveable {mutableStateOf("")}
            val desktopImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {uris->
                backupPickerOpen=false
                if(uris.isNotEmpty())transfers.inspectDesktopBackup(uris,desktopOrigin)
            }
            var avatarPickerOpen by rememberSaveable {mutableStateOf(false)}
            val avatar=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
                avatarPickerOpen=false
                if(uri!=null)try {
                    contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    profile.attachAvatar(uri)
                } catch(_:SecurityException) {events.message.value="Хранилище не разрешило сохранить доступ к изображению"}
            }
            LaunchedEffect(events) {events.requestAvatar.collect {
                if(!avatarPickerOpen) {
                    avatarPickerOpen=true
                    try {avatar.launch(arrayOf("image/*"))}
                    catch(_:Exception) {avatarPickerOpen=false;events.message.value="Не удалось открыть выбор изображения"}
                }
            }}
            var pendingLocalTrackId by rememberSaveable {mutableStateOf<String?>(null)}
            val localFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
                val trackId=pendingLocalTrackId
                pendingLocalTrackId=null
                if(uri!=null && trackId!=null) {
                    try {
                        contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        transfers.attachLocalFile(trackId,uri)
                    } catch(_:SecurityException) {
                        events.message.value="Хранилище не разрешило сохранить доступ к файлу. Выберите его через другое хранилище"
                    }
                }
            }
            LaunchedEffect(events) {events.requestLocalFile.collect {trackId->
                if(pendingLocalTrackId==null) {
                    pendingLocalTrackId=trackId
                    try {localFile.launch(arrayOf("audio/*"))}
                    catch(_:Exception) {
                        pendingLocalTrackId=null
                        events.message.value="Не удалось открыть выбор аудиофайла"
                    }
                }
            }}
            val musicPermission=if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            val activityPreferences=remember {getPreferences(MODE_PRIVATE)}
            var musicPermissionAsked by rememberSaveable {mutableStateOf(activityPreferences.getBoolean("music_permission_asked",false))}
            var scanAccessDialog by rememberSaveable {mutableStateOf(false)}
            val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                if(allowed)transfers.scan() else events.message.value="Доступ к музыке не разрешён. Можно выбрать отдельные файлы"
            }
            var notificationsAsked by rememberSaveable {mutableStateOf(false)}
            val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {allowed->
                if(!allowed)events.message.value="Статус загрузок доступен в библиотеке. Уведомления можно включить в настройках Android"
            }
            LaunchedEffect(events) {events.requestNotifications.collect {
                if(Build.VERSION.SDK_INT>=33 && !notificationsAsked && ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                    notificationsAsked=true;notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }}
            if(scanAccessDialog) {
                val canRequest=shouldShowRequestPermissionRationale(musicPermission) || !musicPermissionAsked
                NeDotifyTheme(dark,appearance.accent) {
                AlertDialog(onDismissRequest={scanAccessDialog=false},title={Text("Доступ к музыке")},
                    text={Text(if(canRequest)"Разрешите доступ к аудио, чтобы найти музыку в хранилище. Отдельные файлы можно выбрать без этого разрешения." else "Android больше не показывает запрос. Разрешите доступ к музыке в настройках приложения или выберите отдельные файлы.")},
                    confirmButton={TextButton({
                        scanAccessDialog=false
                        if(canRequest) {
                            musicPermissionAsked=true;activityPreferences.edit {putBoolean("music_permission_asked",true)}
                            permission.launch(musicPermission)
                        } else try {startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,"package:$packageName".toUri()))}
                        catch(_:Exception) {events.message.value="Не удалось открыть настройки Android"}
                    }){Text(if(canRequest)"Разрешить" else "Настройки Android")}},
                    dismissButton={TextButton({scanAccessDialog=false}){Text("Отмена")}})
                }
            }
            MusicApp(home,library,search,downloads,settings,profile,lyrics,connections,transfers,playback,events,onImport={documents.launch(arrayOf("audio/*"))},onScan={
                if(ContextCompat.checkSelfPermission(this@MainActivity,musicPermission)==PackageManager.PERMISSION_GRANTED)transfers.scan()
                else scanAccessDialog=true
            },onExportBackup={
                if(!backupPickerOpen && !transfers.backupBusy.value && transfers.backupPreview.value==null) {
                    backupPickerOpen=true
                    try {exportBackup.launch("NeDotify-library-${java.time.LocalDate.now()}.json")}
                    catch(_:Exception) {backupPickerOpen=false;events.message.value="Не удалось выбрать место для резервной копии"}
                }
            },onImportBackup={
                if(!backupPickerOpen && !transfers.backupBusy.value && transfers.backupPreview.value==null) {
                    backupPickerOpen=true
                    try {importBackup.launch(arrayOf("application/json","text/json","application/octet-stream"))}
                    catch(_:Exception) {backupPickerOpen=false;events.message.value="Не удалось открыть выбор резервной копии"}
                }
            },backupPickerOpen=backupPickerOpen,onImportDesktop={origin->
                if(!backupPickerOpen && !transfers.backupBusy.value && transfers.backupPreview.value==null) {
                    desktopOrigin=origin;backupPickerOpen=true
                    try {desktopImport.launch(arrayOf("application/vnd.sqlite3","application/x-sqlite3","application/octet-stream","*/*"))}
                    catch(_:Exception) {backupPickerOpen=false;events.message.value="Не удалось открыть выбор БД"}
                }
            })
        }
    }
}
