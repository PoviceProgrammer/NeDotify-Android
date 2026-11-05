package ru.nedotify.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.ensureActive

@Entity(tableName = "tracks", indices = [Index("source"), Index("favorite"), Index("addedAt")])
data class Track(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String = "Неизвестный исполнитель",
    val album: String = "",
    val durationMs: Long = 0,
    val source: String,
    val sourceId: String = "",
    val sourceUrl: String = "",
    val localUri: String = "",
    val coverUrl: String = "",
    val favorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val mimeType: String = "",
    @ColumnInfo(defaultValue = "0") val importedPlayCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val importedLastPlayed: Long = 0,
    @ColumnInfo(defaultValue = "0") val importedDownloaded: Boolean = false,
)

/** A verified fallback survives process death; it never makes the track available offline. */
@Entity(tableName = "provider_mappings", primaryKeys = ["trackId", "source"],
    foreignKeys = [ForeignKey(entity=Track::class,parentColumns=["id"],childColumns=["trackId"],onDelete=ForeignKey.CASCADE)])
data class ProviderMapping(val trackId:String,val source:String,val sourceId:String,
    val title:String,val artist:String,val album:String,val durationMs:Long) {
    fun candidate()=Track("$source:$sourceId",title,artist,album,durationMs,source,sourceId)
    companion object {
        fun verified(track:Track,candidate:Track):ProviderMapping? =
            ResolutionIdentity.admitted(track,listOf(candidate))?.let {
                ProviderMapping(track.id,it.source,it.sourceId,it.title,it.artist,it.album,it.durationMs)
            }
    }
}

@Entity(tableName="local_track_aliases",indices=[Index("trackId")],
    foreignKeys=[ForeignKey(entity=Track::class,parentColumns=["id"],childColumns=["trackId"],onDelete=ForeignKey.CASCADE)])
data class LocalTrackAlias(@PrimaryKey val portableId:String,val trackId:String)

@Entity(tableName = "playlists")
data class Playlist(@PrimaryKey val id: String, val name: String, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "smart_playlists")
data class SmartPlaylist(@PrimaryKey val id: String, val name: String, val rulesJson: String,
    val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [ForeignKey(entity=Playlist::class,parentColumns=["id"],childColumns=["playlistId"],onDelete=ForeignKey.CASCADE),
        ForeignKey(entity=Track::class,parentColumns=["id"],childColumns=["trackId"],onDelete=ForeignKey.CASCADE)],
    indices = [Index("trackId")])
data class PlaylistTrack(val playlistId: String, val trackId: String, val position: Int)

@Entity(tableName = "history", indices = [Index("trackId"),Index("startedAt")])
data class ListeningSession(@PrimaryKey val id: String, val trackId: String, val startedAt: Long,
    val listenedMs: Long, val completed: Boolean)

data class PlaylistWithCount(@Embedded val playlist: Playlist, val count: Int)

/** A consistent Room snapshot; file parsing and JSON work happen outside the transaction. */
data class BackupSnapshot(val tracks:List<Track>,val playlists:List<Playlist>,
    val links:List<PlaylistTrack>,val history:List<ListeningSession>,val smartPlaylists:List<SmartPlaylist> = emptyList(),
    val localAliases:List<LocalTrackAlias> = emptyList(),val totalSessions:Int=history.size)
data class ResolvedStream(val url: String, val mimeType: String? = null, val expiresAt: Long = 0)
data class SearchTrack(val id:String,val title:String,val artist:String,val album:String="")

@Entity(tableName="offline_downloads",foreignKeys=[ForeignKey(entity=Track::class,parentColumns=["id"],childColumns=["trackId"],onDelete=ForeignKey.CASCADE)])
data class OfflineDownload(@PrimaryKey val trackId:String,val generation:String,val state:String="queued",
    val bytes:Long=0,val total:Long=-1,val percent:Float=-1f,val error:String="",
    val uri:String="",val mimeType:String="",val updatedAt:Long=System.currentTimeMillis())

