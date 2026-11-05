package ru.nedotify.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The migration of the third version to the fourth.
 *
 * The third version is the last one before the counters of the imported rows, the table of the
 * provider mappings and the table of the local aliases were added, and before `tracks.addedAt`
 * got its index.
 *
 * This case is the guard of **Room's own** runtime validation. The JVM guard
 * (`LibraryMigrationJvmTest`) runs the same SQL against a real SQLite and compares the result
 * with the exported schema, but the check of the schema that Room itself performs
 * (`TableInfo`), the rollback of a failed migration and the foreign keys of the new tables are
 * Room's behaviour and need a device.
 *
 * The case compiles; it has never been executed here, because an APK build is forbidden in
 * this environment. That limitation is recorded in the report of the area.
 */
class LibraryMigrationV4Test {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),LibraryDatabase::class.java)

    @Test fun upgradingFromTheThirdVersionKeepsTheLibraryAndAddsTheImportedCounters() {
        val name="library-migration-3-4-test"
        helper.createDatabase(name,3).use { db ->
            db.execSQL("INSERT INTO tracks VALUES ('local:1','Песня','Исполнитель','Альбом',120000,'local','','','content://media/1','',1,42,'audio/mpeg')")
            db.execSQL("INSERT INTO playlists VALUES ('p','Мой плейлист',42)")
            db.execSQL("INSERT INTO playlist_tracks VALUES ('p','local:1',0)")
            db.execSQL("INSERT INTO history VALUES ('s','local:1',42,5000,0)")
            db.execSQL("INSERT INTO offline_downloads VALUES ('local:1','generation','complete',100,100,100.0,'','https://source','audio/mpeg',42)")
            db.execSQL("INSERT INTO smart_playlists VALUES ('smart','Умный','{\"version\":1}',42)")
        }
        helper.runMigrationsAndValidate(name,4,true,LibraryDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT title,favorite,localUri,importedPlayCount,importedLastPlayed,importedDownloaded FROM tracks").use {
                assertTrue(it.moveToFirst())
                assertEquals("Песня",it.getString(0))
                assertEquals(1,it.getInt(1))
                assertEquals("content://media/1",it.getString(2))
                assertEquals("the counters of an imported row start at zero",0,it.getInt(3))
                assertEquals(0L,it.getLong(4))
                assertEquals(0,it.getInt(5))
            }
            db.query("SELECT listenedMs FROM history").use { assertTrue(it.moveToFirst()); assertEquals(5000L,it.getLong(0)) }
            db.query("SELECT COUNT(*) FROM playlist_tracks").use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM offline_downloads").use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM smart_playlists").use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM provider_mappings").use { assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM local_track_aliases").use { assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)) }
        }
    }

    /**
     * The cascade of the new tables.
     *
     * A row of the provider mappings and a row of the local aliases belong to a track: the
     * foreign keys take them away with the track.
     */
    @Test fun theNewTablesFollowTheTrackTheyBelongTo() {
        val name="library-migration-3-4-cascade-test"
        helper.createDatabase(name,3).use { db ->
            db.execSQL("INSERT INTO tracks VALUES ('local:1','Песня','Исполнитель','Альбом',120000,'local','','','content://media/1','',1,42,'audio/mpeg')")
        }
        helper.runMigrationsAndValidate(name,4,true,LibraryDatabase.MIGRATION_3_4).use { db ->
            db.execSQL("INSERT INTO provider_mappings VALUES ('local:1','youtube','abc','Песня','Исполнитель','Альбом',120000)")
            db.execSQL("INSERT INTO local_track_aliases VALUES ('portable-local:abc','local:1')")
            db.execSQL("DELETE FROM tracks WHERE id='local:1'")
            db.query("SELECT COUNT(*) FROM provider_mappings").use { assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM local_track_aliases").use { assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)) }
        }
    }
}
