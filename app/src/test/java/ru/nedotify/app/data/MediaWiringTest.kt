package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The wiring of the media path.
 *
 * The media path has its own HTTP client: the metadata client carries a 30 s
 * **total** budget, and Media3 reads the body of a media request through one
 * call — a total budget would cut every transfer off in the middle. A
 * regression that put the metadata client back on the media path would break
 * every download and every stream, and no other test would notice: the
 * behaviour is invisible to a unit test that only calls the client.
 *
 * The contract is therefore pinned on the **source** of the wiring itself.
 */
class MediaWiringTest {
    @Test fun `the download path reads the media through the media client`() {
        val storage=source("ru/nedotify/app/downloads/DownloadStorage.kt")
        assertTrue("the download must not use the metadata client",
            storage.contains("OkHttpDataSource.Factory(network.mediaHttp)"))
        assertFalse(storage.contains("OkHttpDataSource.Factory(network.http)"))
    }
    @Test fun `the player reads the media through the media client`() {
        val service=source("ru/nedotify/app/player/PlaybackService.kt")
        assertTrue("the player must not use the metadata client",
            service.contains("OkHttpDataSource.Factory(repository.network.mediaHttp)"))
        assertFalse(service.contains("OkHttpDataSource.Factory(repository.network.http)"))
    }
    @Test fun `the manifest of a download is read through the media client`() {
        val storage=source("ru/nedotify/app/downloads/DownloadStorage.kt")
        assertTrue("the manifest must not be read through the metadata client",
            storage.contains("ProviderHttp(network.mediaHttp)"))
        assertFalse(storage.contains("ProviderHttp(network.http)"))
    }
    @Test fun `the two clients are two clients`() {
        val network=MusicNetwork()
        assertNotSame(network.http,network.mediaHttp)
        // The metadata client has a total budget; the media client has none.
        assertEquals(30_000,network.http.callTimeoutMillis)
        assertEquals(0,network.mediaHttp.callTimeoutMillis)
    }
    /** The source of a file of the module, found from the working directory. */
    private fun source(path:String):String {
        var root=File("").absoluteFile
        while(root.parentFile!=null) {
            val file=File(root,"src/main/java/$path")
            if(file.isFile)return file.readText()
            root=root.parentFile
        }
        throw AssertionError("the source file $path was not found")
    }
}
