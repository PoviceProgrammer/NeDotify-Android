package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test

class LocalImportTitleTest {
    @Test fun missingProviderDisplayNameFallsBackToUriInsteadOfCrashingImport() {
        assertEquals("Моя песня",localImportTitle(null,"Моя песня.mp3"))
        assertEquals("audio:12",localImportTitle("   ","audio:12"))
        assertEquals("Аудиофайл",localImportTitle(null,null))
    }
    @Test fun hiddenOrControlOnlyNamesCannotCreateUnexportableEmptyTitles() {
        assertEquals("Аудиофайл",localImportTitle(".mp3",null))
        assertEquals("Аудиофайл",localImportTitle("\n\t.mp3",null))
        assertEquals("Тихийокеан",localImportTitle("Тихий\u0000океан.flac",null))
        assertEquals("Без расширения",localImportTitle("Без расширения",null))
    }
    @Test fun metadataTruncationCannotSplitEmojiAndPoisonTheLibraryBackup() {
        val prefix="а".repeat(2047)
        assertEquals(prefix,localImportTitle(prefix+"🎵.mp3",null))
        assertEquals("Песня",localMetadataText("\uD800Песня\uDC00"))
        assertEquals("Музыка 🎵",localMetadataText("Музыка 🎵"))
    }
}
