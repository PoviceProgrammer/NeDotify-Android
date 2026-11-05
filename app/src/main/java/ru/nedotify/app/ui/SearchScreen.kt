package ru.nedotify.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.nedotify.app.data.SearchResultGroup
import ru.nedotify.app.data.SearchResultGroups

@Composable
fun SearchScreen(model:MusicViewModel) {
    var query by rememberSaveable {mutableStateOf("")}
    var source by rememberSaveable {mutableStateOf("all")}
    var category by rememberSaveable {mutableStateOf("tracks")}
    var selectedGroupKey by rememberSaveable(query,source,category) {mutableStateOf<String?>(null)}
    val result by model.search.collectAsStateWithLifecycle()
    val history by model.searchHistory.collectAsStateWithLifecycle()
    val appearance by model.appearance.collectAsStateWithLifecycle()
    val keyboard=LocalSoftwareKeyboardController.current
    val listState=rememberLazyListState()
    val groups=remember(result.tracks,category) {when(category){"artists"->SearchResultGroups.artists(result.tracks);"albums"->SearchResultGroups.albums(result.tracks);else->emptyList()}}
    val selectedGroup=groups.firstOrNull {it.key==selectedGroupKey}
    fun submit(value:String=query) {
        query=value
        if(value.isNotBlank())model.rememberSearch(value)
        model.search(value,source)
        keyboard?.hide()
    }
    LaunchedEffect(appearance.offline){if(appearance.offline)source="local"}
    LaunchedEffect(query,source){model.search(query,source)}
    LaunchedEffect(category,selectedGroupKey){listState.scrollToItem(0)}
    LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item(key="heading") {PageTitle("Поиск","Найдите свою музыку")}
        item(key="query") {
            OutlinedTextField(query,{query=it.take(200)},modifier=Modifier.fillMaxWidth(),placeholder={Text("Трек или исполнитель")},
                label={Text("Поиск музыки")},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)},
                trailingIcon={if(query.isNotEmpty())IconButton({query=""}){Icon(Icons.Outlined.Close,"Очистить запрос")}},
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={submit()}),
                shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
        }
        item(key="sources") {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("all" to "Все","soundcloud" to "SoundCloud","youtube" to "YouTube Music","local" to "На устройстве").forEach {(id,name)->
                    FilterChip(selected=source==id,onClick={source=id},enabled=!appearance.offline || id=="local",label={Text(name)},
                        modifier=Modifier.heightIn(min=48.dp),leadingIcon={Icon(when(id){"all"->Icons.Outlined.Search;"soundcloud"->Icons.Outlined.Cloud;"youtube"->Icons.Outlined.PlayCircle;else->Icons.Outlined.FolderOpen},null,Modifier.size(17.dp))})
                }
            }
        }
        if(appearance.offline)item(key="offline") {Text("Офлайн: поиск по локальным и полностью скачанным трекам",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(source=="youtube")item(key="youtube-note") {Text("YouTube Music может ограничивать доступ. В таком случае попробуйте другой источник.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item(key="categories") {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("tracks" to "Треки","artists" to "Исполнители","albums" to "Альбомы").forEach {(id,title)->
                    FilterChip(category==id,{category=id},label={Text(title)},modifier=Modifier.heightIn(min=48.dp))
                }
            }
        }
        if(query.isBlank()) {
            if(history.isNotEmpty()) {
                item(key="history-title") {BrowseHeading("Недавние запросы","Очистить"){model.clearSearchHistory()}}
                items(history,key={"history:$it"}) {value->
                    SearchHistoryRow(value,onSearch={submit(value)},onRemove={model.removeSearch(value)})
                }
            } else item(key="search-empty") {EmptyState("Что послушаем?","Введите название трека или исполнителя. Выполненные запросы сохранятся здесь.")}
        } else {
            if(result.busy)item(key="loading") {
                Row(Modifier.fillMaxWidth().padding(vertical=24.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
                    Text("Ищем музыку…",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            result.error?.let {error->item(key="error") {EmptyState("Поиск не завершён",error,"Повторить",{model.search(query,source)})}}
            if(!result.busy && result.error==null && result.tracks.isEmpty())item(key="no-results") {
                EmptyState("Ничего не найдено",if(source=="local")"Проверьте название. Добавить аудиофайлы можно в библиотеке или настройках." else "Попробуйте другое название или источник.")
            }
            if(result.tracks.isNotEmpty()) {
                if(category=="tracks") {
                    item(key="results-title") {Text("Найдено треков: ${result.tracks.size}",style=MaterialTheme.typography.titleMedium)}
                    items(result.tracks,key={"result:${it.id}"}) {track->
                        TrackRow(track,model,result.tracks,onPlay={model.rememberSearch(query);keyboard?.hide();model.play(track,result.tracks)})
                    }
                } else if(selectedGroup==null) {
                    item(key="groups-title") {
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(if(category=="artists")"Исполнители в результатах" else "Альбомы в результатах",style=MaterialTheme.typography.titleMedium)
                            Text("Группы из найденных треков: ${groups.size}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if(groups.isEmpty())item(key="no-groups") {EmptyState("Нет сведений",if(category=="artists")"У найденных треков не указан исполнитель." else "У найденных треков не указан альбом. Посмотрите вкладку «Треки».")}
                    items(groups,key={"group:${it.key}"}) {group->
                        SearchGroupRow(group,onOpen={selectedGroupKey=group.key},onPlay={
                            model.rememberSearch(query);keyboard?.hide();model.play(group.tracks.first(),group.tracks)
                        })
                    }
                } else {
                    item(key="group-heading") {
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            TextButton({selectedGroupKey=null}) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Spacer(Modifier.width(6.dp));Text(if(category=="artists")"К исполнителям" else "К альбомам")}
                            Text(selectedGroup.title,style=MaterialTheme.typography.titleLarge)
                            Text("Найдено треков: ${selectedGroup.tracks.size}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(selectedGroup.tracks,key={"group-track:${it.id}"}) {track->
                        TrackRow(track,model,selectedGroup.tracks,onPlay={model.rememberSearch(query);keyboard?.hide();model.play(track,selectedGroup.tracks)})
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchGroupRow(group:SearchResultGroup,onOpen:()->Unit,onPlay:()->Unit) {
    Row(Modifier.fillMaxWidth().glass(14.dp).clickable(role=Role.Button,onClick=onOpen).padding(start=12.dp,top=8.dp,bottom=8.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Cover(group.tracks.firstOrNull()?.coverUrl.orEmpty(),Modifier.size(50.dp))
        Column(Modifier.weight(1f).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(group.title,style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(listOfNotNull(group.artist.takeIf{it.isNotBlank()},"${group.tracks.size} треков в результатах").joinToString(" · "),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        IconButton(onPlay){Icon(Icons.Outlined.PlayCircle,"Слушать найденные треки: ${group.title}",tint=Blue)}
    }
}
