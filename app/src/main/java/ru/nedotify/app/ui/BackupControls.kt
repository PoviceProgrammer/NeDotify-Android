package ru.nedotify.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.nedotify.app.data.BackupSummary

@Composable
fun BackupControls(busy:Boolean,status:String,error:String?,pickerOpen:Boolean,onExport:()->Unit,onImport:()->Unit) {
    Column(Modifier.fillMaxWidth().glass().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Резервная копия библиотеки",style=MaterialTheme.typography.titleMedium)
        Text("Сохраните треки, избранное, плейлисты и историю прослушивания в JSON.",
            style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Аудиофайлы и скачанная музыка в копию не входят. Локальные файлы на другом устройстве нужно выбрать заново.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onExport,enabled=!busy && !pickerOpen,modifier=Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.SaveAlt,null)
            Spacer(Modifier.width(10.dp))
            Text("Сохранить копию")
        }
        OutlinedButton(onImport,enabled=!busy && !pickerOpen,modifier=Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Restore,null)
            Spacer(Modifier.width(10.dp))
            Text("Восстановить из копии")
        }
        if(busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodySmall)
        } else if(pickerOpen)Text("Выберите файл или место для сохранения",style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,
            modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
    }
}

@Composable
fun BackupImportDialog(summary:BackupSummary,busy:Boolean,status:String,error:String?,onConfirm:()->Unit,onDismiss:()->Unit,notice:String="") {
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("Восстановить библиотеку?")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Данные копии будут объединены с библиотекой. Ваши треки и плейлисты сохранятся.")
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                BackupCount("Треков",summary.tracks)
                BackupCount("В избранном",summary.favorites)
                BackupCount("Плейлистов",summary.playlists)
                if(summary.smartPlaylists>0)BackupCount("Умных плейлистов",summary.smartPlaylists)
                BackupCount("Записей истории",summary.sessions)
                if(summary.localTracks>0)BackupCount("Локальных треков",summary.localTracks)
            }
            if(notice.isNotBlank())Text(notice,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(summary.localTracks>0)Text("Если локальный трек недоступен, откройте его меню → «Указать файл».",
                color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,
                modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
            if(busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodySmall)
            }
        }},
        confirmButton={TextButton(onConfirm,enabled=!busy){Text("Объединить")}},
        dismissButton={TextButton(onDismiss,enabled=!busy){Text("Отмена")}})
}

@Composable
private fun BackupCount(title:String,count:Int) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(title,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Text(count.toString(),style=MaterialTheme.typography.labelLarge)
    }
}
