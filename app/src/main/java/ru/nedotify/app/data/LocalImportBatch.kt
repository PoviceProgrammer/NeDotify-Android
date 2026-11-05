package ru.nedotify.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class LocalImportResult(val imported:Int=0,val failed:Int=0)

/** One revoked or unavailable document must not prevent importing the other selections. */
internal suspend fun <T> importLocalBatch(items:List<T>,importOne:suspend (T)->Unit):LocalImportResult {
    var imported=0;var failed=0
    for(item in items.distinct()) {
        currentCoroutineContext().ensureActive()
        try {importOne(item);imported++}
        catch(error:CancellationException) {throw error}
        catch(_:Exception) {currentCoroutineContext().ensureActive();failed++}
    }
    return LocalImportResult(imported,failed)
}
