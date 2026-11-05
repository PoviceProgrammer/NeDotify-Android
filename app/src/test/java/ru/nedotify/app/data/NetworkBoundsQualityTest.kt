package ru.nedotify.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkBoundsQualityTest {
    @Test fun `nested or trailing JSON fails before source DOM traversal`() {
        listOf("[".repeat(41)+"]".repeat(41),"{} {}","{\"a\":").forEach {raw->
            try {sourceObject(raw);fail("invalid JSON")}
            catch(_:SourceFailure) {}
        }
    }
    @Test fun `quoted delimiters do not consume JSON nesting budget`() {
        val text="[".repeat(80)+" } \\\" text"
        assertEquals(text,sourceObject(JSONObject().put("text",text).toString()).getString("text"))
        assertEquals(1,sourceArray("[{\"ok\":true}]").length())
    }
    private fun network(body:()->ResponseBody)=MusicNetwork(OkHttpClient.Builder().addInterceptor {chain->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body()).build()
    }.build())
    private fun unknownLength(value:String)=object:ResponseBody() {
        private val buffer=Buffer().writeUtf8(value)
        override fun contentType()="text/plain".toMediaType()
        override fun contentLength()=-1L
        override fun source():BufferedSource=buffer
    }
    @Test fun `known oversized response fails before buffering`()=runBlocking {
        try {network {"123456".toResponseBody()}.get("https://fixture.test/",maxBytes=5);fail("oversized")}
        catch(error:SourceFailure) {assertTrue(error.message!!.contains("слишком большой"))}
    }
    @Test fun `unknown length response cannot bypass streaming bound`()=runBlocking {
        try {network {unknownLength("а".repeat(12_000))}.get("https://fixture.test/",maxBytes=8_192);fail("oversized")}
        catch(error:SourceFailure) {assertTrue(error.message!!.contains("слишком большой"))}
    }
    @Test fun `utf8 response exactly at byte bound remains intact`()=runBlocking {
        assertEquals("Музыка",network {unknownLength("Музыка")}.get("https://fixture.test/",maxBytes=12))
    }
    private fun format(quality:String,preset:String="mp3",protocol:String="progressive")=
        JSONObject().put("quality",quality).put("preset",preset).put("format",JSONObject().put("protocol",protocol))
    @Test fun `SoundCloud quality chooses compatible full length format`() {
        val sq=format("sq");val hq=format("hq");val snip=format("hq","mp3_snipped");val protectedFormat=format("hq","ctr-mp3")
        val formats=listOf(snip,protectedFormat,sq,hq,format("hq",protocol="hls"))
        assertSame(hq,soundCloudTranscoding(formats,"","high"))
        assertSame(sq,soundCloudTranscoding(formats,"","standard"))
        assertSame(sq,soundCloudTranscoding(formats,"","dataSaver"))
    }
    @Test fun `SoundCloud no preview fallback when only protected or snippets exist`() {
        try {soundCloudTranscoding(listOf(format("sq","cbc-mp3"),format("hq").put("snipped",true)),"");fail("no full stream")}
        catch(_:SourceFailure) {}
    }
    @Test fun `YouTube quality preference selects real available bitrates excluding DRM`()=runBlocking {
        val payload="""{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
            {"mimeType":"audio/mp4","bitrate":64000,"url":"https://cdn.test/low"},
            {"mimeType":"audio/mp4","bitrate":128000,"url":"https://cdn.test/standard"},
            {"mimeType":"audio/webm","bitrate":256000,"url":"https://cdn.test/high"},
            {"mimeType":"audio/mp4","bitrate":320000,"url":"https://cdn.test/protected","drmFamilies":["WIDEVINE"]},
            {"mimeType":"video/mp4","bitrate":1000000,"url":"https://cdn.test/video"}
        ]}}"""
        val client=InnerTubeClient(network {payload.toResponseBody("application/json".toMediaType())})
        val track=Track("youtube:abcdefghijk","Track",source="youtube",sourceId="abcdefghijk")
        assertEquals("https://cdn.test/low",client.resolve(track,"dataSaver").url)
        assertEquals("https://cdn.test/standard",client.resolve(track,"standard").url)
        assertEquals("https://cdn.test/high",client.resolve(track,"high").url)
    }
}
