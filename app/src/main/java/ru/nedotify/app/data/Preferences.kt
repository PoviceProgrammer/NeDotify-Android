package ru.nedotify.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The saved queue: the current key wins, the legacy key an older install wrote
 * is the fallback. The legacy key is read, never written.
 */
internal fun savedQueue(current: String?, legacy: String?): String =
    current?.takeIf { it.isNotBlank() } ?: legacy ?: ""

data class Appearance(val mode: String = "dark", val glass: Boolean = true, val offline: Boolean = false,
    val accent: String = "cyan", val background: Boolean = true, val lyricsAutoScroll: Boolean = true)

/**
 * The settings of the app.
 *
 * The class owns three files:
 *  * the settings (`nedotify`),
 *  * the queue (`nedotify_queue`),
 *  * the identity of the library (a plain file of its own).
 *
 * **The queue has its own file on purpose.** It is the app's only durable
 * session state, and a corrupt settings file must not take it with it: the
 * settings file is replaced with an empty one when it cannot be parsed, the
 * queue file is not.
 *
 * **The identity has its own file on purpose.** The settings file is replaced
 * when it is damaged, and a new identity would turn the device's own backup
 * into another device's copy: the restore would no longer know that the copy
 * comes from this device.
 *
 * The class is the only writer of its files, so the shared snapshot below is
 * exact: a read that follows a write never sees the value from before it, and
 * the readers do not each open their own subscription of the file. The writes
 * themselves are serialised by one mutex, so two writers inside the class
 * cannot publish their results in the wrong order.
 */
