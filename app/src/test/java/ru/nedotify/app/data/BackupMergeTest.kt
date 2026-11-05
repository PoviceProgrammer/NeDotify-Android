package ru.nedotify.app.data

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BackupMergeTest {
    private val library="2f958e2c-f8c1-4c88-bb50-f0b945e24acb"
    private val other="bdb99367-6c7a-42d7-8880-87fd96f235ee"
    private val local=Track("local:12","Мой файл",source="local",localUri="content://media/12",addedAt=10)
    private val remote=Track("soundcloud:42","Трек",source="soundcloud",sourceId="42",addedAt=20)
    private val empty=BackupSnapshot(emptyList(),emptyList(),emptyList(),emptyList())
    private fun portable(track:Track=local,origin:String=library)=track.copy(
        id=BackupCodec.portableTrackId(track,origin),localUri="")
    private fun backup(tracks:List<Track>,playlists:List<BackupPlaylist> = emptyList(),
        history:List<ListeningSession> = emptyList())=LibraryBackup(library,123,tracks,playlists,history)

    @Test fun sameLibraryRestorePreservesLocalGrantsMetadataAndAddedAt() {
        val saved=portable().copy(title="Из копии",favorite=true,addedAt=999)
        val plan=BackupMerge.plan(empty.copy(tracks=listOf(local)),backup(listOf(saved)),library)
        assertEquals(local.copy(favorite=true),plan.tracks.single())
    }

    @Test fun anotherDevicesMediaStoreIdCannotOverwriteThisDevicesFile() {
        val foreign=portable(origin=other).copy(title="Другой файл")
        val plan=BackupMerge.plan(empty.copy(tracks=listOf(local)),backup(listOf(foreign)),library)
        assertNotEquals(local.id,plan.tracks.single().id)
        assertEquals("",plan.tracks.single().localUri)
        assertEquals(local,empty.copy(tracks=listOf(local)).tracks.single())
    }

    @Test fun repeatMergeIsIdempotentAndKeepsExistingPlaylistOrderAndName() {
        val nativeId=local.id
        val transferred=portable()
        val playlist=Playlist("p","Текущее имя",1)
        val session=ListeningSession("s",nativeId,10,2000,true)
        val existing=BackupSnapshot(listOf(local,remote.copy(favorite=true)),listOf(playlist),
            listOf(PlaylistTrack("p",remote.id,0)),listOf(session))
        val incoming=backup(listOf(transferred,remote),listOf(BackupPlaylist(playlist.copy(name="Старое"),
            listOf(transferred.id,remote.id))),listOf(session.copy(trackId=transferred.id,listenedMs=1000,completed=false)))
        val first=BackupMerge.plan(existing,incoming,library)
        assertEquals(listOf(PlaylistTrack("p",nativeId,1)),first.links)
        assertEquals(playlist,first.playlists.single())
        assertEquals(session,first.history.single())
        assertTrue(first.tracks.single {it.id==remote.id}.favorite)
        val after=existing.copy(tracks=first.tracks,links=existing.links+first.links,history=first.history)
        val again=BackupMerge.plan(after,incoming,library)
        assertTrue(again.links.isEmpty());assertEquals(first.history,again.history)
    }

    @Test fun restoredPlaceholderKeepsIdentityAfterAttachingAndTransferringAgain() {
        val saved=portable(origin=other)
        val attached=saved.copy(localUri="content://documents/new")
        val plan=BackupMerge.plan(empty.copy(tracks=listOf(attached)),backup(listOf(saved)),library)
        assertEquals(attached,plan.tracks.single())
        assertEquals(saved.id,BackupCodec.portableTrackId(attached,library))
    }

    @Test fun conflictingHistoryCannotBeSilentlyAddedToStatistics() {
        val old=ListeningSession("s",remote.id,1,1000,false)
        assertThrows(SourceFailure::class.java) {
            BackupMerge.plan(empty.copy(history=listOf(old)),
                backup(listOf(remote),history=listOf(old.copy(startedAt=2,listenedMs=9999))),library)
        }
    }

    @Test fun invalidReferencesAndSourceCollisionsFailBeforeAnyPlanIsReturned() {
        assertThrows(SourceFailure::class.java) {
            BackupMerge.plan(empty,backup(listOf(remote),listOf(BackupPlaylist(Playlist("p","p"),listOf("missing")))),library)
        }
        assertThrows(SourceFailure::class.java) {
            BackupMerge.plan(empty.copy(tracks=listOf(remote.copy(sourceId="43"))),backup(listOf(remote)),library)
        }
    }

    @Test fun importedMeasuredTimeCanAdvanceButNeverDecreaseOrDouble() {
        val old=ListeningSession("s",remote.id,1,1000,false)
        val incoming=old.copy(listenedMs=2500,completed=true)
        val plan=BackupMerge.plan(empty.copy(history=listOf(old)),backup(listOf(remote),history=listOf(incoming)),library)
        assertEquals(incoming,plan.history.single())
        val repeated=BackupMerge.plan(empty.copy(history=plan.history),backup(listOf(remote),history=listOf(incoming)),library)
        assertEquals(2500L,repeated.history.sumOf {it.listenedMs})
    }

    @Test fun smartRulesRestoreIsIdempotentAndDoesNotReplaceCurrentRules() {
        val current=SmartPlaylist("smart","Мой выбор",SmartRules(favorite=true).encode(),1)
        val incoming=current.copy(name="Старое имя",rulesJson=SmartRules(unplayed=true).encode())
        val fresh=SmartPlaylist("another","Новый",SmartRules(downloaded=true).encode(),2)
        val imported=backup(emptyList()).copy(smartPlaylists=listOf(incoming,fresh))
        val existing=empty.copy(smartPlaylists=listOf(current))
        val first=BackupMerge.plan(existing,imported,library)
        assertEquals(listOf(current,fresh),first.smartPlaylists)
        assertEquals(first.smartPlaylists,BackupMerge.plan(existing.copy(smartPlaylists=first.smartPlaylists),imported,library).smartPlaylists)
    }

    @Test fun anotherDevicesKnownRecordingReusesGrantAndRemapsEveryReference() {
        val native=local.copy(artist="Артист",album="Альбом",durationMs=180000)
        val incoming=portable(native,other).copy(favorite=true)
        val saved=backup(listOf(incoming),listOf(BackupPlaylist(Playlist("p","P"),listOf(incoming.id))),
            listOf(ListeningSession("s",incoming.id,1,500,false)))
        val plan=BackupMerge.plan(empty.copy(tracks=listOf(native)),saved,library)
        assertEquals(native.id,plan.tracks.single().id);assertEquals(native.localUri,plan.tracks.single().localUri)
        assertEquals(native.id,plan.links.single().trackId);assertEquals(native.id,plan.history.single().trackId)
        assertEquals(LocalTrackAlias(incoming.id,native.id),plan.localAliases.single())
        val renamed=native.copy(title="Изменено после импорта")
        val again=BackupMerge.plan(empty.copy(tracks=listOf(renamed),localAliases=plan.localAliases),saved,library)
        assertEquals(renamed.title,again.tracks.single().title);assertEquals(native.id,again.tracks.single().id)
    }

    @Test fun ambiguousOrUnknownMetadataNeverClaimsAnotherDevicesFile() {
        val track=local.copy(artist="Artist",durationMs=1000)
        val foreign=portable(track,other)
        val ambiguous=BackupMerge.plan(empty.copy(tracks=listOf(track,track.copy(id="local:other"))),backup(listOf(foreign)),library)
        assertEquals(foreign.id,ambiguous.tracks.single().id)
        val unknown=BackupMerge.plan(empty.copy(tracks=listOf(local)),backup(listOf(portable(local,other))),library)
        assertNotEquals(local.id,unknown.tracks.single().id)
    }

    /**
     * The library the DAO keeps after a restore.
     *
     * The DAO applies a plan with insert-only links (`@Insert(IGNORE)`) and upserted rows
     * (`@Upsert`); it never deletes a row. The helper below is that apply step, so a test can
     * state what the library looks like after the restore without a device.
     */
    private fun apply(existing: BackupSnapshot, plan: BackupSnapshot): BackupSnapshot {
        val tracks=existing.tracks.associateBy {it.id}.toMutableMap()
        plan.tracks.forEach {tracks[it.id]=it}
        val playlists=existing.playlists.associateBy {it.id}.toMutableMap()
        plan.playlists.forEach {playlists[it.id]=it}
        val links=existing.links.associateBy {it.playlistId to it.trackId}.toMutableMap()
        plan.links.forEach {links[it.playlistId to it.trackId]=it}
        val history=existing.history.associateBy {it.id}.toMutableMap()
        plan.history.forEach {history[it.id]=it}
        val smart=existing.smartPlaylists.associateBy {it.id}.toMutableMap()
        plan.smartPlaylists.forEach {smart[it.id]=it}
        return BackupSnapshot(tracks.values.toList(),playlists.values.toList(),links.values.toList(),
            history.values.toList(),smart.values.toList())
    }

    @Test fun theSameFileImportedTwiceCreatesNoDuplicate() {
        val native=local.copy(artist="Артист",album="Альбом",durationMs=180000)
        val saved=portable(native)
        val document=LibraryBackup(library,10,listOf(saved),
            listOf(BackupPlaylist(Playlist("p","Плейлист"),listOf(saved.id))),
            listOf(ListeningSession("s",saved.id,10,2000,true)))
        val first=BackupMerge.plan(empty,document,library)
        val afterFirst=apply(empty,first)
        assertEquals(1,afterFirst.tracks.size);assertEquals(1,afterFirst.links.size);assertEquals(1,afterFirst.history.size)
        val second=BackupMerge.plan(afterFirst,document,library)
        assertTrue("a repeated import must not add a playlist entry",second.links.isEmpty())
        val afterSecond=apply(afterFirst,second)
        assertEquals(1,afterSecond.tracks.size);assertEquals(1,afterSecond.links.size);assertEquals(1,afterSecond.history.size)
        assertEquals(afterFirst.tracks.single().id,afterSecond.tracks.single().id)
        assertEquals(afterFirst.history.single().id,afterSecond.history.single().id)
    }

    @Test fun theSameFileFromAnotherPcLabelMergesIntoTheSameRow() {
        val native=local.copy(artist="Артист",album="Альбом",durationMs=180000)
        val foreign=portable(native,other)
        val document=LibraryBackup(other,10,listOf(foreign),
            listOf(BackupPlaylist(Playlist("q","Плейлист ПК"),listOf(foreign.id))),
            listOf(ListeningSession("s2",foreign.id,20,3000,true)))
        val plan=BackupMerge.plan(empty.copy(tracks=listOf(native)),document,library)
        assertEquals("the recording of the other PC is the row of this device",native.id,plan.tracks.single().id)
        assertEquals("the file grant of this device survives",native.localUri,plan.tracks.single().localUri)
        assertEquals(native.id,plan.links.single().trackId)
        assertEquals(native.id,plan.history.single().trackId)
    }

    @Test fun aMergedRowKeepsTheUnionOfFavouritesPlaylistsAndHistory() {
        val native=local.copy(id="local:1",artist="Артист",album="Альбом",durationMs=180000,
            favorite=true,localUri="content://media/1")
        val foreign=portable(native,other)
        val document=LibraryBackup(other,10,listOf(foreign.copy(favorite=true)),
            listOf(BackupPlaylist(Playlist("q","С ПК"),listOf(foreign.id))),
            listOf(ListeningSession("s2",foreign.id,20,3000,true)))
        val existing=BackupSnapshot(listOf(native),listOf(Playlist("p","Мой плейлист",1)),
            listOf(PlaylistTrack("p",native.id,0)),listOf(ListeningSession("s1",native.id,10,1000,false)))
        val plan=BackupMerge.plan(existing,document,library)
        val after=apply(existing,plan)
        assertEquals("the two rows of the same recording become one row",1,after.tracks.size)
        assertEquals("the favourite is the union",true,after.tracks.single().favorite)
        assertEquals("no playlist may be lost",2,after.playlists.size)
        assertEquals("the playlist memberships are the union",2,after.links.size)
        assertEquals(setOf("p" to native.id,"q" to native.id),after.links.map {it.playlistId to it.trackId}.toSet())
        assertEquals("the history rows are the union",2,after.history.size)
        assertEquals(setOf("s1","s2"),after.history.map {it.id}.toSet())
        assertTrue(after.history.all {it.trackId==native.id})
    }

    @Test fun ambiguousRecordsAreNeverMergedAndNoRowIsLost() {
        val first=local.copy(id="local:1",artist="Артист",album="Альбом",durationMs=180000)
        val second=local.copy(id="local:2",artist="Артист",album="Альбом",durationMs=180000)
        val foreign=portable(first,other)
        val document=LibraryBackup(other,10,listOf(foreign),emptyList(),emptyList())
        val existing=BackupSnapshot(listOf(first,second),emptyList(),emptyList(),emptyList())
        val plan=BackupMerge.plan(existing,document,library)
        assertEquals("an ambiguous record is not merged into an existing row",foreign.id,plan.tracks.single().id)
        val after=apply(existing,plan)
        assertEquals("both rows of the library survive",3,after.tracks.size)
    }

    /**
     * The restore never deletes a row.
     *
     * The merge is a pure function, and the property is observable in its result: every track
     * of the library is in the plan, and the plan holds no other track than the ones of the
     * library and of the backup. The rows that the plan does not mention are not deleted
     * either - the DAO writes the links with an insert that ignores a conflict and the rows
     * with an upsert - and that last part is a source check, see
     * [theDaoWritesTheRestoreWithoutDeleting].
     */
    @Test fun theRestoreNeverDeletesARow() {
        val native=local.copy(artist="Артист",album="Альбом",durationMs=180000,favorite=true)
        val existing=BackupSnapshot(listOf(native,remote),listOf(Playlist("p","П",1)),
            listOf(PlaylistTrack("p",native.id,0)),listOf(ListeningSession("s",native.id,10,1000,true)),
            listOf(SmartPlaylist("smart","Умный",SmartRules(favorite=true).encode(),1)))
        val incoming=backup(listOf(portable(native).copy(title="Из копии")),
            listOf(BackupPlaylist(Playlist("q","Q"),listOf(portable(native).id))),
            listOf(ListeningSession("s2",portable(native).id,20,2000,true)))
        val after=apply(existing,BackupMerge.plan(existing,incoming,library))
        assertTrue("every track of the library survives the restore",
            existing.tracks.all { row -> after.tracks.any { it.id==row.id } })
        assertTrue("every playlist of the library survives the restore",
            existing.playlists.all { row -> after.playlists.any { it.id==row.id } })
        assertTrue("every playlist entry of the library survives the restore",
            existing.links.all { row -> after.links.any { it.playlistId==row.playlistId && it.trackId==row.trackId } })
        assertTrue("every listening session of the library survives the restore",
            existing.history.all { row -> after.history.any { it.id==row.id } })
        assertTrue("every smart playlist of the library survives the restore",
            existing.smartPlaylists.all { row -> after.smartPlaylists.any { it.id==row.id } })
        assertEquals("the restore adds the playlist of the backup",2,after.playlists.size)
    }

    /**
     * The DAO writes the restore without deleting a row.
     *
     * This is a source check: the write strategy of the DAO cannot be observed from a JVM
     * test. The rows of the library are upserted and the playlist entries are inserted with a
     * conflict that is ignored, so a row that the plan does not mention stays in the database.
     */
    @Test fun theDaoWritesTheRestoreWithoutDeleting() {
        val models=source("ru/nedotify/app/data/Models.kt")
        val restore=body(models,"@Transaction suspend fun restoreBackup(")
        assertFalse("the restore must not delete a row",restore.contains("DELETE"))
        assertTrue("the playlist entries are inserted, never replaced",
            models.contains("@Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun addLinks"))
        assertTrue("the rows are upserted, never replaced",models.contains("@Upsert suspend fun putAll"))
        assertTrue("the history is upserted, never replaced",models.contains("@Upsert suspend fun putSessions"))
    }

    /**
     * The batches of the restore stop at the cancellation.
     *
     * The write of a batch is checked before the next one starts, so a cancelled import does
     * not go on with the rest of the library. The transaction of the restore is one, so the
     * batches themselves are not committed one by one: this is the boundary at which the
     * cancellation is noticed.
     */
    @Test fun theBatchesOfTheRestoreStopAtTheCancellation()=runTest {
        val written=mutableListOf<Int>()
        val job=launch {
            writeBatches((1..10).map { listOf(it) }) { batch ->
                written+=batch[0]
                if(batch[0]==3)currentCoroutineContext().cancel()
            }
        }
        job.join()
        assertEquals("the batches after the cancellation must not be written",listOf(1,2,3),written)
    }

    /**
     * The restore is one Room transaction.
     *
     * This is the one property of the restore that cannot be observed from a JVM test: the
     * rollback of a transaction is Room's own behaviour and needs a device. The check below is
     * a source check, and it is one of the two static checks left in this file.
     */
    @Test fun theRestoreIsOneRoomTransaction() {
        val models=source("ru/nedotify/app/data/Models.kt")
        assertTrue("the restore must be one transaction",models.contains("@Transaction suspend fun restoreBackup("))
        val restore=body(models,"@Transaction suspend fun restoreBackup(")
        assertTrue("the rows must be written in batches",restore.contains("chunked(250)"))
        assertTrue("the history must be written in batches",restore.contains("chunked(500)"))
    }

    /**
     * An older copy of the device's own remote track does not roll its metadata back.
     *
     * The identity of a remote track is its own id: the device and the copy name the same
     * recording. The row of the library wins, so the current title, artist, album and
     * duration survive the restore of an older copy.
     */
    @Test fun restoringAnOlderCopyOfTheDevicesOwnRemoteTrackKeepsTheCurrentMetadata() {
        val current=remote.copy(title="Новое название",artist="Новый исполнитель",album="Новый альбом",
            durationMs=210000,addedAt=20)
        val older=current.copy(title="Старое название",artist="Старый исполнитель",album="Старый альбом",
            durationMs=180000,addedAt=10,favorite=true)
        val plan=BackupMerge.plan(empty.copy(tracks=listOf(current)),backup(listOf(older)),library)
        val merged=plan.tracks.single()
        assertEquals(current.id,merged.id)
        assertEquals("the current title must survive",current.title,merged.title)
        assertEquals("the current artist must survive",current.artist,merged.artist)
        assertEquals("the current album must survive",current.album,merged.album)
        assertEquals("the current duration must survive",current.durationMs,merged.durationMs)
        assertEquals("the moment the track entered the library must survive",current.addedAt,merged.addedAt)
        assertTrue("the import may add a favourite",merged.favorite)
    }

    /**
     * The counters of the imported rows are not summed twice.
     *
     * The same copy may be imported more than once; a sum would count every session of the
     * desktop again. An import therefore never lowers the counters of the device.
     */
    @Test fun theImportedCountersAreNeitherLoweredNorSummed() {
        val native=local.copy(importedPlayCount=4,importedLastPlayed=100)
        val incoming=portable(native).copy(importedPlayCount=3,importedLastPlayed=50)
        val plan=BackupMerge.plan(empty.copy(tracks=listOf(native)),backup(listOf(incoming)),library)
        assertEquals("the counter of the device must not be lowered",4,plan.tracks.single().importedPlayCount)
        assertEquals(100L,plan.tracks.single().importedLastPlayed)
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

    /** The source of a file of the module, found from the working directory. */
    private fun source(path:String):String {
        var root=java.io.File("").absoluteFile
        while(root.parentFile!=null) {
            val file=java.io.File(root,"src/main/java/$path")
            if(file.isFile)return file.readText()
            root=root.parentFile
        }
        throw AssertionError("the source file $path was not found")
    }
}
