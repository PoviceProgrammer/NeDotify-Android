package ru.nedotify.app.data

import org.json.JSONObject

data class LrcLine(val timeMs:Long,val text:String)
data class LyricsResult(val plain:String="",val lines:List<LrcLine> = emptyList(),val instrumental:Boolean=false,val source:String="")

/** Bounded LRCLIB text; malformed timestamps are skipped without breaking the player. */
fun parseLrc(text:String):List<LrcLine> {
    require(text.length<=256_000) {"Текст песни слишком большой"}
    val pattern=Regex("\\[(\\d{1,9}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val lines=ArrayList<LrcLine>()
    text.lineSequence().forEach {line->
        val lyric=line.replace(pattern,"").trim()
        pattern.findAll(line).forEach {match->
            val minutes=match.groupValues[1].toLongOrNull()
            val seconds=match.groupValues[2].toIntOrNull()
            if(minutes!=null && seconds!=null && seconds<60 && minutes<10_080) {
                val millis=match.groupValues[3].padEnd(3,'0').toLong()
                lines+=LrcLine(minutes*60_000+seconds*1000+millis,lyric)
                require(lines.size<=20_000) {"В тексте слишком много строк"}
            }
        }
    }
    return lines.distinct().sortedBy {it.timeMs}
}

fun lyricsResult(result:JSONObject):LyricsResult {
    val plain=result.optText("plainLyrics")
    val synced=result.optText("syncedLyrics")
    require(plain.length<=256_000 && synced.length<=256_000) {"Текст песни слишком большой"}
    val instrumental=result.opt("instrumental")==true
    // The provider that served the text is part of the result: an empty source
    // would be written into the disk cache and shown to the user.
    return LyricsResult(plain=plain,lines=if(instrumental)emptyList() else parseLrc(synced),instrumental=instrumental,source="LRCLIB")
}
