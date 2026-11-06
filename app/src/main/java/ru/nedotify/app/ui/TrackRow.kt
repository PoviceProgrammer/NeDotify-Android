package ru.nedotify.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.nedotify.app.data.PlaylistWithCount
import ru.nedotify.app.data.Track
import ru.nedotify.app.ui.state.DownloadsViewModel
import ru.nedotify.app.ui.state.LibraryViewModel
import ru.nedotify.app.ui.state.PlaybackConnection
import ru.nedotify.app.ui.tokens.DesignTokens

data class TrackRowUiState(val track:Track,val active:Boolean=false,val playlists:List<PlaylistWithCount> = emptyList(),val downloadState:String?=null)
data class TrackRowActions(
    val play:()->Unit,val favorite:()->Unit,val addToPlaylist:(String)->Unit,
    val download:(()->Unit)?=null,val chooseLocalFile:(()->Unit)?=null,
    val removeFromPlaylist:(()->Unit)?=null,val delete:(()->Unit)?=null
)

/** Route adapter for library operations; the visible row only receives state and actions. */
@Composable
fun LibraryTrackRow(track:Track,library:LibraryViewModel,downloads:DownloadsViewModel,playback:PlaybackConnection,
    queue:List<Track>,onPlay:(()->Unit)?=null,onRemoveFromPlaylist:(()->Unit)?=null,allowDelete:Boolean=false) {
    val active by remember(playback,track.id) {playback.playback.map {it.id==track.id}.distinctUntilChanged()}.collectAsStateWithLifecycle(false)
    val favorite by remember(library,track.id,track.favorite) {
        library.tracks.map {rows->rows.firstOrNull {it.id==track.id}?.favorite?:track.favorite}.distinctUntilChanged()
    }.collectAsStateWithLifecycle(track.favorite)
    val download by remember(downloads,track.id) {downloads.downloadStates.map {it[track.id]}.distinctUntilChanged()}.collectAsStateWithLifecycle(null)
    val playlists by library.playlists.collectAsStateWithLifecycle()
    val displayed=remember(track,favorite){track.copy(favorite=favorite)}
    TrackRowContent(TrackRowUiState(displayed,active,playlists,download),TrackRowActions(
        play=onPlay?:{playback.play(track,queue)},favorite={library.favorite(track)},addToPlaylist={library.addToPlaylist(it,track)},
        download=if(track.source!="local" && track.localUri.isBlank())({downloads.download(track)}) else null,
        chooseLocalFile=if(track.source=="local")({library.selectLocalFile(track)}) else null,
        removeFromPlaylist=onRemoveFromPlaylist,delete=if(allowDelete)({library.deleteTrack(track)}) else null
    ))
}

@Composable
fun TrackRowContent(state:TrackRowUiState,actions:TrackRowActions) {
    val track=state.track
    var menu by remember {mutableStateOf(false)}
    var choosePlaylist by remember {mutableStateOf(false)}
    var confirmDelete by rememberSaveable(track.id) {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().heightIn(min=76.dp),verticalAlignment=Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min=76.dp).clickable(role=Role.Button,onClickLabel="Слушать ${track.title}",onClick=actions.play)
            .padding(vertical=DesignTokens.Spacing.sm.dp),verticalAlignment=Alignment.CenterVertically) {
            Cover(track.coverUrl,Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(horizontal=DesignTokens.Spacing.md.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(track.title,maxLines=2,overflow=TextOverflow.Ellipsis,
                    color=if(state.active)Blue else MaterialTheme.colorScheme.onSurface,
                    style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
                Text(track.artist,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                if(track.source=="local" && track.localUri.isBlank())Text("Нужно выбрать аудиофайл",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                else if(state.downloadState=="complete")Text("Скачано",style=MaterialTheme.typography.labelMedium,color=Blue)
            }
        }
        Box {
            IconButton({menu=true},Modifier.size(DesignTokens.Layout.touchTarget.dp)){Icon(Icons.Outlined.MoreVert,"Действия с треком")}
            DropdownMenu(menu,{menu=false}) {
                DropdownMenuItem(text={Text(if(track.favorite)"Убрать из избранного" else "В избранное")},leadingIcon={Icon(Icons.Outlined.FavoriteBorder,null)},onClick={actions.favorite();menu=false})
                DropdownMenuItem(text={Text("Добавить в плейлист")},leadingIcon={Icon(Icons.AutoMirrored.Outlined.PlaylistAdd,null)},onClick={choosePlaylist=true;menu=false})
                actions.removeFromPlaylist?.let {remove->DropdownMenuItem(text={Text("Убрать из плейлиста")},onClick={menu=false;remove()})}
                actions.chooseLocalFile?.let {choose->DropdownMenuItem(text={Text("Указать файл")},leadingIcon={Icon(Icons.Outlined.FolderOpen,null)},onClick={choose();menu=false})}
                if(state.downloadState=="complete")DropdownMenuItem(text={Text("Скачано на устройство")},enabled=false,onClick={})
                else if(state.downloadState !in setOf("queued","preparing","downloading","removing"))actions.download?.let {download->
                    DropdownMenuItem(text={Text("Скачать для офлайн")},leadingIcon={Icon(Icons.Outlined.Download,null)},onClick={download();menu=false})
                }
                actions.delete?.let {DropdownMenuItem(text={Text("Удалить из библиотеки")},leadingIcon={Icon(Icons.Outlined.DeleteOutline,null)},onClick={menu=false;confirmDelete=true})}
            }
        }
    }
    if(confirmDelete)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Удалить трек из библиотеки?")},
        text={Text("${track.title} будет удалён из библиотеки, плейлистов, истории и очереди. Исходный аудиофайл останется на устройстве.")},
        confirmButton={TextButton({actions.delete?.invoke();confirmDelete=false}){Text("Удалить")}},dismissButton={TextButton({confirmDelete=false}){Text("Отмена")}})
    if(choosePlaylist)AlertDialog(onDismissRequest={choosePlaylist=false},title={Text("Выберите плейлист")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=380.dp).verticalScroll(rememberScrollState())) {
            if(state.playlists.isEmpty())Text("Создайте плейлист в библиотеке")
            state.playlists.forEach {item->TextButton({actions.addToPlaylist(item.playlist.id);choosePlaylist=false},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(item.playlist.name)}}
        }
    },confirmButton={TextButton({choosePlaylist=false}){Text("Закрыть")}})
}
