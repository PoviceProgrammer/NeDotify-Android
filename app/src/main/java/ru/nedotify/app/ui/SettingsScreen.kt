package ru.nedotify.app.ui

import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.nedotify.app.data.Appearance
import ru.nedotify.app.data.detail
import ru.nedotify.app.data.message

@Composable
fun SettingsScreen(model: MusicViewModel, onImport: () -> Unit, onScan: () -> Unit, onExportBackup: () -> Unit, onImportBackup: () -> Unit, backupPickerOpen: Boolean, onImportDesktop: (String) -> Unit = {}) {
    val appearance by model.appearance.collectAsStateWithLifecycle()
    val backupBusy by model.backupBusy.collectAsStateWithLifecycle()
    val backupStatus by model.backupStatus.collectAsStateWithLifecycle()
    val backupError by model.backupError.collectAsStateWithLifecycle()
    val searches by model.searchHistory.collectAsStateWithLifecycle()
    val audio by model.audioSettings.collectAsStateWithLifecycle()
    val audioStatus by model.audioStatus.collectAsStateWithLifecycle()
    val audioFailure by model.repository.audioSettings.writeFailure.collectAsStateWithLifecycle()
    val connections by model.connections.collectAsStateWithLifecycle()
    val resets by model.repository.preferences.pendingResets.collectAsStateWithLifecycle()
    var clearSearches by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageTitle("Настройки", "Настройте приложение под себя") }
        if (resets.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Every pending reset is listed: two damaged files are two
                    // losses, and the second one must not hide the first.
                    resets.forEach { reset ->
                        Text(reset.message(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Text(reset.detail(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { SettingsHeading("Оформление") }
        item { AppearanceControls(appearance, model::mode, model::accent, model::glass, model::background) }
        item { SettingsHeading("Плеер") }
        item { AudioSettingsControls(audio, model::updateAudio, audioStatus, audioFailure) }
        item {
            Column(Modifier.fillMaxWidth().glass().padding(16.dp)) {
                SettingSwitch("Следовать тексту песни", "Прокручивать синхронизированный текст к текущей строке", appearance.lyricsAutoScroll, model::lyricsAutoScroll)
                Text("Текст загружается при открытии вкладки. Уже полученные тексты доступны офлайн из ограниченного кэша. Android может очистить этот кэш.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { SettingsHeading("Музыка и хранилище") }
        item { ConnectionsControls(connections, model::configureSpotify, model::beginSpotifyLogin, model::saveYandexToken, model::logoutProvider, model::openCatalog) }
        item { CatalogImportSummary(model) }
        item {
            Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingSwitch("Офлайн-режим", "Поиск в локальной библиотеке; воспроизведение файлов и завершённых загрузок", appearance.offline, model::offline)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                DownloadsSummary(model)
                TextButton(onImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(10.dp)); Text("Выбрать аудиофайлы")
                }
                TextButton(onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.LibraryMusic, null); Spacer(Modifier.width(10.dp)); Text("Найти музыку на устройстве")
                }
            }
        }
        item { BackupControls(backupBusy, backupStatus, backupError, backupPickerOpen, onExportBackup, onImportBackup) }
        item { DesktopImportControls(backupBusy || backupPickerOpen, onImportDesktop) }
        item { SettingsHeading("Конфиденциальность") }
        item {
            Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("История поиска", style = MaterialTheme.typography.titleMedium)
                Text("Сохранена только на этом устройстве: ${searches.size} запросов.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ clearSearches = true }, enabled = searches.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Очистить историю поиска") }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("NeDotify для Android", style = MaterialTheme.typography.titleMedium)
                Text("Музыка всегда с тобой", style = MaterialTheme.typography.bodyMedium, color = Blue)
                Text("Воспроизведение работает в фоне. После перезапуска очередь восстанавливается на паузе.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (clearSearches) AlertDialog(onDismissRequest = { clearSearches = false }, title = { Text("Очистить историю поиска?") },
        text = { Text("Сохранённые поисковые запросы будут удалены с этого устройства.") },
        confirmButton = { TextButton({ model.clearSearchHistory(); clearSearches = false }) { Text("Очистить") } },
        dismissButton = { TextButton({ clearSearches = false }) { Text("Отмена") } })
}

/** Recovery controls stay reachable after process recreation or closing the catalogue. */
@Composable
private fun CatalogImportSummary(model:MusicViewModel) {
    val state by model.catalogImports.state.collectAsStateWithLifecycle()
    if(state.importing || state.paused)Column(Modifier.fillMaxWidth().glass().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Импорт: ${state.title}",style=MaterialTheme.typography.titleMedium)
        Text("Добавлено ${state.completed}; недоступно ${state.unavailable}; всего ${state.total}",style=MaterialTheme.typography.bodySmall)
        state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(state.importing)OutlinedButton(model::pauseCatalogImport) {Text("Приостановить импорт")}
        else Button(model::resumeCatalogImport) {Text("Продолжить импорт")}
        TextButton(model::discardCatalogImport) {Text("Завершить импорт, сохранив добавленное")}
    }
}

/**
 * The download summary.
 *
 * The progress of a download is written every 750 ms. The list is read here
 * and not in the scope of the whole screen, so a progress write does not
 * re-run every control of the settings.
 */
@Composable
private fun DownloadsSummary(model: MusicViewModel) {
    val downloads by model.downloads.collectAsStateWithLifecycle()
    Text("Скачано ${downloads.count { it.download.state == "complete" }} треков · ${downloadSize(downloads.filter {it.download.state=="complete"}.sumOf { it.download.bytes.coerceAtLeast(0) })}",
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun AppearanceControls(appearance: Appearance, onMode: (String) -> Unit, onAccent: (String) -> Unit, onGlass: (Boolean) -> Unit, onBackground: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Тема приложения", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("dark" to "Тёмная", "light" to "Светлая", "system" to "Системная").forEach { (id, title) ->
                FilterChip(appearance.mode == id, { onMode(id) }, label = { Text(title) })
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
        Text("Акцентный цвет", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("cyan" to "Голубой", "blue" to "Синий", "violet" to "Фиолетовый", "mint" to "Мятный", "system" to "Из системы").forEach { (id, title) ->
                FilterChip(appearance.accent == id, { onAccent(id) }, label = { Text(title) },
                    leadingIcon = { Icon(Icons.Outlined.Circle, null, tint = themeAccentColors(id, MaterialTheme.colorScheme.background.red < .3f).first, modifier = Modifier.size(14.dp)) })
            }
        }
        if (appearance.accent == "system") Text(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Акцент из палитры обоев Android. Светлая и тёмная тема выбираются отдельно."
            else "Системный акцент доступен с Android 12. На этом устройстве используется голубой цвет.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingSwitch("Стеклянные панели", "Непрозрачные поверхности при экономии энергии и на Android ниже 12", appearance.glass, onGlass)
        SettingSwitch("Атмосферный фон", "Звёзды и спокойный градиент в стиле NeDotify", appearance.background, onBackground)
    }
}

@Composable
private fun SettingsHeading(title: String) { Text(title, style = MaterialTheme.typography.titleMedium) }

@Composable
fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}
