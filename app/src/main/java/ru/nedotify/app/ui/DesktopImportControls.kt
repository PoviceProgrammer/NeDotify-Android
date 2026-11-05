package ru.nedotify.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DesktopImportControls(busy:Boolean,onImport:(String)->Unit) {
    var origin by rememberSaveable {mutableStateOf("")}
    var closed by rememberSaveable {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().glass().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Перенести библиотеку с ПК",style=MaterialTheme.typography.titleMedium)
        Text("Можно выбрать БД и соответствующий файл -wal вместе. Выберите полную копию nedotify_storage.db после закрытия настольного плеера. Файл проверяется и открывается только для чтения.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(origin,{origin=it.take(80)},label={Text("Имя источника, например «Домашний ПК»")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
        Text("Повторный перенос с того же ПК узнаётся по постоянному идентификатору внутри копии. Имя источника — только подпись для вас.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        SettingSwitch("Настольный плеер закрыт","В копии сохранены все изменения базы",closed,{closed=it})
        OutlinedButton({onImport(origin.trim())},enabled=!busy&&closed&&origin.isNotBlank()&&origin.none {it.isISOControl()},modifier=Modifier.fillMaxWidth()) {
            Text("Выбрать копию БД ПК")
        }
    }
}
