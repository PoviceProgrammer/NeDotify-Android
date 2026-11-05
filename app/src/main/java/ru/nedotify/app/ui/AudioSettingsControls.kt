package ru.nedotify.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.nedotify.app.data.AudioSettings
import ru.nedotify.app.data.AudioWriteFailure
import ru.nedotify.app.player.AudioRuntimeStatus
import kotlin.math.roundToInt

/**
 * The audio controls.
 *
 * The fields show an optimistic value while the write is in flight. When the
 * write fails the optimistic value is rolled back: a value that was never
 * saved must not look like a saved one.
 */
@Composable
fun AudioSettingsControls(
    settings: AudioSettings,
    onChange: ((AudioSettings) -> AudioSettings) -> Unit,
    status: AudioRuntimeStatus = AudioRuntimeStatus(),
    failure: AudioWriteFailure? = null,
) {
    val value = settings.normalized()
    // The drafts are keyed on the persisted value; the generation is bumped
    // when a write fails, so the drafts fall back to the persisted value.
    var generation by remember { mutableIntStateOf(0) }
    var bands by remember(value.bandGainsDb, generation) { mutableStateOf(value.bandGainsDb) }
    var seconds by remember(value.crossfadeSeconds, generation) { mutableFloatStateOf(value.crossfadeSeconds.toFloat()) }
    LaunchedEffect(failure?.id) { if (failure != null) generation++ }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (failure != null) {
            Text(failure.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Качество потока", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("high" to "Высокое", "standard" to "Обычное", "dataSaver" to "Экономия трафика").forEach { (id, label) ->
                    FilterChip(value.quality == id, { onChange { it.copy(quality = id) } }, label = { Text(label) })
                }
            }
            Text("Доступное качество зависит от источника. Изменение применяется при следующей загрузке потока и не прерывает текущий трек.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GraphicEq, null, tint = Blue)
                Text("Эквалайзер", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                Switch(value.eqEnabled, { enabled -> onChange { it.copy(eqEnabled = enabled) } }, enabled = status.eqSupported, modifier = Modifier.semantics { contentDescription = "Эквалайзер" })
            }
            Text(status.notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Ровно" to List(5) { 0f }, "Бас" to listOf(5f, 3f, 0f, -1f, 1f), "Вокал" to listOf(-2f, 0f, 4f, 3f, 0f)).forEach { (name, gains) ->
                    FilterChip(bands == gains, { bands = gains.map {it.coerceIn(status.bandMinDb,status.bandMaxDb)}; onChange { it.copy(eqEnabled = true, bandGainsDb = bands) } }, enabled = status.eqSupported, label = { Text(name) })
                }
            }
            val minimum = maxOf(-12f, status.bandMinDb).coerceAtMost(12f)
            val maximum = minOf(12f, status.bandMaxDb).coerceAtLeast(minimum + .01f)
            bands.forEachIndexed { index, requested ->
                val gain=requested.coerceIn(minimum,maximum)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(listOf("60 Гц", "230 Гц", "910 Гц", "3,6 кГц", "14 кГц")[index], style = MaterialTheme.typography.labelMedium)
                        Text("${if (gain > 0) "+" else ""}${gain.roundToInt()} дБ", style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(gain.coerceIn(minimum, maximum), { new -> bands = bands.toMutableList().also { it[index] = new } },
                        onValueChangeFinished = {
                            val requested = bands[index]
                            onChange { latest -> latest.copy(bandGainsDb = latest.normalized().bandGainsDb.toMutableList().also { it[index] = requested }) }
                        }, enabled = value.eqEnabled && status.eqSupported,
                        valueRange = minimum..maximum, steps = ((maximum - minimum).roundToInt() - 1).coerceAtLeast(0),
                        modifier = Modifier.semantics { contentDescription = "Полоса эквалайзера ${AudioSettings.frequenciesHz[index].roundToInt()} герц" })
                }
            }
            Text("Показаны значения с учётом диапазона аудиовыхода; пресеты ограничиваются этим диапазоном. Частоты подстраиваются под полосы устройства. При усилении резервируется запас громкости.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        EffectStrength("Усиление баса", value.bassEnabled, value.bassStrength, status.bassSupported, generation,
            { enabled -> onChange { it.copy(bassEnabled = enabled) } }, { strength -> onChange { it.copy(bassStrength = strength) } })
        EffectStrength("Пространственный звук", value.virtualizerEnabled, value.virtualizerStrength, status.virtualizerSupported, generation,
            { enabled -> onChange { it.copy(virtualizerEnabled = enabled) } }, { strength -> onChange { it.copy(virtualizerStrength = strength) } })
        Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Плавный переход", style = MaterialTheme.typography.titleMedium)
            Text(if (seconds < .5f) "Выключен" else "${seconds.roundToInt()} с", color = Blue)
            Slider(seconds, { seconds = it }, onValueChangeFinished = { val requested = seconds.roundToInt(); onChange { it.copy(crossfadeSeconds = requested) } },
                valueRange = 0f..12f, steps = 11, modifier = Modifier.semantics { contentDescription = "Длительность плавного перехода" })
            Text(if (status.crossfadeActive) "Сейчас звучит переход между треками" else "Следующий трек мягко вступает перед окончанием текущего. Если он не готов, музыка продолжает играть без затухания.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().glass().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Продолжать музыку", style = MaterialTheme.typography.titleSmall)
                Text("Добавлять похожие треки, когда очередь заканчивается", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(value.autoplay, { enabled -> onChange { it.copy(autoplay = enabled) } }, modifier = Modifier.semantics { contentDescription = "Продолжать музыку" })
        }
        Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Кэш потоков", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(64, 128, 256, 512, 1024).forEach { mb ->
                    FilterChip(value.cacheLimitMb == mb, { onChange { it.copy(cacheLimitMb = mb) } },
                        label = { Text(if (mb == 1024) "1 ГБ" else "$mb МБ") })
                }
            }
            Text("Лимит вступит в силу после перезапуска приложения. Скачанная музыка хранится отдельно и не удаляется при очистке кэша.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EffectStrength(
    title: String,
    enabled: Boolean,
    strength: Float,
    supported: Boolean,
    generation: Int,
    onEnabled: (Boolean) -> Unit,
    onStrength: (Float) -> Unit,
) {
    var draft by remember(strength, generation) { mutableFloatStateOf(strength) }
    Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(enabled, onEnabled, enabled = supported, modifier = Modifier.semantics { contentDescription = title })
        }
        if (!supported) Text("Недоступно на текущем аудиовыходе", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(draft, { draft = it }, onValueChangeFinished = { onStrength(draft) }, valueRange = 0f..1f, enabled = supported && enabled,
            modifier = Modifier.semantics { contentDescription = "Интенсивность: $title" })
    }
}
