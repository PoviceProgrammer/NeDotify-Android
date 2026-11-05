package ru.nedotify.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The fence between a listening checkpoint and the removal of the track.
 *
 * The defect it pins: the player wrote a checkpoint in three steps - find the
 * track, remember it, write the session. Nothing kept the three steps
 * together, so a deletion that landed between the first and the third step was
 * undone by the second one: the track the user had deleted came back.
 *
 * The contract:
 * - a delete that lands while a checkpoint is being written waits for the
 *   checkpoint and then removes the row the checkpoint has written;
 * - a checkpoint for a track that does not exist writes nothing at all;
 * - a checkpoint that precedes the delete is removed together with the track;
 * - a write of a track row that lands after the delete does not bring the row
 *   back, and the same holds for the writers of the DAO (the media-store scan,
 *   the favourite toggle, the playlist writes).
 *
 * Every test uses its own track id: the tombstone of a delete lives in the
 * process, and a test must not inherit the delete of another one.
 */
class LibraryWriteFenceTest {
    /** The library as the DAO sees it: the rows of the tracks and of the history. */
    private class FakeLibrary {
        val tracks=mutableMapOf<String,Track>()
        val sessions=mutableListOf<ListeningSession>()
        fun remember(track:Track) {tracks[track.id]=track}
        fun putSession(session:ListeningSession) {sessions.removeAll {it.id==session.id};sessions+=session}
        fun deleteTrack(id:String) {tracks.remove(id);sessions.removeAll {it.trackId==id}}
    }

    private fun track(id:String)=Track(id,"Title",source="local")

    @Test fun aDeleteDuringACheckpointWaitsForItAndTheRowDoesNotComeBack()=runTest {
        val library=FakeLibrary()
        val item=track("fence-checkpoint")
        val session=ListeningSession("fence-checkpoint-session",item.id,1,1000,false)
        library.remember(item)
        // The write is suspended in the middle: the delete lands exactly there.
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val writes=LibraryWrites({library.tracks[it]},
            {row,record->entered.complete(Unit);release.await();library.remember(row);library.putSession(record)},
            {id->library.deleteTrack(id)}, {row->library.remember(row)})
        val recording=async {writes.record(session)}
        entered.await()
        val deleting=async {writes.delete(item.id)}
        runCurrent()
        // The delete must not run inside the write. The fence is what keeps the
        // row from being written back after the user has deleted it.
        assertFalse("the delete must wait for the checkpoint that is in flight",deleting.isCompleted)
        release.complete(Unit)
        recording.await();deleting.await()
        assertTrue("the deleted track must not come back",library.tracks.isEmpty())
        assertTrue("the history of a deleted track must not survive",library.sessions.isEmpty())
    }

    /**
     * The interleaving of the test above, without the fence: the sequence the
     * player used before the fence - find the track, remember it, write the
     * session. The deletion that lands between the first and the third step is
     * undone by the second one, and the deleted track comes back.
     *
     * The test exists to prove that the interleaving of the fence test is a
     * real race and not a contrived one.
     */
    @Test fun theSameInterleavingBringsTheRowBackWithoutTheFence()=runTest {
        val library=FakeLibrary()
        val item=track("fence-unfenced")
        val session=ListeningSession("fence-unfenced-session",item.id,1,1000,false)
        library.remember(item)
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val writing=async {
            val found=library.tracks[session.trackId]   // step 1: find the track
            entered.complete(Unit)
            release.await()                             // the delete lands here
            library.remember(found!!)                   // step 2: remember it
            library.putSession(session)                 // step 3: write the session
        }
        entered.await()
        library.deleteTrack(item.id)                   // the deletion of the user
        release.complete(Unit)
        writing.await()
        assertTrue("the unfenced sequence resurrects the deleted track - the defect the fence removes",
            library.tracks.containsKey(item.id))
        assertEquals(1,library.sessions.size)
    }

    @Test fun aCheckpointForATrackThatDoesNotExistWritesNothing()=runTest {
        val library=FakeLibrary()
        var stored=0
        val writes=LibraryWrites({library.tracks[it]},
            {row,record->stored++;library.remember(row);library.putSession(record)},
            {id->library.deleteTrack(id)}, {row->library.remember(row)})
        writes.record(ListeningSession("fence-missing-session","fence-missing",1,1000,false))
        assertEquals("no row may be written for a track that does not exist",0,stored)
        assertTrue(library.tracks.isEmpty())
        assertTrue(library.sessions.isEmpty())
    }

