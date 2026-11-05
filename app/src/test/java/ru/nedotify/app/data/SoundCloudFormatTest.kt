package ru.nedotify.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SoundCloudFormatTest {
    private fun format(protocol:String)=JSONObject().put("format",JSONObject().put("protocol",protocol))
    @Test fun `format remains compatible with the prepared MediaSource`() {
        val hls=format("hls");val progressive=format("progressive")
        assertSame(hls,soundCloudTranscoding(listOf(progressive,hls),"application/x-mpegURL"))
        assertSame(progressive,soundCloudTranscoding(listOf(hls,progressive),""))
    }
    @Test fun `changed provider format is an explicit failure rather than an extractor crash`() {
        try {soundCloudTranscoding(listOf(format("hls")),"");fail("Expected SourceFailure")}
        catch(error:SourceFailure){assertTrue(error.message!!.contains("формат"))}
    }
    @Test fun `a protected track is not reported as a changed format`() {
        val protectedFormat=JSONObject().put("format",JSONObject().put("protocol","ctr-hls"))
        val otherProtected=JSONObject().put("format",JSONObject().put("protocol","cbc-mp3"))
        try {soundCloudTranscoding(listOf(protectedFormat,otherProtected),"");fail("Expected SourceFailure")}
        catch(error:SourceFailure){
            assertTrue(error.message!!.contains("DRM"))
            assertFalse(error.message!!.contains("формат"))
        }
        assertTrue(soundCloudFormatFailure(listOf(protectedFormat)).contains("DRM"))
        assertTrue(soundCloudFormatFailure(listOf(protectedFormat,format("progressive"))).contains("формат"))
    }
}
