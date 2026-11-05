package ru.nedotify.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The default limit of the streaming cache, in megabytes. */
const val DEFAULT_CACHE_LIMIT_MB = 256

/**
 * The message the app shows when the audio settings could not be saved.
 *
 * The message of the system exception is not for the user: it names a path
 * and a class, and it tells the user nothing they can act on. The technical
 * detail goes to the log.
 */
const val AUDIO_WRITE_FAILED_MESSAGE = "Не удалось сохранить настройки звука"

/**
 * A failed write.
 *
 * The id counts the failures, so a repeated message is still a new failure:
 * the UI shows it and rolls the optimistic value back.
 */
data class AudioWriteFailure(val id: Long, val message: String)

/**
 * The limit of the streaming cache.
 *
 * `confirmed` is false while the file has not answered yet, and it stays
 * false when the file could not be read. A caller that cannot wait uses the
 * value and can see that the user's choice is not confirmed: the limit is
 * never replaced by a silent default.
 */
data class CacheLimit(val megabytes: Int, val confirmed: Boolean)

/**
 * The audio settings of the app.
 *
 * The class is the only writer of its file, so the shared snapshot below is
 * exact: a read that follows a write never sees the value from before it.
 * The writes are serialised by one mutex, so two of them cannot publish
 * their results in the wrong order.
 */
