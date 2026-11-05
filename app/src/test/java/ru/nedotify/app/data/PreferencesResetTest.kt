package ru.nedotify.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The reset channel of the preferences layer.
 *
 * A reset is a fact about the device: the file that held the values was
 * replaced, and the user must learn that the values are gone. The notice is
 * therefore durable, and the channel carries more than one notice.
 */
class PreferencesResetTest {
    @get:Rule val folder = TemporaryFolder()

    /** A file that is not a preferences proto: the read must fail on it. */
    private val corruptBytes = byteArrayOf(0x0A, 0x20, 0x01)

    private fun diskStore(
        file: File,
        scope: CoroutineScope,
        kind: PreferenceFile,
        resets: PreferenceResets,
    ): DataStore<Preferences> = createStore(kind, resets, scope, file,
        OkioStorage(fileSystem = FileSystem.SYSTEM, serializer = PreferencesSerializer,
            producePath = { file.absolutePath.toPath() }))

    private fun scope(): Pair<CoroutineScope, Job> {
        val job = SupervisorJob()
        return CoroutineScope(job + Dispatchers.IO) to job
    }

    /**
     * The name of the queue file on disk is the contract with every existing
     * install: the queue of an install that is upgraded is found in this
     * file, and the file holds the app's only durable session state.
     */
    @Test fun theQueueFileHasItsOwnNameOnDisk() {
        assertEquals("nedotify_queue", PreferencesLayer.fileName(PreferenceFile.QUEUE))
        val directory = folder.root
        assertEquals(File(directory, "datastore/nedotify_queue.preferences_pb"),
            PreferencesLayer.storeFile(directory, PreferencesLayer.fileName(PreferenceFile.QUEUE)))
        assertEquals(File(directory, "datastore/nedotify.preferences_pb"),
            PreferencesLayer.storeFile(directory, PreferencesLayer.fileName(PreferenceFile.SETTINGS)))
        assertEquals(File(directory, "datastore/nedotify_audio.preferences_pb"),
            PreferencesLayer.storeFile(directory, PreferencesLayer.fileName(PreferenceFile.AUDIO)))
    }

    /**
     * The notice survives the process: the evidence (the replaced file) is
     * gone, the notice must not be.
     */
    @Test fun theAnnouncedResetSurvivesARestart() = runBlocking {
        val ledger = File(folder.root, "resets")
        val file = File(folder.root, "durable.preferences_pb")
        file.writeBytes(corruptBytes)
        val (scope, job) = scope()
        try {
            val resets = PreferenceResets(ResetLedger(ledger))
            val store = diskStore(file, scope, PreferenceFile.SETTINGS, resets)
            store.data.first()
            assertEquals(PreferenceFile.SETTINGS, resets.pending.value?.file)
            assertTrue("the notice is written to the durable record", ledger.exists())
        } finally { job.cancelAndJoin() }
        // The next launch of the app: the notice is still there.
        val restored = PreferenceResets(ResetLedger(ledger))
        assertEquals("the notice survives the restart", PreferenceFile.SETTINGS, restored.pending.value?.file)
        assertTrue(restored.pending.value!!.message().isNotBlank())
        assertNotNull(restored.pending.value!!.detail())
        // The notice is shown once: the acknowledgement clears the record.
        restored.acknowledge()
        val afterAcknowledgement = PreferenceResets(ResetLedger(ledger))
        assertNull("the notice was shown", afterAcknowledgement.pending.value)
    }

    /**
     * The channel carries more than one notice: two damaged files are two
     * losses, and the second one must not replace the first.
     */
    @Test fun twoDamagedFilesCarryTwoNotices() = runBlocking {
        val (scope, job) = scope()
        try {
            val resets = PreferenceResets()
            val settingsFile = File(folder.root, "two-settings.preferences_pb")
            val audioFile = File(folder.root, "two-audio.preferences_pb")
            settingsFile.writeBytes(corruptBytes)
            audioFile.writeBytes(corruptBytes)
            diskStore(settingsFile, scope, PreferenceFile.SETTINGS, resets).data.first()
            diskStore(audioFile, scope, PreferenceFile.AUDIO, resets).data.first()
            assertEquals("both losses are announced", 2, resets.pendingAll.value.size)
            assertEquals(PreferenceFile.SETTINGS, resets.pending.value?.file)
            // The first notice is shown, the second one waits for its turn.
            resets.acknowledge()
            assertEquals(PreferenceFile.AUDIO, resets.pending.value?.file)
            resets.acknowledge()
            assertNull(resets.pending.value)
        } finally { job.cancelAndJoin() }
    }

    /**
     * A second corruption of the same file is a new notice: a `StateFlow`
     * conflates equal values, and the user would never learn of it.
     */
    @Test fun aSecondCorruptionOfTheSameFileIsANewNotice() = runBlocking {
        val file = File(folder.root, "twice.preferences_pb")
        file.writeBytes(corruptBytes)
        val resets = PreferenceResets()
        val (first, firstJob) = scope()
        val firstNotice = try {
            diskStore(file, first, PreferenceFile.SETTINGS, resets).data.first()
            resets.pending.value
        } finally { firstJob.cancelAndJoin() }
        assertNotNull("the first corruption is announced", firstNotice)
        resets.acknowledge()
        assertNull(resets.pending.value)
        // The file is damaged again.
        file.writeBytes(corruptBytes)
        val (second, secondJob) = scope()
        try {
            diskStore(file, second, PreferenceFile.SETTINGS, resets).data.first()
            val secondNotice = resets.pending.value
            assertNotNull("the second corruption is announced", secondNotice)
            assertNotEquals("the notices are not conflated", firstNotice?.id, secondNotice?.id)
        } finally { secondJob.cancelAndJoin() }
    }

    /**
     * A notice is a fact about a file: a file that is damaged twice before
     * the user has seen the first notice is one loss, not two.
     */
    @Test fun oneNoticePerFileIsEnough() = runBlocking {
        val resets = PreferenceResets()
        resets.announce(PreferenceFile.SETTINGS)
        resets.announce(PreferenceFile.SETTINGS)
        assertEquals(1, resets.pendingAll.value.size)
    }
}
