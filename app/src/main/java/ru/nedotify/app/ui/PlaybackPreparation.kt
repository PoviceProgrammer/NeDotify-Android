package ru.nedotify.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Main-scope queue preparation belongs to the latest user intent, even if IO finishes late. */
internal class PlaybackPreparation<T>(private val scope:CoroutineScope) {
    private var generation=0L
    private var pending:Job?=null
    private var completion:(()->Unit)?=null
    fun cancel() {
        generation++;pending?.cancel();pending=null
        val finish=completion;completion=null;finish?.invoke()
    }
    /**
     * Cancel the preparation and wait until it has really stopped.
     *
     * A cancelled load may still be inside a non-cancellable write. The caller
     * that has to remove what the load wrote — the library row of a track the
     * user deleted — must not overtake that write, or the row comes back.
     */
    suspend fun cancelAndAwait() {
        val job=pending
        cancel()
        job?.join()
    }
    fun submit(load:suspend ()->T,onReady:(T)->Unit,onFailure:(Exception)->Unit,onComplete:()->Unit={}) {
        cancel();val token=generation
        completion=onComplete
        pending=scope.launch {
            try {
                val value=load();ensureActive()
                if(token==generation)onReady(value)
            } catch(error:CancellationException) {throw error}
            catch(error:Exception) {ensureActive();if(token==generation)onFailure(error)}
            finally {if(token==generation) {
                pending=null
                val finish=completion;completion=null;finish?.invoke()
            }}
        }
    }
}