data class DownloadWithTrack(@Embedded val download:OfflineDownload,
    @Relation(parentColumn="trackId",entityColumn="id") val track:Track)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC,id") fun observeTracks(): kotlinx.coroutines.flow.Flow<List<Track>>
    @Query("SELECT COUNT(*) FROM tracks WHERE favorite=1") fun observeFavoriteCount():kotlinx.coroutines.flow.Flow<Int>
    @Query("SELECT * FROM tracks WHERE id=:id") suspend fun track(id: String): Track?
    @Query("SELECT * FROM tracks WHERE id IN (:ids)") suspend fun tracks(ids:List<String>):List<Track>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun ensureTracks(tracks:List<Track>)
    @Upsert suspend fun put(track: Track)
    @Transaction suspend fun remember(track:Track) {putTrack(track)}
    @Transaction suspend fun rememberSession(track:Track,session:ListeningSession) {
        if(DeletedTracks.isDeleted(track.id))return
        remember(track);putSession(session)
    }
    @Upsert suspend fun putAll(tracks: List<Track>)
    /**
     * A scan of the media store refreshes the rows the library has and adds the ones it does
     * not have. A row the user deleted is not added again: the delete took its history, its
     * playlist entries and its offline row away, and the row alone would be a half-restored
     * track.
     */
    @Transaction suspend fun refresh(tracks:List<Track>) {
        val alive=tracks.filter { !DeletedTracks.isDeleted(it.id) }
        if(alive.isEmpty())return
        val old=alive.map {it.id}.chunked(900).flatMap {this.tracks(it)}.associateBy {it.id}
        putAll(alive.map {item->item.preservingLibraryState(old[item.id],refreshLocalMetadata=true)})
    }
    @Transaction suspend fun favorite(track:Track) {
        if(DeletedTracks.isDeleted(track.id))return
        ensureTracks(listOf(track));toggleFavorite(track.id)
    }
    @Query("UPDATE tracks SET favorite=NOT favorite WHERE id=:id") suspend fun toggleFavorite(id: String)
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC,id") suspend fun allTracks(): List<Track>
    @Query("SELECT * FROM provider_mappings WHERE trackId=:id ORDER BY source") suspend fun providerMappings(id:String):List<ProviderMapping>
    @Upsert suspend fun putProviderMapping(mapping:ProviderMapping)
    @Query("SELECT * FROM local_track_aliases") suspend fun localTrackAliases():List<LocalTrackAlias>
    @Upsert suspend fun putLocalTrackAliases(aliases:List<LocalTrackAlias>)
    @Query("DELETE FROM history WHERE trackId=:id") suspend fun deleteTrackHistory(id:String)
    @Query("DELETE FROM tracks WHERE id=:id") suspend fun deleteTrackRow(id:String)
    @Transaction suspend fun deleteTrack(id:String) { deleteTrackHistory(id);deleteTrackRow(id) }
    @Query("SELECT * FROM playlists ORDER BY createdAt,id") suspend fun allPlaylists():List<Playlist>
    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId,position,trackId") suspend fun allPlaylistLinks():List<PlaylistTrack>
    @Query("SELECT * FROM history ORDER BY startedAt,id") suspend fun allSessions():List<ListeningSession>
    @Query("SELECT * FROM history WHERE id IN (:ids)") suspend fun sessions(ids:List<String>):List<ListeningSession>
    @Query("SELECT * FROM history ORDER BY startedAt DESC,id DESC LIMIT 100000") suspend fun exportSessions():List<ListeningSession>
    @Query("SELECT COUNT(*) FROM history") suspend fun sessionCount():Int
    @Query("SELECT * FROM smart_playlists ORDER BY createdAt DESC,id")
    fun smartPlaylists():kotlinx.coroutines.flow.Flow<List<SmartPlaylist>>
    @Query("SELECT * FROM smart_playlists ORDER BY createdAt,id") suspend fun allSmartPlaylists():List<SmartPlaylist>
    @Query("SELECT * FROM smart_playlists WHERE id=:id")
    fun observeSmartPlaylist(id:String):kotlinx.coroutines.flow.Flow<SmartPlaylist?>
    @Upsert suspend fun putSmartPlaylist(playlist:SmartPlaylist)
    @Query("UPDATE smart_playlists SET name=:name,rulesJson=:rules WHERE id=:id")
    suspend fun editSmartPlaylist(id:String,name:String,rules:String):Int
    @Query("DELETE FROM smart_playlists WHERE id=:id") suspend fun deleteSmartPlaylist(id:String)
    @Query(DISCOVERY_CANDIDATES_SQL)
    fun observeDiscoveryCandidates(now:Long):kotlinx.coroutines.flow.Flow<List<DiscoveryCandidate>>
    @RawQuery(observedEntities=[Track::class,ListeningSession::class,OfflineDownload::class])
    fun observeDiscoveryChanges(query:androidx.sqlite.db.SupportSQLiteQuery):kotlinx.coroutines.flow.Flow<Int>
    @Query(DISCOVERY_CANDIDATES_SQL) suspend fun allDiscoveryCandidates(now:Long):List<DiscoveryCandidate>
    @Query(DISCOVERY_CANDIDATES_SQL + " LIMIT 5000") suspend fun discoveryCandidates(now:Long):List<DiscoveryCandidate>
    @Transaction suspend fun backupSnapshot():BackupSnapshot =
        BackupSnapshot(allTracks(),allPlaylists(),allPlaylistLinks(),allSessions(),allSmartPlaylists(),localTrackAliases())
    @Transaction suspend fun exportSnapshot():BackupSnapshot =
        BackupSnapshot(allTracks(),allPlaylists(),allPlaylistLinks(),exportSessions(),allSmartPlaylists(),totalSessions=sessionCount())
    @Transaction suspend fun restoreBackup(backup:LibraryBackup,libraryId:String) {
        // Preflight every collision before writing; Room rolls the whole merge back on failure.
        val relevantHistory=backup.history.map {it.id}.chunked(900).flatMap {sessions(it)}
        val plan=BackupMerge.plan(BackupSnapshot(allTracks(),allPlaylists(),allPlaylistLinks(),relevantHistory,
            allSmartPlaylists(),localTrackAliases()),backup,libraryId)
        // Preflight already validates every collision; bulk inserts avoid per-row SELECT/UPDATE.
        writeBatches(plan.tracks.chunked(250)) { putAll(it) }
        writeBatches(plan.playlists.chunked(250)) { putPlaylists(it) }
        writeBatches(plan.links.chunked(500)) { addLinks(it) }
        writeBatches(plan.history.chunked(500)) { putSessions(it) }
        writeBatches(plan.smartPlaylists.chunked(250)) { putSmartPlaylists(it) }
        putLocalTrackAliases(plan.localAliases)
        // The restore brings the rows of the backup back into the library: a track the user
        // deleted before the restore is in the library again, and the tombstone of the delete
        // must not survive it.
        plan.tracks.forEach { DeletedTracks.restore(it.id) }
    }
    @Query("UPDATE tracks SET localUri=:uri,mimeType=:mime WHERE id=:id AND source='local'")
    suspend fun attachLocal(id:String,uri:String,mime:String):Int
    @Query("SELECT id,title,artist,album FROM tracks WHERE localUri!='' OR id IN (SELECT trackId FROM offline_downloads WHERE state='complete') ORDER BY addedAt DESC,id LIMIT :limit OFFSET :offset")
    suspend fun searchPage(limit:Int,offset:Int):List<SearchTrack>
    @Query("SELECT p.*,COUNT(pt.trackId) AS count FROM playlists p LEFT JOIN playlist_tracks pt ON pt.playlistId=p.id GROUP BY p.id ORDER BY p.createdAt DESC,p.id")
    fun playlists(): kotlinx.coroutines.flow.Flow<List<PlaylistWithCount>>
    @Query("SELECT t.* FROM tracks t JOIN playlist_tracks pt ON t.id=pt.trackId WHERE pt.playlistId=:id ORDER BY pt.position,t.id")
    suspend fun playlistTracks(id: String): List<Track>
    @Query("SELECT t.* FROM tracks t JOIN playlist_tracks pt ON t.id=pt.trackId WHERE pt.playlistId=:id ORDER BY pt.position,t.id")
    fun observePlaylist(id:String):kotlinx.coroutines.flow.Flow<List<Track>>
    @Upsert suspend fun putPlaylist(playlist: Playlist)
    @Upsert suspend fun putPlaylists(playlists:List<Playlist>)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun addLinks(links:List<PlaylistTrack>)
    @Upsert suspend fun putSessions(sessions:List<ListeningSession>)
    @Upsert suspend fun putSmartPlaylists(playlists:List<SmartPlaylist>)
    @Query("SELECT COALESCE(MAX(position),-1)+1 FROM playlist_tracks WHERE playlistId=:id") suspend fun nextPosition(id:String):Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addLink(link: PlaylistTrack)
    @Transaction suspend fun addToPlaylist(playlistId: String, track: Track) {
        if(DeletedTracks.isDeleted(track.id))return
        putTrack(track)
        addLink(PlaylistTrack(playlistId,track.id,nextPosition(playlistId)))
    }
    /** Resolve the source outside Room, then commit the playlist and its ordered tracks together. */
    @Transaction suspend fun importPlaylist(playlist:Playlist, tracks:List<Track>) {
        putPlaylist(playlist)
        val distinct=tracks.distinctBy {it.id}
        var position=nextPosition(playlist.id)
        if(position<0 || position.toLong()+distinct.size>Int.MAX_VALUE)
            throw SourceFailure("Слишком большой плейлист")
        distinct.forEach {track->
            if(DeletedTracks.isDeleted(track.id))return@forEach
            putTrack(track)
            addLink(PlaylistTrack(playlist.id,track.id,position++))
        }
    }
    @Query("DELETE FROM playlists WHERE id=:id") suspend fun deletePlaylist(id:String)
    @Query("DELETE FROM playlist_tracks WHERE playlistId=:playlistId AND trackId=:trackId")
    suspend fun removeFromPlaylist(playlistId:String,trackId:String):Int
    @Query("UPDATE playlists SET name=:name WHERE id=:id") suspend fun renamePlaylist(id:String,name:String)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertSession(session:ListeningSession)
    @Query("SELECT * FROM history WHERE id=:id") suspend fun session(id:String):ListeningSession?
    @Query("UPDATE history SET listenedMs=MAX(listenedMs,:listenedMs),completed=MAX(completed,:completed) WHERE id=:id")
    suspend fun advanceSession(id:String,listenedMs:Long,completed:Boolean)
    @Transaction suspend fun putSession(session:ListeningSession) {
        if(session.id.isBlank() || session.trackId.isBlank() || session.startedAt<0 || session.listenedMs<0)
            throw SourceFailure("Некорректная запись прослушивания")
        val existing=this.session(session.id)
        if(existing!=null && (existing.trackId!=session.trackId || existing.startedAt!=session.startedAt))
            throw SourceFailure("Конфликт идентификатора прослушивания")
        insertSession(session);advanceSession(session.id,session.listenedMs,session.completed)
    }
    @Query("SELECT COALESCE(SUM(listenedMs),0) FROM history") fun listenedMs(): kotlinx.coroutines.flow.Flow<Long>
    @RawQuery(observedEntities = [ListeningSession::class, Track::class])
    fun observeReportChanges(query: androidx.sqlite.db.SupportSQLiteQuery): kotlinx.coroutines.flow.Flow<Int>
    @Query("""SELECT COALESCE(SUM(listenedMs),0) AS totalMs, COUNT(*) AS sessions,
        COALESCE(SUM(CASE WHEN completed THEN 1 ELSE 0 END),0) AS completedSessions,
        COUNT(DISTINCT trackId) AS uniqueTracks FROM history
        WHERE startedAt >= :start AND startedAt <= :end AND listenedMs > 0""")
    suspend fun reportTotals(start: Long, end: Long): ReportTotals
    @RawQuery suspend fun reportDays(query: androidx.sqlite.db.SupportSQLiteQuery): List<ReportDaySum>
    @Query("""SELECT t.*, SUM(h.listenedMs) AS listenedMs, COUNT(*) AS sessions
        FROM history h JOIN tracks t ON t.id=h.trackId
        WHERE h.startedAt >= :start AND h.startedAt <= :end AND h.listenedMs > 0
        GROUP BY h.trackId ORDER BY listenedMs DESC, h.trackId LIMIT 10""")
    suspend fun reportTopTracks(start: Long, end: Long): List<RankedTrack>
    @Query("""SELECT CASE WHEN t.artist IS NULL OR TRIM(t.artist)='' THEN 'Неизвестный исполнитель'
        ELSE TRIM(t.artist) END AS name, SUM(h.listenedMs) AS listenedMs, COUNT(*) AS sessions
        FROM history h LEFT JOIN tracks t ON t.id=h.trackId
        WHERE h.startedAt >= :start AND h.startedAt <= :end AND h.listenedMs > 0
        AND LOWER(TRIM(COALESCE(t.artist,''))) NOT IN ('','unknown artist','unknown','неизвестный исполнитель','<unknown>')
        GROUP BY TRIM(t.artist) ORDER BY listenedMs DESC, name COLLATE NOCASE, name""")
    suspend fun reportArtistRows(start: Long, end: Long): List<RankedArtist>
    @Transaction suspend fun reportTopArtists(start:Long,end:Long):List<RankedArtist> =
        normalizeReportArtists(reportArtistRows(start,end))
    @Transaction
    @Query("""SELECT * FROM history WHERE startedAt >= :start AND startedAt <= :end AND listenedMs > 0
        ORDER BY startedAt DESC, id DESC LIMIT 100""")
    suspend fun reportHistory(start: Long, end: Long): List<HistoryItem>
    @Transaction suspend fun reportSnapshot(window: ReportWindow): ReportSnapshot {
        val previous = if (window.previousStartInclusive != null && window.previousEndInclusive != null)
            reportTotals(window.previousStartInclusive, window.previousEndInclusive).totalMs else null
        return ReportSnapshot(reportTotals(window.startInclusive, window.endInclusive), previous,
            reportDays(window.dailyQuery()), reportTopTracks(window.startInclusive, window.endInclusive),
            reportTopArtists(window.startInclusive, window.endInclusive), reportHistory(window.startInclusive, window.endInclusive))
    }
    @Query("SELECT t.* FROM tracks t JOIN history h ON h.trackId=t.id GROUP BY t.id ORDER BY MAX(h.startedAt) DESC,t.id LIMIT 20")
    fun recent(): kotlinx.coroutines.flow.Flow<List<Track>>
    @Transaction @Query("SELECT * FROM offline_downloads ORDER BY updatedAt DESC,trackId")
    fun downloads():kotlinx.coroutines.flow.Flow<List<DownloadWithTrack>>
    @Query("SELECT * FROM offline_downloads WHERE trackId=:id") suspend fun download(id:String):OfflineDownload?
    @Query("SELECT * FROM offline_downloads WHERE trackId IN (:ids) AND state='complete'")
    suspend fun completedDownloads(ids:List<String>):List<OfflineDownload>
    @Query("SELECT * FROM offline_downloads") suspend fun allDownloads():List<OfflineDownload>
    @Upsert suspend fun putDownload(download:OfflineDownload)
    @Query("UPDATE offline_downloads SET state=:state,error=:error,updatedAt=:time WHERE trackId=:id AND generation=:generation")
    suspend fun downloadState(id:String,generation:String,state:String,error:String="",time:Long=System.currentTimeMillis()):Int
    @Query("UPDATE offline_downloads SET bytes=:bytes,total=:total,percent=:percent WHERE trackId=:id AND generation=:generation AND state='downloading'")
    suspend fun downloadProgress(id:String,generation:String,bytes:Long,total:Long,percent:Float)
    @Query("UPDATE offline_downloads SET uri=:uri,mimeType=:mime WHERE trackId=:id AND generation=:generation AND state='preparing'")
    suspend fun downloadSource(id:String,generation:String,uri:String,mime:String):Int
    @Query("UPDATE offline_downloads SET state='queued' WHERE trackId=:id AND generation=:generation AND state IN ('preparing','downloading')")
    suspend fun downloadInterrupted(id:String,generation:String)
    @Query("DELETE FROM offline_downloads WHERE trackId=:id AND generation=:generation")
    suspend fun deleteDownload(id:String,generation:String)
}

