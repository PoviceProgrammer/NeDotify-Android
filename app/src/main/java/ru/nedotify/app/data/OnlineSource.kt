package ru.nedotify.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Recheck after async setup and before publishing a response that may outlive offline mode. */
internal suspend fun <T> onlineSource(isOffline:suspend ()->Boolean,load:suspend ()->T):T {
    currentCoroutineContext().ensureActive()
    if(isOffline())throw SourceFailure("Включён офлайн-режим")
    val result=load()
    currentCoroutineContext().ensureActive()
    if(isOffline())throw SourceFailure("Включён офлайн-режим")
    return result
}