class Preferences internal constructor(
    private val store: DataStore<Preferences>,
    private val queueStore: DataStore<Preferences>,
    private val identityFile: File,
    private val resets: PreferenceResets,
    private val scope: CoroutineScope = PreferencesLayer.scope,
) {
    constructor(context: Context) : this(PreferencesLayer.settings(context), PreferencesLayer.queue(context),
        PreferencesLayer.identityFile(context), PreferencesLayer.resets(context))

    fun acknowledgeReset() = resets.acknowledge()

    private val lock = Any()

    /** The one shared snapshot of the settings file. */
    private val settingsValues = MutableStateFlow<Preferences?>(null)

    /** The one shared snapshot of the queue file. */
    private val queueValues = MutableStateFlow<Preferences?>(null)

    /**
     * The single writer of the files of this class.
     *
     * Two writers inside one class can publish their results in the wrong
     * order: the shared snapshot would go backwards and a reader would see a
     * value the file no longer holds. The mutex serialises the writes, so
     * the snapshot is published in the order the file was changed.
     */
    private val writeLock = Mutex()

    /**
     * The keys whose value in a file had another type than the key.
     *
     * The set is guarded by [lock]: it belongs to the same snapshot the
     * values themselves belong to.
     */
    private val mismatched = mutableMapOf<PreferenceFile, MutableSet<Preferences.Key<*>>>()

    /**
     * The settings file, with the read side fallback of a transient error.
     *
     * A corrupt file is repaired by the corruption handler of the layer, so
     * the fallback only covers the errors that are not a corruption.
     */
    private val settingsData = store.data.catch { error ->
        if (error is IOException) {
            reportDiagnostic("The settings file could not be read", error)
            emit(emptyPreferences())
        } else throw error
    }

    private val queueData = queueStore.data.catch { error ->
        if (error is IOException) {
            reportDiagnostic("The queue file could not be read", error)
            emit(emptyPreferences())
        } else throw error
    }

    /**
     * The one-time migration of the queue out of the settings file.
     *
     * The queue of an older install is copied into the queue file and the old
     * keys are removed, so a downgrade cannot restore the queue of an older
     * install. The migration runs before the first read of the queue, so the
     * queue is never served from the wrong file.
     */
    private val queueMigration: Deferred<Unit> = scope.async { migrateQueue() }

    /**
     * The identity leaves the settings file as soon as the app starts.
     *
     * The identity of an older install is still in the settings file, and the
     * settings file is the one that a corruption replaces. The migration runs
     * after the queue migration, so the queue of the older install is moved
     * out first.
     */
    private val identityMigration: Deferred<Unit> = scope.async {
        queueMigration.await()
        try { ensureIdentity() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { reportDiagnostic("The library identity could not be moved to its own file", error) }
    }

    val appearance: Flow<Appearance> = shared(settingsData, settingsValues, PreferenceFile.SETTINGS) { values -> appearanceOf(values) }
    val profile: Flow<UserProfile> = shared(settingsData, settingsValues, PreferenceFile.SETTINGS) { values -> profileOf(values) }
    val searchHistory: Flow<List<String>> = shared(settingsData, settingsValues, PreferenceFile.SETTINGS) { values ->
        PreferenceValues.decodeSearchHistory(values.stringOf(PreferenceFile.SETTINGS, SEARCH_HISTORY).orEmpty())
    }

    /**
     * The reset that is not shown yet: a file was corrupt and was replaced.
     */
    val pendingReset: StateFlow<PreferenceReset?> = resets.pending

    /**
     * Every reset that is not shown yet, oldest first.
     *
     * The channel is a queue: two damaged files are two losses, and the
     * second one must not replace the first.
     */
    val pendingResets: StateFlow<List<PreferenceReset>> = resets.pendingAll

    suspend fun mode(mode: String) {
        require(mode in listOf("dark", "light", "system"))
        write { it[MODE] = mode }
    }

    suspend fun glass(value: Boolean) { write { it[GLASS] = value } }

    suspend fun offline(value: Boolean) { write { it[OFFLINE] = value } }

    suspend fun accent(value: String) {
        require(value in PreferenceValues.accents)
        write { it[ACCENT] = value }
    }

    suspend fun background(value: Boolean) { write { it[BACKGROUND] = value } }

    suspend fun lyricsAutoScroll(value: Boolean) { write { it[LYRICS_FOLLOW] = value } }

    suspend fun profile(name: String, bio: String) {
        val draft = PreferenceValues.profile(name, bio)
        write { it[NAME] = draft.name; it[BIO] = draft.bio }
    }

    suspend fun avatar(uri: String) {
        require(uri.isEmpty() || uri.startsWith("content://"))
        write { it[AVATAR] = uri }
    }

    /**
     * The avatar is removed by writing an empty URI.
     *
     * The store has always accepted it; nothing called it, so the branch was
     * unreachable.
     */
    suspend fun removeAvatar() { avatar("") }

    suspend fun rememberSearch(query: String) {
        write {
            it[SEARCH_HISTORY] = PreferenceValues.encodeSearchHistory(
                PreferenceValues.rememberSearch(PreferenceValues.decodeSearchHistory(it.stringOf(PreferenceFile.SETTINGS, SEARCH_HISTORY).orEmpty()), query))
        }
    }

    suspend fun removeSearch(query: String) {
        write {
            it[SEARCH_HISTORY] = PreferenceValues.encodeSearchHistory(
                PreferenceValues.decodeSearchHistory(it.stringOf(PreferenceFile.SETTINGS, SEARCH_HISTORY).orEmpty()).filterNot { value -> value.equals(query, true) })
        }
    }

    suspend fun clearSearchHistory() { write { it.remove(SEARCH_HISTORY) } }

    /**
     * The queue snapshot; the legacy key is still read, for an older install.
     *
     * The first write under the new key removes the legacy one: a downgrade must
     * not restore a queue that is many selections old.
     */
    suspend fun saveQueue(json: String) {
        queueMigration.await()
        writeQueue { it[QUEUE_V2] = json; it.remove(QUEUE) }
    }

    suspend fun queue(): String {
        queueMigration.await()
        val values = currentQueue()
        val saved = savedQueue(values.stringOf(PreferenceFile.QUEUE, QUEUE_V2), values.stringOf(PreferenceFile.QUEUE, QUEUE))
        if (saved.isNotBlank()) return saved
        // The migration did not reach the queue file: the settings file still
        // holds the queue of the older install.
        val legacy = currentSettings()
        return savedQueue(legacy.stringOf(PreferenceFile.SETTINGS, QUEUE_V2), legacy.stringOf(PreferenceFile.SETTINGS, QUEUE))
    }

    /** The raw queue values: the current key and the legacy one. */
    internal suspend fun queueValues(): Pair<String?, String?> {
        queueMigration.await()
        val values = currentQueue()
        val current = values.stringOf(PreferenceFile.QUEUE, QUEUE_V2)
        val legacy = values.stringOf(PreferenceFile.QUEUE, QUEUE)
        if (current != null || legacy != null) return current to legacy
        val settings = currentSettings()
        return settings.stringOf(PreferenceFile.SETTINGS, QUEUE_V2) to settings.stringOf(PreferenceFile.SETTINGS, QUEUE)
    }

    /**
     * The identity of the device's library.
     *
     * The identity is created once and is never replaced by an imported
     * backup. It lives in a file of its own, so a damaged settings file does
     * not destroy it: a new identity would turn the device's own backup into
     * another device's copy and would downgrade the restore.
     *
     * The file is read first and written only when it holds nothing: a read
     * must not depend on a successful write, and an export must not fail
     * because the device has no write permission it does not need.
     */
    suspend fun libraryId(): String = writeLock.withLock {
        val existing = readIdentity()
        if (existing != null) return@withLock existing
        val minted = adoptLegacyIdentity() ?: UUID.randomUUID().toString()
        writeIdentity(minted)
        minted
    }

    /**
     * The identity of an install that kept it in the settings file.
     *
     * The key is read once, when the identity file holds nothing: the
     * settings file is the one that a corruption replaces, and the identity
     * of the library must not be destroyed with it.
     */
    private suspend fun adoptLegacyIdentity(): String? =
        currentSettings().stringOf(PreferenceFile.SETTINGS, LEGACY_LIBRARY_ID)?.takeIf { it.isNotBlank() }

    /**
     * Moves the identity of an older install into the file of its own.
     *
     * The identity is created once, at the first launch of this version, so
     * the settings file does not hold it any more.
     */
    private suspend fun ensureIdentity() {
        writeLock.withLock {
            if (readIdentity() != null) return@withLock
            val identity = adoptLegacyIdentity() ?: UUID.randomUUID().toString()
            writeIdentity(identity)
        }
    }

    /** The identity in the file, or null when the file holds none. */
    private fun readIdentity(): String? {
        if (!identityFile.exists()) return null
        // A file that cannot be read is an error: minting a new identity here
        // would change the identity of the device behind the user's back.
        return identityFile.readText().trim().takeIf { it.isNotBlank() }
    }

    /** Writes the identity, atomically. */
    private fun writeIdentity(id: String) = writeFileAtomically(identityFile, id)

    /**
     * The write path of the settings file.
     *
     * The result of the write is published, so the shared snapshot is exact
     * and a reader never sees the value from before the write.
     */
    private suspend fun write(transform: (MutablePreferences) -> Unit): Preferences = writeLock.withLock {
        val updated = store.edit(transform)
        synchronized(lock) { settingsValues.value = updated }
        updated
    }

    /**
     * The write path of the queue file.
     *
     * The queue has its own file, but it is written by the same class and
     * under the same lock: one writer, one order of publications.
     */
    private suspend fun writeQueue(transform: (MutablePreferences) -> Unit): Preferences = writeLock.withLock {
        val updated = queueStore.edit(transform)
        synchronized(lock) { queueValues.value = updated }
        updated
    }

    /**
     * The value of the settings file.
     *
     * The file is read once; the read is published only when nothing newer is
     * known, so a read that races with a write cannot undo it.
     */
    private suspend fun currentSettings(): Preferences {
        synchronized(lock) { settingsValues.value?.let { return it } }
        val read = settingsData.first()
        synchronized(lock) {
            settingsValues.value?.let { return it }
            settingsValues.value = read
            return read
        }
    }

    /** The value of the queue file. */
    private suspend fun currentQueue(): Preferences {
        synchronized(lock) { queueValues.value?.let { return it } }
        val read = queueData.first()
        synchronized(lock) {
            queueValues.value?.let { return it }
            queueValues.value = read
            return read
        }
    }

    /**
     * The one-time migration of the queue out of the settings file.
     *
     * The migration is **idempotent**. It is two writes, and a process can
     * die between them: the second run must not stop at the queue file that
     * the first run has already filled, or the old keys would stay in the
     * settings file for good. The copy comes first, so the queue itself is
     * never lost.
     */
    private suspend fun migrateQueue() {
        try {
            val source = currentSettings()
            val current = source.stringOf(PreferenceFile.SETTINGS, QUEUE_V2)
            val legacy = source.stringOf(PreferenceFile.SETTINGS, QUEUE)
            if (current == null && legacy == null) {
                announceMismatches(PreferenceFile.SETTINGS)
                return
            }
            val target = currentQueue()
            if (target.stringOf(PreferenceFile.QUEUE, QUEUE_V2) == null && target.stringOf(PreferenceFile.QUEUE, QUEUE) == null) {
                // The copy comes first: the old keys are removed only after
                // the queue has reached the queue file.
                writeQueue { values ->
                    if (current != null) values[QUEUE_V2] = current
                    if (legacy != null) values[QUEUE] = legacy
                }
            }
            // The old keys are removed in every case: an interrupted
            // migration must not leave them in the settings file forever.
            write { values -> values.remove(QUEUE_V2); values.remove(QUEUE) }
            announceMismatches(PreferenceFile.SETTINGS)
            announceMismatches(PreferenceFile.QUEUE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The queue stays in the settings file; the read falls back to it.
            reportDiagnostic("The queue could not be moved to its own file", error)
        }
    }

    /**
     * A flow over one shared snapshot.
     *
     * The value of the file is read once; every reader then follows the same
     * snapshot instead of opening a subscription of its own.
     */
    private fun <T> shared(
        data: Flow<Preferences>,
        values: MutableStateFlow<Preferences?>,
        file: PreferenceFile,
        read: (Preferences) -> T,
    ): Flow<T> = flow {
        val current = values.value ?: run {
            val read = data.first()
            publishIfEmpty(values, read)
            values.value ?: read
        }
        // The value is derived first and the mismatch is announced before the
        // value is handed out: a reader that stops at the first value (a
        // `first()`) must still see the notice.
        val value = read(current)
        announceMismatches(file)
        emit(value)
        emitAll(values.filterNotNull().map(read).onEach { announceMismatches(file) })
    }.distinctUntilChanged()

    /** Publishes a value that was read, unless a write has published a newer one. */
    private fun publishIfEmpty(
        values: MutableStateFlow<Preferences?>,
        read: Preferences,
    ) {
        synchronized(lock) { if (values.value == null) values.value = read }
    }

    /**
     * The value of a key, or null when the file holds a value of another type.
     *
     * The mismatch is remembered: the file is announced and the offending key
     * is removed, so the value cannot break the next read either.
     */
    private fun Preferences.stringOf(file: PreferenceFile, key: Preferences.Key<String>): String? =
        valueOrNull(key) { markMismatch(file, key) }

    private fun Preferences.booleanOf(file: PreferenceFile, key: Preferences.Key<Boolean>): Boolean? =
        valueOrNull(key) { markMismatch(file, key) }

    /** Remembers that a key of a file holds a value of another type. */
    private fun markMismatch(file: PreferenceFile, key: Preferences.Key<*>) {
        synchronized(lock) { mismatched.getOrPut(file) { mutableSetOf() }.add(key) }
    }

    /**
     * Announces the values the file could not be read with, and removes them.
     *
     * The announcement is durable, and the key is removed from the file: a
     * value the app cannot read must not break the next read either, and the
     * user is told that the setting was dropped.
     */
    private suspend fun announceMismatches(file: PreferenceFile) {
        val keys = synchronized(lock) { mismatched.remove(file).orEmpty() }
        if (keys.isEmpty()) return
        resets.announce(file, ResetCause.INCOMPATIBLE_VALUE)
        try {
            when (file) {
                PreferenceFile.SETTINGS -> write { values -> keys.forEach { values.remove(it) } }
                PreferenceFile.QUEUE -> writeQueue { values -> keys.forEach { values.remove(it) } }
                // The audio file belongs to the audio store.
                PreferenceFile.AUDIO -> return
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportDiagnostic("A value of another type could not be removed", error)
        }
    }

    private fun appearanceOf(values: Preferences) = Appearance(
        values.stringOf(PreferenceFile.SETTINGS, MODE)?.takeIf { value -> value in listOf("dark", "light", "system") } ?: "dark",
        values.booleanOf(PreferenceFile.SETTINGS, GLASS) ?: true,
        values.booleanOf(PreferenceFile.SETTINGS, OFFLINE) ?: false,
        values.stringOf(PreferenceFile.SETTINGS, ACCENT)?.takeIf { value -> value in PreferenceValues.accents } ?: "cyan",
        values.booleanOf(PreferenceFile.SETTINGS, BACKGROUND) ?: true,
        values.booleanOf(PreferenceFile.SETTINGS, LYRICS_FOLLOW) ?: true)

    private fun profileOf(values: Preferences) = UserProfile(
        values.stringOf(PreferenceFile.SETTINGS, NAME) ?: "Моя музыка",
        values.stringOf(PreferenceFile.SETTINGS, BIO) ?: "Слушаю музыку, живу моментом",
        values.stringOf(PreferenceFile.SETTINGS, AVATAR).orEmpty())

    companion object {
        private val MODE = stringPreferencesKey("mode")
        private val GLASS = booleanPreferencesKey("glass")
        private val OFFLINE = booleanPreferencesKey("offline")
        private val QUEUE = stringPreferencesKey("queue_v1")
        private val QUEUE_V2 = stringPreferencesKey("queue_v2")
        /** The identity of an install that kept it in the settings file. */
        private val LEGACY_LIBRARY_ID = stringPreferencesKey("library_id_v1")
        private val ACCENT = stringPreferencesKey("accent_v1")
        private val BACKGROUND = booleanPreferencesKey("background_v1")
        private val LYRICS_FOLLOW = booleanPreferencesKey("lyrics_follow_v1")
        private val NAME = stringPreferencesKey("profile_name_v1")
        private val BIO = stringPreferencesKey("profile_bio_v1")
        private val AVATAR = stringPreferencesKey("profile_avatar_v1")
        private val SEARCH_HISTORY = stringPreferencesKey("search_history_v1")
    }
}
