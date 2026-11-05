package ru.nedotify.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.nedotify.app.data.Track

@Composable
fun BrowseChip(text:String,icon:ImageVector,onClick:()->Unit) {
    SuggestionChip(onClick=onClick,label={Text(text)},icon={Icon(icon,null,Modifier.size(17.dp),tint=Blue)},
        modifier=Modifier.heightIn(min=48.dp),shape=RoundedCornerShape(24.dp),
        colors=SuggestionChipDefaults.suggestionChipColors(containerColor=MaterialTheme.colorScheme.surface.copy(alpha=.65f)))
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun BrowseHeading(title:String,action:String?=null,onAction:()->Unit={}) {
    // The action wraps below the title at large accessibility text sizes.
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=10.dp,bottom=10.dp,end=12.dp))
        if(action!=null)TextButton(onAction,modifier=Modifier.heightIn(min=48.dp)) {
            Text(action);Spacer(Modifier.width(4.dp));Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,Modifier.size(16.dp))
        }
    }
}

@Composable
fun SearchHistoryRow(query:String,onSearch:()->Unit,onRemove:()->Unit) {
    Row(Modifier.fillMaxWidth().glass(12.dp),verticalAlignment=Alignment.CenterVertically) {
        TextButton(onSearch,modifier=Modifier.weight(1f).heightIn(min=56.dp),contentPadding=PaddingValues(12.dp)) {
            Icon(Icons.Outlined.History,null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp))
            Text(query,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onRemove){Icon(Icons.Outlined.Close,"Удалить запрос «$query»")}
    }
}

@Composable
fun BrowseMusicCard(track:Track,onPlay:()->Unit) {
    Column(Modifier.width(132.dp).clip(RoundedCornerShape(14.dp)).clickable(role=Role.Button,onClickLabel="Слушать ${track.title}",onClick=onPlay)) {
        Cover(track.coverUrl,Modifier.fillMaxWidth().aspectRatio(1f))
        Text(track.title,style=MaterialTheme.typography.labelLarge,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=8.dp,start=2.dp,end=2.dp))
        Text(track.artist,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(start=2.dp,end=2.dp,bottom=6.dp))
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CollectionHero(track:Track?,onPlay:()->Unit,onSearch:()->Unit,onImport:()->Unit) {
    val accent=Blue
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF07182C))) {
        // Local scenery stays available offline; it is decoration, not a fabricated album cover.
        Canvas(Modifier.matchParentSize()) {
            val w=size.width;val h=size.height
            drawRect(Brush.verticalGradient(listOf(Color(0xFF10264D),Color(0xFF745274),Color(0xFF173047))))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFF7B9AE).copy(alpha=.45f),Color.Transparent),Offset(w*.76f,h*.32f),w*.45f),w*.45f,Offset(w*.76f,h*.32f))
            for(i in 0..19)drawCircle(Color.White.copy(alpha=.5f),if(i%4==0)1.6f else .8f,Offset(((i*61+13)%229)/229f*w,((i*47+11)%139)/139f*h*.45f))
            fun mountain(points:List<Pair<Float,Float>>,color:Color) {
                drawPath(Path().apply {moveTo(0f,h);points.forEach {(x,y)->lineTo(w*x,h*y)};lineTo(w,h);close()},color)
            }
            mountain(listOf(0f to .7f,.15f to .45f,.27f to .61f,.42f to .37f,.6f to .63f,.78f to .42f,1f to .64f),Color(0xFF283B63))
            mountain(listOf(0f to .82f,.22f to .62f,.37f to .79f,.58f to .56f,.72f to .74f,.9f to .56f,1f to .65f),Color(0xFF112D47))
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF041224).copy(alpha=.9f),Color(0xFF041224).copy(alpha=.22f))))
        }
        Column(Modifier.fillMaxWidth().padding(22.dp).heightIn(min=202.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(if(track==null)"ТВОЯ МУЗЫКА" else "СНОВА В ТВОЁМ РИТМЕ",style=MaterialTheme.typography.labelSmall,color=Color(0xFFC1E6F9))
            Text(track?.title?:"Музыка для\nтвоего момента",style=MaterialTheme.typography.headlineMedium,color=Color.White,maxLines=3,overflow=TextOverflow.Ellipsis)
            Text(track?.artist?:"Найди любимые треки или добавь свои файлы",style=MaterialTheme.typography.bodyMedium,color=Color(0xFFD7E6F2),maxLines=3,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            if(track!=null)Button(onPlay,modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.buttonColors(containerColor=accent,contentColor=MaterialTheme.colorScheme.onPrimary)) {
                Icon(Icons.Filled.PlayArrow,null);Spacer(Modifier.width(6.dp));Text("Слушать снова",fontWeight=FontWeight.SemiBold)
            } else FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Button(onSearch,modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.buttonColors(containerColor=accent,contentColor=MaterialTheme.colorScheme.onPrimary)){Text("Найти музыку")}
                TextButton(onImport,modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.textButtonColors(contentColor=Color.White)){Text("Добавить файлы")}
            }
        }
    }
}