/**
 * The track row of a write that is not a checkpoint.
 *
 * The row the user deleted is not written again: the delete took its history, its playlist
 * entries, its offline row and its identity rows away, and a write that put the row back would
 * leave a track with none of them.
 */
internal suspend fun LibraryDao.putTrack(track:Track) {
    if(DeletedTracks.isDeleted(track.id))return
    put(track.preservingLibraryState(this.track(track.id)))
}

/** Queue/search snapshots are not authority to replace a separately attached local grant. */
internal fun Track.preservingLibraryState(old:Track?,refreshLocalMetadata:Boolean=false):Track {
    if(old==null)return this
    if(old.source!=source || old.sourceId!=sourceId)throw SourceFailure("Конфликт источника трека в библиотеке")
    // A queued/local playlist snapshot can predate a tag scan or explicit file
    // attachment. Only refresh() is authoritative for existing local metadata.
    if(source=="local" && !refreshLocalMetadata)return old
    val keepAttachment=source=="local" && old.localUri.isNotBlank()
    return copy(favorite=old.favorite,addedAt=old.addedAt,
        importedPlayCount=maxOf(old.importedPlayCount,importedPlayCount),
        importedLastPlayed=maxOf(old.importedLastPlayed,importedLastPlayed),
        importedDownloaded=old.importedDownloaded || importedDownloaded,
        localUri=if(keepAttachment)old.localUri else localUri,
        mimeType=if(keepAttachment && old.mimeType.isNotBlank())old.mimeType else mimeType)
}