    @Test fun aCheckpointThatPrecedesTheDeleteIsRemovedWithTheTrack()=runTest {
        val library=FakeLibrary()
        val item=track("fence-precedes")
        val session=ListeningSession("fence-precedes-session",item.id,1,1000,false)
        library.remember(item)
        val writes=LibraryWrites({library.tracks[it]},
            {row,record->library.remember(row);library.putSession(record)},
            {id->library.deleteTrack(id)}, {row->library.remember(row)})
        writes.record(session)
        assertEquals(listOf(session),library.sessions)
        writes.delete(item.id)
        assertTrue(library.tracks.isEmpty())
        assertTrue("the session of a deleted track must be removed with it",library.sessions.isEmpty())
    }

    @Test fun aCheckpointAfterTheDeleteDoesNotBringTheTrackBack()=runTest {
        val library=FakeLibrary()
        val item=track("fence-after")
        val session=ListeningSession("fence-after-session",item.id,1,1000,false)
        library.remember(item)
        val writes=LibraryWrites({library.tracks[it]},
            {row,record->library.remember(row);library.putSession(record)},
            {id->library.deleteTrack(id)}, {row->library.remember(row)})
        writes.delete(item.id)
        writes.record(session)
        assertTrue("a late checkpoint must not bring the deleted track back",library.tracks.isEmpty())
        assertTrue(library.sessions.isEmpty())
    }

    /**
     * A write of a track row shares the fence.
     *
     * The player and the downloader both write a track row before they use it. Without the
     * fence such a write could land between the removal of the track and the end of the
     * deletion, and the row the user deleted would come back.
     */
    @Test fun aTrackWriteCannotInterleaveWithTheRemoval()=runTest {
        val library=FakeLibrary()
        val item=track("fence-write")
        library.remember(item)
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val writes=LibraryWrites({library.tracks[it]},
            {row,record->library.remember(row);library.putSession(record)},
            {id->library.deleteTrack(id)},
            {row->entered.complete(Unit);release.await();library.remember(row)})
        val writing=async {writes.remember(item)}
        entered.await()
        val deleting=async {writes.delete(item.id)}
        runCurrent()
        assertFalse("the delete must wait for the write that is in flight",deleting.isCompleted)
        release.complete(Unit)
        writing.await();deleting.await()
        assertTrue("the deleted track must not come back",library.tracks.isEmpty())
    }

    /**
     * A write of a track row that lands after the delete does not re-create the row.
     *
     * The delete took the history, the playlist entries, the offline row and the identity rows
     * of the track away. A write that put the row back would leave a track with none of them.
     */
    @Test fun aWriteAfterTheDeleteDoesNotReCreateTheRow()=runTest {
        val library=FakeLibrary()
        val item=track("fence-tombstone")
        library.remember(item)
        val writes=LibraryWrites({library.tracks[it]},
            {row,record->library.remember(row);library.putSession(record)},
            {id->library.deleteTrack(id)}, {row->library.remember(row)})
        writes.delete(item.id)
        writes.remember(item)
        assertTrue("a write after the delete must not re-create the row",library.tracks.isEmpty())
    }

    /**
     * A scan of the media store does not re-create a deleted local track.
     *
     * The scan writes a batch of track rows; the rows the user deleted are not part of it.
     */
    @Test fun aMediaStoreScanDoesNotReCreateADeletedTrack()=runTest {
        val library=FakeLibrary()
        val deleted=Track("fence-scan-deleted","Удалённый",source="local")
        val kept=Track("fence-scan-kept","Оставшийся",source="local")
        library.remember(deleted);library.remember(kept)
        val writes=LibraryWrites({library.tracks[it]},
            {row,record->library.remember(row);library.putSession(record)},
            {id->library.deleteTrack(id)}, {row->library.remember(row)})
        writes.delete(deleted.id)
        var scanned=0
        writes.refresh(listOf(deleted,kept)) { rows -> scanned=rows.size; rows.forEach { library.remember(it) } }
        assertEquals("the scan writes the rows the user did not delete",1,scanned)
        assertTrue(library.tracks.containsKey(kept.id))
        assertFalse("the deleted track must not come back",library.tracks.containsKey(deleted.id))
    }

    /**
     * A removal that failed does not leave the track marked as deleted.
     *
     * The tombstone is marked before the removal, so that a writer that lands during the
     * removal does not re-create the row. A failed removal lifts it again: the track is still
     * in the library.
     */
    @Test fun aFailedRemovalLiftsTheTombstoneOfTheDelete()=runTest {
        val library=FakeLibrary()
        val item=track("fence-failed-delete")
        library.remember(item)
        val writes=LibraryWrites({library.tracks[it]},
            {row,record->library.remember(row);library.putSession(record)},
            {id->throw IllegalStateException("the removal failed")}, {row->library.remember(row)})
        var failed=false
        try { writes.delete(item.id) } catch (_:IllegalStateException) { failed=true }
        assertTrue("the removal must fail",failed)
        writes.remember(item)
        assertTrue("the track is still in the library, so the write must go through",
            library.tracks.containsKey(item.id))
    }

