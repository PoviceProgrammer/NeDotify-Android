package ru.nedotify.app.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The preferences layer over real files.
 *
 * The stores are built by the production code over a temporary file with the
 * JVM Okio backend, so a corrupt file can be injected and the repair
 * observed.
 */
class PreferencesStoreTest {
    @get:Rule val folder = TemporaryFolder()

    /** A file that is not a preferences proto: the read must fail on it. */
    private val corruptBytes = byteArrayOf(0x0A, 0x20, 0x01)

    /**
     * The store of a file, built by the production code: the corruption policy
     * of the layer is the one under test.
     */
    private fun diskStore(
        file: File,
        scope: CoroutineScope,
        kind: PreferenceFile = PreferenceFile.SETTINGS,
        resets: PreferenceResets = PreferenceResets(),
    ): DataStore<Preferences> = createStore(kind, resets, scope, file,
        OkioStorage(fileSystem = FileSystem.SYSTEM, serializer = PreferencesSerializer,
            producePath = { file.absolutePath.toPath() }))

    /**
     * A store whose file cannot be written: the parent of the file is a
     * regular file, so the directory of the store cannot be created. The read
     * answers with the default, the write fails.
     */
    private fun unwritableStore(
        name: String,
        scope: CoroutineScope,
        kind: PreferenceFile = PreferenceFile.SETTINGS,
        resets: PreferenceResets = PreferenceResets(),
    ): DataStore<Preferences> {
        val blocker = File(folder.root, "blocker-$name")
        blocker.writeText("not a directory")
        val file = File(blocker, "store.preferences_pb")
        return createStore(kind, resets, scope, file,
            OkioStorage(fileSystem = FileSystem.SYSTEM, serializer = PreferencesSerializer,
                producePath = { file.absolutePath.toPath() }))
    }

