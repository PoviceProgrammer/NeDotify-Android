package ru.nedotify.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The tracks the user deleted.
 *
 * A delete takes the row of the track away together with its history, its playlist entries, its
 * offline row and its identity rows: the foreign keys cascade. A writer that lands afterwards
 * would put the row back **without** those rows, and the library would show a track that is in
 * no playlist, has no history and no download any more.
 *
 * The registry is process-wide because the writers live in two places: the DAO (the media-store
 * scan, the favourite toggle, the playlist writes) and the repository (the player, the
 * downloader). It is not a second lock: [LibraryWrites] still serialises the delete against the
 * writes, and the registry only carries the fact of the delete. The registry is read without a
 * lock on purpose - a DAO method must never take the fence's mutex, because it already holds the
 * database write lock when it runs, and the two locks are always taken in the order
 * "fence, then database".
 *
 * The registry lives in the process and not in the database: a tombstone that survives a restart
 * needs a new Room version, which the coordinator owns. Until then a media-store scan after a
 * restart can bring a deleted local file back - the delete is remembered for the session.
 */
internal object DeletedTracks {
    private val deleted=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The user deleted the track: no write may bring the row back. */
    fun mark(id:String){deleted+=id}

    /** Is the track one the user deleted? */
    fun isDeleted(id:String)=id in deleted

    /** The track is the user's again (a file imported anew): the tombstone is lifted. */
    fun restore(id:String){deleted-=id}
}

/**
 * Listening checkpoints, track writes and removal share one fence; a pre-removal snapshot
 * cannot resurrect a row.
 *
 * The contract of the four lambdas:
 * - [lookup] resolves the track **under the lock**. A track that is gone is
 *   not recorded at all: no history row is created for a row that does not exist.
 * - [store] writes the track row and the session row **together** (one Room
 *   transaction), so the history row can never outlive the track it belongs to.
 * - [keep] writes a track row on its own. It shares the fence, so it can never
 *   interleave with a removal: either it lands before the removal (and the
 *   removal takes the row with it) or after it (and the write is a complete
 *   row, not a half-written resurrection).
 * - [remove] takes the track row and its history rows away together.
 *
 * The fence makes the record and the removal mutually exclusive: a delete
 * that lands during a record either precedes it (the record is skipped) or
 * follows it (the record is removed with the track). A write that started
 * before the deletion can never bring the row back.
 *
 * The fence also carries the fact of the delete: [DeletedTracks]. A write of a
 * track row that lands after the delete does not re-create the row, whether it
 * comes from here or from the DAO.
 */
internal class LibraryWrites(private val lookup:suspend (String)->Track?,
    private val store:suspend (Track,ListeningSession)->Unit,private val remove:suspend (String)->Unit,
    private val keep:suspend (Track)->Unit) {
    private val lock=Mutex()
    suspend fun record(session:ListeningSession)=lock.withLock {
        val track=lookup(session.trackId) ?: return@withLock
        store(track,session)
    }
    suspend fun delete(id:String)=lock.withLock {
        // The tombstone is marked before the removal: a writer that lands after the mark does
        // not re-create the row. A failed removal lifts it again - the track is still there.
        DeletedTracks.mark(id)
        try { remove(id) } catch (error:Throwable) { DeletedTracks.restore(id); throw error }
    }
    /** A write of a track row shares the fence with the checkpoint and the removal. */
    suspend fun remember(track:Track)=lock.withLock {
        if(DeletedTracks.isDeleted(track.id))return@withLock
        keep(track)
    }
    /**
     * A batch of track rows (a media-store scan, a file import) shares the fence and the
     * tombstones: the rows the user deleted are not re-created.
     */
    suspend fun refresh(tracks:List<Track>,write:suspend (List<Track>)->Unit)=lock.withLock {
        val alive=tracks.filter { !DeletedTracks.isDeleted(it.id) }
        if(alive.isNotEmpty())write(alive)
    }
    /** Any other write of the library shares the fence with the checkpoint and the removal. */
    suspend fun <T> guarded(block:suspend ()->T):T=lock.withLock {block()}
}
