package ru.nedotify.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.Storage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which file of the preferences layer was replaced after a corruption. */
enum class PreferenceFile { SETTINGS, QUEUE, AUDIO }

/**
 * Why a file was reset.
 *
 * The cause is part of the notice: a file that was replaced and a file that
 * holds a value the app cannot read are different facts, and the user is
 * told which one happened.
 */
enum class ResetCause {
    /** The file could not be parsed: it was replaced with an empty one. */
    CORRUPT_FILE,

    /** The file holds a value of a type the app does not know. */
    INCOMPATIBLE_VALUE,
}

/**
 * The one-shot notice of a reset.
 *
 * A corrupt file is replaced with an empty one, so the values it held are
 * gone. The user must be told: a silent reset is indistinguishable from a
 * fresh install.
 *
 * The [id] tells two notices of the same file apart. A `StateFlow` conflates
 * equal values, so a second corruption of the same file would never reach the
 * user if the notice carried nothing but the file.
 */
data class PreferenceReset(
    val file: PreferenceFile,
    val id: Long = 0L,
    val cause: ResetCause = ResetCause.CORRUPT_FILE,
)

/** The text the app shows for a reset. */
fun PreferenceReset.message(): String = when (cause) {
    ResetCause.CORRUPT_FILE -> when (file) {
        PreferenceFile.SETTINGS -> "Настройки приложения сброшены: файл настроек был повреждён"
        PreferenceFile.QUEUE -> "Сохранённая очередь сброшена: файл очереди был повреждён"
        PreferenceFile.AUDIO -> "Настройки звука сброшены: файл настроек звука был повреждён"
    }
    ResetCause.INCOMPATIBLE_VALUE -> when (file) {
        PreferenceFile.SETTINGS -> "Часть настроек сброшена: файл настроек содержит несовместимое значение"
        PreferenceFile.QUEUE -> "Сохранённая очередь сброшена: файл очереди содержит несовместимое значение"
        PreferenceFile.AUDIO -> "Часть настроек звука сброшена: файл настроек звука содержит несовместимое значение"
    }
}

/** What the notice explains below its headline. */
fun PreferenceReset.detail(): String = when (cause) {
    ResetCause.CORRUPT_FILE -> when (file) {
        PreferenceFile.SETTINGS -> "Файл настроек был повреждён и заменён. Значения, которые в нём были, потеряны. Если очередь ещё хранилась в нём, она потеряна вместе с ними."
        PreferenceFile.QUEUE -> "Файл очереди был повреждён и заменён. Если очередь ещё хранилась в настройках, она восстановлена оттуда."
        PreferenceFile.AUDIO -> "Файл настроек звука был повреждён и заменён. Значения, которые в нём были, потеряны."
    }
    ResetCause.INCOMPATIBLE_VALUE ->
        "Файл содержит значение, которого приложение не понимает. Это значение сброшено, остальные значения сохранены."
}

/**
 * Where the preferences layer's diagnostics go.
 *
 * The layer is a **checked** boundary: the repair of a file and the migration
 * of the queue are the contract, and a contract must not depend on a side
 * effect that can fail. A log write is such a side effect:
 * `android.util.Log` is not implemented in a JVM unit test, and a log file
 * can be unwritable. The diagnostic is therefore an injected sink, and a sink
 * that fails is a *missing diagnostic*, never a failure of the repair.
 */
fun interface PreferenceDiagnostics {
    /** Something went wrong inside the layer. */
    fun onError(message: String, error: Throwable)
}

/** The production sink of the preferences layer: the app's log. */
object AppPreferenceLog : PreferenceDiagnostics {
    /** The tag the whole app writes under. */
    const val TAG = "NeDotify"

    override fun onError(message: String, error: Throwable) {
        Log.e(TAG, message, error)
    }
}

/**
 * The sink of the preferences layer.
 *
 * The production wiring supplies the app's log; a test supplies a recorder.
 * The default is the app's own log, so a caller that wires nothing still
 * gets the diagnostic in production.
 */
object PreferenceLog {
    /** The sink every diagnostic of the layer goes to. */
    @Volatile
    var diagnostics: PreferenceDiagnostics = AppPreferenceLog
}

