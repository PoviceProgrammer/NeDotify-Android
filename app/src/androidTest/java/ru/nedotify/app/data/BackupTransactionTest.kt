package ru.nedotify.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupTransactionTest {
    private lateinit var database:LibraryDatabase
    private val library="2f958e2c-f8c1-4c88-bb50-f0b945e24acb"
    private val a=Track("soundcloud:1","Первый",source="soundcloud",sourceId="1")
    private val b=Track("soundcloud:2","Второй",source="soundcloud",sourceId="2")
    @Before fun create() {database=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LibraryDatabase::class.java).build()}
    @After fun close() {database.close()}

    @Test fun repeatedRestoreKeepsDownloadsAndOtherData()=runBlocking {
        val dao=database.library()
        dao.put(a.copy(favorite=true));dao.putDownload(OfflineDownload(a.id,"generation","complete",uri="https://example.org/audio"))
        dao.putPlaylist(Playlist("p","Мой"));dao.addToPlaylist("p",a)
        val incoming=LibraryBackup(library,1,listOf(a,b),listOf(BackupPlaylist(Playlist("p","Старое"),listOf(b.id,a.id))),
            listOf(ListeningSession("s",a.id,1,2300,false)))
        dao.restoreBackup(incoming,library);dao.restoreBackup(incoming,library)
        assertEquals(listOf(a.id,b.id),dao.playlistTracks("p").map {it.id})
        assertEquals("Мой",dao.allPlaylists().single().name)
        assertTrue(dao.track(a.id)!!.favorite)
        assertEquals("complete",dao.download(a.id)!!.state)
        assertEquals(2300L,dao.allSessions().single().listenedMs)
    }

    @Test fun failedRestoreLeavesEveryTableUnchanged()=runBlocking {
        val dao=database.library();dao.put(a)
        val session=ListeningSession("same",a.id,1,1000,false);dao.putSession(session)
        val before=dao.backupSnapshot()
        val bad=LibraryBackup(library,1,listOf(a,b),listOf(BackupPlaylist(Playlist("new","new"),listOf(b.id))),
            listOf(session.copy(startedAt=999)))
        try {dao.restoreBackup(bad,library);fail("Expected a conflicting session")}
        catch(_:SourceFailure) { /* Whole transaction failed. */ }
        assertEquals(before,dao.backupSnapshot())
    }

    @Test fun localReattachmentOnlyUpdatesExistingLocalRows()=runBlocking {
        val dao=database.library()
        val local=Track("portable-local:"+"a".repeat(64),"Локальный",source="local",favorite=true)
        dao.putAll(listOf(local,a))
        assertEquals(1,dao.attachLocal(local.id,"content://documents/audio","audio/mpeg"))
        assertEquals(local.copy(localUri="content://documents/audio",mimeType="audio/mpeg"),dao.track(local.id))
        assertEquals(0,dao.attachLocal(a.id,"content://documents/audio","audio/mpeg"))
        assertEquals(0,dao.attachLocal("missing","content://documents/audio","audio/mpeg"))
        assertEquals(a,dao.track(a.id))
    }

    @Test fun resolvedPlaylistImportCommitsDistinctTracksInOrderAndPreservesFavorites()=runBlocking {
        val dao=database.library()
        dao.put(a.copy(favorite=true,addedAt=42))
        dao.importPlaylist(Playlist("imported","Импорт",10),listOf(b,a.copy(addedAt=99),b))
        assertEquals(listOf(b.id,a.id),dao.playlistTracks("imported").map {it.id})
        assertEquals(listOf(0,1),dao.allPlaylistLinks().map {it.position})
        assertTrue(dao.track(a.id)!!.favorite);assertEquals(42L,dao.track(a.id)!!.addedAt)
        assertEquals(2,dao.allTracks().size)
    }
    @Test fun reattachedLocalFileSurvivesRememberPlaylistAndRescanSnapshots()=runBlocking {
        val dao=database.library()
        val stale=Track("portable-local:"+"b".repeat(64),"Локальный",source="local",localUri="content://old",mimeType="audio/aac",favorite=true)
        dao.put(stale);dao.attachLocal(stale.id,"content://new","audio/mpeg")
        dao.remember(stale.copy(localUri=""));dao.putPlaylist(Playlist("files","Файлы"));dao.addToPlaylist("files",stale)
        dao.refresh(listOf(stale.copy(title="Новые теги")))
        val result=dao.track(stale.id)!!
        assertEquals("content://new",result.localUri);assertEquals("audio/mpeg",result.mimeType)
        assertEquals("Новые теги",result.title);assertTrue(result.favorite)
    }
    @Test fun conflictingSessionIdentityCannotChangeMeasuredStatistics()=runBlocking {
        val dao=database.library();val original=ListeningSession("same",a.id,10,1000,false)
        dao.putSession(original)
        for(conflict in listOf(original.copy(trackId=b.id,listenedMs=9999),original.copy(startedAt=11,listenedMs=9999))) {
            try {dao.putSession(conflict);fail("Identity conflict must reject")}catch(_:SourceFailure){}
            assertEquals(original,dao.session(original.id))
        }
        dao.putSession(original.copy(listenedMs=2000,completed=true))
        dao.putSession(original.copy(listenedMs=500))
        assertEquals(original.copy(listenedMs=2000,completed=true),dao.session(original.id))
    }
    @Test fun localQueueAndPlaylistSnapshotsCannotRollBackScannedMetadata()=runBlocking {
        val dao=database.library()
        val stale=Track("local:1","Старое",artist="Старый артист",album="Старый альбом",durationMs=1000,source="local",coverUrl="content://old-cover")
        val scanned=stale.copy(title="Новые теги",artist="Новый артист",album="Новый альбом",durationMs=2000,coverUrl="content://new-cover",localUri="content://attached",mimeType="audio/flac",favorite=true)
        dao.put(scanned);dao.remember(stale)
        dao.putPlaylist(Playlist("snapshot","Очередь"));dao.addToPlaylist("snapshot",stale)
        assertEquals(scanned,dao.track(stale.id))
        dao.refresh(listOf(stale.copy(title="Повторное сканирование",artist="Обновлённый артист")))
        val after=dao.track(stale.id)!!
        assertEquals("Повторное сканирование",after.title);assertEquals("Обновлённый артист",after.artist)
        assertEquals(scanned.localUri,after.localUri);assertEquals(scanned.mimeType,after.mimeType);assertTrue(after.favorite)
    }
    @Test fun removingPlaylistMembershipPreservesTrackHistoryAndOtherCollections()=runBlocking {
        val dao=database.library()
        dao.putAll(listOf(a,b));dao.putPlaylist(Playlist("first","Первый"));dao.putPlaylist(Playlist("second","Второй"))
        dao.addToPlaylist("first",a);dao.addToPlaylist("first",b);dao.addToPlaylist("second",a)
        val session=ListeningSession("keep-history",a.id,1,1000,true);dao.putSession(session)
        assertEquals(1,dao.removeFromPlaylist("first",a.id))
        assertEquals(0,dao.removeFromPlaylist("first",a.id))
        assertEquals(listOf(b.id),dao.playlistTracks("first").map {it.id})
        assertEquals(listOf(a.id),dao.playlistTracks("second").map {it.id})
        assertEquals(a,dao.track(a.id));assertEquals(session,dao.session(session.id))
        assertEquals(2,dao.nextPosition("first"))
        dao.addToPlaylist("first",a)
        assertEquals(listOf(b.id,a.id),dao.playlistTracks("first").map {it.id})
        assertEquals(listOf(1,2),dao.allPlaylistLinks().filter {it.playlistId=="first"}.map {it.position})
    }
}
