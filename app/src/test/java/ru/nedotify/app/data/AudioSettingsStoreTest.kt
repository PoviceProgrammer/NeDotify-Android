package ru.nedotify.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.core.okio.OkioStorage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.FileSystem
import okio.Path.Companion.toPath

class AudioSettingsStoreTest {
    @get:Rule val folder=TemporaryFolder()

    // The Android FileStorage backend reads stubbed SDK_INT=0 in host unit tests.
    // Its pre-26 File.renameTo cannot replace an existing destination on Windows.
    // Explicit JVM Okio storage still writes real protobuf files and atomic replaces.
    private fun diskStore(file:File,scope:CoroutineScope)=AudioSettingsStore(PreferenceDataStoreFactory.create(
        storage=OkioStorage(fileSystem=FileSystem.SYSTEM,serializer=PreferencesSerializer,
            producePath={file.absolutePath.toPath()}),scope=scope),PreferenceResets(),scope)

    @Test fun concurrentIndependentControlsPatchTheLatestPersistedSettings()=runBlocking {
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val file=File(folder.root,"audio.preferences_pb")
        try {
            val store=diskStore(file,scope)
            coroutineScope {
                awaitAll(async {store.update {it.copy(eqEnabled=true)}},async {store.update {it.copy(autoplay=true)}})
            }
            val result=store.settings.first()
            assertTrue(result.eqEnabled);assertTrue(result.autoplay)
            assertEquals(AudioSettings().bandGainsDb,result.bandGainsDb)
        } finally {job.cancelAndJoin()}
    }

    @Test fun separateBandEditsPreserveEachOtherAndSurviveStoreRecreation()=runBlocking {
        val file=File(folder.root,"bands.preferences_pb")
        val firstJob=SupervisorJob()
        try {
            val store=diskStore(file,CoroutineScope(firstJob+Dispatchers.IO))
            coroutineScope {
                awaitAll(
                    async {store.update {latest->latest.copy(bandGainsDb=latest.bandGainsDb.toMutableList().also {it[0]=5f})}},
                    async {store.update {latest->latest.copy(bandGainsDb=latest.bandGainsDb.toMutableList().also {it[1]=-3f})}},
                    async {store.update {it.copy(quality="dataSaver")}},
                )
            }
            assertEquals(listOf(5f,-3f,0f,0f,0f),store.settings.first().bandGainsDb)
        } finally {firstJob.cancelAndJoin()}
        val nextJob=SupervisorJob()
        try {
            val restored=diskStore(file,CoroutineScope(nextJob+Dispatchers.IO))
                .settings.first()
            assertEquals(listOf(5f,-3f,0f,0f,0f),restored.bandGainsDb);assertEquals("dataSaver",restored.quality)
        } finally {nextJob.cancelAndJoin()}
    }

    /**
     * A failed write must be observable: the UI shows it and rolls the
     * optimistic value back instead of showing a value that was never saved.
     *
     * The message the user sees is the app's own: the message of the system
     * names a path and a class and tells the user nothing they can act on.
     */
    @Test fun aFailedWriteIsObservableAndLeavesThePersistedValueAlone()=runBlocking {
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val file=File(folder.root,"failure.preferences_pb")
        try {
            val store=diskStore(file,scope)
            store.update {it.copy(eqEnabled=true)}
            assertNull("a successful write clears the notice",store.writeFailure.value)
            val error=runCatching {store.update {throw IllegalStateException("нет записи")}}.exceptionOrNull()
            assertNotNull("the failure reaches the caller",error)
            val failure=store.writeFailure.value
            assertNotNull("the failure is observable",failure)
            assertEquals("the user sees the message of the app",AUDIO_WRITE_FAILED_MESSAGE,failure!!.message)
            assertFalse("the message of the system is not for the user",failure!!.message.contains("нет записи"))
            assertTrue("the persisted value is unchanged",store.settings.first().eqEnabled)
            // A later successful write clears the notice.
            store.update {it.copy(autoplay=true)}
            assertNull(store.writeFailure.value)
        } finally {job.cancelAndJoin()}
    }

