package ru.nedotify.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalTime
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun HomeScreen(model:MusicViewModel,onImport:()->Unit,onSearch:()->Unit,onLibrary:(String)->Unit) {
    val tracks by model.tracks.collectAsStateWithLifecycle()
    val recent by model.recent.collectAsStateWithLifecycle()
    val playlists by model.playlists.collectAsStateWithLifecycle()
    val recommendations by model.discovery.recommendation.collectAsStateWithLifecycle()
    LaunchedEffect(model.discovery) { model.discovery.loadRecommendations() }
    val favorites=remember(tracks){tracks.filter {it.favorite}}
    val local=remember(tracks){tracks.filter {it.source=="local" || it.localUri.isNotBlank()}}
    val hero=recent.firstOrNull()?:favorites.firstOrNull()?:tracks.firstOrNull()
    val heroQueue=when {recent.isNotEmpty()->recent;favorites.isNotEmpty()->favorites;else->tracks}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item(key="heading") {
            GreetingTitle()
        }
        item(key="shortcuts") {
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                item {BrowseChip("Вся музыка",Icons.Outlined.MusicNote){onLibrary("all")}}
                item {BrowseChip("Любимые · ${favorites.size}",Icons.Outlined.FavoriteBorder){onLibrary("favorites")}}
                item {BrowseChip("Плейлисты · ${playlists.size}",Icons.Outlined.LibraryMusic){onLibrary("playlists")}}
                item {BrowseChip("На устройстве · ${local.size}",Icons.Outlined.FolderOpen){onLibrary("local")}}
            }
        }
        item(key="hero") {
            CollectionHero(hero,onPlay={hero?.let {model.play(it,heroQueue)}},onSearch=onSearch,onImport=onImport)
        }
        item(key="flow") { PersonalFlowCard { model.startFlow() } }
        item(key="recommendations") { RecommendationSection(recommendations, model.discovery::refreshRecommendations, model::play) }
        if(recent.isNotEmpty()) {
            item(key="recent-title") {BrowseHeading("Недавно прослушано","Все треки"){onLibrary("all")}}
            item(key="recent") {
                LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(recent,key={it.id}) {track->BrowseMusicCard(track){model.play(track,recent)}}
                }
            }
        }
        if(favorites.isNotEmpty()) {
            item(key="favorites-title") {BrowseHeading("Любимые треки","Показать все"){onLibrary("favorites")}}
            item(key="favorites") {
                LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(favorites.take(12),key={it.id}) {track->BrowseMusicCard(track){model.play(track,favorites)}}
                }
            }
        }
        item(key="collection-title") {BrowseHeading("Твоя коллекция",if(tracks.isNotEmpty())"Показать все" else null){onLibrary("all")}}
        if(tracks.isEmpty())item(key="empty") {
            EmptyState("Начни свою коллекцию","Найденные и добавленные треки появятся здесь. Любимая музыка будет всегда рядом.","Добавить файлы",onImport)
        }
        items(tracks.take(10),key={"collection:${it.id}"}) {TrackRow(it,model,tracks,allowDelete=true)}
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
    PageTitle(greeting(hour),"Музыка делает всё лучше")
}
