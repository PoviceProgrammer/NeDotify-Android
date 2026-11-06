package ru.nedotify.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.nedotify.app.data.*
import ru.nedotify.app.ui.state.*
import ru.nedotify.app.ui.tokens.DesignTokens

@Composable
fun PlayerScreen(playback:PlaybackConnection,library:LibraryViewModel,lyricsModel:LyricsViewModel,settings:SettingsViewModel) {
    val now by playback.playback.collectAsStateWithLifecycle()
    val tracks by library.tracks.collectAsStateWithLifecycle()
    val lyrics by lyricsModel.lyricsState.collectAsStateWithLifecycle()
    val appearance by settings.appearance.collectAsStateWithLifecycle()
    var tab by rememberSaveable {mutableIntStateOf(0)}
    val trackIndex=remember(tracks){tracks.associateBy {it.id}}
    val current=trackIndex[now.id]
    val queueKeys=remember(now.queue){queueItemKeys(now.queue)}
    LaunchedEffect(now.id,tab){if(tab==2 && now.id.isNotBlank())lyricsModel.loadLyrics()}
    Column(Modifier.fillMaxSize().padding(horizontal=DesignTokens.Spacing.xl.dp)) {
        Text("Сейчас играет",style=MaterialTheme.typography.titleMedium,modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.Center) {
            listOf("Плеер","Очередь","Текст").forEachIndexed {index,title->
                FilterChip(tab==index,{tab=index},label={Text(title)},modifier=Modifier.padding(horizontal=4.dp).heightIn(min=48.dp))
            }
        }
        when(tab) {
            0->BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val wide=maxWidth>500.dp && maxHeight<420.dp
                val coverSize=if(wide)148.dp else minOf(maxWidth,190.dp,maxHeight*.40f).coerceAtLeast(88.dp)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(wide)Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        Cover(now.artwork,Modifier.size(coverSize),now.title)
                        Box(Modifier.weight(1f)){TrackHeading(now,current?.favorite,current?.let {track->{library.favorite(track)}})}
                    } else {
                        Cover(now.artwork,Modifier.size(coverSize).align(Alignment.CenterHorizontally),now.title)
                        TrackHeading(now,current?.favorite,current?.let {track->{library.favorite(track)}})
                    }
                    PlayerTimeline(playback,now.id,now.duration)
                    current?.source?.takeIf {it.isNotBlank()}?.let {source->
                        Text(when(source){"local"->"На устройстве";"soundcloud"->"SoundCloud";"youtube"->"YouTube Music · экспериментально";else->source},
                            style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.align(Alignment.CenterHorizontally))
                    }
                    val nextIndices=((now.activeIndex+1).coerceAtLeast(0)..now.queue.lastIndex).take(2)
                    if(nextIndices.isNotEmpty())Column(Modifier.fillMaxWidth().glass().padding(12.dp)) {
                        BrowseHeading("Далее","Вся очередь"){tab=1}
                        nextIndices.forEach {index->val item=now.queue[index]
                            Row(Modifier.fillMaxWidth().heightIn(min=68.dp).clickable(onClickLabel="Слушать ${item.title}"){playback.queueIndex(index)}
                                .padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                Cover(item.artwork,Modifier.size(40.dp))
                                Column(Modifier.weight(1f).padding(horizontal=10.dp)) {
                                    Text(item.title,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text(item.artist,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            2->LyricsHost(playback,lyricsModel,lyrics,now.id,now.duration,appearance.lyricsAutoScroll,Modifier.weight(1f).fillMaxWidth())
            else->LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(vertical=14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(now.queue.isEmpty())item {EmptyState("Очередь пуста","Выберите трек в библиотеке или поиске")}
                if(now.queue.size>1)item(key="clear-queue") {TextButton(playback::clearQueue) {Text("Очистить очередь, оставив текущий трек")}}
                itemsIndexed(now.queue,key={index,_->queueKeys[index]}) {index,item->
                    val active=index==now.activeIndex
                    var menu by remember {mutableStateOf(false)}
                    Row(Modifier.fillMaxWidth().glass(12.dp).heightIn(min=80.dp).semantics {selected=active}.padding(start=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable(onClickLabel="Слушать ${item.title}"){playback.queueIndex(index)}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.width(26.dp),contentAlignment=Alignment.Center) {
                                if(active)Icon(Icons.Outlined.GraphicEq,"Текущий трек",tint=Blue,modifier=Modifier.size(18.dp))
                                else Text("${index+1}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Cover(item.artwork,Modifier.size(44.dp))
                            Column(Modifier.weight(1f).padding(horizontal=10.dp)) {
                                Text(item.title.ifBlank {"Без названия"},maxLines=2,overflow=TextOverflow.Ellipsis,color=if(active)Blue else MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.bodyMedium)
                                Text(item.artist,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                (item.durationMs.takeIf {it>0}?:trackIndex[item.id]?.durationMs)?.takeIf {it>0}?.let {Text(clock(it),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            }
                        }
                        Box {
                            IconButton({menu=true},Modifier.size(48.dp)){Icon(Icons.Outlined.MoreVert,"Действия в очереди: ${item.title}")}
                            DropdownMenu(menu,{menu=false}) {
                                DropdownMenuItem(text={Text("Выше")},enabled=index>0,onClick={menu=false;playback.moveQueueItem(index,index-1)})
                                DropdownMenuItem(text={Text("Ниже")},enabled=index<now.queue.lastIndex,onClick={menu=false;playback.moveQueueItem(index,index+1)})
                                DropdownMenuItem(text={Text("Убрать из очереди")},onClick={menu=false;playback.removeQueueItem(index)})
                            }
                        }
                    }
                }
            }
        }
        now.error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite}.padding(vertical=6.dp))}
        PlayerTransport(now,playback::shuffle,playback::previous,playback::toggle,playback::next,playback::repeat)
    }
}

@Composable
private fun TrackHeading(now:NowPlaying,favorite:Boolean?,onLike:(()->Unit)?) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(now.title.ifBlank {"Выберите трек"},style=MaterialTheme.typography.titleLarge,maxLines=3,overflow=TextOverflow.Ellipsis)
            if(now.artist.isNotBlank())Text(now.artist,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=3.dp))
        }
        if(onLike!=null)IconToggleButton(favorite==true,{onLike()}) {
            Icon(if(favorite==true)Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,if(favorite==true)"Убрать из избранного" else "В избранное",tint=if(favorite==true)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PlayerTransport(now:NowPlaying,onShuffle:()->Unit,onPrevious:()->Unit,onToggle:()->Unit,onNext:()->Unit,onRepeat:()->Unit) {
    val hasTrack=now.id.isNotBlank()
    val toggleLabel=toggleLabel(now.playing,now.buffering,now.playWhenReady)
    Row(Modifier.fillMaxWidth().padding(vertical=10.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
        IconToggleButton(now.shuffle,{onShuffle()},enabled=hasTrack) {Icon(Icons.Outlined.Shuffle,"Перемешивание",tint=if(now.shuffle)Blue else LocalContentColor.current)}
        IconButton(onPrevious,enabled=hasTrack){Icon(Icons.Filled.SkipPrevious,"Предыдущий трек")}
        FilledIconButton(onToggle,enabled=hasTrack,modifier=Modifier.size(64.dp).semantics {
            contentDescription=toggleLabel
            if(now.buffering)stateDescription="Буферизация"
        }) {
            if(now.buffering)CircularProgressIndicator(Modifier.size(26.dp),color=MaterialTheme.colorScheme.onPrimary,strokeWidth=2.dp)
            else Icon(if(now.playing)Icons.Filled.Pause else Icons.Filled.PlayArrow,null,Modifier.size(34.dp))
        }
        IconButton(onNext,enabled=hasTrack){Icon(Icons.Filled.SkipNext,"Следующий трек")}
        IconButton(onRepeat,enabled=hasTrack,modifier=Modifier.semantics {stateDescription=when(now.repeat){1->"Один трек";2->"Вся очередь";else->"Повтор выключен"}}) {
            Icon(if(now.repeat==1)Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,"Режим повтора",tint=if(now.repeat!=0)Blue else LocalContentColor.current)
        }
    }
}

@Composable
internal fun LyricsContent(state:LyricsState,trackId:String,position:Long,duration:Long,autoScroll:Boolean,modifier:Modifier=Modifier,onSeek:(Long)->Unit,onRetry:()->Unit) {
    val list=rememberLazyListState()
    var follow by rememberSaveable(trackId) {mutableStateOf(true)}
    val manualScroll=remember(trackId) {object:NestedScrollConnection {
        override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset {
            if(source==NestedScrollSource.UserInput && available.y!=0f)follow=false
            return Offset.Zero
        }
    }}
    val dragging by list.interactionSource.collectIsDraggedAsState()
    val current=state.takeIf {it.trackId==trackId}
    val lines=current?.lines.orEmpty()
    val active=activeLyricLine(lines,position)
    val reducedMotion=rememberReducedMotion()
    LaunchedEffect(dragging) {if(dragging)follow=false}
    LaunchedEffect(trackId) {list.scrollToItem(0)}
    LaunchedEffect(active,trackId,follow,autoScroll) {
        if(autoScroll && follow && active>=0) {
            val offset=-list.layoutInfo.viewportSize.height/3
            if(reducedMotion)list.scrollToItem(active,offset) else list.animateScrollToItem(active,offset)
        }
    }
    Column(modifier) {
        if(current?.source?.isNotBlank()==true)Text("Текст: ${current.source}",style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=6.dp))
        if(current?.loading==true || current==null) {
            Column(Modifier.fillMaxSize().padding(vertical=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                CircularProgressIndicator();Text("Ищем текст…",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if(current.error!=null) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(top=14.dp)) {EmptyState("Текст недоступен",current.error,"Повторить",onRetry)}
        } else if(current.instrumental) {
            Column(Modifier.padding(top=14.dp)) {EmptyState("Без слов","У этого трека инструментальная версия")}
        } else if(lines.isEmpty()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical=20.dp)) {
                if(current.plain.isBlank())EmptyState("Текст не найден","Для этого трека пока нет текста","Повторить поиск",onRetry)
                else {Text("Текст без синхронизации",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(current.plain,lineHeight=30.sp,modifier=Modifier.padding(top=12.dp))}
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(manualScroll),state=list,verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=26.dp)) {
                itemsIndexed(lines,key={index,line->"$index:${line.timeMs}"}) {index,line->
                    val selected=index==active
                    Text(line.text.ifBlank {"♪"},style=MaterialTheme.typography.titleLarge,fontWeight=if(selected)FontWeight.Bold else FontWeight.Medium,
                        color=if(selected)Blue else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier=Modifier.fillMaxWidth().clickable(enabled=duration>0 && line.timeMs<=duration){onSeek(line.timeMs.coerceIn(0,duration))}
                            .semantics {this.selected=selected;stateDescription=if(selected)"Текущая строка" else ""}.padding(vertical=10.dp))
                }
            }
            if(autoScroll && !follow)OutlinedButton({follow=true},Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Outlined.MyLocation,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("К текущей строке")
            } else if(!autoScroll)Text("Автопрокрутка выключена в настройках",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=6.dp))
        }
    }
}

/** Observe Android's animation preference without starting another player or polling it. */
@Composable
private fun rememberReducedMotion():Boolean {
    val resolver=LocalContext.current.contentResolver
    fun read()=runCatching {Settings.Global.getFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f}.getOrDefault(false)
    var disabled by remember(resolver) {mutableStateOf(read())}
    DisposableEffect(resolver) {
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {override fun onChange(selfChange:Boolean) {disabled=read()}}
        val registered=runCatching {resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)}.isSuccess
        onDispose {if(registered)resolver.unregisterContentObserver(observer)}
    }
    return disabled
}

/** Only the timeline subscribes to the position poll, so a tick never rebuilds artwork/queue. */
@Composable
private fun PlayerTimeline(model:PlaybackConnection,trackId:String,durationMs:Long) {
    val position by model.positionMs.collectAsStateWithLifecycle()
    val duration=durationMs.coerceAtLeast(0)
    var scrub by rememberSaveable(trackId) {mutableStateOf<Float?>(null)}
    var pending by rememberSaveable(trackId) {mutableStateOf<Long?>(null)}
    var committedAt by rememberSaveable(trackId) {mutableLongStateOf(0)}
    LaunchedEffect(position,pending) {pending=pendingSeekPosition(pending,position,android.os.SystemClock.elapsedRealtime()-committedAt)}
    LaunchedEffect(pending) {if(pending!=null) {kotlinx.coroutines.delay(2_000);pending=null}}
    val shown=(scrub?.toLong()?:pending?:position).coerceIn(0,duration.coerceAtLeast(1))
    Slider(value=shown.toFloat(),onValueChange={scrub=it},onValueChangeFinished={
        scrub?.toLong()?.coerceIn(0,duration)?.let {target->
            if(duration>0) {pending=target;committedAt=android.os.SystemClock.elapsedRealtime();model.seek(target)}
        };scrub=null
    },valueRange=0f..duration.toFloat().coerceAtLeast(1f),enabled=duration>0,
        modifier=Modifier.fillMaxWidth().semantics {contentDescription="Позиция воспроизведения"})
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(clock(shown),style=MaterialTheme.typography.labelMedium)
        Text(if(duration>0)clock(duration) else "—:—",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LyricsHost(model:PlaybackConnection,lyricsModel:LyricsViewModel,lyrics:LyricsState,id:String,duration:Long,follow:Boolean,modifier:Modifier) {
    val position by model.positionMs.collectAsStateWithLifecycle()
    LyricsContent(lyrics,id,position,duration,follow,modifier,model::seek,lyricsModel::retryLyrics)
}
