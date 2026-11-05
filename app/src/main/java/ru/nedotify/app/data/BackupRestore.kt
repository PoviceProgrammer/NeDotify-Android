package ru.nedotify.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The rows of a restore are written in batches, and the cancellation is checked between them.
 *
 * The restore is one Room transaction, so the batches are not committed one by one: either the
 * whole merge is in the database or none of it is. The check between the batches stops a
 * cancelled import from doing the work of the rest of the library, and it is the boundary at
 * which the cancellation is noticed.
 */
internal suspend fun <T> writeBatches(batches:List<List<T>>, write:suspend (List<T>)->Unit) {
    for(batch in batches) {
        currentCoroutineContext().ensureActive()
        write(batch)
    }
}
