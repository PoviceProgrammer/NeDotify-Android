package ru.nedotify.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Called under LyricsClient's existing cache lock on IO. Never part of a library backup. */
class LyricsDiskCache(private val folder:File,private val replace:(File,File)->Unit={temporary,target->
    Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
}) {
    private fun file(key:String)=File(folder,MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") {"%02x".format(it.toInt() and 255)}+".json")
    fun read(key:String,now:Long):Pair<LyricsResult,Long>?=runCatching {
        val file=file(key)
        if(!file.isFile || file.length()>1_200_000)return null
        val text=file.readText(Charsets.UTF_8)
        BackupCodec.requireStrictJson(text)
        val value=JSONObject(text)
        if(value.optInt("schemaVersion")!=2)return null
        val expiry=value.getLong("expiresAt")
        if(expiry<=now || expiry>now+86_400_000L)return null
        val plain=value.getString("plain");if(plain.length>256_000)return null
        val array=value.getJSONArray("lines");if(array.length()>20_000)return null
        val lines=(0 until array.length()).map {index->array.getJSONObject(index).let {line->
            val time=line.getLong("timeMs");val text=line.getString("text")
            if(time !in 0..604_800_000 || text.length>256_000)throw IllegalArgumentException()
            LrcLine(time,text)
        }}
        if(lines.zipWithNext().any {it.first.timeMs>it.second.timeMs})return null
        LyricsResult(plain,lines,value.getBoolean("instrumental"),value.optText("source").take(40)) to expiry
    }.getOrNull()
    fun write(key:String,result:LyricsResult,expiresAt:Long) {
        runCatching {
            if(!folder.exists() && !folder.mkdirs())return
            // A killed process can leave its one atomic-write staging file behind.
            folder.listFiles()?.filter {it.name.matches(Regex("[0-9a-f]{64}\\.json\\.tmp"))}?.forEach {it.delete()}
            val target=file(key);val temporary=File(folder,target.name+".tmp")
            try {
                val json=JSONObject().put("schemaVersion",2).put("expiresAt",expiresAt).put("plain",result.plain).put("instrumental",result.instrumental).put("source",result.source)
                    .put("lines",JSONArray().also {rows->result.lines.forEach {rows.put(JSONObject().put("timeMs",it.timeMs).put("text",it.text))}}).toString()
                if(json.toByteArray(Charsets.UTF_8).size>1_200_000)return
                temporary.writeText(json,Charsets.UTF_8)
                // Never delete the previous offline lyrics before a replacement
                // succeeds. API 26+ supports atomic rename within this directory.
                replace(temporary,target)
                folder.listFiles()?.filter {it.name.matches(Regex("[0-9a-f]{64}\\.json"))}
                    ?.sortedByDescending {it.lastModified()}?.drop(100)?.forEach {it.delete()}
            } finally {temporary.delete()}
        }
    }
}
