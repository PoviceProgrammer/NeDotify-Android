package ru.nedotify.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.nedotify.app.data.*

@Composable
fun PersonalFlowCard(onStart: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(18.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Blue)
            Text("Мой Flow", style = MaterialTheme.typography.titleLarge)
        }
        Text("Непрерывный микс из вашей коллекции: любимые исполнители, знакомые треки и новые добавления.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onStart, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.PlayArrow, null); Text("Включить Flow") }
    }
}

@Composable
fun RecommendationSection(state: DiscoveryState, onRefresh: () -> Unit, onPlay: (Track, List<Track>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Для тебя", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onRefresh, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "Обновить рекомендации") }
        }
        if (state.description.isNotBlank()) Text(state.description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (!state.loading && state.tracks.isEmpty()) Text("Отмечайте любимые треки и слушайте музыку — здесь появится персональная подборка.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.tracks, key = { it.id }) { track -> BrowseMusicCard(track) { onPlay(track, state.tracks) } }
        }
    }
}

@Composable
fun SmartPlaylistRow(playlist: SmartPlaylist, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().glass(14.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onOpen, Modifier.weight(1f).heightIn(min = 64.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Blue); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Обновляется по правилам", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Действия с умным плейлистом ${playlist.name}") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Изменить правила") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
fun SmartPlaylistDialog(initial: SmartPlaylist? = null, onDismiss: () -> Unit, onSave: (String, SmartRules) -> Unit) {
    val parsed = remember(initial?.id, initial?.rulesJson) { runCatching { initial?.let { SmartRules.decode(it.rulesJson) } ?: SmartRules() } }
    val original = parsed.getOrDefault(SmartRules())
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var favorite by rememberSaveable(initial?.id) { mutableStateOf(original.favorite?.toString() ?: "all") }
    var source by rememberSaveable(initial?.id) { mutableStateOf(original.source ?: "all") }
    var artist by rememberSaveable(initial?.id) { mutableStateOf(original.artist) }
    var query by rememberSaveable(initial?.id) { mutableStateOf(original.query) }
    var minimum by rememberSaveable(initial?.id) { mutableStateOf(if (original.minListenedMs == 0L) "" else (original.minListenedMs / 60_000).toString()) }
    var minimumEdited by rememberSaveable(initial?.id) { mutableStateOf(false) }
    var days by rememberSaveable(initial?.id) { mutableStateOf(original.addedWithinDays?.toString().orEmpty()) }
    var notPlayed by rememberSaveable(initial?.id) { mutableStateOf(original.notPlayedDays?.toString().orEmpty()) }
    var limit by rememberSaveable(initial?.id) { mutableStateOf(original.limit.toString()) }
    var order by rememberSaveable(initial?.id) { mutableStateOf(original.order.name) }
    var downloaded by rememberSaveable(initial?.id) { mutableStateOf(original.downloaded?.toString() ?: "all") }
    var unplayed by rememberSaveable(initial?.id) { mutableStateOf(original.unplayed?.toString() ?: "all") }
    var error by rememberSaveable(initial?.id) { mutableStateOf(if (parsed.isFailure) "Старые правила повреждены. Укажите новые условия перед сохранением." else null) }
    fun number(value: String, optional: Boolean, label: String): Int? = if (optional && value.isBlank()) null
        else value.toIntOrNull() ?: throw SourceFailure("Проверьте поле «$label»")
    fun bool(value: String): Boolean? = when (value) { "true" -> true; "false" -> false; else -> null }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "Умный плейлист" else "Изменить правила") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Треки подбираются из вашей библиотеки и обновляются автоматически. Все условия действуют вместе.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            RuleChoice("Избранное", favorite, listOf("all" to "Все треки", "true" to "Только любимые", "false" to "Кроме любимых")) { favorite = it }
            RuleChoice("Источник", source, listOf("all" to "Любой источник", "local" to "Локальные файлы", "soundcloud" to "SoundCloud", "youtube" to "YouTube Music", "yandex" to "Яндекс Музыка")) { source = it }
            OutlinedTextField(artist, { artist = it.take(200) }, label = { Text("Исполнитель содержит") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(query, { query = it.take(200) }, label = { Text("Название, альбом или исполнитель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            RuleChoice("Скачивание", downloaded, listOf("all" to "Любые", "true" to "Скачанные", "false" to "Не скачанные")) { downloaded = it }
            RuleChoice("Прослушивания", unplayed, listOf("all" to "Любые", "true" to "Ещё не слушал", "false" to "Уже слушал")) { unplayed = it }
            RuleNumber("Прослушано не меньше минут", minimum) { minimum = it; minimumEdited = true }
            if (!minimumEdited && original.minListenedMs % 60_000 != 0L) Text(
                "Текущее условие: ${original.minListenedMs / 1_000.0} с", style = MaterialTheme.typography.bodySmall)
            RuleNumber("Добавлены за последние дни", days) { days = it }
            RuleNumber("Не слушал последние дни", notPlayed) { notPlayed = it }
            RuleNumber("Максимум треков (1–1000)", limit) { limit = it }
            RuleChoice("Порядок", order, SmartOrder.entries.map { it.name to when (it) {
                SmartOrder.RECENT -> "Недавно добавленные"; SmartOrder.OLDEST -> "Давно добавленные"; SmartOrder.TITLE -> "По названию"
                SmartOrder.ARTIST -> "По исполнителю"; SmartOrder.MOST_PLAYED -> "Больше слушал"; SmartOrder.LEAST_PLAYED -> "Меньше слушал"
            } }) { order = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton({
        try {
            if (name.isBlank()) throw SourceFailure("Введите название")
            val rules = SmartRules(bool(favorite), source.takeUnless { it == "all" }, artist, query,
                if (minimumEdited) (number(minimum, true, "Минуты")?.toLong() ?: 0) * 60_000 else original.minListenedMs,
                number(days, true, "Добавлены за дни"),
                number(limit, false, "Максимум треков")!!, SmartOrder.valueOf(order), bool(downloaded), bool(unplayed),
                number(notPlayed, true, "Не слушал дни")).requireFilter()
            onSave(name.trim(), rules)
        } catch (failure: Exception) { error = failure.message ?: "Проверьте правила" }
    }, enabled = name.isNotBlank()) { Text("Сохранить") } }, dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
}

@Composable
private fun RuleNumber(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit).take(8)) }, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun RuleChoice(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("$label: ${options.firstOrNull { it.first == value }?.second.orEmpty()}", Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(open, { open = false }) { options.forEach { (id, name) ->
            DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); open = false },
                trailingIcon = { if (id == value) Icon(Icons.Outlined.Check, null) })
        } }
    }
}
