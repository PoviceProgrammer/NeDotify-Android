package ru.nedotify.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI

/** Opt-in diagnostic, not part of the deterministic regression gate. */
class LiveProviderProbeTest {
    @Test fun liveSourceReadiness()=runBlocking {
        assumeTrue(System.getProperty("nedotify.live.probes")=="true" && System.getenv("NEDOTIFY_LIVE_PROBES")=="1")
        val trace=JSONArray()
        val network=MusicNetwork(OkHttpClient.Builder().addInterceptor {chain->
            chain.proceed(chain.request()).also {response->
                val text=response.peekBody(4L*1024*1024).string()
                trace.put(JSONObject().put("host",response.request.url.host).put("path",response.request.url.encodedPath)
                    .put("status",response.code).put("length",text.length)
                    .put("clientIdMatches",Regex("client_id[:=]\\s*\"([A-Za-z0-9]{32})\"").findAll(text).count()))
            }
        }.build())
        val report=JSONObject()
        for(source in listOf("soundcloud","youtube")) {
            val result=JSONObject()
            try {
                val list=if(source=="soundcloud")SoundCloudClient(network).search("NCS") else InnerTubeClient(network).search("NCS")
                result.put("searchCount",list.size)
                val track=list.firstOrNull()?:throw SourceFailure("No search results")
                val stream=if(source=="soundcloud")SoundCloudClient(network).resolve(track) else InnerTubeClient(network).resolve(track)
                network.http.newCall(Request.Builder().url(stream.url).header("Range","bytes=0-8191").build()).execute().use {
                    result.put("http",it.code)
                    result.put("contentType",it.header("Content-Type"))
                    result.put("readable",it.isSuccessful && (it.body?.source()?.readByteArray(128)?.isNotEmpty()==true))
                    result.put("hls",stream.mimeType=="application/x-mpegURL")
                }
                if(stream.mimeType=="application/x-mpegURL") {
                    var manifestUrl=stream.url
                    for(depth in 0 until 3) {
                        val manifest=network.get(manifestUrl)
                        if(!manifest.startsWith("#EXTM3U"))throw SourceFailure("Invalid HLS manifest")
                        val entry=manifest.lineSequence().map(String::trim).firstOrNull {it.isNotBlank() && !it.startsWith('#')}?:throw SourceFailure("Empty HLS manifest")
                        val next=URI(manifestUrl).resolve(entry).toString()
                        if(manifest.contains("#EXTINF")) {
                            network.http.newCall(Request.Builder().url(next).header("Range","bytes=0-8191").build()).execute().use {chunk->
                                result.put("firstChunkReadable",chunk.isSuccessful && (chunk.body?.source()?.readByteArray(128)?.isNotEmpty()==true))
                            }
                            break
                        }
                        manifestUrl=next
                    }
                }
            } catch(error:Exception) {result.put("error",error.message?:error.javaClass.simpleName)}
            report.put(source,result)
        }
        report.put("trace",trace)
        val file=File("build/reports/provider-probe.json");file.parentFile?.mkdirs();file.writeText(report.toString(2))
        println(report.toString(2))
        check(listOf("soundcloud","youtube").any {source->
            val result=report.getJSONObject(source)
            !result.has("error") && result.optBoolean("readable") &&
                (!result.optBoolean("hls") || result.optBoolean("firstChunkReadable"))
        }) {"No provider returned readable media; see build/reports/provider-probe.json"}
    }
}
