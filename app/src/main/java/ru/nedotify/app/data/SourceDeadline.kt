package ru.nedotify.app.data

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Convert only this operation's timeout; the caller's cancellation must retain its meaning. */
internal suspend fun <T> sourceDeadline(millis:Long,message:String,block:suspend ()->T):T =
    try {withTimeout(millis) {block()}}
    catch(error:TimeoutCancellationException) {currentCoroutineContext().ensureActive();throw SourceFailure(message)}