@Database(entities=[Track::class,Playlist::class,PlaylistTrack::class,ListeningSession::class,OfflineDownload::class,SmartPlaylist::class,ProviderMapping::class,LocalTrackAlias::class],version=4,exportSchema=true)
abstract class LibraryDatabase: RoomDatabase() {
    abstract fun library():LibraryDao
    companion object {
        val MIGRATION_3_4=object:Migration(3,4) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN importedPlayCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN importedLastPlayed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN importedDownloaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_addedAt ON tracks(addedAt)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS provider_mappings (
                    trackId TEXT NOT NULL, source TEXT NOT NULL,sourceId TEXT NOT NULL,
                    title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,durationMs INTEGER NOT NULL,
                    PRIMARY KEY(trackId,source), FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_track_aliases (
                    portableId TEXT NOT NULL PRIMARY KEY,trackId TEXT NOT NULL,
                    FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_track_aliases_trackId ON local_track_aliases(trackId)")
            }
        }
        val MIGRATION_2_3=object:Migration(2,3) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS smart_playlists (
                    id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,rulesJson TEXT NOT NULL,createdAt INTEGER NOT NULL)""")
            }
        }
        val MIGRATION_1_2=object:Migration(1,2) {
            override fun migrate(db:SupportSQLiteDatabase) {db.execSQL("""CREATE TABLE IF NOT EXISTS offline_downloads (
                trackId TEXT NOT NULL PRIMARY KEY,generation TEXT NOT NULL,state TEXT NOT NULL,
                bytes INTEGER NOT NULL,total INTEGER NOT NULL,percent REAL NOT NULL,error TEXT NOT NULL,
                uri TEXT NOT NULL,mimeType TEXT NOT NULL,updatedAt INTEGER NOT NULL,
                FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")}
        }
    }
}