    /**
     * The limit of the streaming cache is never replaced by a silent default:
     * the caller can see that the file has not confirmed it yet, and the
     * value of the file is applied when it answers.
     */
    @Test fun theCacheLimitIsNotConfirmedBeforeTheFileAnswers()=runBlocking {
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val file=File(folder.root,"cache.preferences_pb")
        try {
            val store=diskStore(file,scope)
            val confirmed=withTimeout(5_000){store.cacheLimit.first{it.confirmed}}
            assertEquals(256,confirmed.megabytes)
            store.update {it.copy(cacheLimitMb=512)}
            assertTrue(store.cacheLimit.value.confirmed)
            assertEquals(512,store.cacheLimit.value.megabytes)
            assertEquals(512,store.cacheLimitMb())
        } finally {job.cancelAndJoin()}
    }

    /**
     * A caller that cannot wait sees a limit that the file has not confirmed:
     * the default must not pass for the choice of the user.
     */
    @Test fun theCacheLimitIsUnconfirmedWhileTheFileHasNotAnswered()=runBlocking {
        // The scope of the store is finished: the file never answers.
        val job=SupervisorJob();job.cancel()
        val scope=CoroutineScope(job+Dispatchers.IO)
        val file=File(folder.root,"cache-unanswered.preferences_pb")
        try {
            val store=diskStore(file,scope)
            assertFalse("the file has not answered yet",store.cacheLimit.value.confirmed)
            assertEquals(DEFAULT_CACHE_LIMIT_MB,store.cacheLimit.value.megabytes)
        } finally {job.cancel()}
    }

    /**
     * A corrupt audio file is repaired by the policy of the layer, and the
     * reset is announced.
     */
    @Test fun aCorruptAudioFileIsRepairedAndTheResetIsAnnounced()=runBlocking {
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val file=File(folder.root,"audio-corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(0x0A,0x20,0x01))
        try {
            val resets=PreferenceResets()
            val store=createStore(PreferenceFile.AUDIO,resets,scope,file,
                OkioStorage(fileSystem=FileSystem.SYSTEM,serializer=PreferencesSerializer,
                    producePath={file.absolutePath.toPath()}))
            val audio=AudioSettingsStore(store,resets,scope)
            assertEquals(AudioSettings(),audio.settings.first())
            audio.update {it.copy(eqEnabled=true)}
            assertTrue(audio.settings.first().eqEnabled)
            assertEquals(PreferenceFile.AUDIO,resets.pending.value?.file)
        } finally {job.cancelAndJoin()}
    }

    /**
     * A value of another type is a corruption of that key: the read must not
     * die with it, the default is used, and the user is told.
     */
    @Test fun aValueOfAnotherTypeInTheAudioFileIsTreatedAsAbsentAndAnnounced()=runBlocking {
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val file=File(folder.root,"audio-mistyped.preferences_pb")
        try {
            val resets=PreferenceResets()
            val store=createStore(PreferenceFile.AUDIO,resets,scope,file,
                OkioStorage(fileSystem=FileSystem.SYSTEM,serializer=PreferencesSerializer,
                    producePath={file.absolutePath.toPath()}))
            // The file holds a number under the name of the quality.
            store.edit { it[androidx.datastore.preferences.core.intPreferencesKey("stream_quality")]=7 }
            val audio=AudioSettingsStore(store,resets,scope)
            assertEquals("high",audio.settings.first().quality)
            val reset=resets.pending.value
            assertNotNull("the file is announced",reset)
            assertEquals(PreferenceFile.AUDIO,reset?.file)
            assertEquals(ResetCause.INCOMPATIBLE_VALUE,reset?.cause)
        } finally {job.cancelAndJoin()}
    }
}
