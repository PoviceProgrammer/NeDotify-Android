package ru.nedotify.app.ui

import ru.nedotify.app.ui.state.*
import ru.nedotify.app.ui.tokens.DesignTokens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import ru.nedotify.app.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ProfileScreen(model:ProfileViewModel,library:LibraryViewModel,downloadsModel:DownloadsViewModel,playback:PlaybackConnection,onLibrary:(String)->Unit,onSettings:()->Unit) {
    val profile by model.profile.collectAsStateWithLifecycle()
    val favoriteCount by library.favoriteCount.collectAsStateWithLifecycle()
    val playlists by library.playlists.collectAsStateWithLifecycle()
    val downloads by downloadsModel.downloads.collectAsStateWithLifecycle()
    val period by model.reportPeriod.collectAsStateWithLifecycle()
    val state by model.report.collectAsStateWithLifecycle()
    var editing by rememberSaveable {mutableStateOf(false)}
    var historyLimit by rememberSaveable(period) {mutableIntStateOf(10)}
    val report=state.report
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top=4.dp,bottom=6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Box(Modifier.size(132.dp),contentAlignment=Alignment.Center) {
                    Box(Modifier.size(120.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Blue.copy(alpha=.35f),Color(0xFF8951B5).copy(alpha=.38f)))).border(1.5.dp,Blue,CircleShape),contentAlignment=Alignment.Center) {
                        Icon(Icons.Outlined.Person,null,Modifier.size(62.dp),tint=MaterialTheme.colorScheme.onSurface)
                        if(profile.avatarUri.isNotBlank())AsyncImage(artworkModel(profile.avatarUri,false),"Фото профиля",Modifier.fillMaxSize(),contentScale=ContentScale.Crop,
                            error=androidx.compose.ui.graphics.painter.ColorPainter(Color.Transparent))
                    }
                    FilledTonalIconButton(model::selectAvatar,Modifier.align(Alignment.BottomEnd).size(48.dp)) {Icon(Icons.Outlined.PhotoCamera,"Выбрать фото профиля")}
                }
                if(profile.avatarUri.isNotBlank())TextButton(model::removeAvatar) {Text("Убрать фото профиля")}
                Text(profile.name,style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(top=10.dp),maxLines=3,overflow=TextOverflow.Ellipsis)
                Text(profile.bio,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=4.dp))
                TextButton({editing=true}) {Icon(Icons.Outlined.Edit,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("Редактировать профиль")}
            }
        }
        item {
            Text("Ваша статистика",style=MaterialTheme.typography.titleLarge)
            Row(Modifier.padding(top=10.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ReportPeriod.entries.forEach {value->FilterChip(period==value,{model.selectReportPeriod(value)},label={Text(reportPeriodLabel(value))})}
            }
        }
        if(state.busy) item {
            Column(Modifier.fillMaxWidth().glass().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth());Text("Считаем время прослушивания…",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if(state.error!=null) item {EmptyState("Статистика недоступна",state.error.orEmpty(),"Повторить",model::retryReport)}
        else if(report!=null) {
            item {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if(maxWidth<330.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale>=1.5f)Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        ProfileStatTile("Минут прослушано",(report.totalMs/60_000).toString(),Icons.Outlined.Headphones,Modifier.fillMaxWidth())
                        ProfileStatTile("Любимых треков",favoriteCount.toString(),Icons.Outlined.FavoriteBorder,Modifier.fillMaxWidth()){onLibrary("favorites")}
                        ProfileStatTile("Плейлистов",playlists.size.toString(),Icons.AutoMirrored.Outlined.QueueMusic,Modifier.fillMaxWidth()){onLibrary("playlists")}
                    } else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        ProfileStatTile("Минут прослушано",(report.totalMs/60_000).toString(),Icons.Outlined.Headphones,Modifier.weight(1f))
                        ProfileStatTile("Любимых треков",favoriteCount.toString(),Icons.Outlined.FavoriteBorder,Modifier.weight(1f)){onLibrary("favorites")}
                        ProfileStatTile("Плейлистов",playlists.size.toString(),Icons.AutoMirrored.Outlined.QueueMusic,Modifier.weight(1f)){onLibrary("playlists")}
                    }
                }
                Text("Любимые и плейлисты — вся коллекция",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=7.dp))
            }
            if(report.sessions==0) item {
                EmptyState("Здесь появится ваша музыка","Слушайте треки: статистика считает фактически прослушанное время. Перемотка не добавляет минуты.","Открыть библиотеку",{onLibrary("all")})
            } else {
                item {
                    Column(Modifier.fillMaxWidth().glass().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("В ритме музыки",style=MaterialTheme.typography.titleMedium)
                        Text("${report.sessions} прослушиваний · ${report.uniqueTracks} треков · ${report.completedSessions} завершённых",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        ReportComparison(report.previousMs,report.changePercent)
                        ListeningChart(report.daily)
                    }
                }
                if(report.topTracks.isNotEmpty()) {
                    item {Text("Топ треков",style=MaterialTheme.typography.titleLarge)}
                    val topQueue=report.topTracks.map {it.track}
                    items(report.topTracks.take(5),key={"top:${it.track.id}"}) {ranked->
                        Row(Modifier.fillMaxWidth().glass(14.dp).clickable {playback.play(ranked.track,topQueue)}.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                            Cover(ranked.track.coverUrl,Modifier.size(48.dp))
                            Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                                Text(ranked.track.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
                                Text(ranked.track.artist,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelMedium)
                                Text("${listeningTime(ranked.listenedMs)} · ${ranked.sessions} прослушиваний",color=Blue,style=MaterialTheme.typography.labelSmall)
                            }
                            Icon(Icons.Outlined.PlayCircle,"Слушать ${ranked.track.title}",tint=Blue)
                        }
                    }
                }
                if(report.topArtists.isNotEmpty()) item {
                    Column(Modifier.fillMaxWidth().glass().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Топ исполнителей",style=MaterialTheme.typography.titleMedium)
                        report.topArtists.take(5).forEachIndexed {index,artist->
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("${index+1}",color=Blue,modifier=Modifier.width(28.dp))
                                Column(Modifier.weight(1f)) {Text(artist.name,maxLines=2,overflow=TextOverflow.Ellipsis);Text("${listeningTime(artist.listenedMs)} · ${artist.sessions} прослушиваний",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Быстрый доступ",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(bottom=2.dp))
                ProfileShortcut("Любимые треки","$favoriteCount треков",Icons.Outlined.FavoriteBorder){onLibrary("favorites")}
                ProfileShortcut("Мои плейлисты","${playlists.size} плейлистов",Icons.AutoMirrored.Outlined.QueueMusic){onLibrary("playlists")}
                ProfileShortcut("Скачанные треки","${downloads.count {it.download.state=="complete"}} треков на устройстве",Icons.Outlined.Download){onLibrary("downloads")}
                ProfileShortcut("Настройки","Оформление, плеер и хранение",Icons.Outlined.Settings,onSettings)
            }
        }
        if(!state.busy && state.error==null && report?.history?.isNotEmpty()==true) {
            item {Text("История прослушивания",style=MaterialTheme.typography.titleLarge)}
            val queue=report.history.mapNotNull {it.track}.distinctBy {it.id}
            items(report.history.take(historyLimit),key={"history:${it.session.id}"}) {entry->
                val track=entry.track
                Column(Modifier.fillMaxWidth().glass(14.dp).then(if(track!=null)Modifier.clickable {playback.play(track,queue)} else Modifier).padding(13.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(track?.title?:"Трек удалён из библиотеки",maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Medium)
                    if(track!=null)Text(track.artist,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${historyDate(entry.session.startedAt)} · ${listeningTime(entry.session.listenedMs)} прослушано",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(report.history.size>historyLimit)item {OutlinedButton({historyLimit+=20},Modifier.fillMaxWidth()){Text("Показать ещё")}}
        }
    }
    if(editing) {
        var name by rememberSaveable {mutableStateOf(profile.name)}
        var bio by rememberSaveable {mutableStateOf(profile.bio)}
        val saving by model.profileSave.collectAsStateWithLifecycle()
        val startedAt=rememberSaveable {saving.revision}
        val draft=ProfileDraft.of(name,bio)
        LaunchedEffect(saving.revision) {if(saving.revision>startedAt)editing=false}
        AlertDialog(onDismissRequest={if(!saving.busy)editing=false},title={Text("Мой профиль")},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name,{name=ProfileDraft.of(it,bio).name},label={Text("Имя")},enabled=!saving.busy,singleLine=true,modifier=Modifier.fillMaxWidth(),supportingText={Text("${name.length}/40")})
                OutlinedTextField(bio,{bio=ProfileDraft.of(name,it).bio},label={Text("О себе")},enabled=!saving.busy,minLines=2,maxLines=4,modifier=Modifier.fillMaxWidth(),supportingText={Text("${bio.length}/160")})
                (draft.error?:saving.error)?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
                if(saving.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },confirmButton={TextButton({model.updateProfile(draft.name,draft.bio)},enabled=draft.valid && !saving.busy){Text("Сохранить")}},dismissButton={TextButton({editing=false},enabled=!saving.busy){Text("Отмена")}})
    }
}

private fun historyDate(time:Long):String = DateTimeFormatter.ofPattern("d MMM, HH:mm",Locale.forLanguageTag("ru"))
    .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
