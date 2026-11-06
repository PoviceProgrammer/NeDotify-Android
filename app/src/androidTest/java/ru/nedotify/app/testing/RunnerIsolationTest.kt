package ru.nedotify.app.testing

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import ru.nedotify.app.NeDotifyApp

/** Must pass before any real Android acceptance run can be trusted as isolated. */
class RunnerIsolationTest {
    @Test fun theRunnerBootsAStandardApplicationAndKeepsAllStorageUnderItsScratchRoot() {
        val runner=InstrumentationRegistry.getInstrumentation() as IsolatedAndroidTestRunner
        val context=runner.targetContext
        assertFalse(context.applicationContext is NeDotifyApp)
        val root=runner.fixtureRoot.canonicalPath+File.separator
        for(file in listOf(context.filesDir,context.noBackupFilesDir,context.cacheDir,context.codeCacheDir,
            context.getDatabasePath("nedotify-android.db"),context.getFileStreamPath("fixture.txt"))) {
            assertTrue(file.path,file.canonicalPath.startsWith(root))
            assertFalse(file.canonicalPath.startsWith(runner.productionDataDir.path+File.separator))
        }
    }

    @Test fun preferencesAndDatabaseWritesUseOnlyFixtureStorage() {
        val runner=InstrumentationRegistry.getInstrumentation() as IsolatedAndroidTestRunner
        val context=runner.targetContext
        context.getSharedPreferences("fixture-only",Context.MODE_PRIVATE).edit().putString("key","fixture").commit()
        assertEquals("fixture",context.getSharedPreferences("fixture-only",Context.MODE_PRIVATE).getString("key",null))
        val file=context.getDatabasePath("isolation-fixture.db")
        context.openOrCreateDatabase("isolation-fixture.db",Context.MODE_PRIVATE,null).use {db->
            db.execSQL("CREATE TABLE marker (value TEXT NOT NULL)")
            db.execSQL("INSERT INTO marker VALUES ('fixture')")
            db.rawQuery("SELECT value FROM marker",null).use {cursor->assertTrue(cursor.moveToFirst());assertEquals("fixture",cursor.getString(0))}
        }
        assertTrue(file.exists())
        assertTrue(file.canonicalPath.startsWith(runner.fixtureRoot.canonicalPath+File.separator))
        assertTrue(context.deleteDatabase("isolation-fixture.db"))
    }

    @Test fun fixtureStorageRejectsAbsolutePathsTraversalAndExternalMigrations() {
        val context=(InstrumentationRegistry.getInstrumentation() as IsolatedAndroidTestRunner).targetContext
        for(name in listOf("../nedotify-android.db","/data/local/real.db","nested/file.db","..")) {
            assertThrows(IllegalArgumentException::class.java) {context.getDatabasePath(name)}
        }
        assertThrows(UnsupportedOperationException::class.java) {context.moveDatabaseFrom(context,"fixture.db")}
        assertThrows(UnsupportedOperationException::class.java) {context.moveSharedPreferencesFrom(context,"fixture")}
    }
}
