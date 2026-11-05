package ru.nedotify.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.nedotify.app.data.DailyListening
import ru.nedotify.app.data.ReportPeriod
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun reportPeriodLabel(period:ReportPeriod)=when(period) {
    ReportPeriod.WEEK->"7 дней"
    ReportPeriod.MONTH->"30 дней"
    ReportPeriod.ALL->"Всё время"
}

internal fun listeningTime(ms:Long):String {
    val minutes=ms.coerceAtLeast(0)/60_000
    return when {
        ms in 1..59_999->"меньше минуты"
        minutes>=60->"${minutes/60} ч ${minutes%60} мин"
        else->"$minutes мин"
    }
}

@Composable
internal fun ProfileStatTile(title:String,value:String,icon:ImageVector,modifier:Modifier=Modifier,onClick:(()->Unit)?=null) {
    Column(modifier.glass(14.dp).then(if(onClick!=null)Modifier.clickable(onClick=onClick) else Modifier).padding(12.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Icon(icon,null,tint=Blue,modifier=Modifier.size(27.dp))
        Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
        Text(title,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ProfileShortcut(title:String,subtitle:String,icon:ImageVector,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().glass(14.dp).clickable(onClick=onClick).padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=Blue,modifier=Modifier.size(27.dp))
        Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
            Text(title,style=MaterialTheme.typography.bodyMedium)
            Text(subtitle,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ReportComparison(previousMs:Long?,changePercent:Double?) {
    val text=when {
        previousMs==null->"За всё время"
        previousMs==0L->"В прошлом периоде прослушиваний не было"
        changePercent==null || !changePercent.isFinite()->"Прошлый период: ${listeningTime(previousMs)}"
        else->"${if(changePercent>0)"+" else ""}${String.format(Locale.ROOT,"%.0f",changePercent)}% к прошлому периоду · было ${listeningTime(previousMs)}"
    }
    Text(text,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Bars are decorative; equivalent daily values are available to TalkBack. */
@Composable
internal fun ListeningChart(daily:List<DailyListening>,modifier:Modifier=Modifier) {
    val days=daily.takeLast(30)
    if(days.isEmpty())return
    val accent=Blue
    val muted=MaterialTheme.colorScheme.outline.copy(alpha=.4f)
    val format=DateTimeFormatter.ofPattern("d MMM",Locale.forLanguageTag("ru"))
    val description=days.joinToString("; ") {"${format.format(it.date)}: ${listeningTime(it.listenedMs)}"}
    Column(modifier.fillMaxWidth()) {
        Text(if(daily.size>30)"Последние 30 дней" else "По дням",style=MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().height(112.dp).padding(top=12.dp).semantics {contentDescription=description}) {
            val max=days.maxOf {it.listenedMs}.coerceAtLeast(1L)
            val step=size.width/days.size
            val width=(step*.66f).coerceAtLeast(1f)
            days.forEachIndexed {index,day->
                val height=(day.listenedMs.toDouble()/max*size.height).toFloat().coerceAtLeast(2f)
                drawRoundRect(if(day.listenedMs>0)accent else muted,Offset(index*step+(step-width)/2,size.height-height),Size(width,height),CornerRadius(3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(format.format(days.first().date),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(days.size>1)Text(format.format(days.last().date),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val peak=days.maxByOrNull {it.listenedMs}
        if(peak!=null && peak.listenedMs>0)Text("Больше всего: ${format.format(peak.date)} — ${listeningTime(peak.listenedMs)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
        Text("Прослушивания сгруппированы по дате начала сессии",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
    }
}
