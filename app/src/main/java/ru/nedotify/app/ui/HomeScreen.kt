package ru.nedotify.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import ru.nedotify.app.ui.state.*
import ru.nedotify.app.ui.tokens.DesignTokens
import java.time.LocalTime

/** B: collection and recent music lead; every count and recommendation comes from device data. */
@Composable
fun HomeScreen(model:HomeViewModel,library:LibraryViewModel,downloads:DownloadsViewModel,playback:PlaybackConnection,
    onImport:()->Unit,onSearch:()->Unit,onLibrary:(String)->Unit,onOpenPlaylist:(String)->Unit={library.openPlaylist(it)}) {
    val tracks by model.tracks.collectAsStateWithLifecycle()
    val recent by model.recent.collectAsStateWithLifecycle()
    val playlists by model.playlists.collectAsStateWithLifecycle()
    val recommendations by model.discovery.recommendation.collectAsStateWithLifecycle()
    val favorites=remember(tracks){tracks.filter {it.favorite}}
    val local=remember(tracks){tracks.filter {it.source=="local" || it.localUri.isNotBlank()}}
    LaunchedEffect(model.discovery) {model.discovery.loadRecommendations()}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=DesignTokens.Spacing.xl.dp,vertical=DesignTokens.Spacing.md.dp),
        verticalArrangement=Arrangement.spacedBy(DesignTokens.Spacing.lg.dp)) {
        item(key="heading") {GreetingTitle()}
        item(key="collection-command") {
            Column(Modifier.fillMaxWidth().glass().clickable(role=Role.Button,onClickLabel="Открыть коллекцию",onClick={onLibrary("all")})
                .padding(DesignTokens.Spacing.xl.dp),verticalArrangement=Arrangement.spacedBy(DesignTokens.Spacing.md.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LibraryMusic,null,tint=Blue,modifier=Modifier.size(28.dp))
                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                        Text("Ваша коллекция",style=MaterialTheme.typography.titleMedium)
                        Text("${tracks.size} треков · ${playlists.size} плейлистов",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,tint=Blue)
                }
            }
        }
        item(key="shortcuts") {
            LazyRow(horizontalArrangement=Arrangement.spacedBy(DesignTokens.Spacing.sm.dp)) {
                item {BrowseChip("Любимые · ${favorites.size}",Icons.Outlined.FavoriteBorder){onLibrary("favorites")}}
                item {BrowseChip("Скачано",Icons.Outlined.Download){onLibrary("downloads")}}
                item {BrowseChip("На устройстве · ${local.size}",Icons.Outlined.FolderOpen){onLibrary("local")}}
                item {BrowseChip("Поиск",Icons.Outlined.Search,onSearch)}
            }
        }
        if(recent.isNotEmpty()) {
            item(key="recent-heading") {BrowseHeading("Недавно слушали","Вся коллекция"){onLibrary("all")}}
            items(recent.take(4),key={"recent:${it.id}"}) {track->LibraryTrackRow(track,library,downloads,playback,recent)}
        }
        if(tracks.isEmpty()) {
            item(key="empty") {EmptyState("Здесь начнётся ваша музыка","Добавьте аудиофайлы или найдите любимую запись.","Добавить файлы",onImport)}
            item(key="empty-search") {OutlinedButton(onSearch,Modifier.fillMaxWidth().heightIn(min=48.dp)) {Icon(Icons.Outlined.Search,null);Spacer(Modifier.width(8.dp));Text("Найти музыку")}}
        } else {
            item(key="flow") {PersonalFlowCard {playback.startFlow()}}
        }
        if(playlists.isNotEmpty()) {
            item(key="playlist-heading") {BrowseHeading("Мои плейлисты","Все"){onLibrary("playlists")}}
            items(playlists.take(3),key={"playlist:${it.playlist.id}"}) {item->
                Row(Modifier.fillMaxWidth().heightIn(min=76.dp).clickable(role=Role.Button,onClick={onOpenPlaylist(item.playlist.id)})
                    .padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh,RoundedCornerShape(DesignTokens.Radius.small.dp)),contentAlignment=Alignment.Center) {Icon(Icons.Outlined.LibraryMusic,null,tint=Blue)}
                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                        Text(item.playlist.name,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("${item.count} треков",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key="recommendations") {RecommendationSection(recommendations,model.discovery::refreshRecommendations,playback::play)}
        if(favorites.isNotEmpty()) {
            item(key="favorites-heading") {BrowseHeading("Любимая музыка","Все"){onLibrary("favorites")}}
            item(key="favorites") {LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {items(favorites.take(8),key={it.id}) {track->BrowseMusicCard(track){playback.play(track,favorites)}}}}
        }
        if(recent.isEmpty() && tracks.isNotEmpty()) {
            item(key="tracks-heading") {BrowseHeading("Треки коллекции","Все"){onLibrary("all")}}
            items(tracks.take(5),key={"track:${it.id}"}) {track->LibraryTrackRow(track,library,downloads,playback,tracks,allowDelete=true)}
        }
        item(key="bottom-space") {Spacer(Modifier.height(8.dp))}
    }
}

@Composable
private fun GreetingTitle() {
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val hour by produceState(LocalTime.now().hour,lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while(true) {value=LocalTime.now().hour;kotlinx.coroutines.delay(60_000)}
        }
    }
    PageTitle(greeting(hour),"Побудьте в своём ритме")
}