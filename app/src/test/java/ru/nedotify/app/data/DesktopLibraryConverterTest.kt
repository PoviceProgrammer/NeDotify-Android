package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DesktopLibraryConverterTest {
    private val a=mapOf<String,Any?>("id" to 1L,"title" to "Песня","artist" to "Исполнитель","source" to "local","duration" to 180.5,
        "file_path" to "C:\\private\\secret.mp3","cover_path" to "C:\\private\\cover.jpg","is_favorite" to 1L,"added_at" to "2026-10-01 12:30:00")
    private val b=a+mapOf("id" to 2L,"source" to "soundcloud","source_id" to "12345","source_url" to "https://soundcloud.com/artist/song?secret_token=secret")
    private val playlist=mapOf<String,Any?>("id" to 9L,"name" to "Мои треки","created_at" to "2026-10-01 12:30:00")
    private val session=mapOf<String,Any?>("id" to 5L,"track_id" to 1L,"played_at" to "2026-10-02 12:30:00","duration_listened" to 3.25,"listening_measured" to 1L,"completed" to 0L)
    private fun convert(origin:String="Домашний ПК",tracks:List<Map<String,Any?>> = listOf(a,b),history:List<Map<String,Any?>> = listOf(session))=
        DesktopLibraryConverter.convert(origin,tracks,listOf(playlist),listOf(mapOf("playlist_id" to 9L,"track_id" to 2L,"position" to 0),mapOf("playlist_id" to 9L,"track_id" to 1L,"position" to 1)),history,1234)
    @Test fun keepsRealMetadataOrderingFavoritesAndMeasuredTimeWithoutPrivatePaths() {
        val preview=convert();val backup=preview.backup
        assertEquals(2,backup.tracks.size);assertEquals(180_500L,backup.tracks.first().durationMs)
        assertTrue(backup.tracks.first().favorite);assertEquals(3250L,backup.history.single().listenedMs)
        assertEquals("soundcloud:12345",backup.playlists.single().trackIds.first())
        assertTrue(backup.tracks.first().id.startsWith("portable-local:"))
        val json=String(BackupCodec.encode(backup),Charsets.UTF_8)
        assertFalse(json.contains("secret"));assertFalse(json.contains("C:\\"));assertFalse(json.contains("localUri"))
    }
    @Test fun sameSourceNameIsStableButAnotherComputerDoesNotAliasLocalRows() {
        val first=convert().backup;val again=convert("  домашний пк  ").backup;val other=convert("Другой ПК").backup
        assertEquals(first.tracks.first().id,again.tracks.first().id);assertEquals(first.history.single().id,again.history.single().id)
        assertNotEquals(first.tracks.first().id,other.tracks.first().id)
    }
    @Test fun legacyDurationInferenceIsNotImportedAsMeasuredListening() {
        val preview=convert(history=listOf(session+("listening_measured" to 0L)))
        assertTrue(preview.backup.history.isEmpty());assertTrue(preview.notice.contains("без отметки измеренного"))
    }
    @Test fun remoteDuplicatesShareIdentityAndFavoritesWithoutDuplicatedPlaylistItems() {
        val duplicate=b+mapOf("id" to 3L,"is_favorite" to 0L)
        val result=convert(tracks=listOf(a,b,duplicate)).backup
        assertEquals(2,result.tracks.size);assertTrue(result.tracks.single {it.source=="soundcloud"}.favorite)
    }
    @Test fun timestampsSupportSQLiteUtcAndExplicitOffsets() {
        val expected=Instant.parse("2026-10-01T12:30:00Z").toEpochMilli()
        assertEquals(expected,DesktopLibraryConverter.timestamp("2026-10-01 12:30:00"))
        assertEquals(expected,DesktopLibraryConverter.timestamp("2026-10-01T15:30:00+03:00"))
        assertThrows(SourceFailure::class.java) {DesktopLibraryConverter.timestamp("not a date")}
        assertThrows(SourceFailure::class.java) {DesktopLibraryConverter.timestamp(Double.NaN)}
    }
    @Test fun invalidCopiesFailBeforeTheyCanBeMerged() {
        // A typed failure: the importer's error boundary keeps a `SourceFailure` and shows its
        // message instead of the generic "cannot read the database".
        assertThrows(SourceFailure::class.java) {convert(origin="")}
        assertThrows(SourceFailure::class.java) {convert(origin="x".repeat(81))}
        assertThrows(SourceFailure::class.java) {convert(tracks=listOf(a,a))}
        assertThrows(SourceFailure::class.java) {convert(tracks=listOf(a+("duration" to Double.POSITIVE_INFINITY)))}
    }
    @Test fun desktopSmartRulesAreTranslatedInsteadOfStoredAsSql() {
        val rules=DesktopLibraryConverter.desktopRules("{\"favorite\":true,\"downloaded\":false,\"not_played_days\":30,\"sort\":\"most_played\"}")
        assertEquals(true,rules.favorite);assertEquals(false,rules.downloaded);assertEquals(30,rules.notPlayedDays)
        assertEquals(SmartOrder.MOST_PLAYED,rules.order)
        assertThrows(SourceFailure::class.java) {DesktopLibraryConverter.desktopRules("{\"sql\":\"DROP TABLE tracks\"}")}
    }
    @Test fun malformedSmartRuleDocumentsCannotBypassBoundedStrictParser() {
        listOf("{\"favorite\":true,\"favorite\":false}","{\"favorite\":true} trailing","{\"nested\":"+"[".repeat(1000)+"0"+"]".repeat(1000)+"}").forEach {raw->
            assertThrows(SourceFailure::class.java){DesktopLibraryConverter.desktopRules(raw)}
        }
    }
    @Test fun metadataTruncationCannotSplitAnEmojiAndInvalidateTheWholeImport() {
        val result=convert(tracks=listOf(a+mapOf("title" to "a".repeat(2047)+"🎵","artist" to "b".repeat(2047)+"🎵"),b)).backup
        assertEquals("a".repeat(2047),result.tracks.first().title)
        assertEquals("b".repeat(2047),result.tracks.first().artist)
    }

    @Test fun permanentPcIdentitySurvivesGrowthRenameAndFirstListeningSession() {
        val identity="81f45f66-b07c-4efc-802e-e7d04dc2bc78"
        fun stable(name:String,tracks:List<Map<String,Any?>>,history:List<Map<String,Any?>>)=DesktopLibraryConverter.convert(
            name,tracks,listOf(playlist),emptyList(),history,1,sourceIdentity=identity).backup
        val first=stable("ПК",listOf(a),emptyList())
        val grown=stable("Новое имя ПК",listOf(a,b),listOf(session))
        assertEquals(identity,first.libraryId);assertEquals(first.libraryId,grown.libraryId)
        assertEquals(first.tracks.single().id,grown.tracks.first().id)
        assertEquals(first.playlists.single().playlist.id,grown.playlists.single().playlist.id)
    }

    @Test fun legacySameLabelAndMissingDatesCannotSilentlyAliasDifferentPcRows() {
        val noDate=a-("added_at")
        val first=convert("ПК",listOf(noDate),emptyList())
        val other=convert("ПК",listOf(noDate+("title" to "Другая песня")),emptyList())
        assertNotEquals(first.backup.libraryId,other.backup.libraryId)
        assertNotEquals(first.backup.tracks.single().id,other.backup.tracks.single().id)
        assertTrue(first.notice.contains("нет постоянного идентификатора"))
    }

    @Test fun reusedDesktopLocalRowIdWithDifferentRecordingCannotRetainOldRow() {
        val identity="81f45f66-b07c-4efc-802e-e7d04dc2bc78"
        fun source(row:Map<String,Any?>)=DesktopLibraryConverter.convert("ПК",listOf(row),emptyList(),emptyList(),emptyList(),1,sourceIdentity=identity).backup
        val first=source(a);val replacement=source(a+("title" to "Совсем другая песня"))
        val existing=BackupSnapshot(first.tracks,emptyList(),emptyList(),emptyList())
        val plan=BackupMerge.plan(existing,replacement,identity)
        assertNotEquals(first.tracks.single().id,plan.tracks.single().id)
        assertEquals("Совсем другая песня",plan.tracks.single().title)
    }

    @Test fun malformedSmartRowIsSkippedAndMissingColumnsAreDisclosed() {
        val preview=DesktopLibraryConverter.convert("ПК",listOf(a),emptyList(),emptyList(),emptyList(),1,
            smartRows=listOf(mapOf("id" to 1L,"name" to "Broken","rule_json" to ""),
                mapOf("id" to 2L,"name" to "Valid","rule_json" to "{\"favorite\":true}")),
            columns=DesktopColumns.FULL.copy(tracks=DesktopColumns.FULL.tracks-setOf("source","duration","is_favorite","added_at")))
        assertEquals("Valid",preview.backup.smartPlaylists.single().name)
        assertTrue(preview.notice.contains("пропущены: 1"))
        for(field in listOf("источник","длительность","избранное","дата добавления"))assertTrue(preview.notice.contains(field))
    }

    @Test fun zeroMeasuredSessionAndDesktopCountersPreservePlayedEvidence() {
        val preview=convert(tracks=listOf(a+mapOf("play_count" to 5L,"last_played" to "2026-10-02 12:30:00","is_downloaded" to 1L)),
            history=listOf(session+("duration_listened" to 0L)))
        val track=preview.backup.tracks.single()
        assertEquals(5,track.importedPlayCount);assertTrue(track.importedDownloaded);assertEquals("",track.localUri)
        assertEquals(0L,preview.backup.history.single().listenedMs)
        assertTrue(track.importedLastPlayed>0)
    }

    @Test fun anUnreadableDateDoesNotAbortTheWholeImportAndIsDisclosed() {
        val broken=a+mapOf("added_at" to "не дата")
        val preview=convert(tracks=listOf(broken,b),history=listOf(session+("played_at" to "не дата")))
        assertEquals("the import continues",2,preview.backup.tracks.size)
        assertEquals(0L,preview.backup.tracks.first().addedAt)
        assertTrue("the lost date is disclosed",preview.notice.contains("Непрочитанные даты"))
    }

    @Test fun ambiguousLocalRecordsAreDisclosedInsteadOfBeingMergedSilently() {
        val twin=a+mapOf("id" to 3L)
        val preview=convert(tracks=listOf(a,twin))
        assertEquals("both records are kept",2,preview.backup.tracks.size)
        assertTrue("the ambiguity is disclosed",preview.notice.contains("не объединяются автоматически"))
    }

    @Test fun aMalformedPlaylistLinkDoesNotAbortTheWholeImportAndIsDisclosed() {
        val preview=DesktopLibraryConverter.convert("ПК",listOf(a,b),listOf(playlist),
            listOf(mapOf("playlist_id" to 9L,"track_id" to 2L,"position" to 0),
                mapOf("playlist_id" to 9L,"track_id" to "не число","position" to 1),
                mapOf("playlist_id" to "не число","track_id" to 1L,"position" to 2)),
            listOf(session),1234)
        assertEquals("the import continues",2,preview.backup.tracks.size)
        assertEquals("the good link survives",1,preview.backup.playlists.single().trackIds.size)
        assertTrue("the loss is disclosed",preview.notice.contains("неверным идентификатором"))
    }
}
