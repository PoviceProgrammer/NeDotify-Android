package ru.nedotify.app.downloads

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.DatabaseProvider
import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.Downloader
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import ru.nedotify.app.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadStorageTest {
    private lateinit var storage:DownloadStorage
    private lateinit var directory:File
    private lateinit var database:SQLiteDatabase
    private val hits=mutableListOf<String>()
    private var rejectSecond=true
    private var live=false
    private var empty=false
    private var captionsOnly=false
    private var hugeManifest=false
    private val host="https://playback.media-streaming.soundcloud.cloud/test/"
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        directory=File(context.cacheDir,"download-test-${UUID.randomUUID()}").apply {mkdirs()}
        val isolated=object:ContextWrapper(context) {
            override fun getFilesDir()=directory
            override fun getDatabasePath(name:String)=File(directory,name)
        }
        database=SQLiteDatabase.create(null)
        val provider=object:DatabaseProvider {
            override fun getReadableDatabase()=database
            override fun getWritableDatabase()=database
        }
        storage=DownloadStorage(isolated,MusicNetwork(OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request();val path=request.url.encodedPath
            synchronized(hits){hits+=path}
            val playlist="#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\n${host}one.aac?Signature=${request.url.queryParameter("Signature")}\n#EXTINF:5,\n${host}two.aac?Signature=${request.url.queryParameter("Signature")}\n"+(if(live)"" else "#EXT-X-ENDLIST\n")
            val denied=path.endsWith("two.aac") && rejectSecond
            val text=when {
                hugeManifest->"#EXTM3U\n"+"#padding\n".repeat(270_000)
                captionsOnly->"#EXTM3U\n#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID=\"cc\",NAME=\"English\",INSTREAM-ID=\"CC1\"\n"
                empty->"#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-ENDLIST\n"
                else->playlist
            }
            val bytes=if(path.endsWith(".m3u8"))text.toByteArray() else ByteArray(4096){if(path.endsWith("one.aac"))1 else 2}
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(denied)503 else 200).message("test")
                .body(bytes.toResponseBody((if(path.endsWith(".m3u8"))"application/x-mpegURL" else "audio/aac").toMediaType())).build()
        }.build()),provider)
    }
    @After fun close() {storage.cache.release();database.close();directory.deleteRecursively()}
    @Test fun hlsFailureCanResumeWithFreshSignaturesAndCompletedFilesReadWithoutNetwork()=runBlocking {
        val id="test:track";val old="${host}index.m3u8?Signature=old"
        storage.verifyVod(old)
        try {awaitDownload(storage.downloader(id,old,"application/x-mpegURL",DownloadStorage.observedKeys()),Downloader.ProgressListener {_,_,_->});fail("Must fail on second segment")}
        catch(_:java.io.IOException){}
        assertTrue(storage.bytes(id)>0)
        val firstReads=synchronized(hits){hits.count {it.endsWith("one.aac")}}
        rejectSecond=false
        val fresh="${host}index.m3u8?Signature=fresh"
        storage.refreshManifests(id,fresh)
        val seen=DownloadStorage.observedKeys()
        awaitDownload(storage.downloader(id,fresh,"application/x-mpegURL",seen),Downloader.ProgressListener {_,_,_->})
        assertEquals(firstReads,synchronized(hits){hits.count {it.endsWith("one.aac")}})
        val networkReads=hits.size
        for(file in listOf("index.m3u8","one.aac","two.aac")) {
            val source=storage.factory(id,false).createDataSource()
            try {
                source.open(DataSpec.Builder().setUri("$host$file?Signature=expired".toUri()).build())
                val out=ByteArrayOutputStream();val buffer=ByteArray(1024)
                while(true){val count=source.read(buffer,0,buffer.size);if(count<0)break;out.write(buffer,0,count)}
                assertTrue(out.size()>0)
            } finally {source.close()}
        }
        assertEquals(networkReads,hits.size)
        storage.remove(id);assertEquals(0L,storage.bytes(id))
    }
    @Test fun livePlaylistCannotBeDeclaredAsACompleteRecording()=runBlocking {
        live=true
        try {storage.verifyVod("${host}index.m3u8");fail("Must reject live HLS")}
        catch(_:SourceFailure){}
    }
    @Test fun emptyVodCannotBeDeclaredAsDownloadedMusic()=runBlocking {
        empty=true
        try {storage.verifyVod("${host}index.m3u8");fail("Must reject empty HLS")}
        catch(_:SourceFailure){}
    }
    @Test fun multivariantWithoutMediaCannotBeDeclaredAsDownloadedMusic()=runBlocking {
        captionsOnly=true
        try {storage.verifyVod("${host}index.m3u8");fail("A captions-only master has no downloadable music")}
        catch(_:SourceFailure){}
    }
    @Test fun manifestSizeIsBoundedBeforeHlsParserReadsIt()=runBlocking {
        hugeManifest=true
        try {storage.verifyVod("${host}index.m3u8");fail("Oversized manifest must reject")}
        catch(error:SourceFailure){assertTrue(error.message.orEmpty().contains("большой"))}
    }
}
