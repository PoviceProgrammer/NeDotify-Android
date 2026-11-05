package ru.nedotify.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.nedotify.app.data.PlaylistWithCount

@Composable
fun LibraryScreen(model:MusicViewModel,onImport:()->Unit,onCreate:()->Unit) {
    val tracks by model.tracks.collectAsStateWithLifecycle()
    val playlists by model.playlists.collectAsStateWithLifecycle()
    val selected by model.playlistSelection.collectAsStateWithLifecycle()
    val selectedId by model.selectedPlaylistId.collectAsStateWithLifecycle()
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val filter by model.libraryFilter.collectAsStateWithLifecycle()
    val smartPlaylists by model.discovery.smartPlaylists.collectAsStateWithLifecycle()
    val smartId by model.discovery.selectedSmartId.collectAsStateWithLifecycle()
    val smartState by model.discovery.smartTrackState.collectAsStateWithLifecycle()
    val smartTracks=if(filter=="smart" && smartState.playlistId==smartId)smartState.tracks else emptyList()
    var creatingSmart by rememberSaveable { mutableStateOf(false) }
    var editingSmartId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingSmartId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable {mutableStateOf<String?>(null)}
    var deletingId by rememberSaveable {mutableStateOf<String?>(null)}
    var playlistName by rememberSaveable {mutableStateOf("")}
    var sort by rememberSaveable {mutableStateOf("recent")}
    var sortMenu by remember {mutableStateOf(false)}
    val visible=remember(tracks,selected,smartTracks,filter,sort) {
        val rows=when(filter){"favorites"->tracks.filter {it.favorite};"local"->tracks.filter {it.source=="local" || it.localUri.isNotBlank()};"playlist"->selected.orEmpty();"smart"->smartTracks;else->tracks}
        when(sort){"title"->rows.sortedBy {it.title.lowercase()};"artist"->rows.sortedWith(compareBy({it.artist.lowercase()},{it.title.lowercase()}));else->rows}
    }
    val ready=remember(downloads){downloads.filter {it.download.state=="complete"}.map {it.track}}
    val selectedName=playlists.firstOrNull {it.playlist.id==selectedId}?.playlist?.name
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item(key="heading") {PageTitle("Библиотека","Ваша музыка в одном месте")}
        item(key="filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("all" to "Треки","playlists" to "Плейлисты","smart" to "Умные","favorites" to "Любимые","local" to "Файлы","downloads" to "Скачанные").forEach {(id,label)->
                    FilterChip(filter==id,{model.setLibraryFilter(id)},label={Text(label)},modifier=Modifier.heightIn(min=48.dp))
                }
            }
        }
        item(key="tiles") {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                LibraryTile("Любимые","${tracks.count {it.favorite}} треков",Icons.Outlined.FavoriteBorder,Modifier.weight(1f)){model.setLibraryFilter("favorites")}
                LibraryTile("Скачанные","${ready.size} треков",Icons.Outlined.Download,Modifier.weight(1f)){model.setLibraryFilter("downloads")}
                LibraryTile("Создать","плейлист",Icons.Outlined.Add,Modifier.weight(1f),onCreate)
            }
        }
        if(filter=="downloads") {
            item(key="downloads-title") {
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    BrowseHeading("Загрузки",if(ready.isNotEmpty())"Слушать всё" else null){ready.firstOrNull()?.let {model.play(it,ready)}}
                    Text("Скачано ${ready.size} треков · ${downloadSize(downloads.filter {it.download.state=="complete"}.sumOf {it.download.bytes.coerceAtLeast(0)})}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(downloads.size>ready.size)Text("Другие загрузки: ${downloads.size-ready.size} · ${downloadSize(downloads.filter {it.download.state!="complete"}.sumOf {it.download.bytes.coerceAtLeast(0)})}",style=MaterialTheme.typography.bodySmall)
                }
            }
            if(downloads.isEmpty())item(key="downloads-empty") {EmptyState("Музыка всегда с тобой","Откройте меню трека в поиске или библиотеке и выберите «Скачать для офлайн».")}
            items(downloads,key={"download:${it.download.trackId}"}) {DownloadCard(it,model,ready)}
        } else {
            if(filter=="all" || filter=="playlists" || filter=="smart") {
                item(key="smart-title") { BrowseHeading("Умные плейлисты", "Создать") { creatingSmart = true } }
                if(smartPlaylists.isEmpty())item(key="smart-empty") {
                    EmptyState("Музыка по вашим правилам", "Соберите список из любимых, скачанных или ещё не прослушанных треков. Он обновится сам.", "Создать по правилам") { creatingSmart = true }
                }
                items(if(filter=="all")smartPlaylists.take(3) else smartPlaylists,key={"smart:${it.id}"}) { playlist ->
                    SmartPlaylistRow(playlist,onOpen={model.discovery.openSmart(playlist.id);model.setLibraryFilter("smart")},
                        onEdit={editingSmartId=playlist.id},onDelete={deletingSmartId=playlist.id})
                }
            }
            if(filter=="all" || filter=="playlists") {
                item(key="playlists-title") {
                    BrowseHeading("Мои плейлисты",if(filter=="all" && playlists.size>4)"Показать все" else "Создать") {
                        if(filter=="all" && playlists.size>4)model.setLibraryFilter("playlists") else onCreate()
                    }
                }
                if(playlists.isEmpty())item(key="playlists-empty") {EmptyState("Соберите своё настроение","Добавляйте любимые треки в плейлисты для любого момента.","Создать плейлист",onCreate)}
                items(if(filter=="all")playlists.take(4) else playlists,key={"playlist:${it.playlist.id}"}) {pl->
                    LibraryPlaylistRow(pl,onOpen={model.openPlaylist(pl.playlist.id)},onRename={editingId=pl.playlist.id;playlistName=pl.playlist.name},onDelete={deletingId=pl.playlist.id})
                }
            }
            if(filter!="playlists" && (filter!="smart" || smartId!=null)) {
                item(key="tracks-title") {
                    Column {
                        if(filter=="playlist")TextButton({model.setLibraryFilter("playlists")}){Text("Все плейлисты")}
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(when(filter){"favorites"->"Любимые треки";"local"->"Файлы на устройстве";"playlist"->selectedName?:"Плейлист";"smart"->smartPlaylists.firstOrNull {it.id==smartId}?.name?:"Умный плейлист";else->"Треки коллекции"},style=MaterialTheme.typography.titleLarge)
                                Text("${visible.size} треков",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(visible.isNotEmpty())IconButton({model.play(visible.first(),visible)}){Icon(Icons.Filled.PlayArrow,"Воспроизвести список",tint=Blue)}
                            Box {
                                IconButton({sortMenu=true}){Icon(Icons.Outlined.Sort,"Сортировка треков")}
                                DropdownMenu(sortMenu,{sortMenu=false}) {
                                    listOf("recent" to if(filter=="playlist" || filter=="smart")"Порядок плейлиста" else "Недавно добавленные","title" to "По названию","artist" to "По исполнителю").forEach {(id,label)->
                                        DropdownMenuItem(text={Text(label)},onClick={sort=id;sortMenu=false},trailingIcon={if(sort==id)Icon(Icons.Outlined.Check,null,tint=Blue)})
                                    }
                                }
                            }
                            IconButton(onImport){Icon(Icons.Outlined.FolderOpen,"Добавить аудиофайлы",tint=Blue)}
                        }
                    }
                }
                if(visible.isEmpty())item(key="tracks-empty") {
                    EmptyState(when(filter){"favorites"->"Здесь будут любимые треки";"playlist"->"Добавьте первые треки";"smart"->"Нет подходящих треков";else->"Пока нет музыки"},
                        when(filter){"favorites"->"Нажмите сердечко в плеере или выберите «В избранное» в меню трека.";"playlist"->"Откройте меню любого трека и выберите «Добавить в плейлист».";"smart"->"Измените правила или добавьте подходящую музыку в библиотеку.";else->"Добавьте файлы с устройства или найдите музыку через поиск."},
                        if(filter in setOf("favorites","playlist","smart"))null else "Добавить файлы",onImport)
                }
                items(visible,key={"track:${it.id}"}){track->TrackRow(track,model,visible,
                    onRemoveFromPlaylist=if(filter=="playlist" && selectedId.isNotBlank())({model.removeFromPlaylist(selectedId,track)}) else null,allowDelete=true)}
            }
        }
    }
    if(creatingSmart)SmartPlaylistDialog(onDismiss={creatingSmart=false},onSave={name,rules->
        model.discovery.createSmart(name,rules);creatingSmart=false;model.setLibraryFilter("smart")
    })
    editingSmartId?.let { id -> smartPlaylists.firstOrNull {it.id==id}?.let { playlist ->
        SmartPlaylistDialog(playlist,onDismiss={editingSmartId=null},onSave={name,rules->model.discovery.editSmart(id,name,rules);editingSmartId=null})
    } }
    deletingSmartId?.let { id -> AlertDialog(onDismissRequest={deletingSmartId=null},title={Text("Удалить умный плейлист?")},
        text={Text("Правила будут удалены. Музыка останется в библиотеке.")},confirmButton={TextButton({model.discovery.deleteSmart(id);deletingSmartId=null}){Text("Удалить")}},
        dismissButton={TextButton({deletingSmartId=null}){Text("Отмена")}}) }
    editingId?.let {id->AlertDialog(onDismissRequest={editingId=null},title={Text("Название плейлиста")},
        text={OutlinedTextField(playlistName,{playlistName=it.take(120)},singleLine=true,label={Text("Название")})},
        confirmButton={TextButton({model.renamePlaylist(id,playlistName);editingId=null},enabled=playlistName.isNotBlank()){Text("Сохранить")}},
        dismissButton={TextButton({editingId=null}){Text("Отмена")}})}
    deletingId?.let {id->AlertDialog(onDismissRequest={deletingId=null},title={Text("Удалить плейлист?")},text={Text("Треки останутся в вашей библиотеке.")},
        confirmButton={TextButton({model.deletePlaylist(id);deletingId=null;if(filter=="playlist")model.setLibraryFilter("playlists")}){Text("Удалить")}},
        dismissButton={TextButton({deletingId=null}){Text("Отмена")}})}
}

