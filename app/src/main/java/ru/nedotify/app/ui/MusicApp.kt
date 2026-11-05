package ru.nedotify.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.*
import ru.nedotify.app.data.*
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicApp(model:MusicViewModel,onImport:()->Unit,onScan:()->Unit,onExportBackup:()->Unit,onImportBackup:()->Unit,backupPickerOpen:Boolean=false,onImportDesktop:(String)->Unit={}) {
    val appearance by model.appearance.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val dark=appearance.mode=="dark" || appearance.mode=="system" && isSystemInDarkTheme()
    val powerSave=rememberPowerSaveMode()
    var backStack by rememberSaveable {mutableStateOf(listOf("home"))}
    val route=backStack.last()
    val screenStates=rememberSaveableStateHolder()
    fun navigate(route:String) {backStack=navigateTo(backStack,route)}
    val requestedRoute by model.requestedRoute.collectAsStateWithLifecycle()
    LaunchedEffect(requestedRoute) {requestedRoute?.let {navigate(it);model.requestedRoute.value=null}}

    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var newPlaylist by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val haze=remember { HazeState() }
    val snack=remember { SnackbarHostState() }
    LaunchedEffect(message) {message?.let {snack.showSnackbar(it);model.acknowledgeMessage(it)}}
    PreferenceResetNotice(model,snack)
    BackHandler(backStack.size>1 && !fullPlayer) {backStack=navigateBack(backStack)}
    val openLibrary:(String)->Unit={filter->model.setLibraryFilter(filter);navigate("library")}
    NeDotifyTheme(dark,appearance.accent) {
        CompositionLocalProvider(LocalBackdrop provides haze,LocalGlass provides appearance.glass,LocalOffline provides appearance.offline,LocalPowerSave provides powerSave) {
            Box(Modifier.fillMaxSize()) {
                NightSky(Modifier.fillMaxSize().hazeSource(haze),dark,appearance.background)
                Scaffold(containerColor=Color.Transparent,snackbarHost={if(!fullPlayer)SnackbarHost(snack)},
                    topBar={
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LibraryMusic,null,tint=Blue,modifier=Modifier.size(27.dp))
                            Text("NeDotify",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f).padding(start=9.dp))
                            IconButton({navigate("profile")}){Icon(Icons.Outlined.AccountCircle,"Профиль")}
                            IconButton({navigate("settings")}){Icon(Icons.Outlined.Settings,"Настройки")}
                        }
                    },bottomBar={
                        Column(Modifier.navigationBarsPadding().imePadding()) {
                            MiniPlayerHost(model,onOpen={fullPlayer=true})
                            NavigationBar(containerColor=Color.Transparent,modifier=Modifier.heightIn(min=68.dp)) {
                                listOf(Triple("home","Главная",Icons.Outlined.Home),Triple("search","Поиск",Icons.Outlined.Search),Triple("library","Библиотека",Icons.Outlined.LibraryMusic)).forEach { (id,label,icon)->
                                    NavigationBarItem(route==id,{navigate(id)},icon={Icon(icon,null)},label={Text(label,fontSize=11.sp)},
                                        colors=NavigationBarItemDefaults.colors(selectedIconColor=Blue,selectedTextColor=Blue,indicatorColor=Blue.copy(alpha=.12f)))
                                }
                            }
                        }
                    }) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize(),contentAlignment=Alignment.TopCenter) {
                    Box(Modifier.widthIn(max=840.dp).fillMaxSize()) {
                        screenStates.SaveableStateProvider(route) {when(route) {
                            "home"->HomeScreen(model,onImport,{navigate("search")},openLibrary)
                            "search"->SearchScreen(model)
                            "library"->LibraryScreen(model,onImport,{newPlaylist=true})
                            "settings"->SettingsScreen(model,onImport,onScan,onExportBackup,onImportBackup,backupPickerOpen,onImportDesktop)
                            "profile"->ProfileScreen(model,openLibrary,{navigate("settings")})
                        }}
                    }
                    }
                }
                if(fullPlayer) {
                    ModalBottomSheet(onDismissRequest={fullPlayer=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
                        containerColor=MaterialTheme.colorScheme.background,contentWindowInsets={WindowInsets.safeDrawing}) {
                        Box {
                            PlayerScreen(model)
                            SnackbarHost(snack,Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
                if(newPlaylist)AlertDialog(onDismissRequest={newPlaylist=false},title={Text("Новый плейлист")},
                    text={OutlinedTextField(name,{name=it.take(120)},label={Text("Название")},singleLine=true)},
                    confirmButton={TextButton({model.createPlaylist(name);name="";newPlaylist=false},enabled=name.isNotBlank()){Text("Создать")}},
                    dismissButton={TextButton({newPlaylist=false}){Text("Отмена")}})
                BackupPreviewHost(model)
                CatalogBrowserHost(model)
            }
        }
    }
}

@Composable
fun TrackRow(track:Track,model:MusicViewModel,queue:List<Track>,onPlay:(()->Unit)?=null,onRemoveFromPlaylist:(()->Unit)?=null,allowDelete:Boolean=false) {
    var menu by remember { mutableStateOf(false) }
    var choosePlaylist by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(track.id) {mutableStateOf(false)}
    val active by remember(model,track.id) {model.playback.map {it.id==track.id}.distinctUntilChanged()}.collectAsStateWithLifecycle(false)
    val playlists by model.playlists.collectAsStateWithLifecycle()
    val downloadStates by model.downloadStates.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable(onClick=onPlay?:{model.play(track,queue)}).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Cover(track.coverUrl,Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
            Text(track.title,maxLines=2,overflow=TextOverflow.Ellipsis,color=if(active)Blue else MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
            Text(track.artist,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)
            if(track.source=="local" && track.localUri.isBlank())Text("Нужно выбрать аудиофайл",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton({menu=true}){Icon(Icons.Outlined.MoreVert,"Действия с треком")}
            DropdownMenu(menu,{menu=false}) {
                DropdownMenuItem(text={Text(if(track.favorite)"Убрать из избранного" else "В избранное")},onClick={model.favorite(track);menu=false})
                DropdownMenuItem(text={Text("Добавить в плейлист")},onClick={choosePlaylist=true;menu=false})
                if(onRemoveFromPlaylist!=null)DropdownMenuItem(text={Text("Убрать из плейлиста")},onClick={menu=false;onRemoveFromPlaylist()})
                if(track.source=="local")
                    DropdownMenuItem(text={Text("Указать файл")},leadingIcon={Icon(Icons.Outlined.FolderOpen,null)},onClick={model.selectLocalFile(track);menu=false})
                if(track.source!="local" && track.localUri.isBlank() && downloadStates[track.id] !in setOf("complete","queued","preparing","downloading","removing"))
                    DropdownMenuItem(text={Text("Скачать для офлайн")},leadingIcon={Icon(Icons.Outlined.Download,null)},onClick={model.download(track);menu=false})
                if(downloadStates[track.id]=="complete")DropdownMenuItem(text={Text("Скачано на устройство")},onClick={menu=false},enabled=false)
                if(allowDelete)DropdownMenuItem(text={Text("Удалить из библиотеки")},leadingIcon={Icon(Icons.Outlined.DeleteOutline,null)},onClick={menu=false;confirmDelete=true})
            }
        }
    }
    if(confirmDelete)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Удалить трек из библиотеки?")},
        text={Text("${track.title} будет удалён из библиотеки, плейлистов, истории и очереди. Исходный аудиофайл останется на устройстве.")},
        confirmButton={TextButton({model.deleteTrack(track);confirmDelete=false}){Text("Удалить")}},dismissButton={TextButton({confirmDelete=false}){Text("Отмена")}})
    if(choosePlaylist)AlertDialog(onDismissRequest={choosePlaylist=false},title={Text("Выберите плейлист")},
        text={Column(Modifier.verticalScroll(rememberScrollState())) {
            if(playlists.isEmpty())Text("Создайте плейлист в библиотеке")
            playlists.forEach { item->TextButton({model.addToPlaylist(item.playlist.id,track);choosePlaylist=false}){Text(item.playlist.name)} }
        }},confirmButton={TextButton({choosePlaylist=false}){Text("Закрыть")}})
}


@Composable
private fun MiniPlayerHost(model:MusicViewModel,onOpen:()->Unit) {
    val now by model.playback.collectAsStateWithLifecycle()
    val tracks by model.tracks.collectAsStateWithLifecycle()
    val index=remember(tracks){tracks.associateBy {it.id}}
    if(now.id.isNotBlank())MiniPlayer(model,now,index[now.id]?.favorite==true,onOpen,model::toggle,{index[now.id]?.let(model::favorite)})
}

@Composable
private fun BackupPreviewHost(model:MusicViewModel) {
    val preview by model.backupPreview.collectAsStateWithLifecycle()
    val busy by model.backupBusy.collectAsStateWithLifecycle()
    val status by model.backupStatus.collectAsStateWithLifecycle()
    val error by model.backupError.collectAsStateWithLifecycle()
    val notice by model.backupNotice.collectAsStateWithLifecycle()
    preview?.let {BackupImportDialog(it,busy,status,error,model::restoreBackup,model::dismissBackupPreview,notice)}
}

@Composable
private fun CatalogBrowserHost(model:MusicViewModel) {
    val catalog by model.catalog.collectAsStateWithLifecycle()
    CatalogBrowser(catalog,model::catalogQuery,model::catalogSearch,model::catalogPlaylists,model::catalogLiked,
        model::catalogPlaylist,model::playCatalogTrack,model::importCatalog,model::dismissCatalog,
        model::pauseCatalogImport,model::resumeCatalogImport,model::discardCatalogImport)
}

@Composable
private fun PreferenceResetNotice(model:MusicViewModel,snack:SnackbarHostState) {
    val reset by model.preferenceReset.collectAsStateWithLifecycle()
    LaunchedEffect(reset) {reset?.let {snack.showSnackbar(it.message());model.acknowledgePreferenceReset(it)}}
}
