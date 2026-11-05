package ru.nedotify.app.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONTokener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Whether the write-ahead log of the desktop copy could be read.
 *
 * The desktop stores its newest writes in a `-wal` sidecar next to the database. A copy of the
 * `.db` alone is **valid but stale**: the newest track and the whole history are missing, without
 * a word. The importer therefore reads the sidecar when the provider exposes it.
 */
enum class DesktopWalState { NONE, COPIED, ABSENT }

class DesktopLibraryImport(private val context: Context) {
    suspend fun inspectDocuments(uris:List<Uri>,origin:String):DesktopImportPreview=withContext(Dispatchers.IO) {
        val selected=uris.distinct()
        if(selected.size !in 1..2)throw SourceFailure("Выберите один файл .db и, при наличии, его файл .db-wal")
        val named=selected.map {uri->
            if(uri.scheme!="content")throw SourceFailure("Выберите копию БД через системное окно")
            val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                if(it.moveToFirst())it.getString(0).orEmpty() else ""
            }.orEmpty()
            uri to name
        }
        val main=named.singleOrNull {!it.second.endsWith("-wal",true)}
            ?:throw SourceFailure("Выберите файл .db вместе с журналом WAL")
        val wal=named.singleOrNull {it.second.endsWith("-wal",true)}
        if(selected.size==2 && (wal==null || wal.second!=main.second+"-wal"))
            throw SourceFailure("Имя журнала должно совпадать с именем БД и заканчиваться на -wal")
        inspect(main.first,origin,wal?.first)
    }

    suspend fun inspect(uri: Uri, origin: String,walUri:Uri?=null): DesktopImportPreview = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw SourceFailure("Выберите копию БД через системное окно")
        val name = "desktop-import-${UUID.randomUUID()}"
        val temp = File(context.cacheDir, "$name.db")
        val sidecar = File(context.cacheDir, "$name.db-wal")
        try {
            copyDocument(uri, temp)
            ensureActive()
            if (!headerOk(temp)) throw SourceFailure("Выбран не файл SQLite")
            val wasWal=isWal(temp)
            if(walUri!=null) {
                if(walUri.scheme!="content")throw SourceFailure("Выберите журнал через системное окно")
                copyDocument(walUri,sidecar);ensureActive();DesktopWalValidator.validate(temp,sidecar)
            }
            // Opening the copy read-write lets SQLite replay the journal into the copy, so the
            // import sees the committed data instead of a stale snapshot.
            recover(temp)
            val coroutine=currentCoroutineContext()
            readSnapshot(temp, origin, if(walUri!=null)DesktopWalState.COPIED else if(wasWal)DesktopWalState.ABSENT else DesktopWalState.NONE) {coroutine.ensureActive()}
        } catch (error: java.util.concurrent.CancellationException) { throw error }
        catch (error: SourceFailure) { throw error }
        catch (_: OutOfMemoryError) {
            // An allocation failure is an `Error`, not an `Exception`: it used to escape every
            // `catch (Exception)` and killed the process. A copy that does not fit is a failed
            // import, not a crash.
            throw SourceFailure("Не удалось прочитать БД. Копия слишком велика для памяти устройства")
        }
        catch (_: Exception) {
            throw SourceFailure("Не удалось прочитать БД. Закройте настольный плеер и выберите полную копию NeDotify .db")
        }
        finally {
            temp.delete(); sidecar.delete()
            File(context.cacheDir, "$name.db-shm").delete()
        }
    }

    /** Only fixed, allowlisted columns are queried; source DB is never modified. */
    internal fun readSnapshot(file: File, origin: String, wal: DesktopWalState = DesktopWalState.NONE,checkActive:()->Unit={}): DesktopImportPreview =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            val check = db.rawQuery("PRAGMA quick_check(1)", null).use { it.moveToFirst() && it.getString(0) == "ok" }
            if (!check) throw SourceFailure("Копия БД повреждена")
            val journal = db.rawQuery("PRAGMA journal_mode", null).use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
            fun rows(table: String, columns: List<String>, maximum: Int, required: Boolean = true): List<Map<String, Any?>> {
                val exists = db.rawQuery("SELECT type FROM sqlite_master WHERE name=?", arrayOf(table)).use { it.moveToFirst() && it.getString(0) == "table" }
                if (!exists) { if (required) throw SourceFailure("В копии нет таблицы $table") else return emptyList() }
                val available = db.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) } }
                val identityColumns=if(table=="playlist_tracks")setOf("playlist_id","track_id") else setOf("id")
                if(!available.containsAll(identityColumns))throw SourceFailure("Неподдерживаемая схема БД ПК: нет идентификаторов в $table")
                val selected = columns.filter { it in available }
                if (selected.isEmpty()) throw SourceFailure("Неподдерживаемая схема БД ПК")
                // The identifiers come from the untrusted copy: they are checked against a strict
                // allow-list and quoted before they reach the SQL text.
                selected.forEach { if (!identifier.matches(it)) throw SourceFailure("Неподдерживаемая схема БД ПК") }
                val projection = selected.joinToString(",") { "\"$it\"" }
                val table0=DesktopRows.of(selected)
                return db.rawQuery("SELECT $projection FROM \"$table\" LIMIT ${maximum + 1}", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            checkActive()
                            if (size >= maximum) throw SourceFailure("В таблице $table слишком много записей")
                            val cells=arrayOfNulls<Any?>(selected.size)
                            selected.indices.forEach { index -> cells[index] = when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                else -> null
                            } }
                            add(table0.row(cells))
                        }
                    }
                }
            }
            val trackColumns = DesktopColumns.FULL.tracks.toList()
            val playlistColumns = listOf("id", "name", "created_at", "is_smart", "smart_rules_json")
            val linkColumns = listOf("playlist_id", "track_id", "position")
            val historyColumns = listOf("id", "track_id", "played_at", "duration_listened", "listening_measured", "completed")
            val smartColumns = listOf("id", "name", "rule_json", "created_at")
            val columns = DesktopColumns(
                tracks = availableColumns(db, "tracks", trackColumns),
                playlists = availableColumns(db, "playlists", playlistColumns),
                playlistTracks = availableColumns(db, "playlist_tracks", linkColumns),
                history = availableColumns(db, "history", historyColumns),
                smartPlaylists = availableColumns(db, "smart_playlists", smartColumns),
            )
            val preview = DesktopLibraryConverter.convert(origin,
                rows("tracks", trackColumns, 20_000),
                rows("playlists", playlistColumns, 2_000),
                rows("playlist_tracks", linkColumns, 100_000),
                rows("history", historyColumns, 100_000, required = false),
                System.currentTimeMillis(),
                rows("smart_playlists", smartColumns, 100, required = false),
                columns,readSourceIdentity(db))
            val notice = buildList {
                add(preview.notice)
                if (wal!=DesktopWalState.NONE || journal.equals("wal", true)) {
                    if (wal == DesktopWalState.COPIED)
                        add("Журнал WAL прочитан вместе с копией: самые новые записи ПК перенесены.")
                    else if (wal == DesktopWalState.ABSENT)
                        add("Копия БД сделана в режиме WAL, а соответствующий файл .db-wal не был выбран. " +
                            "Самые новые записи ПК могут отсутствовать: закройте плеер на ПК и скопируйте файл .db заново.")
                }
            }.joinToString("\n\n")
            return DesktopImportPreview(preview.backup, notice)
        }

    /** The columns a table really has; a table that is not there at all has none. */
    private fun availableColumns(db: SQLiteDatabase, table: String, columns: List<String>): Set<String> {
        val exists = db.rawQuery("SELECT type FROM sqlite_master WHERE name=?", arrayOf(table)).use { it.moveToFirst() && it.getString(0) == "table" }
        if (!exists) return emptySet()
        val available = db.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        return columns.filter { it in available }.toSet()
    }

    private suspend fun copyDocument(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024); var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break
                total += count; if (total > 128L * 1024 * 1024) throw SourceFailure("Копия БД больше 128 МБ")
                output.write(buffer, 0, count)
            }
        } } ?: throw SourceFailure("Не удалось прочитать копию ПК")
    }

    /** Lets SQLite replay the journal of the copy into the copy itself. */
    private fun recover(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use {
                if(it.moveToFirst() && it.getInt(0)!=0)throw SourceFailure("Не удалось применить журнал WAL. Создайте копию после закрытия плеера на ПК")
            }
            // API 26/27 SQLite cannot open a WAL database read-only. Convert only our private copy.
            db.rawQuery("PRAGMA journal_mode=DELETE",null).use {
                if(!it.moveToFirst() || !it.getString(0).equals("delete",true))throw SourceFailure("Не удалось подготовить копию БД ПК")
            }
        }
    }

    private fun readSourceIdentity(db:SQLiteDatabase):String? {
        val columns=availableColumns(db,"settings",listOf("key","value"))
        if(!columns.containsAll(listOf("key","value")))return null
        return db.rawQuery("SELECT \"value\" FROM \"settings\" WHERE \"key\"=? LIMIT 1",arrayOf("portable_library_id")).use {
            if(!it.moveToFirst())null else runCatching {JSONTokener(it.getString(0)).nextValue() as? String}.getOrNull()
        }
    }

    private fun headerOk(file: File): Boolean {
        val header = file.inputStream().use { input -> ByteArray(16).also { buffer ->
            var offset = 0
            while (offset < buffer.size) { val count = input.read(buffer, offset, buffer.size - offset); if (count < 0) break; offset += count }
        } }
        return header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
    }

    /**
     * Whether the copy of the database is in the write-ahead log mode.
     *
     * The read must be complete: a single `read()` may return fewer bytes than asked for, and
     * a header that was read half way would look like a database without the log.
     */
    private fun isWal(file: File): Boolean {
        val header = file.inputStream().use { input -> ByteArray(20).also { buffer ->
            var offset = 0
            while (offset < buffer.size) { val count = input.read(buffer, offset, buffer.size - offset); if (count < 0) break; offset += count }
        } }
        if (header.size < 20) return false
        return header[18].toInt() == 2 || header[19].toInt() == 2
    }

    companion object {
        /** The identifiers that may reach the SQL text of the copy. */
        private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
