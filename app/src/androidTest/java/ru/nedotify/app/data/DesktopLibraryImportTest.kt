package ru.nedotify.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class DesktopLibraryImportTest {
    @Test fun selectedSnapshotIsReadOnlyAndDoesNotBecomeAnAndroidFileGrant() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"desktop-fixture-${UUID.randomUUID()}.db")
        try {
            SQLiteDatabase.openOrCreateDatabase(file,null).use {db->
                db.execSQL("CREATE TABLE tracks(id INTEGER PRIMARY KEY,title TEXT,artist TEXT,source TEXT,duration REAL,added_at TEXT,is_favorite INTEGER)")
                db.execSQL("INSERT INTO tracks VALUES (1,'Песня','Артист','local',123.5,'2026-10-01 12:00:00',1)")
                db.execSQL("CREATE TABLE playlists(id INTEGER PRIMARY KEY,name TEXT,created_at TEXT)")
                db.execSQL("INSERT INTO playlists VALUES (1,'Коллекция','2026-10-01 12:00:00')")
                db.execSQL("CREATE TABLE playlist_tracks(playlist_id INTEGER,track_id INTEGER,position INTEGER)")
                db.execSQL("INSERT INTO playlist_tracks VALUES (1,1,0)")
            }
            fun hash()=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
            val before=hash();val result=DesktopLibraryImport(context).readSnapshot(file,"Fixture PC")
            assertEquals(before,hash());assertEquals(1,result.backup.tracks.size)
            assertEquals("",result.backup.tracks.single().localUri);assertTrue(result.backup.tracks.single().favorite)
        } finally {file.delete()}
    }
}
