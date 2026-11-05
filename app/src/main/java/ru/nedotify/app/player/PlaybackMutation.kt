package ru.nedotify.app.player

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The service calls this on its player looper: preparation may suspend, final validation/apply may not. */
internal suspend fun <T> guardedPlaybackMutation(prepare:suspend ()->T,stillCurrent:()->Boolean,apply:(T)->Unit):Boolean {
    if(!stillCurrent())return false
    val prepared=prepare()
    currentCoroutineContext().ensureActive()
    if(!stillCurrent())return false
    apply(prepared)
    return true
}
