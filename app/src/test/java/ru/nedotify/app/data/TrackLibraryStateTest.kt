package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test

class TrackLibraryStateTest {
    @Test fun `stale queue snapshot cannot erase or roll back an attached local document`() {
        val stale=Track("portable-local:"+"a".repeat(64),"Файл",source="local",localUri="content://old/file",mimeType="audio/aac",addedAt=1)
        val current=stale.copy(localUri="content://new/file",mimeType="audio/mpeg",favorite=true,addedAt=2)
        assertEquals(current,stale.preservingLibraryState(current))
        assertEquals(current,stale.copy(localUri="",mimeType="").preservingLibraryState(current))
    }
    @Test fun `refresh can update metadata and insert missing files without losing library flags`() {
        val old=Track("local:1","Старое название",source="local",favorite=true,addedAt=1)
        val fresh=old.copy(title="Прочитанные теги",favorite=false,addedAt=99,localUri="content://media/1",mimeType="audio/flac")
        assertEquals(fresh.copy(favorite=true,addedAt=1),fresh.preservingLibraryState(old,refreshLocalMetadata=true))
        assertEquals(fresh,fresh.preservingLibraryState(null))
    }
    @Test fun `same primary key cannot be used to change playback source`() {
        val old=Track("soundcloud:1","Трек",source="soundcloud",sourceId="1")
        assertThrows(SourceFailure::class.java){old.copy(source="youtube").preservingLibraryState(old)}
        assertThrows(SourceFailure::class.java){old.copy(sourceId="2").preservingLibraryState(old)}
    }
    @Test fun `remembered local queue snapshot cannot roll back scanned tags but refresh can update them`() {
        val stale=Track("local:1","Старое",artist="Старый артист",album="Старый альбом",durationMs=1000,source="local",coverUrl="content://old-cover")
        val scanned=stale.copy(title="Новые теги",artist="Новый артист",album="Новый альбом",durationMs=2000,coverUrl="content://new-cover",localUri="content://attached",mimeType="audio/flac",favorite=true)
        assertEquals(scanned,stale.preservingLibraryState(scanned))
        val nextScan=stale.copy(title="Следующее сканирование",artist="Другой артист")
        assertEquals(nextScan.copy(localUri=scanned.localUri,mimeType=scanned.mimeType,favorite=true),nextScan.preservingLibraryState(scanned,refreshLocalMetadata=true))
    }
    @Test fun `remembered remote result can refresh format instead of retaining stale MIME`() {
        val old=Track("soundcloud:1","Трек",source="soundcloud",sourceId="1",mimeType="audio/mpeg",favorite=true,addedAt=1)
        val new=old.copy(mimeType="application/x-mpegURL",favorite=false,addedAt=999)
        assertEquals(new.copy(favorite=true,addedAt=1),new.preservingLibraryState(old))
    }
}
