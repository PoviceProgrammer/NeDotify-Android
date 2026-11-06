package ru.nedotify.app.ui

import ru.nedotify.app.ui.state.*
import ru.nedotify.app.ui.tokens.DesignTokens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import ru.nedotify.app.data.*
import java.util.Locale

@Composable
fun DownloadCard(row:DownloadWithTrack,model:DownloadsViewModel,playback:PlaybackConnection,queue:List<Track>) {
    val item=row.download;val track=row.track
    var confirmRemove by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().glass(14.dp).clickable(enabled=item.state=="complete"){playback.play(track,queue)}.padding(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Cover(track.coverUrl,Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(horizontal=10.dp)) {
                Text(track.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelLarge)
                Text(track.artist,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when(item.state) {
                    "complete"->"Скачано · ${downloadSize(item.bytes)}"
                    "downloading"->"${if(item.percent>=0)"${item.percent.toInt()}% · " else ""}${downloadSize(item.bytes)}"
                    "preparing"->"Подготовка загрузки…"
                    "queued"->"В очереди · ожидание сети или свободного места"
                    "paused"->"На паузе · ${downloadSize(item.bytes)}"
                    "removing"->"Удаление…"
                    else->item.error.ifBlank {"Загрузка прервана"}
                },style=MaterialTheme.typography.labelMedium,color=if(item.state=="complete")Blue else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when(item.state) {
                "queued","preparing","downloading"->IconButton({model.pauseDownload(track.id)}){Icon(Icons.Filled.Pause,"Приостановить загрузку")}
                "paused","failed"->IconButton({model.resumeDownload(track.id)}){Icon(Icons.Filled.PlayArrow,if(item.state=="failed")"Повторить загрузку" else "Продолжить загрузку",tint=Blue)}
                "remove_failed"->IconButton({model.removeDownload(track.id)}){Icon(Icons.Outlined.Refresh,"Повторить удаление",tint=Blue)}
                "complete"->Icon(Icons.Outlined.CheckCircle,"Доступен без сети",tint=Blue,modifier=Modifier.padding(12.dp))
            }
            if(item.state!="removing")IconButton({confirmRemove=true}){Icon(Icons.Outlined.DeleteOutline,if(item.state in setOf("complete","remove_failed"))"Удалить скачанное" else "Отменить загрузку")}
        }
        if(item.state=="downloading") {
            val modifier=Modifier.fillMaxWidth().padding(top=9.dp).height(3.dp).semantics {contentDescription="Ход загрузки ${track.title}"}
            if(item.percent>=0)LinearProgressIndicator(progress={item.percent/100f},modifier=modifier,color=Blue)
            else LinearProgressIndicator(modifier=modifier,color=Blue)
        }
    }
    if(confirmRemove)AlertDialog(onDismissRequest={confirmRemove=false},title={Text(if(item.state=="complete")"Удалить скачанную музыку?" else "Отменить загрузку?")},
        text={Text("${track.title}\nТрек останется в библиотеке и плейлистах.")},
        confirmButton={TextButton({model.removeDownload(track.id);confirmRemove=false}){Text("Удалить")}},
        dismissButton={TextButton({confirmRemove=false}){Text("Оставить")}})
}

fun downloadSize(bytes:Long):String=if(bytes<1024*1024)"${bytes.coerceAtLeast(0)/1024} КБ" else "%.1f МБ".format(Locale.ROOT,bytes/1048576.0)