/**
 * Reports a diagnostic of the layer.
 *
 * A diagnostic is a courtesy, never a part of a decision: a sink that fails
 * is a *missing diagnostic*, never a failure of the repair or of the
 * migration. The layer therefore never lets a diagnostic change its answer.
 */
internal fun reportDiagnostic(message: String, error: Throwable) {
    try { PreferenceLog.diagnostics.onError(message, error) }
    catch (failure: Exception) { /* the diagnostic is lost, the repair is not */ }
}

/**
 * Writes a small file atomically.
 *
 * The text is written next to the target and moved over it: a process that
 * dies in the middle leaves the old file, never a half-written one.
 */
internal fun writeFileAtomically(target: File, text: String) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, target.name + ".tmp")
    temp.writeText(text)
    try {
        java.nio.file.Files.move(temp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } catch (error: Exception) {
        if (!temp.renameTo(target)) throw error
    } finally {
        if (temp.exists()) temp.delete()
    }
}

/**
 * The durable record of the pending resets.
 *
 * The notice must outlive the process: the file that was repaired is the
 * evidence, and the notice is the only way the user learns that the evidence
 * is gone. The record is therefore a file of its own — it lives next to the
 * preferences files, but no corruption handler ever replaces it.
 */
internal class ResetLedger(private val file: File?) {
    /** The pending resets, oldest first. */
    fun pending(): List<PreferenceReset> = try {
        if (file == null || !file.exists()) emptyList()
        else file.readLines().mapNotNull { line -> decode(line) }
    } catch (error: Exception) {
        // A broken record must not break the app: the notices it holds are
        // shown again, the app is not stopped.
        emptyList()
    }

    /** Appends a reset: the notice is durable before the repair is handed back. */
    fun append(reset: PreferenceReset) {
        val target = file ?: return
        val lines = pending().map { encode(it) } + encode(reset)
        writeFileAtomically(target, lines.joinToString("\n"))
    }

    /** Removes the oldest reset: it was shown. */
    fun removeFirst() {
        val target = file ?: return
        val lines = pending().drop(1)
        writeFileAtomically(target, lines.joinToString("\n"))
    }

    private fun encode(reset: PreferenceReset) = "${reset.file.name}:${reset.cause.name}"

    private fun decode(line: String): PreferenceReset? {
        val parts = line.split(':')
        val file = PreferenceFile.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return null
        val cause = ResetCause.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: ResetCause.CORRUPT_FILE
        return PreferenceReset(file, cause = cause)
    }

    companion object {
        /** A record that is not written anywhere: the notices die with the process. */
        fun inMemory() = ResetLedger(null)
    }
}

/**
 * The signal of the preferences layer.
 *
 * The layer is process wide: a reset is a fact about the device, not about one
 * screen. The notice is shown once and then acknowledged.
 *
 * The channel is a **queue**, not a slot: the layer owns three files, and two
 * of them can be damaged. The second notice must not replace the first, and
 * two notices of the same file must both reach the user — a `StateFlow`
 * conflates equal values, so every notice carries its own [PreferenceReset.id].
 */
