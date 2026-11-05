package ru.nedotify.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {
    private lateinit var database:LibraryDatabase
    @Before fun create() {database=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LibraryDatabase::class.java).build()}
    @After fun close(){database.close()}
    @Test fun favoritePlaylistOrderAndMeasuredHistory()=runBlocking {
        val dao=database.library()
        val a=Track("local:a","Длинное название трека",source="local",localUri="content://media/1")
        val b=Track("local:b","Второй",source="local",localUri="content://media/2")
        dao.putAll(listOf(a,b));dao.toggleFavorite(a.id)
        assertTrue(dao.track(a.id)!!.favorite)
        dao.putPlaylist(Playlist("p","Мой плейлист"));dao.addToPlaylist("p",b);dao.addToPlaylist("p",a);dao.addToPlaylist("p",b)
        assertEquals(listOf(b.id,a.id),dao.playlistTracks("p").map {it.id})
        assertTrue(dao.track(a.id)!!.favorite)
        dao.putSession(ListeningSession("session",a.id,1,1500,false));dao.putSession(ListeningSession("session",a.id,1,3000,false))
        assertEquals(3000L,dao.listenedMs().first())
        dao.deletePlaylist("p");assertEquals(2,dao.allTracks().size)
    }
    @Test fun staleFavoriteCannotOverwriteUpdatedMetadataAndReimportPreservesUserData()=runBlocking {
        val dao=database.library()
        val stale=Track("document:a","Старое имя",source="local",localUri="content://audio/1",addedAt=123)
        dao.put(stale)
        dao.refresh(listOf(stale.copy(title="Новое имя",addedAt=999)))
        dao.favorite(stale)
        assertEquals("Новое имя",dao.track(stale.id)!!.title)
        dao.refresh(listOf(stale.copy(title="После импорта",addedAt=999)))
        val refreshed=dao.track(stale.id)!!
        assertEquals("После импорта",refreshed.title);assertTrue(refreshed.favorite);assertEquals(123L,refreshed.addedAt)
        dao.ensureTracks(listOf(stale));assertEquals(refreshed,dao.track(stale.id))
    }
    @Test fun batchRefreshPreservesFavoritesBeyondSqliteParameterLimitAndPlaylistRemainsLive()=runBlocking {
        val dao=database.library()
        val tracks=(0..1100).map {Track("local:$it","Трек $it",source="local",favorite=it==1000)}
        dao.putAll(tracks);dao.refresh(tracks.map {it.copy(title="Обновлён",favorite=false)})
        assertEquals(1101,dao.allTracks().size);assertTrue(dao.track("local:1000")!!.favorite)
        dao.putPlaylist(Playlist("p","До"));dao.addToPlaylist("p",dao.track("local:1000")!!)
        assertEquals(1,dao.observePlaylist("p").first().size)
        dao.renamePlaylist("p","После");assertEquals("После",dao.playlists().first().single().playlist.name)
        dao.deletePlaylist("p");assertTrue(dao.observePlaylist("p").first().isEmpty());assertEquals(1101,dao.allTracks().size)
    }
    @Test fun pausedOrReplacedGenerationCannotBeCompletedByAnOldWorker()=runBlocking {
        val dao=database.library();val track=Track("soundcloud:one","Трек",source="soundcloud")
        dao.put(track);dao.putDownload(OfflineDownload(track.id,"old","downloading"))
        dao.downloadProgress(track.id,"old",100,1000,10f)
        dao.putDownload(dao.download(track.id)!!.copy(generation="paused-generation",state="paused"))
        assertEquals(0,dao.downloadState(track.id,"old","complete"))
        dao.downloadProgress(track.id,"old",1000,1000,100f)
        dao.downloadInterrupted(track.id,"old")
        assertEquals("paused",dao.download(track.id)!!.state);assertEquals(100L,dao.download(track.id)!!.bytes)
        assertTrue(dao.completedDownloads(listOf(track.id)).isEmpty())
        dao.putDownload(OfflineDownload(track.id,"new","downloading"))
        dao.downloadState(track.id,"new","complete");assertEquals(1,dao.completedDownloads(listOf(track.id)).size)
        dao.deleteDownload(track.id,"old");assertNotNull(dao.download(track.id))
        dao.deleteDownload(track.id,"new");assertNotNull(dao.track(track.id))
    }
}
