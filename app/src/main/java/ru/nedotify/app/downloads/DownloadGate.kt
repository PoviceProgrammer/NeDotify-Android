package ru.nedotify.app.downloads

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The download slot and the foreground step.
 *
 * The permit comes **first**. WorkManager runs up to twenty workers at once,
 * and a worker that entered the foreground before it waits for one of the
 * two slots would hold a foreground-service quota while it waits — the quota
 * is a resource of the whole app, and the system takes it away when it runs
 * out. A worker that waits for its slot outside the foreground consumes
 * nothing.
 */
suspend fun <T> downloadGate(slots: Semaphore, foreground: suspend () -> Unit, block: suspend () -> T): T =
    slots.withPermit { foreground(); block() }
