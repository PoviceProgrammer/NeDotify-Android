package ru.nedotify.app.data

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

/** Earliest server deadline wins; CloudFront uses a different alphabet from base64url. */
fun streamExpiry(url:String,now:Long=System.currentTimeMillis()):Long {
    val params=runCatching {URI(url).rawQuery.orEmpty().split('&').mapNotNull {part->
        val pair=part.split('=',limit=2)
        if(pair.size!=2)null else pair[0].lowercase(Locale.ROOT) to URLDecoder.decode(pair[1].replace("+","%2B"),"UTF-8")
    }}.getOrDefault(emptyList())
    val deadlines=params.filter {it.first in setOf("expire","expires")}.mapNotNull {secondsToMs(it.second)}.toMutableList()
    params.filter {it.first=="policy"}.forEach {(_,encoded)->
        val policy=runCatching {
            require(encoded.length<=32_768)
            val standard=encoded.replace('-','+').replace('_','=').replace('~','/')
            val bytes=runCatching {Base64.getDecoder().decode(standard)}.getOrElse {Base64.getUrlDecoder().decode(encoded)}
            JSONObject(String(bytes,Charsets.UTF_8))
        }.getOrNull()
        val policyDeadlines=policy?.optJSONArray("Statement")?.objects().orEmpty().mapNotNull {statement->
            secondsToMs(statement.optJSONObject("Condition")?.optJSONObject("DateLessThan")?.optString("AWS:EpochTime").orEmpty())
        }.toMutableList()
        policy?.optString("expires")?.let {secondsToMs(it)?.let(policyDeadlines::add)}
        // A malformed signed policy must not extend an otherwise long-lived URL.
        if(policyDeadlines.isEmpty())deadlines+=now+60_000 else deadlines+=policyDeadlines
    }
    return (deadlines.minOrNull()?: (now+15*60_000))-60_000
}
private fun secondsToMs(raw:String):Long?=raw.toLongOrNull()?.takeIf {it>=0 && it<=Long.MAX_VALUE/1000}?.times(1000)
