package ru.nedotify.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class DownloadMigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),LibraryDatabase::class.java)
    @Test fun upgradingKeepsTracksFavoritesPlaylistsAndListeningHistory() {
        val name="offline-migration-test"
        helper.createDatabase(name,1).use {db->
            db.execSQL("INSERT INTO tracks VALUES ('local:test','Тест','Исполнитель','Альбом',120000,'local','','','content://music/1','',1,42,'audio/mpeg')")
            db.execSQL("INSERT INTO playlists VALUES ('playlist','Коллекция',42)")
            db.execSQL("INSERT INTO playlist_tracks VALUES ('playlist','local:test',0)")
            db.execSQL("INSERT INTO history VALUES ('session','local:test',42,5000,0)")
        }
        helper.runMigrationsAndValidate(name,2,true,LibraryDatabase.MIGRATION_1_2).use {db->
            db.query("SELECT title,favorite,localUri FROM tracks WHERE id='local:test'").use {cursor->
                assertTrue(cursor.moveToFirst());assertEquals("Тест",cursor.getString(0));assertEquals(1,cursor.getInt(1));assertEquals("content://music/1",cursor.getString(2))
            }
            for(table in listOf("playlists","playlist_tracks","history"))db.query("SELECT COUNT(*) FROM $table").use {it.moveToFirst();assertEquals(1,it.getInt(0))}
            db.query("SELECT COUNT(*) FROM offline_downloads").use {it.moveToFirst();assertEquals(0,it.getInt(0))}
        }
    }
}