@Composable
fun LibraryTile(title:String,count:String,icon:ImageVector,modifier:Modifier=Modifier,onClick:()->Unit) {
    Column(modifier.heightIn(min=120.dp).glass(16.dp).clickable(role=Role.Button,onClick=onClick).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(icon,null,tint=if(title=="Любимые") {
            if(MaterialTheme.colorScheme.surface.luminance()>.5f)Color(0xFFB52366) else Color(0xFFFF78B1)
        } else Blue,modifier=Modifier.size(28.dp))
        Text(title,style=MaterialTheme.typography.labelLarge)
        Text(count,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LibraryPlaylistRow(item:PlaylistWithCount,onOpen:()->Unit,onRename:()->Unit,onDelete:()->Unit) {
    var menu by remember {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().glass(14.dp).clickable(role=Role.Button,onClick=onOpen).padding(start=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh,androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Icon(Icons.AutoMirrored.Outlined.QueueMusic,null,tint=Blue,modifier=Modifier.size(27.dp))}
        Column(Modifier.weight(1f).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(item.playlist.name,style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text("${item.count} треков",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton({menu=true}){Icon(Icons.Outlined.MoreVert,"Действия с плейлистом ${item.playlist.name}")}
            DropdownMenu(menu,{menu=false}) {
                DropdownMenuItem(text={Text("Переименовать")},onClick={menu=false;onRename()},leadingIcon={Icon(Icons.Outlined.Edit,null)})
                DropdownMenuItem(text={Text("Удалить")},onClick={menu=false;onDelete()},leadingIcon={Icon(Icons.Outlined.DeleteOutline,null)})
            }
        }
    }
}
