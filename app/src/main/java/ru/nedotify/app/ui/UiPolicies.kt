package ru.nedotify.app.ui

import ru.nedotify.app.data.LrcLine
import java.net.URI

/** Back retains the actual screen that opened settings/profile, including nested visits. */
internal fun navigateTo(stack:List<String>,route:String):List<String> =
    if(stack.lastOrNull()==route)stack else (stack+route).let {if(it.size>20)listOf("home")+it.takeLast(19) else it}

internal fun navigateBack(stack:List<String>):List<String> = if(stack.size>1)stack.dropLast(1) else listOf("home")

/** Only network artwork and Android media/document URIs may reach the image loader. */
internal fun artworkModel(value:String,offline:Boolean):String? {
    if(value.isBlank() || value.length>8192)return null
    val uri=runCatching {URI(value)}.getOrNull()?:return null
    return when(uri.scheme?.lowercase(java.util.Locale.ROOT)) {
        "https"->value.takeIf {!offline && !uri.host.isNullOrBlank() && uri.userInfo==null}
        "content"->value.takeIf {!uri.authority.isNullOrBlank()}
        else->null
    }
}

/** The last line at a timestamp wins, including duplicate LRC timestamps. */
internal fun activeLyricLine(lines:List<LrcLine>,position:Long):Int {
    var low=0;var high=lines.size
    while(low<high) {
        val middle=(low+high) ushr 1
        if(lines[middle].timeMs<=position)low=middle+1 else high=middle
    }
    return low-1
}

internal fun nextRepeatMode(current:Int):Int=when(current){0->2;2->1;else->0}

/** The action of the play button: pause while the sound runs, play otherwise. */
internal fun toggleLabel(playing:Boolean,buffering:Boolean,playWhenReady:Boolean):String =
    if(playing || buffering && playWhenReady)"Пауза" else "Воспроизвести"

/** Keep a committed thumb until the controller acknowledges it or the bounded wait expires. */
internal fun pendingSeekPosition(target:Long?,actual:Long,elapsedMs:Long):Long? =
    target?.takeUnless {kotlin.math.abs(it-actual)<=750 || elapsedMs>=2_000}

internal fun greeting(hour:Int):String=when(hour){in 5..11->"Доброе утро!";in 12..17->"Добрый день!";else->"Добрый вечер!"}

/** Queue duplicates keep an occurrence key instead of binding state to their list index. */
internal fun queueItemKeys(rows:List<QueueRow>):List<String> {
    val seen=mutableMapOf<String,Int>()
    return rows.map {row->val occurrence=seen.getOrDefault(row.id,0);seen[row.id]=occurrence+1;"${row.id}:$occurrence"}
}
