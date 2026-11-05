package ru.nedotify.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.nedotify.app.data.CatalogPlaylist
import ru.nedotify.app.data.CatalogTrack

data class CatalogBrowserState(val provider:String="",val title:String="Коллекция",val query:String="",
    val tracks:List<CatalogTrack> = emptyList(),val playlists:List<CatalogPlaylist> = emptyList(),
    val busy:Boolean=false,val error:String?=null,val importing:Boolean=false,val progress:String="",
    val importPaused:Boolean=false,val hasSearched:Boolean=false)

@Composable
fun CatalogBrowser(state:CatalogBrowserState,onQuery:(String)->Unit,onSearch:()->Unit,onPlaylists:()->Unit,
    onLiked:()->Unit,onPlaylist:(CatalogPlaylist)->Unit,onPlay:(CatalogTrack)->Unit,onImport:()->Unit,onDismiss:()->Unit,
    onPauseImport:()->Unit = {},onResumeImport:()->Unit = {},onDiscardImport:()->Unit = {}) {
    if(state.provider.isBlank())return
    val working=state.busy || state.importing
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().widthIn(max=840.dp).fillMaxHeight(.94f).safeDrawingPadding(),
            shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onDismiss){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Закрыть коллекцию")}
                    Column(Modifier.weight(1f)) {
                        Text(if(state.provider=="spotify")"Spotify" else "Яндекс Музыка",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                        Text(state.title,style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
                OutlinedTextField(state.query,onQuery,enabled=!working,singleLine=true,label={Text("Трек или исполнитель")},
                    trailingIcon={IconButton(onSearch,enabled=!working && state.query.isNotBlank()){Icon(Icons.Outlined.Search,"Искать в сервисе")}},modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onPlaylists,enabled=!working){Text("Плейлисты")}
                    OutlinedButton(onLiked,enabled=!working){Text("Избранное")}
                }
                if(state.provider=="spotify")Text("При запуске будет найдено совпадение в SoundCloud или YouTube. Версия записи может отличаться; неточные совпадения не добавляются.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(working)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.progress.isNotBlank())Text(state.progress,style=MaterialTheme.typography.bodySmall,
                    modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                if(state.importing) {
                    Text("Импорт продолжится после закрытия окна. Добавленные треки сохраняются по мере обработки.",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onPauseImport) {Text("Приостановить импорт")}
                        TextButton(onDiscardImport) {Text("Отменить импорт")}
                    }
                }
                if(state.importPaused)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onResumeImport) {Text("Продолжить импорт")}
                    OutlinedButton(onDiscardImport) {Text("Отменить импорт")}
                }
                state.error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,
                    modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
                if(state.tracks.isNotEmpty())Button(onImport,enabled=!working && !state.importPaused,modifier=Modifier.fillMaxWidth()){Text("Импортировать список · ${state.tracks.size}")}
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(state.playlists.distinctBy {it.key},key={it.key}) {playlist->
                        ListItem(headlineContent={Text(playlist.title,maxLines=2,overflow=TextOverflow.Ellipsis)},supportingContent={Text("${playlist.count} треков")},
                            modifier=Modifier.glass().clickable(enabled=!working){onPlaylist(playlist)})
                    }
                    items(state.tracks.distinctBy {it.key},key={it.key}) {track->
                        ListItem(headlineContent={Text(track.title,maxLines=2,overflow=TextOverflow.Ellipsis)},supportingContent={Text(track.artist,maxLines=1,overflow=TextOverflow.Ellipsis)},
                            trailingContent={IconButton({onPlay(track)},enabled=!working){Icon(Icons.Filled.PlayArrow,if(state.provider=="spotify")"Найти и воспроизвести" else "Воспроизвести")}},modifier=Modifier.glass())
                    }
                    if(!working && state.tracks.isEmpty() && state.playlists.isEmpty() && state.error==null)item {
                        Text(if(state.hasSearched)"В этом списке треки не найдены. Попробуйте поиск или откройте другой список." else "Откройте плейлисты, избранное или найдите музыку.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}
