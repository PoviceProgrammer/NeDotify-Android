package ru.nedotify.app.player

import ru.nedotify.app.data.SourceFailure
import ru.nedotify.app.data.Track

/** A clicked result may be outside a filtered or concurrently refreshed list. */
fun playbackQueue(track:Track,queue:List<Track>):List<Track> =
    (if(queue.any {it.id==track.id})queue else listOf(track)+queue).distinctBy {it.id}

data class QueueSelection<T>(val items:List<T>,val originalIndices:List<Int>,val startIndex:Int,val keptRequestedStart:Boolean)

/** Appending a Flow batch must not randomly insert its shuffle windows behind the current cursor. */
internal fun appendedShuffleTraversal(previous:IntArray,existingCount:Int,addedCount:Int):IntArray {
    require(existingCount>=0 && addedCount>=0 && existingCount.toLong()+addedCount<=Int.MAX_VALUE)
    val seen=BooleanArray(existingCount)
    val valid=previous.size==existingCount && previous.all {index->
        if(index !in 0 until existingCount || seen[index])false else {seen[index]=true;true}
    }
    val retained=if(valid)previous else IntArray(existingCount){it}
    return retained+IntArray(addedCount){existingCount+it}
}

internal data class QueueSourceReplacement<T>(val index:Int,val item:T)

/** Preserve positions, duplicates and unknown rows; refresh the audible item last. */
internal fun <T:Any> planQueueSourceRefresh(items:List<T>,refreshed:Map<String,T>,currentIndex:Int,includeCurrent:Boolean=true,id:(T)->String):List<QueueSourceReplacement<T>> =
    items.mapIndexedNotNull {index,old->
        if(index==currentIndex && !includeCurrent)null else refreshed[id(old)]?.takeIf {it!=old}?.let {QueueSourceReplacement(index,it)}
    }
        .sortedWith(compareBy<QueueSourceReplacement<T>> {it.index==currentIndex}.thenBy {it.index})

fun <T:Any> selectQueue(ids:List<String>,available:Map<String,T>,requestedStart:Int):QueueSelection<T> {
    // A deliberate clear is valid; a nonempty queue whose records disappeared is an error.
    if(ids.isEmpty())return QueueSelection(emptyList(),emptyList(),0,false)
    val retained=ids.indices.filter {available.containsKey(ids[it])}
    if(retained.isEmpty())throw SourceFailure("В очереди нет доступных треков")
    val requested=requestedStart.coerceIn(0,ids.lastIndex)
    val chosen=retained.firstOrNull {it>=requested}?:retained.first()
    return QueueSelection(retained.map {available.getValue(ids[it])},retained,retained.indexOf(chosen),chosen==requested)
}