class PreferenceResets internal constructor(
    private val ledger: ResetLedger = ResetLedger.inMemory(),
) {
    private val lock = Any()

    /** The notices that are not shown yet, oldest first. */
    private val queue = ArrayDeque<PreferenceReset>()

    /** The number of the notices of this process: it keeps them apart. */
    private var sequence = 0L

    private val _pending = MutableStateFlow<PreferenceReset?>(null)

    /** The reset that is not shown yet, if any. */
    val pending: StateFlow<PreferenceReset?> = _pending.asStateFlow()

    private val _pendingAll = MutableStateFlow<List<PreferenceReset>>(emptyList())

    /** Every reset that is not shown yet, oldest first. */
    val pendingAll: StateFlow<List<PreferenceReset>> = _pendingAll.asStateFlow()

    init {
        // The notices of a previous process are restored: the evidence (the
        // repaired file) is gone, the notice must not be.
        ledger.pending().forEach { enqueue(it) }
    }

    /**
     * A file was replaced: the notice is durable before the repair is handed
     * back, so a process that dies in between does not lose the notice
     * together with the evidence.
     *
     * One notice per file: a file that is damaged twice before the user has
     * seen the first notice is one loss, not two.
     */
    internal suspend fun announce(file: PreferenceFile, cause: ResetCause = ResetCause.CORRUPT_FILE) {
        val reset = newReset(file, cause) ?: return
        // The notice is published **before** the record is written: a reader
        // that stops at the first value (a `first()`) must not miss it while
        // the record is being written.
        enqueue(reset)
        withContext(Dispatchers.IO) { record(reset) }
    }

    /**
     * The same notice, for a caller that cannot suspend.
     *
     * The corruption handler of a file is such a caller: the record is
     * written there, on the thread of the store, before the repair is handed
     * back. The file is repaired only after this call returns, so a process
     * that dies in between finds the damaged file again and announces it
     * again.
     */
    internal fun announceNow(file: PreferenceFile, cause: ResetCause = ResetCause.CORRUPT_FILE) {
        val reset = newReset(file, cause) ?: return
        enqueue(reset)
        record(reset)
    }

    /** The notice of a file, or null when the file is already announced. */
    private fun newReset(file: PreferenceFile, cause: ResetCause): PreferenceReset? = synchronized(lock) {
        if (queue.any { it.file == file }) null
        else PreferenceReset(file, id = ++sequence, cause = cause)
    }
    /** Writes the notice to the durable record. */
    private fun record(reset: PreferenceReset) {
        try { ledger.append(reset) }
        catch (error: Exception) { reportDiagnostic("A reset could not be recorded", error) }
    }

    /**
     * The notice was shown; it must not be shown a second time.
     *
     * The record is written here, on the caller's thread: the notice is shown
     * rarely and the file is tiny, and a record that is written later would
     * show the notice again after a restart.
     */
    fun acknowledge() {
        val head = synchronized(lock) {
            val removed = queue.removeFirstOrNull()
            publish()
            removed
        }
        if (head == null) return
        try { ledger.removeFirst() }
        catch (error: Exception) { reportDiagnostic("A reset could not be cleared", error) }
    }

    /** Publishes the head of the queue and the whole queue. */
    private fun publish() {
        _pending.value = queue.firstOrNull()
        _pendingAll.value = queue.toList()
    }

    /** Publishes a notice that was restored from the durable record. */
    private fun enqueue(reset: PreferenceReset) {
        synchronized(lock) {
            queue.addLast(reset.copy(id = ++sequence))
            publish()
        }
    }
}

/**
 * The three preferences files of the app.
 *
 * There is one instance of each file per process: DataStore refuses two
 * active stores over the same file. The class is the only place that knows
 * how a file is created, so the corruption policy and the reset notice cannot
 * be forgotten in one of them.
 */
internal object PreferencesLayer {
    /** The names on disk. They are the contract with an existing install. */
    const val SETTINGS_FILE = "nedotify"
    const val QUEUE_FILE = "nedotify_queue"
    const val AUDIO_FILE = "nedotify_audio"

    /** The record of the pending resets. It is never replaced. */
    const val RESETS_FILE = "nedotify_resets"

    /** The identity of the library. It is not a preferences file. */
    const val IDENTITY_FILE = "nedotify_library_id"

    /** The scope of the stores. It lives as long as the process. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private val stores = mutableMapOf<String, DataStore<Preferences>>()

    @Volatile
    private var processResets: PreferenceResets? = null

    /**
     * The signal of a reset. One instance for the whole process.
     *
     * The record of the pending notices lives in the app's own files, so a
     * notice survives the process that made it.
     */
    fun resets(context: Context): PreferenceResets = synchronized(lock) {
        processResets ?: PreferenceResets(ResetLedger(resetFile(context.applicationContext.filesDir)))
            .also { processResets = it }
    }

    fun settings(context: Context): DataStore<Preferences> = of(context, PreferenceFile.SETTINGS)

    fun queue(context: Context): DataStore<Preferences> = of(context, PreferenceFile.QUEUE)

    fun audio(context: Context): DataStore<Preferences> = of(context, PreferenceFile.AUDIO)

