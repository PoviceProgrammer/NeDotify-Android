package ru.nedotify.app.data

import java.util.Locale

/**
 * The pure preflight of a restore: it decides what the merge does before a single row is written.
 *
 * The identity model has two levels, and both are needed:
 *
 * * A track of **this** device keeps its own portable identity (the library id plus the row id).
 *   A re-import of the same library therefore merges into the same row and keeps the file grant,
 *   the "added at" and the title the user sees.
 * * A track of **another** device arrives with a different portable identity, because the identity
 *   is scoped to the library that produced it. Such a track is recognised by its **metadata**:
 *   the source of a local track is the constant "local" and can never identify anything, so the
 *   source comparison alone could never fire. Without this second level a restore from another
 *   device produced a second row for every local track — a duplicate that no UI could remove.
 *
 * The metadata comparison is deliberately narrow: the same title, artist, album and duration. An
 * ambiguous match (two rows of this device with the same metadata) is **not** merged; the track is
 * added instead, so nothing is ever silently lost.
 *
 * The counters of the imported rows (`importedPlayCount`, `importedLastPlayed`) are combined with
 * `maxOf`, not summed. The same copy may be imported more than once - the merge is idempotent -
 * and a sum would count every session of the desktop twice. An import therefore never lowers the
 * counters of the device and never invents a higher one.
 */
object BackupMerge {
    private val whitespace = Regex("\\s+")

    /** The identity of a recording: its metadata, normalised. */
    internal fun recordingKey(track: Track): String =
        listOf(track.title, track.artist, track.album, track.durationMs.toString())
            .joinToString("\u0000") { normalise(it) }

    private fun normalise(value: String): String =
        whitespace.replace(value.trim(), " ").lowercase(Locale.ROOT)

    fun plan(existing: BackupSnapshot, backup: LibraryBackup, libraryId: String): BackupSnapshot {
        val tracks = existing.tracks.associateBy { it.id }.toMutableMap()
        val localRows = existing.tracks.filter { it.source == "local" }
        // The rows of this device: the portable identity is the library id plus the row id.
        val localIds = localRows.groupBy { BackupCodec.portableTrackId(it, libraryId) }
        if (localIds.any { it.value.size != 1 }) throw SourceFailure("Неоднозначные локальные записи в библиотеке")
        // The rows of another device: the recording is recognised by its metadata.
        val byMetadata = localRows.filter { matchable(it) }.groupBy { recordingKey(it) }
        val aliases=existing.localAliases.associate {it.portableId to it.trackId}
        val claimed = HashSet<String>()
        val remap = HashMap<String, String>()
        val sameOrigin = HashSet<String>()
        backup.tracks.forEach { incoming ->
            if (incoming.source != "local") {
                // The identity of a remote track is its own id ("youtube:<id>"): the device and
                // the copy name the same recording. A row the device already has is therefore
                // the device's own row, and the policy of the merge - the library row wins -
                // applies: an older copy of the device's own track may not roll its current
                // title, artist, album and duration back.
                remap[incoming.id] = incoming.id
                if (tracks.containsKey(incoming.id)) sameOrigin.add(incoming.id)
                return@forEach
            }
            val own = localIds[incoming.id]?.singleOrNull() ?: aliases[incoming.id]?.let {tracks[it]}
            if (own != null) {
                remap[incoming.id] = own.id
                sameOrigin.add(incoming.id)
                claimed.add(own.id)
                return@forEach
            }
            val candidate = if(matchable(incoming))byMetadata[recordingKey(incoming)]?.singleOrNull() else null
            if (candidate != null && claimed.add(candidate.id)) remap[incoming.id] = candidate.id
            else remap[incoming.id] = incoming.id
        }
        val changedTracks = backup.tracks.map { incoming ->
            val id = remap.getValue(incoming.id)
            val old = tracks[id]
            if (old != null && (old.source != incoming.source || old.sourceId != incoming.sourceId))
                throw SourceFailure("Конфликт источника трека в резервной копии")
            val merged = when {
                old == null -> incoming.copy(id = id, localUri = "")
                // The same device: the library row wins. The import may add a favourite, never
                // take one away, and it never revokes the file grant of the device.
                incoming.id in sameOrigin -> old.copy(favorite = old.favorite || incoming.favorite,
                    importedPlayCount=maxOf(old.importedPlayCount,incoming.importedPlayCount),
                    importedLastPlayed=maxOf(old.importedLastPlayed,incoming.importedLastPlayed),
                    importedDownloaded=old.importedDownloaded || incoming.importedDownloaded)
                // Another device's copy of the same recording: the imported metadata wins, so the
                // track does appear; the device keeps its file grant and the moment it entered the
                // library.
                else -> old.copy(title = incoming.title, artist = incoming.artist, album = incoming.album,
                    durationMs = incoming.durationMs, favorite = old.favorite || incoming.favorite,
                    importedPlayCount=maxOf(old.importedPlayCount,incoming.importedPlayCount),
                    importedLastPlayed=maxOf(old.importedLastPlayed,incoming.importedLastPlayed),
                    importedDownloaded=old.importedDownloaded || incoming.importedDownloaded)
            }
            merged.also { tracks[id] = it }
        }
        val playlists = existing.playlists.associateBy { it.id }.toMutableMap()
        val links = existing.links.groupBy { it.playlistId }
        val newLinks = mutableListOf<PlaylistTrack>()
        backup.playlists.forEach { entry ->
            playlists.putIfAbsent(entry.playlist.id, entry.playlist)
            val current = links[entry.playlist.id].orEmpty()
            val seen = current.map { it.trackId }.toMutableSet()
            var position = current.maxOfOrNull { it.position }?.let {
                if (it == Int.MAX_VALUE) throw SourceFailure("Слишком большой плейлист")
                it + 1
            } ?: 0
            entry.trackIds.forEach { portableId ->
                val id = remap[portableId] ?: throw SourceFailure("Плейлист ссылается на отсутствующий трек")
                if (seen.add(id)) {
                    if (position == Int.MAX_VALUE) throw SourceFailure("Слишком большой плейлист")
                    newLinks += PlaylistTrack(entry.playlist.id, id, position++)
                }
            }
        }
        val sessions = existing.history.associateBy { it.id }
        val history = backup.history.map { incoming ->
            val id = remap[incoming.trackId] ?: throw SourceFailure("История ссылается на отсутствующий трек")
            val old = sessions[incoming.id]
            if (old != null && (old.trackId != id || old.startedAt != incoming.startedAt))
                throw SourceFailure("Конфликт сессии прослушивания в резервной копии")
            incoming.copy(trackId = id, listenedMs = maxOf(incoming.listenedMs, old?.listenedMs ?: 0),
                completed = incoming.completed || old?.completed == true)
        }
        val existingSmart = existing.smartPlaylists.associateBy { it.id }
        val smart = backup.smartPlaylists.map { incoming ->
            SmartRules.decode(incoming.rulesJson)
            existingSmart[incoming.id] ?: incoming
        }
        val newAliases=backup.tracks.filter {it.source=="local"}.map {LocalTrackAlias(it.id,remap.getValue(it.id))}
        return BackupSnapshot(changedTracks, backup.playlists.map { playlists.getValue(it.playlist.id) }, newLinks, history, smart,newAliases)
    }

    /** A recording that may be recognised by its metadata alone. */
    internal fun matchable(track:Track)=track.durationMs>0 && track.title.isNotBlank() &&
        normalise(track.title)!="без названия" && normalise(track.artist) !in
        setOf("","unknown artist","unknown","неизвестный исполнитель","<unknown>")
}
