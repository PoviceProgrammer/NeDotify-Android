package ru.nedotify.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MiniPlayer(model:MusicViewModel,now:NowPlaying,favorite:Boolean,onOpen:()->Unit,onToggle:()->Unit,onLike:()->Unit) {
    // Only the progress bar follows the position: the bar itself is not rebuilt
    // twice a second.
    MiniPlayerLayout(now,favorite,onOpen,onToggle,onLike) { MiniProgress(model,now.duration) }
}

@Composable
internal fun MiniPlayerBar(now:NowPlaying,position:Long,favorite:Boolean,onOpen:()->Unit,onToggle:()->Unit,onLike:()->Unit) {
    MiniPlayerLayout(now,favorite,onOpen,onToggle,onLike) { MiniProgressBar(position,now.duration) }
}

/** The position is collected in the leaf that draws it, never in the bar. */
@Composable
private fun MiniProgress(model:MusicViewModel,duration:Long) {
    val position by model.positionMs.collectAsStateWithLifecycle()
    MiniProgressBar(position,duration)
}

@Composable
private fun MiniPlayerLayout(now:NowPlaying,favorite:Boolean,onOpen:()->Unit,onToggle:()->Unit,onLike:()->Unit,
    progress:@Composable ()->Unit) {
    Column(Modifier.padding(horizontal=12.dp,vertical=4.dp).fillMaxWidth().glass(16.dp)
        .background(Brush.horizontalGradient(listOf(Blue.copy(alpha=.06f),Blue.copy(alpha=.18f))))
        .border(1.dp,Blue.copy(alpha=.36f),RoundedCornerShape(16.dp)).clickable(onClickLabel="Открыть плеер",onClick=onOpen)) {
        Row(Modifier.padding(horizontal=8.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
            Cover(now.artwork,Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(horizontal=10.dp)) {
                Text(now.title.ifBlank {"Без названия"},maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelLarge)
                Text(now.artist,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconToggleButton(favorite,{onLike()}) {Icon(if(favorite)Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,if(favorite)"Убрать из избранного" else "В избранное",tint=if(favorite)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)}
            FilledTonalIconButton(onToggle,Modifier.size(48.dp).semantics {
                contentDescription=toggleLabel(now.playing,now.buffering,now.playWhenReady)
                if(now.buffering)stateDescription="Буферизация"
            }) {
                if(now.buffering)CircularProgressIndicator(Modifier.size(23.dp),strokeWidth=2.dp)
                else Icon(if(now.playing)Icons.Filled.Pause else Icons.Filled.PlayArrow,null,tint=Blue)
            }
        }
        progress()
    }
}

@Composable
private fun MiniProgressBar(position:Long,duration:Long) {
    LinearProgressIndicator(progress={if(duration>0)(position.toDouble()/duration).toFloat().coerceIn(0f,1f) else 0f},
        modifier=Modifier.fillMaxWidth().height(2.dp).semantics {contentDescription="Прогресс воспроизведения";stateDescription=if(duration>0)"${clock(position)} из ${clock(duration)}" else "Длительность неизвестна"},
        color=Blue,trackColor=Blue.copy(alpha=.12f))
}