    /**
     * The file of a kind.
     *
     * The queue has its own file: it is the app's only durable session state,
     * and a corrupt settings file must not take it with it.
     */
    fun fileName(kind: PreferenceFile): String = when (kind) {
        PreferenceFile.SETTINGS -> SETTINGS_FILE
        PreferenceFile.QUEUE -> QUEUE_FILE
        PreferenceFile.AUDIO -> AUDIO_FILE
    }

    /**
     * The identity of the device's library.
     *
     * It is a file of its own: the settings file is replaced when it is
     * damaged, and the identity of the library must survive that — a new
     * identity would turn the device's own backup into another device's copy.
     */
    fun identityFile(context: Context): File =
        File(context.applicationContext.filesDir, IDENTITY_FILE)

    /**
     * The path of a preferences file.
     *
     * It is the path `preferencesDataStore` used, so the settings of an
     * existing install are still found after the upgrade.
     */
    fun storeFile(directory: File, name: String): File {
        val file = File(directory, "datastore/$name.preferences_pb")
        file.parentFile?.mkdirs()
        return file
    }

    /** The record of the pending resets. It is not a preferences file. */
    fun resetFile(directory: File): File {
        val file = File(directory, "datastore/$RESETS_FILE")
        file.parentFile?.mkdirs()
        return file
    }

    private fun of(context: Context, kind: PreferenceFile): DataStore<Preferences> {
        val file = storeFile(context.applicationContext.filesDir, fileName(kind))
        return synchronized(lock) {
            // The store is keyed by its path: two contexts of the same process
            // must not share a store over the first context's directory.
            stores[file.absolutePath] ?: createStore(kind, resets(context), scope, file).also { created ->
                stores[file.absolutePath] = created
                // The file is read in the background: the first reader of the app
                // does not wait for the disk.
                scope.launch {
                    try { created.data.first() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        reportDiagnostic("A preferences file could not be read", error)
                    }
                }
            }
        }
    }
}

/**
 * The store of one preferences file.
 *
 * This is the only place where a file is created, so the corruption policy
 * cannot be forgotten in one of them.
 *
 * The platform backend cannot be built outside the DataStore library, so the
 * test brings its own [storage]; the corruption policy is the same either
 * way.
 */
internal fun createStore(
    kind: PreferenceFile,
    resets: PreferenceResets,
    scope: CoroutineScope,
    file: File,
    storage: Storage<Preferences>? = null,
): DataStore<Preferences> {
    val handler = corruptionHandler(kind, resets)
    return if (storage == null) PreferenceDataStoreFactory.create(
        corruptionHandler = handler, scope = scope, produceFile = { file })
    else PreferenceDataStoreFactory.create(storage = storage, corruptionHandler = handler, scope = scope)
}

/**
 * The corruption policy of the layer.
 *
 * Without a handler DataStore substitutes its default, which rethrows the
 * corruption. The read side survives that (the caller catches the IOException
 * and shows the defaults), but the write path re-reads and re-throws the
 * failure forever: every setting change fails, the corrupt file is never
 * rewritten, and the state survives a restart.
 *
 * The handler replaces the file with an empty one and announces the reset, so
 * the app can tell the user that the settings were lost. The notice is
 * written to the durable record **before** the replacement is handed back: a
 * process that dies in between must not lose the notice together with the
 * evidence.
 */
internal fun corruptionHandler(file: PreferenceFile, resets: PreferenceResets): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler {
        resets.announceNow(file, ResetCause.CORRUPT_FILE)
        emptyPreferences()
    }

/**
 * The value of a key, or null when the file holds a value of another type.
 *
 * A file written by another version of the app (or edited by hand) can hold a
 * value of the wrong type. The proto itself is valid, so the corruption
 * handler never fires, but the typed read throws a `ClassCastException` — an
 * unchecked exception that the `IOException` fallback of the read does not
 * catch. The key is treated as absent and the mismatch is reported, so the
 * file can be announced and repaired.
 */
internal inline fun <reified T> Preferences.valueOrNull(
    key: Preferences.Key<T>,
    onMismatch: () -> Unit = {},
): T? = try {
    // The value is read as `Any` and cast inside the guard: the cast is what
    // fails for a value of another type.
    @Suppress("UNCHECKED_CAST")
    val raw: Any? = this[key]
    raw as T?
} catch (error: ClassCastException) {
    onMismatch()
    null
}
