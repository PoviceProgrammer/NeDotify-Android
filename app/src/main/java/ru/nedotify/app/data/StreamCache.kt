package ru.nedotify.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-owned single-flight jobs survive cancellation of an individual UI waiter.
 *
 * A waiter that goes away must not leave the flight behind: the job lives on
 * the app's scope, so a cancelled waiter would neither cancel it nor release
 * the slot it occupies, and the next request for the same track would join a
 * flight nobody is waiting for. The flight therefore counts its waiters and
 * is cancelled when the last one leaves.
 */
class StreamCache(private val scope:CoroutineScope,private val capacity:Int=128,
    private val now:()->Long=System::currentTimeMillis,private val loader:suspend (String)->ResolvedStream) {
    private val lock=Mutex()
    private val values=linkedMapOf<String,ResolvedStream>()
    private class Flight(val token:Any,val task:Deferred<ResolvedStream>,var waiters:Int)
    private val inflight=mutableMapOf<String,Flight>()
    init {require(capacity>0)}
    suspend fun invalidate(id:String) {
        val old=lock.withLock {values.remove(id);inflight.remove(id)}
        old?.task?.cancel()
    }
    suspend fun invalidateMatching(matches:(String)->Boolean) {
        val old=lock.withLock {
            values.keys.filter(matches).forEach(values::remove)
            inflight.keys.filter(matches).mapNotNull(inflight::remove)
        }
        old.forEach {it.task.cancel()}
    }
    suspend fun cancelAll() {
        val old=lock.withLock {values.clear();inflight.values.toList().also {inflight.clear()}}
        old.forEach {it.task.cancel()}
    }
    suspend fun get(id:String):ResolvedStream {
        val flight=lock.withLock {
            values.remove(id)?.takeIf {it.expiresAt>now()}?.let {values[id]=it;return it}
            val existing=inflight[id]
            if(existing!=null){existing.waiters++;existing}
            else {
                val token=Any()
                val task=scope.async(start=CoroutineStart.LAZY) {
                    try {
                        loader(id).also {stream->lock.withLock {
                            if(inflight[id]?.token===token) {
                                values[id]=stream
                                while(values.size>capacity)values.remove(values.keys.first())
                            }
                        }}
                    } finally {withContext(NonCancellable){lock.withLock {if(inflight[id]?.token===token)inflight.remove(id)}}}
                }
                val created=Flight(token,task,1)
                inflight[id]=created
                task.start()
                created
            }
        }
        return try {flight.task.await()}
        catch(cancelled:CancellationException) {
            // The waiter is gone. The flight survives as long as another waiter
            // needs it; the last one releases the slot and the job.
            // The cleanup must not be cancelled by the very cancellation that
            // brought us here: `Mutex.lock` suspends cancellably, so a
            // contended lock would cancel the cleanup before it ran and the
            // flight would keep its waiter forever.
            val orphan=withContext(NonCancellable) {
                lock.withLock {
                    val current=inflight[id]
                    if(current?.token!==flight.token)null
                    else {current.waiters--;if(current.waiters<=0){inflight.remove(id);current}else null}
                }
            }
            orphan?.task?.cancel()
            throw cancelled
        }
    }
}
