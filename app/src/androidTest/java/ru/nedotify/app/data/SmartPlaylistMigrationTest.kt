package ru.nedotify.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SmartPlaylistMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    @Test fun versionTwoKeepsDownloadAndLibraryWhileAddingRulesTable() {
        val name = "smart-playlist-migration-test"
        helper.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO tracks VALUES ('local:1','Трек','Артист','',100000,'local','','','content://media/1','',1,42,'audio/mpeg')")
            db.execSQL("INSERT INTO history VALUES ('s','local:1',42,1000,0)")
            db.execSQL("INSERT INTO offline_downloads VALUES ('local:1','generation','complete',100,100,100.0,'','https://source','audio/mpeg',42)")
        }
        helper.runMigrationsAndValidate(name, 3, true, LibraryDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT favorite,localUri FROM tracks").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals("content://media/1", it.getString(1)) }
            db.query("SELECT listenedMs FROM history").use { assertTrue(it.moveToFirst()); assertEquals(1000L, it.getLong(0)) }
            db.query("SELECT state FROM offline_downloads").use { assertTrue(it.moveToFirst()); assertEquals("complete", it.getString(0)) }
            db.query("SELECT COUNT(*) FROM smart_playlists").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun versionOneCanMigrateThroughBothSteps() {
        val name = "smart-playlist-migration-from-one-test"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO tracks VALUES ('local:old','Песня','Автор','Альбом',123000,'local','','','content://documents/old','',1,10,'audio/ogg')")
            db.execSQL("INSERT INTO playlists VALUES ('p','Мой плейлист',10)")
            db.execSQL("INSERT INTO playlist_tracks VALUES ('p','local:old',0)")
            db.execSQL("INSERT INTO history VALUES ('old-session','local:old',10,1234,1)")
        }
        helper.runMigrationsAndValidate(name, 3, true, LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT title,favorite,localUri FROM tracks WHERE id='local:old'").use {
                assertTrue(it.moveToFirst()); assertEquals("Песня",it.getString(0)); assertEquals(1,it.getInt(1)); assertEquals("content://documents/old",it.getString(2))
            }
            db.query("SELECT name FROM playlists WHERE id='p'").use {assertTrue(it.moveToFirst());assertEquals("Мой плейлист",it.getString(0))}
            db.query("SELECT trackId,position FROM playlist_tracks WHERE playlistId='p'").use {assertTrue(it.moveToFirst());assertEquals("local:old",it.getString(0));assertEquals(0,it.getInt(1))}
            db.query("SELECT listenedMs,completed FROM history WHERE id='old-session'").use {assertTrue(it.moveToFirst());assertEquals(1234L,it.getLong(0));assertEquals(1,it.getInt(1))}
            db.query("SELECT COUNT(*) FROM offline_downloads").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            db.query("SELECT COUNT(*) FROM smart_playlists").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun measuredHistoryAndCompleteDownloadDriveLiveRulesAndBackup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = database.library()
            val a = Track("soundcloud:1", "Первый", source = "soundcloud", sourceId = "1", addedAt = 1)
            val b = Track("soundcloud:2", "Второй", source = "soundcloud", sourceId = "2", addedAt = 2)
            dao.putAll(listOf(a, b))
            dao.putSession(ListeningSession("s", a.id, 100, 4500, false))
            dao.putSession(ListeningSession("future", b.id, 9000, 9999, true))
            dao.putDownload(OfflineDownload(a.id, "generation", "complete"))
            val candidates = dao.observeDiscoveryCandidates(1000).first()
            val actual = candidates.single { it.track.id == a.id }
            assertEquals(4500L, actual.listenedMs); assertEquals(1, actual.playCount); assertEquals(100L, actual.lastPlayed)
            assertTrue(actual.downloaded)
            assertEquals(0L, candidates.single { it.track.id == b.id }.listenedMs)
            val smart = SmartPlaylist("smart", "Скачано и слушал", SmartRules(downloaded = true, unplayed = false).encode(), 1)
            dao.putSmartPlaylist(smart)
            assertEquals(listOf(a.id), SmartPlaylistEngine.select(candidates, SmartRules.decode(smart.rulesJson), 1000).map { it.id })
            assertEquals(smart, dao.backupSnapshot().smartPlaylists.single())
            assertEquals(1, dao.editSmartPlaylist(smart.id, "Новое имя", SmartRules(unplayed = true).encode()))
            assertEquals("Новое имя", dao.observeSmartPlaylist(smart.id).first()!!.name)
            dao.deleteSmartPlaylist(smart.id)
            assertTrue(dao.smartPlaylists().first().isEmpty())
            assertEquals(2, dao.allTracks().size)
        } finally { database.close() }
    }
}
