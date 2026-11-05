package ru.nedotify.app.player

import ru.nedotify.app.data.QueueSnapshot
import ru.nedotify.app.data.QueueSnapshotCodec
import kotlin.math.abs

/**
 * The queue checkpoint.
 *
 * The queue *identity* (what is queued and how it is traversed) is saved at
 * once; the *position* is saved on a much slower cadence. The old guard
 * compared the whole payload, so the position made it change on every tick and
 * the settings file was rewritten ~12 times a minute (REG P2-01).
 */
internal class QueueCheckpoint(private val stepMs:Long) {
    private var identity=""
    private var position=0L

    /**
     * Whether the snapshot must be written now.
     *
     * The caller's [QueueSnapshot] is not modified; the checkpoint keeps the
     * identity and the position it last wrote.
     */
    fun shouldWrite(snapshot:QueueSnapshot,force:Boolean=false):Boolean {
        val next=QueueSnapshotCodec.identity(snapshot)
        if(next!=identity) {identity=next;position=snapshot.positionMs;return true}
        if(force||abs(snapshot.positionMs-position)>=stepMs) {position=snapshot.positionMs;return true}
        return false
    }
}