class AudioSettingsStore internal constructor(
    private val store: DataStore<Preferences>,
    private val resets: PreferenceResets,
    private val scope: CoroutineScope = PreferencesLayer.scope,
) {
    constructor(context: Context) : this(PreferencesLayer.audio(context), PreferencesLayer.resets(context))

    private val lock = Any()

    /** The one shared snapshot of the file. */
    private val values = MutableStateFlow<Preferences?>(null)

    /**
     * The single writer of the file.
     *
     * Two writes inside one class can publish their results in the wrong
     * order: the shared snapshot would go backwards and a reader would see a
     * value the file no longer holds.
     */
    private val writeLock = Mutex()

    /** Whether the file could not be read: the value is not confirmed then. */
    private var readFailed = false

    /** The keys whose value in the file had another type than the key. */
    private val mismatched = mutableSetOf<Preferences.Key<*>>()

    private val data = store.data.catch { error ->
        if (error is IOException) {
            reportDiagnostic("The audio settings could not be read", error)
            synchronized(lock) { readFailed = true }
            emit(emptyPreferences())
        } else throw error
    }

    private val failures = AtomicLong(0)

    private val _writeFailure = MutableStateFlow<AudioWriteFailure?>(null)

    /** The last failed write, if any. */
    val writeFailure: StateFlow<AudioWriteFailure?> = _writeFailure.asStateFlow()

    private val _cacheLimit = MutableStateFlow(CacheLimit(DEFAULT_CACHE_LIMIT_MB, false))

    /** The limit of the streaming cache and whether the file confirmed it. */
    val cacheLimit: StateFlow<CacheLimit> = _cacheLimit.asStateFlow()

    /** The reset that is not shown yet: the file was corrupt and was replaced. */
    val pendingReset: StateFlow<PreferenceReset?> = resets.pending

    init {
        // The file is read in the background: the caller that cannot wait
        // (the streaming cache) must not block on the disk, and the value it
        // uses must say whether the file has confirmed it.
        scope.launch {
            try {
                val file = data.first()
                publishIfEmpty(file)
                // The values are derived once here, so a value of another type
                // is noticed even when no reader collects the flow.
                read(file)
                announceMismatches()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportDiagnostic("The audio settings could not be read", error) }
        }
    }

    val settings: Flow<AudioSettings> = flow {
        val current = values.value ?: run {
            val read = data.first()
            publishIfEmpty(read)
            values.value ?: read
        }
        // The value is derived first and the mismatch is announced before the
        // value is handed out: a reader that stops at the first value (a
        // `first()`) must still see the notice.
        val value = read(current)
        announceMismatches()
        emit(value)
        emitAll(values.filterNotNull().map(::read).onEach { announceMismatches() })
    }.distinctUntilChanged()

    /**
     * The limit of the streaming cache, read from the file.
     *
     * The read is awaited: the caller's choice is never replaced by a silent
     * default. A caller that cannot wait reads [cacheLimit] and sees whether
     * the value is confirmed.
     */
    suspend fun cacheLimitMb(): Int = settings.first().cacheLimitMb

    suspend fun update(transform: (AudioSettings) -> AudioSettings) {
        try {
            writeLock.withLock {
                val updated = store.edit {
                    // Every control patches the latest persisted model, never the stale UI snapshot.
                    val value = transform(read(it)).normalized()
                    it[EQ] = value.eqEnabled; BANDS.forEachIndexed { index, key -> it[key] = value.bandGainsDb[index] }
                    it[BASS] = value.bassEnabled; it[BASS_STRENGTH] = value.bassStrength
                    it[VIRTUALIZER] = value.virtualizerEnabled; it[VIRTUALIZER_STRENGTH] = value.virtualizerStrength
                    it[CROSSFADE] = value.crossfadeSeconds; it[AUTOPLAY] = value.autoplay
                    it[QUALITY] = value.quality; it[CACHE_LIMIT] = value.cacheLimitMb
                }
                publish(updated)
            }
            _writeFailure.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The failure is observable: the UI rolls the optimistic value back
            // instead of showing a value that was never saved. The message is
            // the app's own: the message of the system is not for the user.
            reportDiagnostic("The audio settings could not be saved", error)
            _writeFailure.value = AudioWriteFailure(failures.incrementAndGet(), AUDIO_WRITE_FAILED_MESSAGE)
            throw error
        }
    }

    /** Publishes the result of a write: it is the newest value of the file. */
    private fun publish(read: Preferences) {
        synchronized(lock) {
            values.value = read
            _cacheLimit.value = CacheLimit(read(read).cacheLimitMb, true)
        }
    }

    /** Publishes a value that was read, unless a write has published a newer one. */
    private fun publishIfEmpty(read: Preferences) {
        synchronized(lock) {
            if (values.value == null) {
                values.value = read
                // The value is confirmed only when the file has answered: a
                // read that failed leaves the limit unconfirmed.
                _cacheLimit.value = CacheLimit(read(read).cacheLimitMb, confirmed = !readFailed)
            }
        }
    }

    /**
     * The value of a key, or null when the file holds a value of another type.
     *
     * The mismatch is remembered: the file is announced and the offending key
     * is removed, so the value cannot break the read again.
     */
    private fun Preferences.stringOf(key: Preferences.Key<String>): String? =
        valueOrNull(key) { markMismatch(key) }

    private fun Preferences.booleanOf(key: Preferences.Key<Boolean>): Boolean? =
        valueOrNull(key) { markMismatch(key) }

    private fun Preferences.intOf(key: Preferences.Key<Int>): Int? =
        valueOrNull(key) { markMismatch(key) }

    private fun Preferences.floatOf(key: Preferences.Key<Float>): Float? =
        valueOrNull(key) { markMismatch(key) }

    /** Remembers that a key of the file holds a value of another type. */
    private fun markMismatch(key: Preferences.Key<*>) {
        synchronized(lock) { mismatched.add(key) }
    }

    /**
     * Announces the values the file could not be read with, and removes them.
     *
     * The announcement is durable, and the key is removed from the file: a
     * value the app cannot read must not break the next read either, and the
     * user is told that the setting was dropped.
     */
    private suspend fun announceMismatches() {
        val keys = synchronized(lock) { mismatched.toList().also { mismatched.clear() } }
        if (keys.isEmpty()) return
        resets.announce(PreferenceFile.AUDIO, ResetCause.INCOMPATIBLE_VALUE)
        try {
            writeLock.withLock {
                val updated = store.edit { current -> keys.forEach { current.remove(it) } }
                publish(updated)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportDiagnostic("A value of another type could not be removed", error)
        }
    }

    private fun read(values: Preferences) = AudioSettings(
        values.booleanOf(EQ) ?: false, BANDS.map { key -> values.floatOf(key) ?: 0f },
        values.booleanOf(BASS) ?: false, values.floatOf(BASS_STRENGTH) ?: .3f,
        values.booleanOf(VIRTUALIZER) ?: false, values.floatOf(VIRTUALIZER_STRENGTH) ?: .3f,
        values.intOf(CROSSFADE) ?: 0, values.booleanOf(AUTOPLAY) ?: false,
        values.stringOf(QUALITY) ?: "high", values.intOf(CACHE_LIMIT) ?: DEFAULT_CACHE_LIMIT_MB).normalized()

    companion object {
        private val EQ = booleanPreferencesKey("eq_enabled")
        private val BANDS = List(5) { floatPreferencesKey("eq_band_$it") }
        private val BASS = booleanPreferencesKey("bass_enabled")
        private val BASS_STRENGTH = floatPreferencesKey("bass_strength")
        private val VIRTUALIZER = booleanPreferencesKey("virtualizer_enabled")
        private val VIRTUALIZER_STRENGTH = floatPreferencesKey("virtualizer_strength")
        private val CROSSFADE = intPreferencesKey("crossfade_seconds")
        private val AUTOPLAY = booleanPreferencesKey("autoplay")
        private val QUALITY = stringPreferencesKey("stream_quality")
        private val CACHE_LIMIT = intPreferencesKey("stream_cache_mb")
    }
}