    /**
     * The restore lifts the tombstone of the rows it wrote.
     *
     * The restore brings the rows of the backup back into the library. A track the user
     * deleted before the restore is in the library again, and the delete must not survive it.
     */
    @Test fun theRestoreLiftsTheTombstoneOfTheRowsItWrote() {
        val models=source("ru/nedotify/app/data/Models.kt")
        assertTrue("the restore must lift the tombstone of the rows it wrote",
            body(models,"suspend fun restoreBackup(").contains("DeletedTracks.restore"))
    }

    /**
     * The fence covers the write only if the DAO writes the track row and the
     * session row together. Two separate transactions would leave a window in
     * which the session row exists without its track.
     */
    @Test fun theDaoWritesTheTrackAndTheSessionInOneTransaction() {        val models=source("ru/nedotify/app/data/Models.kt")
        val remember=Regex("@Transaction\\s+suspend fun rememberSession\\([^)]*\\)\\s*\\{[^}]*\\}").find(models)?.value
        assertNotNull("rememberSession must be one Room transaction",remember)
        assertTrue("the track row and the session row must be written together",
            remember!!.contains("remember(track)")&&remember.contains("putSession(session)"))
        val delete=Regex("@Transaction\\s+suspend fun deleteTrack\\([^)]*\\)\\s*\\{[^}]*\\}").find(models)?.value
        assertNotNull("deleteTrack must be one Room transaction",delete)
        assertTrue("the history of the track must be removed with the track",
            delete!!.contains("deleteTrackHistory(id)")&&delete.contains("deleteTrackRow(id)"))
    }

    /**
     * Every write of a track row of the DAO asks the tombstone.
     *
     * The DAO writes a track row in five places: the checkpoint, the scan of the media store,
     * the favourite toggle and the two playlist writes. A delete takes the history, the
     * playlist entries, the offline row and the identity rows of the track away; a write that
     * puts the row back would leave a track with none of them. Every one of those writes must
     * therefore ask the tombstone of the delete.
     */
    @Test fun everyTrackWriteOfTheDaoAsksTheTombstone() {
        val models=source("ru/nedotify/app/data/Models.kt")
        val putTrack=body(models,"internal suspend fun LibraryDao.putTrack(")
        assertTrue("the write of a track row must ask the tombstone",putTrack.contains("DeletedTracks.isDeleted"))
        assertTrue("the write of a track row must keep the state of the library",
            putTrack.contains("preservingLibraryState"))
        assertTrue("the checkpoint must ask the tombstone",
            body(models,"suspend fun rememberSession(").contains("DeletedTracks.isDeleted"))
        assertTrue("the scan of the media store must ask the tombstone",
            body(models,"suspend fun refresh(").contains("DeletedTracks.isDeleted"))
        assertTrue("the favourite toggle must ask the tombstone",
            body(models,"suspend fun favorite(").contains("DeletedTracks.isDeleted"))
        assertTrue("the playlist write must ask the tombstone",
            body(models,"suspend fun addToPlaylist(").contains("DeletedTracks.isDeleted"))
        assertTrue("the import of a playlist must ask the tombstone",
            body(models,"suspend fun importPlaylist(").contains("DeletedTracks.isDeleted"))
    }

    /**
     * The playlist writes of the DAO do not call the unqualified `remember(track)`.
     *
     * The unqualified call writes the track row behind the back of the fence: a delete that
     * races it resurrects the row. The playlist writes must go through the write that asks the
     * tombstone.
     */
    @Test fun thePlaylistWritesDoNotCallTheUnqualifiedRemember() {
        val models=source("ru/nedotify/app/data/Models.kt")
        for(signature in listOf("suspend fun addToPlaylist(","suspend fun importPlaylist(")) {
            val body=body(models,signature)
            assertFalse("the playlist write must not call the unqualified remember: $signature",
                Regex("(?<![A-Za-z0-9_.])remember\\(track\\)").containsMatchIn(body))
            assertTrue("the playlist write must go through the fenced write: $signature",
                body.contains("putTrack(track)"))
        }
    }

    /** The source of a file of the module, found from the working directory. */
    private fun source(path:String):String {
        var root=File("").absoluteFile
        while(root.parentFile!=null) {
            val file=File(root,"src/main/java/$path")
            if(file.isFile)return file.readText()
            root=root.parentFile
        }
        throw AssertionError("the source file $path was not found")
    }

    /** The body of a function of the module source, up to its closing brace. */
    private fun body(source:String,signature:String):String {
        val start=source.indexOf(signature)
        assertTrue("the function $signature must exist",start>=0)
        val lines=source.substring(start).lines()
        val collected=mutableListOf<String>()
        for((index,line) in lines.withIndex()) {
            if(index>0)collected+=line
            if(index>0 && line.trim()=="}")break
        }
        return collected.joinToString("\n")
    }
}
