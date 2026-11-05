package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsDiskCacheTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun legacySchemasAreIgnoredAndInterruptedTemporaryWritesAreReclaimed() {
        val directory=temp.newFolder();val cache=LyricsDiskCache(directory)
        cache.write("key",LyricsResult("Text"),50_000)
        val target=directory.listFiles()!!.single()
        val orphan=java.io.File(directory,target.name+".tmp").apply {writeText("interrupted")}
        val unrelated=java.io.File(directory,"keep.tmp").apply {writeText("keep")}
        target.writeText(org.json.JSONObject(target.readText()).also {it.remove("schemaVersion")}.toString())
        assertNull(cache.read("key",10_000))
        cache.write("key",LyricsResult("New text"),50_000)
        assertFalse(orphan.exists());assertTrue(unrelated.exists())
        assertEquals("New text",cache.read("key",10_000)!!.first.plain)
    }
    @Test fun cachedTextSurvivesNewClientAndExpires() {
        val directory=temp.newFolder();val text=LyricsResult("Песня",listOf(LrcLine(1234,"Строка")),source="LRCLIB")
        LyricsDiskCache(directory).write("track/key",text,50_000)
        assertEquals(text,LyricsDiskCache(directory).read("track/key",10_000)!!.first)
        assertNull(LyricsDiskCache(directory).read("track/key",50_001))
        assertNull(LyricsDiskCache(directory).read("another/key",10_000))
    }
    @Test fun corruptCacheIsIgnoredAndKeysCannotEscapeFolder() {
        val directory=temp.newFolder();val cache=LyricsDiskCache(directory)
        cache.write("../../../outside",LyricsResult("Текст"),50_000)
        assertTrue(directory.listFiles()!!.all {it.parentFile==directory && it.name.matches(Regex("[a-f0-9]{64}\\.json"))})
        directory.listFiles()!!.single().writeText("bad json")
        assertNull(cache.read("../../../outside",10_000))
    }
    @Test fun diskCacheIsBoundedWithoutDeletingUnrelatedFiles() {
        val directory=temp.newFolder();val unrelated=java.io.File(directory,"keep.txt").apply {writeText("keep")}
        val cache=LyricsDiskCache(directory)
        repeat(105) {cache.write("track$it",LyricsResult("Text$it"),50_000)}
        assertEquals(100,directory.listFiles()!!.count {it.extension=="json"});assertTrue(unrelated.isFile)
    }
    @Test fun failedAtomicReplacementKeepsPreviousOfflineLyricsAndRemovesTemporaryFile() {
        val directory=temp.newFolder();val original=LyricsResult("Старый сохранённый текст")
        LyricsDiskCache(directory).write("key",original,50_000)
        val before=directory.listFiles()!!.single().readBytes()
        LyricsDiskCache(directory){_,_->throw java.io.IOException("Move failed")}.write("key",LyricsResult("Новый текст"),60_000)
        assertEquals(original,LyricsDiskCache(directory).read("key",10_000)!!.first)
        assertArrayEquals(before,directory.listFiles()!!.single().readBytes())
    }
    @Test fun cachedJsonDepthAndDuplicateFieldsAreRejectedBeforePlatformParser() {
        val directory=temp.newFolder();val cache=LyricsDiskCache(directory)
        cache.write("key",LyricsResult("Текст"),50_000)
        val target=directory.listFiles()!!.single()
        for(raw in listOf("{\"nested\":"+"[".repeat(1000)+"0"+"]".repeat(1000)+"}","{\"expiresAt\":50000,\"expiresAt\":60000}")) {
            target.writeText(raw);assertNull(cache.read("key",10_000))
        }
    }
}