    /**
     * A store **without** the corruption policy: the premise of the repair.
     */
    private fun rawStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            storage = OkioStorage(fileSystem = FileSystem.SYSTEM, serializer = PreferencesSerializer,
                producePath = { file.absolutePath.toPath() }),
            scope = scope)

    private fun scope(): Pair<CoroutineScope, Job> {
        val job = SupervisorJob()
        return CoroutineScope(job + Dispatchers.IO) to job
    }

    private fun identityFile(name: String) = File(folder.root, "identity-$name")

    @Test fun aCorruptFileIsACorruption() = runBlocking {
        val file = File(folder.root, "broken.preferences_pb")
        file.writeBytes(corruptBytes)
        val (scope, job) = scope()
        try {
            val store = rawStore(file, scope)
            val error = try { store.data.first(); null } catch (failure: Exception) { failure }
            assertTrue("the premise: the file cannot be parsed ($error)", error is CorruptionException)
            // Without the policy the write path is wedged for good.
            val write = try { store.edit { it[stringPreferencesKey("mode")] = "light" }; null }
            catch (failure: Exception) { failure }
            assertTrue("the write fails as well ($write)", write is CorruptionException)
        } finally { job.cancelAndJoin() }
    }

    @Test fun aCorruptFileIsRepairedAndTheWritesWorkAgain() = runBlocking {
        val file = File(folder.root, "corrupt.preferences_pb")
        file.writeBytes(corruptBytes)
        val (scope, job) = scope()
        try {
            val resets = PreferenceResets()
            val store = diskStore(file, scope, PreferenceFile.SETTINGS, resets)
            // The read is repaired instead of failing.
            assertTrue(store.data.first().asMap().isEmpty())
            // The write path works again: this is the part that never recovered.
            store.edit { it[stringPreferencesKey("mode")] = "light" }
            assertEquals("light", store.data.first()[stringPreferencesKey("mode")])
            // The reset is announced: a silent reset looks like a fresh install.
            assertEquals(PreferenceFile.SETTINGS, resets.pending.value?.file)
        } finally { job.cancelAndJoin() }
    }

    @Test fun theRepairSurvivesARestart() = runBlocking {
        val file = File(folder.root, "restart.preferences_pb")
        file.writeBytes(corruptBytes)
        val (first, firstJob) = scope()
        try {
            val resets = PreferenceResets()
            val store = diskStore(file, first, PreferenceFile.SETTINGS, resets)
            assertTrue(store.data.first().asMap().isEmpty())
            store.edit { it[stringPreferencesKey("mode")] = "light" }
        } finally { firstJob.cancelAndJoin() }
        assertFalse("the corrupt file was replaced", file.readBytes().contentEquals(corruptBytes))
        val (second, secondJob) = scope()
        try {
            val resets = PreferenceResets()
            val store = diskStore(file, second, PreferenceFile.SETTINGS, resets)
            // A fresh store over the same file reads and writes.
            assertEquals("light", store.data.first()[stringPreferencesKey("mode")])
            store.edit { it[stringPreferencesKey("mode")] = "dark" }
            assertEquals("dark", store.data.first()[stringPreferencesKey("mode")])
            assertNull("the file was not corrupt any more", resets.pending.value)
        } finally { secondJob.cancelAndJoin() }
    }

    @Test fun theQueueSurvivesACorruptSettingsFile() = runBlocking {
        val settingsFile = File(folder.root, "queue-survivor.preferences_pb")
        val queueFile = File(folder.root, "queue-survivor-queue.preferences_pb")
        val payload = "{\"tracks\":[\"a\",\"b\"]}"
        val (first, firstJob) = scope()
        try {
            val resets = PreferenceResets()
            val preferences = Preferences(
                diskStore(settingsFile, first, PreferenceFile.SETTINGS, resets),
                diskStore(queueFile, first, PreferenceFile.QUEUE, resets),
                identityFile("survivor"), resets, first)
            preferences.saveQueue(payload)
            assertEquals(payload, preferences.queue())
        } finally { firstJob.cancelAndJoin() }
        // The settings file is corrupted from the outside.
        settingsFile.writeBytes(corruptBytes)
        val (second, secondJob) = scope()
        try {
            val resets = PreferenceResets()
            val preferences = Preferences(
                diskStore(settingsFile, second, PreferenceFile.SETTINGS, resets),
                diskStore(queueFile, second, PreferenceFile.QUEUE, resets),
                identityFile("survivor"), resets, second)
            // The settings are read, repaired and the reset is announced.
            assertEquals(Appearance(), preferences.appearance.first())
            assertEquals(PreferenceFile.SETTINGS, resets.pending.value?.file)
            // The queue is in its own file: the corruption did not take it.
            assertEquals(payload, preferences.queue())
        } finally { secondJob.cancelAndJoin() }
    }

    @Test fun theQueueOfAnOlderInstallIsMovedIntoItsOwnFile() = runBlocking {
        val settingsFile = File(folder.root, "legacy.preferences_pb")
        val queueFile = File(folder.root, "legacy-queue.preferences_pb")
        val (scope, job) = scope()
        try {
            val resets = PreferenceResets()
            // The older install kept the queue in the settings file.
            val settings = diskStore(settingsFile, scope, PreferenceFile.SETTINGS, resets)
            settings.edit { it[stringPreferencesKey("queue_v2")] = "payload" }
            val preferences = Preferences(settings,
                diskStore(queueFile, scope, PreferenceFile.QUEUE, resets), identityFile("legacy"), resets, scope)
            assertEquals("payload", preferences.queue())
            val (current, legacy) = preferences.queueValues()
            assertEquals("payload", current)
            assertNull("the legacy key is removed by the migration", legacy)
            assertNull("the old key is gone from the settings file",
                settings.data.first()[stringPreferencesKey("queue_v2")])
        } finally { job.cancelAndJoin() }
    }

    /**
     * A kill between the two writes of the migration leaves the queue in the
     * queue file and the old keys in the settings file. The next run must
     * remove them: the migration is not allowed to stop at the queue file it
     * has already filled.
     */
    @Test fun anInterruptedMigrationLeavesNoStaleKeyBehind() = runBlocking {
        val settingsFile = File(folder.root, "interrupted.preferences_pb")
        val queueFile = File(folder.root, "interrupted-queue.preferences_pb")
        val (scope, job) = scope()
        try {
            val resets = PreferenceResets()
            val settings = diskStore(settingsFile, scope, PreferenceFile.SETTINGS, resets)
            val queue = diskStore(queueFile, scope, PreferenceFile.QUEUE, resets)
            // The state a kill between the two writes leaves behind.
            settings.edit { it[stringPreferencesKey("queue_v2")] = "payload" }
            queue.edit { it[stringPreferencesKey("queue_v2")] = "payload" }
            val preferences = Preferences(settings, queue, identityFile("interrupted"), resets, scope)
            assertEquals("payload", preferences.queue())
            assertNull("the stale key is gone from the settings file",
                settings.data.first()[stringPreferencesKey("queue_v2")])
        } finally { job.cancelAndJoin() }
    }

    /**
     * The migration cannot write: the failure is reported, and the queue of
     * the older install is still served from the settings file.
     */
    @Test fun aFailedMigrationIsReportedAndTheQueueIsKept() = runBlocking {
        val settingsFile = File(folder.root, "migration-failure.preferences_pb")
        val (scope, job) = scope()
        val recorded = mutableListOf<String>()
        try {
            withDiagnostics(PreferenceDiagnostics { message, _ -> recorded += message }) {
                val resets = PreferenceResets()
                val settings = diskStore(settingsFile, scope, PreferenceFile.SETTINGS, resets)
                settings.edit { it[stringPreferencesKey("queue_v2")] = "payload" }
                // The queue file cannot be written: the copy fails.
                val queue = unwritableStore("migration-failure-queue", scope, PreferenceFile.QUEUE, resets)
                val preferences = Preferences(settings, queue, identityFile("migration"), resets, scope)
                assertEquals("the queue stays in the settings file", "payload", preferences.queue())
                assertTrue("the failure is reported: $recorded",
                    recorded.any { it.contains("could not be moved") })
            }
        } finally { job.cancelAndJoin() }
    }

    @Test fun theSettingsArePersistedAndReadBack() = runBlocking {
        val (scope, job) = scope()
        try {
            val resets = PreferenceResets()
            val preferences = Preferences(
                diskStore(File(folder.root, "settings.preferences_pb"), scope, PreferenceFile.SETTINGS, resets),
                diskStore(File(folder.root, "settings-queue.preferences_pb"), scope, PreferenceFile.QUEUE, resets),
                identityFile("settings"), resets, scope)
            preferences.mode("light")
            assertEquals("light", preferences.appearance.first().mode)
            preferences.offline(true)
            assertTrue(preferences.appearance.first().offline)
            preferences.accent("violet")
            assertEquals("violet", preferences.appearance.first().accent)
            preferences.glass(false)
            assertFalse(preferences.appearance.first().glass)
            preferences.background(false)
            assertFalse(preferences.appearance.first().background)
            preferences.lyricsAutoScroll(false)
            assertFalse(preferences.appearance.first().lyricsAutoScroll)
            preferences.profile("Артур", "Музыка")
            assertEquals("Артур", preferences.profile.first().name)
            preferences.rememberSearch("Rock")
            assertEquals(listOf("Rock"), preferences.searchHistory.first())
            preferences.removeSearch("Rock")
            assertTrue(preferences.searchHistory.first().isEmpty())
            preferences.avatar("content://avatar/1")
            assertEquals("content://avatar/1", preferences.profile.first().avatarUri)
            preferences.removeAvatar()
            assertEquals("", preferences.profile.first().avatarUri)
        } finally { job.cancelAndJoin() }
    }

    @Test fun theLibraryIdentityIsCreatedOnce() = runBlocking {
        val (scope, job) = scope()
        try {
            val resets = PreferenceResets()
            val preferences = Preferences(
                diskStore(File(folder.root, "identity.preferences_pb"), scope, PreferenceFile.SETTINGS, resets),
                diskStore(File(folder.root, "identity-queue.preferences_pb"), scope, PreferenceFile.QUEUE, resets),
                identityFile("library"), resets, scope)
            val identity = preferences.libraryId()
            assertNotNull(identity)
            assertTrue(identity.isNotBlank())
            assertEquals(identity, preferences.libraryId())
        } finally { job.cancelAndJoin() }
    }

    /**
     * The identity of the library does not live in the settings file: that
     * file is replaced when it is damaged, and a new identity would turn the
     * device's own backup into another device's copy.
     *
     * The identity is also not written on every call: a read must not depend
     * on a successful write.
     */
    @Test fun theLibraryIdentityIsNeitherStoredInTheSettingsFileNorRewritten() = runBlocking {
        val settingsFile = File(folder.root, "identity-outside.preferences_pb")
        val (scope, job) = scope()
        try {
            val resets = PreferenceResets()
            val settings = diskStore(settingsFile, scope, PreferenceFile.SETTINGS, resets)
            val identity = identityFile("outside")
            val preferences = Preferences(settings,
                diskStore(File(folder.root, "identity-outside-queue.preferences_pb"), scope, PreferenceFile.QUEUE, resets),
                identity, resets, scope)
            val id = preferences.libraryId()
            assertTrue(id.isNotBlank())
            assertEquals("the identity is stable", id, preferences.libraryId())
            assertTrue("the settings file is untouched", settings.data.first().asMap().isEmpty())
            assertEquals("the identity is in its own file", id, identity.readText().trim())
        } finally { job.cancelAndJoin() }
    }

    /**
     * The identity of the library survives a corruption of the settings file.
     */
    @Test fun theLibraryIdentitySurvivesACorruptSettingsFile() = runBlocking {
        val settingsFile = File(folder.root, "identity-survivor.preferences_pb")
        val queueFile = File(folder.root, "identity-survivor-queue.preferences_pb")
        val identity = identityFile("survivor")
        val (first, firstJob) = scope()
        val id = try {
            val resets = PreferenceResets()
            val preferences = Preferences(
                diskStore(settingsFile, first, PreferenceFile.SETTINGS, resets),
                diskStore(queueFile, first, PreferenceFile.QUEUE, resets),
                identity, resets, first)
            preferences.libraryId()
        } finally { firstJob.cancelAndJoin() }
        // The settings file is damaged from the outside.
        settingsFile.writeBytes(corruptBytes)
        val (second, secondJob) = scope()
        try {
            val resets = PreferenceResets()
            val preferences = Preferences(
                diskStore(settingsFile, second, PreferenceFile.SETTINGS, resets),
                diskStore(queueFile, second, PreferenceFile.QUEUE, resets),
                identity, resets, second)
            assertEquals("the identity of the device is the same", id, preferences.libraryId())
        } finally { secondJob.cancelAndJoin() }
    }

    /**
     * A value of another type is a corruption of that key: the read must not
     * die with it, the default is used, and the user is told.
     */
    @Test fun aValueOfAnotherTypeIsTreatedAsAbsentAndAnnounced() = runBlocking {
        val settingsFile = File(folder.root, "mistyped.preferences_pb")
        val (scope, job) = scope()
        try {
            val resets = PreferenceResets()
            val settings = diskStore(settingsFile, scope, PreferenceFile.SETTINGS, resets)
            // The file holds a boolean under the name of the theme.
            settings.edit { it[booleanPreferencesKey("mode")] = true }
            val preferences = Preferences(settings,
                diskStore(File(folder.root, "mistyped-queue.preferences_pb"), scope, PreferenceFile.QUEUE, resets),
                identityFile("mistyped"), resets, scope)
            assertEquals("the default is used", "dark", preferences.appearance.first().mode)
            val reset = resets.pending.value
            assertNotNull("the file is announced", reset)
            assertEquals(PreferenceFile.SETTINGS, reset?.file)
            assertEquals(ResetCause.INCOMPATIBLE_VALUE, reset?.cause)
            assertNull("the value of another type is removed",
                settings.data.first()[booleanPreferencesKey("mode")])
        } finally { job.cancelAndJoin() }
    }

    /**
     * A write that fails must not look like a write that succeeded.
     */
    @Test fun aFailedWriteLeavesTheValueAlone() = runBlocking {
        val (scope, job) = scope()
        try {
            val resets = PreferenceResets()
            val settings = unwritableStore("write-failure", scope, PreferenceFile.SETTINGS, resets)
            val preferences = Preferences(settings,
                unwritableStore("write-failure-queue", scope, PreferenceFile.QUEUE, resets),
                identityFile("write-failure"), resets, scope)
            val failure = runCatching { preferences.mode("light") }.exceptionOrNull()
            assertNotNull("the failure reaches the caller", failure)
            assertEquals("the value was not changed", "dark", preferences.appearance.first().mode)
        } finally { job.cancelAndJoin() }
    }

    /**
     * The shared snapshot must not go backwards: two writers inside one class
     * can publish their results in the wrong order, and a reader would then
     * see a value the file no longer holds.
     */
    @Test fun theSharedSnapshotDoesNotRegressUnderAConcurrentWrite() = runBlocking {
        repeat(8) { round ->
            val (scope, job) = scope()
            try {
                val resets = PreferenceResets()
                val settings = diskStore(File(folder.root, "snapshot-$round.preferences_pb"), scope, PreferenceFile.SETTINGS, resets)
                val preferences = Preferences(settings,
                    diskStore(File(folder.root, "snapshot-$round-queue.preferences_pb"), scope, PreferenceFile.QUEUE, resets),
                    identityFile("snapshot-$round"), resets, scope)
                val seen = mutableListOf<Int>()
                val watcher = launch { preferences.searchHistory.collect { seen += it.size } }
                coroutineScope {
                    awaitAll(*(1..16).map { index -> async { preferences.rememberSearch("q$index") } }.toTypedArray())
                }
                watcher.cancel()
                assertEquals("the snapshot never goes backwards (round $round): $seen",
                    seen.sorted(), seen)
                val file = PreferenceValues.decodeSearchHistory(
                    settings.data.first()[stringPreferencesKey("search_history_v1")].orEmpty())
                assertEquals("the snapshot is the value of the file (round $round)",
                    file.size, preferences.searchHistory.first().size)
            } finally { job.cancelAndJoin() }
        }
    }

    /**
     * The settings file has **one** write path.
     *
     * Two writers inside one class can publish their results in the wrong
     * order: the shared snapshot would go backwards and a reader would see a
     * value the file no longer holds. The migration writes through the same
     * path as every other write, and every write is serialised.
     *
     * The order of two publications cannot be forced from a JVM test (the
     * actor of DataStore applies the writes one after another, and the
     * publication follows the completion of the write), so the structure of
     * the class is pinned here — the same way `LibraryWriteFenceTest` pins
     * the fence of the library.
     */
    @Test fun theSettingsFileHasOneWritePath() {
        val source = source("ru/nedotify/app/data/Preferences.kt")
        val migration = body(source, "private suspend fun migrateQueue()")
        assertFalse("the migration writes through the write path of the class",
            migration.contains("store.edit"))
        assertFalse("the migration writes through the write path of the class",
            migration.contains("queueStore.edit"))
        val write = body(source, "private suspend fun write(transform:")
        assertTrue("every write of the settings file is serialised: $write",
            write.contains("writeLock.withLock"))
    }

    /** The source of a file of the module, found from the working directory. */
    private fun source(path: String): String {
        var root = File("").absoluteFile
        while (root.parentFile != null) {
            val file = File(root, "src/main/java/$path")
            if (file.isFile) return file.readText()
            root = root.parentFile
        }
        throw AssertionError("the source file $path was not found")
    }

    /** The body of a function of the module source, up to its closing brace. */
    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("the function $signature was not found", start >= 0)
        val lines = source.substring(start).lines()
        val collected = StringBuilder()
        var depth = 0
        for (line in lines) {
            collected.append(line).append('\n')
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth == 0 && collected.isNotEmpty() && collected.contains('{')) break
        }
        return collected.toString()
    }

    @Test fun theResetNoticeIsShownOnce() = runBlocking {
        val resets = PreferenceResets()
        assertNull(resets.pending.value)
        resets.announce(PreferenceFile.AUDIO)
        assertEquals(PreferenceFile.AUDIO, resets.pending.value?.file)
        assertTrue(resets.pending.value!!.message().isNotBlank())
        resets.acknowledge()
        assertNull(resets.pending.value)
    }

    /**
     * The path of a file is the contract with an existing install: the
     * settings must still be found, and the queue must not be in them.
     */
    @Test fun theSettingsKeepTheirPathAndTheQueueHasItsOwn() {
        assertEquals("nedotify", PreferencesLayer.fileName(PreferenceFile.SETTINGS))
        assertEquals("nedotify_audio", PreferencesLayer.fileName(PreferenceFile.AUDIO))
        assertNotEquals("the queue must not live in the settings file",
            PreferencesLayer.fileName(PreferenceFile.SETTINGS), PreferencesLayer.fileName(PreferenceFile.QUEUE))
        val directory = folder.root
        assertEquals(File(directory, "datastore/nedotify.preferences_pb"),
            PreferencesLayer.storeFile(directory, PreferencesLayer.fileName(PreferenceFile.SETTINGS)))
        assertNotEquals(PreferencesLayer.storeFile(directory, PreferencesLayer.fileName(PreferenceFile.SETTINGS)),
            PreferencesLayer.storeFile(directory, PreferencesLayer.fileName(PreferenceFile.QUEUE)))
    }
}
