package ru.nedotify.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The persisted queue. The *identity* (what is queued and how it is traversed) and the
 * *position* (where playback is) are separate: the identity decides whether the queue
 * changed, the position is written on a slower cadence.
 */
data class QueueSnapshot(
    val ids:List<String>,
    val index:Int,
    val currentId:String,
    val repeat:Int,
    val shuffle:Boolean,
    val shuffleOrder:List<Int>,
    val positionMs:Long,
)

/**
 * The queue snapshot codec.
 *
 * The payload carries its own version and the read path validates it: an unknown
 * version is refused instead of restoring a shape nobody wrote. The version 2 payload
 * (the flat one this app used to write under `queue_v1`) is still read, so an existing
 * saved queue keeps restoring.
 */
object QueueSnapshotCodec {
    /** The payload version this build writes. */
    const val VERSION=3
    /** The flat payload an older install wrote; it is read, never written. */
    const val LEGACY_VERSION=2

    fun encode(snapshot:QueueSnapshot):String {
        val queue=JSONObject().put("ids",JSONArray(snapshot.ids)).put("index",snapshot.index)
            .put("currentId",snapshot.currentId).put("repeat",snapshot.repeat)
            .put("shuffle",snapshot.shuffle).put("shuffleOrder",JSONArray(snapshot.shuffleOrder))
        return JSONObject().put("version",VERSION).put("queue",queue).put("position",snapshot.positionMs).toString()
    }

    /** The queue identity: everything but the position. */
    fun identity(snapshot:QueueSnapshot):String = buildString {
        append(JSONArray(snapshot.ids)).append('|').append(snapshot.index).append('|')
        append(snapshot.currentId).append('|').append(snapshot.repeat).append('|')
        append(snapshot.shuffle).append('|').append(JSONArray(snapshot.shuffleOrder))
    }

    /** @return the snapshot, or null when the payload is blank, malformed or of an unknown version. */
    fun decode(raw:String):QueueSnapshot? {
        if(raw.isBlank())return null
        val json=runCatching {JSONObject(raw)}.getOrNull()?:return null
        return when(json.optInt("version",-1)) {
            VERSION->decodeCurrent(json)
            LEGACY_VERSION->decodeLegacy(json)
            else->null
        }
    }

    private fun decodeCurrent(json:JSONObject):QueueSnapshot? {
        val queue=json.optJSONObject("queue")?:return null
        val ids=queue.optJSONArray("ids")?:return null
        val list=stringList(ids)
        val currentId=queue.optString("currentId")
        return snapshot(list,indexOf(queue,list,currentId),currentId,
            queue.optInt("repeat",0),queue.optBoolean("shuffle",false),intList(queue.optJSONArray("shuffleOrder")),
            json.optLong("position",0))
    }

    private fun decodeLegacy(json:JSONObject):QueueSnapshot? {
        val ids=json.optJSONArray("ids")?:return null
        val list=stringList(ids)
        val currentId=json.optString("currentId")
        return snapshot(list,indexOf(json,list,currentId),currentId,
            json.optInt("repeat",0),json.optBoolean("shuffle",false),intList(json.optJSONArray("shuffleOrder")),
            json.optLong("position",0))
    }

    /** Without an explicit index the current item decides where the queue resumes. */
    private fun indexOf(json:JSONObject,ids:List<String>,currentId:String):Int =
        if(json.has("index"))json.optInt("index",0) else ids.indexOf(currentId).coerceAtLeast(0)

    private fun snapshot(ids:List<String>,index:Int,currentId:String,repeat:Int,shuffle:Boolean,
        shuffleOrder:List<Int>,positionMs:Long):QueueSnapshot =
        QueueSnapshot(ids,index.coerceAtLeast(0),currentId,repeat.coerceIn(0,2),shuffle,
            shuffleOrder,positionMs.coerceAtLeast(0))

    private fun stringList(array:JSONArray):List<String> = (0 until array.length()).map {array.optString(it)}
    /** A missing or malformed entry stays -1: the reader rejects such an order instead of shortening it. */
    private fun intList(array:JSONArray?):List<Int> =
        if(array==null)emptyList() else (0 until array.length()).map {array.optInt(it,-1)}
}
