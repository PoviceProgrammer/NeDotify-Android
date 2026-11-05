package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test

class BackupExportTest {
    private val library="81f45f66-b07c-4efc-802e-e7d04dc2bc78"
    @Test fun exportKeepsRecentHistoryWithinBothLimitsWithoutMutatingTheLibrarySnapshot() {
        val tracks=List(2000) {index->val id="video"+index.toString().padStart(6,'0')
            Track("youtube:$id","t".repeat(2000),"a".repeat(1000),source="youtube",sourceId=id,addedAt=1)}
        val history=List(100001) {ListeningSession("session-$it",tracks.first().id,it.toLong(),1000,false)}
        val backup=LibraryBackup(library,1,tracks,emptyList(),history)
        val prepared=BackupExport.prepare(backup)
        assertTrue(prepared.droppedSessions>1) // The combined byte ceiling bites before the row ceiling.
        assertEquals(history.size-prepared.backup.history.size,prepared.droppedSessions)
        assertEquals("session-100000",prepared.backup.history.first().id)
        assertTrue(BackupCodec.encodedSize(prepared.backup)<=BackupCodec.MAX_BYTES)
        assertEquals(100001,backup.history.size)
        assertEquals("session-0",backup.history.first().id)
    }

    @Test fun metadataAloneOverTheByteCeilingProducesAnExportSpecificFailure() {
        val tracks=List(4000) {index->val id="video"+index.toString().padStart(6,'0')
            Track("youtube:$id","я".repeat(2000),"я".repeat(2000),source="youtube",sourceId=id,addedAt=1)}
        val failure=assertThrows(SourceFailure::class.java) {BackupExport.prepare(LibraryBackup(library,1,tracks,emptyList(),emptyList()))}
        assertTrue(failure.message!!.contains("сохранить"));assertTrue(failure.message!!.contains("16 МБ"))
    }
}
