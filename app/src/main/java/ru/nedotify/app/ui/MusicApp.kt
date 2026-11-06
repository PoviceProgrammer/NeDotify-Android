package ru.nedotify.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.*
import ru.nedotify.app.data.message
import ru.nedotify.app.ui.state.*
import ru.nedotify.app.ui.tokens.DesignTokens

/** The shell owns navigation and platform presentation, never domain state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicApp(home:HomeViewModel,library:LibraryViewModel,search:SearchViewModel,
    downloads:DownloadsViewModel,settings:SettingsViewModel,profile:ProfileViewModel,
    lyrics:LyricsViewModel,connections:ConnectionsViewModel,transfers:TransfersViewModel,
    playback:PlaybackConnection,events:UiEvents,
    onImport:()->Unit,onScan:()->Unit,onExportBackup:()->Unit,onImportBackup:()->Unit,
    backupPickerOpen:Boolean=false,onImportDesktop:(String)->Unit={}) {
    val appearance by settings.appearance.collectAsStateWithLifecycle()
    val dark=appearance.mode=="dark" || appearance.mode=="system" && isSystemInDarkTheme()
    val powerSave=rememberPowerSaveMode()
    var encodedNavigation by rememberSaveable {mutableStateOf(encodeNavigation(AppNavigationState()))}
    val navigation=remember(encodedNavigation) {decodeNavigation(encodedNavigation)}
    fun navigate(next:AppNavigationState) {encodedNavigation=encodeNavigation(next)}
    val destination=navigation.current
    val screenStates=rememberSaveableStateHolder()
    val requestedRoute by events.requestedRoute.collectAsStateWithLifecycle()
    LaunchedEffect(requestedRoute) {requestedRoute?.let {route->
        val page=when(route) {"settings"->AppPage.SETTINGS;"profile"->AppPage.PROFILE;"search"->AppPage.SEARCH;"library"->AppPage.LIBRARY;else->null}
        page?.let {navigate(navigation.open(AppDestination(it)))}
        events.requestedRoute.compareAndSet(route,null)
    }}
    LaunchedEffect(destination) {
        when(destination.page) {
            AppPage.COLLECTION->library.openPlaylist(destination.itemId)
            AppPage.DOWNLOADS->library.setLibraryFilter("downloads")
            AppPage.LIBRARY->library.setLibraryFilter(destination.filter.ifEmpty {"all"})
            else->Unit
        }
    }
    val visited=remember {mutableSetOf<String>()}
    val activeKeys=navigation.stacks.values.flatten().map {"${it.page}:${it.itemId}:${it.filter}"}.toSet()
    LaunchedEffect(activeKeys) {
        (visited-activeKeys).forEach {screenStates.removeState(it)}
        visited.retainAll(activeKeys)
    }
    val message by events.message.collectAsStateWithLifecycle()
    val snack=remember {SnackbarHostState()}
    LaunchedEffect(message) {message?.let {snack.showSnackbar(it);events.acknowledgeMessage(it)}}
    val reset by settings.preferenceReset.collectAsStateWithLifecycle()
    LaunchedEffect(reset) {reset?.let {snack.showSnackbar(it.message());settings.acknowledgePreferenceReset(it)}}
    BackHandler(navigation.canGoBack) {navigate(navigation.back())}
    var newPlaylist by rememberSaveable {mutableStateOf(false)}
    var name by rememberSaveable {mutableStateOf("")}
    val haze=remember {HazeState()}
    val openLibrary:(String)->Unit={filter->
        library.setLibraryFilter(filter)
        navigate(navigation.open(AppDestination(AppPage.LIBRARY,filter=filter)))
    }
    val openPlaylist:(String)->Unit={id->
        library.openPlaylist(id)
        navigate(navigation.open(AppDestination(AppPage.COLLECTION,id)))
    }
    NeDotifyTheme(dark,appearance.accent) {
        CompositionLocalProvider(LocalBackdrop provides haze,LocalGlass provides appearance.glass,
            LocalOffline provides appearance.offline,LocalPowerSave provides powerSave) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val rail=maxWidth>=DesignTokens.Layout.railBreakpoint.dp
                val playerPane=maxWidth>=DesignTokens.Layout.paneBreakpoint.dp && maxHeight>=480.dp
                LaunchedEffect(playerPane,navigation.playerOpen) {
                    if(playerPane && navigation.playerOpen)navigate(navigation.showPlayer(false))
                }
                NightSky(Modifier.fillMaxSize().hazeSource(haze),dark,appearance.background)
                Scaffold(containerColor=Color.Transparent,snackbarHost={if(!navigation.playerOpen || playerPane)SnackbarHost(snack)},
                    topBar={
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=DesignTokens.Spacing.lg.dp),
                            verticalAlignment=Alignment.CenterVertically) {
                            if(navigation.canGoBack && !navigation.playerOpen)IconButton({navigate(navigation.back())}) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")
                            } else Icon(Icons.Outlined.LibraryMusic,null,tint=Blue,modifier=Modifier.size(28.dp))
                            Text("NeDotify",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f).padding(start=8.dp))
                            IconButton({navigate(navigation.selectTab(RootTab.SEARCH))}){Icon(Icons.Outlined.Search,"Открыть поиск")}
                            IconButton({navigate(navigation.open(AppDestination(AppPage.PROFILE)))}){Icon(Icons.Outlined.AccountCircle,"Профиль")}
                            IconButton({navigate(navigation.open(AppDestination(AppPage.SETTINGS)))}){Icon(Icons.Outlined.Settings,"Настройки")}
                        }
                    },bottomBar={
                        if(!rail)Column(Modifier.navigationBarsPadding().imePadding()) {
                            MiniPlayerHost(playback,library) {navigate(navigation.showPlayer())}
                            RootNavigation(navigation.selectedTab,false) {navigate(navigation.selectTab(it))}
                        }
                    }) {padding->
                    Row(Modifier.padding(padding).fillMaxSize()) {
                        if(rail)NavigationRail(Modifier.navigationBarsPadding(),containerColor=Color.Transparent) {
                            RootNavigation(navigation.selectedTab,true) {navigate(navigation.selectTab(it))}
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.TopCenter) {
                                val key="${destination.page}:${destination.itemId}:${destination.filter}"
                                SideEffect {visited.add(key)}
                                screenStates.SaveableStateProvider(key) {
                                    when(destination.page) {
                                        AppPage.HOME->HomeScreen(home,library,downloads,playback,onImport,{navigate(navigation.selectTab(RootTab.SEARCH))},openLibrary,openPlaylist)
                                        AppPage.SEARCH->SearchScreen(search,library,downloads,playback)
                                        AppPage.LIBRARY,AppPage.COLLECTION,AppPage.DOWNLOADS->LibraryScreen(library,downloads,playback,onImport,{newPlaylist=true},openPlaylist,onFilter=openLibrary)
                                        AppPage.PROFILE->ProfileScreen(profile,library,downloads,playback,openLibrary,{navigate(navigation.open(AppDestination(AppPage.SETTINGS)))})
                                        AppPage.SETTINGS->SettingsScreen(settings,connections,transfers,downloads,search,onImport,onScan,onExportBackup,onImportBackup,backupPickerOpen,onImportDesktop)
                                    }
                                }
                            }
                            if(rail && !playerPane)Box(Modifier.navigationBarsPadding()) {
                                MiniPlayerHost(playback,library) {navigate(navigation.showPlayer())}
                            }
                        }
                        if(playerPane) {
                            VerticalDivider()
                            Box(Modifier.width(340.dp).fillMaxHeight().navigationBarsPadding()) {
                                PlayerScreen(playback,library,lyrics,settings)
                            }
                        }
                    }
                }
                if(navigation.playerOpen && !playerPane)ModalBottomSheet(
                    onDismissRequest={navigate(navigation.showPlayer(false))},
                    sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
                    containerColor=MaterialTheme.colorScheme.background,contentWindowInsets={WindowInsets.safeDrawing}) {
                    Box {
                        PlayerScreen(playback,library,lyrics,settings)
                        SnackbarHost(snack,Modifier.align(Alignment.BottomCenter))
                    }
                }
                if(newPlaylist)AlertDialog(onDismissRequest={newPlaylist=false},title={Text("Новый плейлист")},
                    text={OutlinedTextField(name,{name=it.take(120)},label={Text("Название")},singleLine=true)},
                    confirmButton={TextButton({library.createPlaylist(name);name="";newPlaylist=false},enabled=name.isNotBlank()){Text("Создать")}},
                    dismissButton={TextButton({newPlaylist=false}){Text("Отмена")}})
                BackupPreviewHost(transfers)
                CatalogBrowserHost(connections)
            }
        }
    }
}

@Composable
internal fun RootNavigation(selected:RootTab,rail:Boolean,onSelect:(RootTab)->Unit) {
    val items=listOf(Triple(RootTab.HOME,"Главная",Icons.Outlined.Home),Triple(RootTab.SEARCH,"Поиск",Icons.Outlined.Search),Triple(RootTab.LIBRARY,"Библиотека",Icons.Outlined.LibraryMusic))
    if(rail)items.forEach {(tab,label,icon)->NavigationRailItem(selected==tab,{onSelect(tab)},icon={Icon(icon,null)},label={Text(label)})}
    else NavigationBar(containerColor=Color.Transparent) {
        items.forEach {(tab,label,icon)->NavigationBarItem(selected==tab,{onSelect(tab)},icon={Icon(icon,null)},label={Text(label)})}
    }
}

@Composable
private fun MiniPlayerHost(playback:PlaybackConnection,library:LibraryViewModel,onOpen:()->Unit) {
    val now by playback.playback.collectAsStateWithLifecycle()
    val tracks by library.tracks.collectAsStateWithLifecycle()
    val active=remember(tracks,now.id) {tracks.firstOrNull {it.id==now.id}}
    if(now.id.isNotBlank())MiniPlayer(playback,now,active?.favorite==true,onOpen,playback::toggle,{active?.let(library::favorite)})
}

@Composable
private fun BackupPreviewHost(transfers:TransfersViewModel) {
    val preview by transfers.backupPreview.collectAsStateWithLifecycle()
    val busy by transfers.backupBusy.collectAsStateWithLifecycle()
    val status by transfers.backupStatus.collectAsStateWithLifecycle()
    val error by transfers.backupError.collectAsStateWithLifecycle()
    val notice by transfers.backupNotice.collectAsStateWithLifecycle()
    preview?.let {BackupImportDialog(it,busy,status,error,transfers::restoreBackup,transfers::dismissBackupPreview,notice)}
}

@Composable
private fun CatalogBrowserHost(connections:ConnectionsViewModel) {
    val catalog by connections.catalog.collectAsStateWithLifecycle()
    CatalogBrowser(catalog,connections::catalogQuery,connections::catalogSearch,connections::catalogPlaylists,connections::catalogLiked,
        connections::catalogPlaylist,connections::playCatalogTrack,connections::importCatalog,connections::dismissCatalog,
        connections::pauseCatalogImport,connections::resumeCatalogImport,connections::discardCatalogImport)
}
