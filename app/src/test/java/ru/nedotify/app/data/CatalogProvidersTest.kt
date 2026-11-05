package ru.nedotify.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogProvidersTest {
    private val spotify="""{"id":"1234567890123456789012","name":"Север","type":"track","duration_ms":210000,"artists":[{"name":"Артист"}],"album":{"name":"Альбом","images":[{"url":"https://i.scdn.co/image/cover"}]},"preview_url":"https://cdn/preview"}"""
    @Test fun `Spotify parser retains metadata but never consumes preview or credentials`() {
        val track=CatalogParsers.spotifyTrack(JSONObject(spotify))!!
        assertEquals("Север",track.title);assertEquals("Артист",track.artist);assertEquals(210000L,track.durationMs)
        assertEquals("https://open.spotify.com/track/1234567890123456789012",track.pageUrl)
        assertFalse(track.toString().contains("preview"))
        assertNull(CatalogParsers.spotifyTrack(JSONObject(spotify).put("is_local",true)))
        assertNull(CatalogParsers.spotifyTrack(JSONObject(spotify).put("type","episode")))
        assertEquals("",CatalogParsers.spotifyTrack(JSONObject(spotify).put("album",JSONObject("""{"images":[{"url":"https://cover.test/a?token=secret"}]}""")))!!.coverUrl)
    }
    @Test fun `Yandex parser preserves version and ignores podcasts`() {
        val json=JSONObject("""{"id":1234,"title":"Север","version":"Remix","durationMs":120000,"artists":[{"name":"Артист"}],"albums":[{"title":"Альбом"}],"coverUri":"avatars.yandex.net/get-music/%%"}""")
        val track=CatalogParsers.yandexTrack(json)!!
        assertEquals("1234",track.id);assertEquals("Север (Remix)",track.title)
        assertEquals("https://avatars.yandex.net/get-music/400x400",track.coverUrl)
        assertNull(CatalogParsers.yandexTrack(json.put("type","podcast-episode")))
    }
    @Test fun `matcher requires title artist version and similar duration`() {
        val catalog=CatalogTrack("spotify","123","Север",listOf("Артист"),durationMs=180000)
        val match=Track("youtube:abcdefghijk","Артист - Север (Official Audio)","Артист",durationMs=181000,source="youtube",sourceId="abcdefghijk")
        assertEquals(match,CatalogMatcher.match(catalog,listOf(match))?.track)
        assertNull(CatalogMatcher.match(catalog,listOf(match.copy(artist="Чужой",title="Север"))))
        assertNull(CatalogMatcher.match(catalog,listOf(match.copy(title="Артист - Север (Remix)"))))
        assertNull(CatalogMatcher.match(catalog,listOf(match.copy(title="Артист - Север (ремикс)"))))
        assertNull(CatalogMatcher.match(catalog,listOf(match.copy(durationMs=240000))))
        assertNull(CatalogMatcher.match(catalog,listOf(match.copy(id="mismatched"))))
        assertNull(CatalogMatcher.match(catalog.copy(title="Другая песня"),listOf(match)))
    }
    @Test fun `only explicit full MP3 download descriptors can be selected`() {
        fun variant(preview:Any,bitrate:Int=192,url:String="https://storage.mds.yandex.net/info"):JSONObject=JSONObject().put("codec","mp3").put("preview",preview).put("bitrateInKbps",bitrate).put("downloadInfoUrl",url)
        assertEquals(320,YandexDownloadInfo.select(listOf(variant(false),variant(false,320),variant(true,512))).bitrate)
        assertThrows(SourceFailure::class.java){YandexDownloadInfo.select(listOf(variant(true)))}
        assertThrows(SourceFailure::class.java){YandexDownloadInfo.select(listOf(variant("false")))}
        assertThrows(SourceFailure::class.java){YandexDownloadInfo.select(listOf(variant(false,url="https://storage.mds.yandex.net.evil.test/info")))}
    }
    @Test fun `download address rejects credential origins ports and hostile XML`() {
        listOf("http://storage.mds.yandex.net/info","https://storage.mds.yandex.net:444/info","https://user@storage.mds.yandex.net/info","https://127.0.0.1/info","https://storage.yandex.net.evil.test/info").forEach {
            assertThrows(SourceFailure::class.java){YandexDownloadInfo.trustedUrl(it)}
        }
        val xml="<download-info><host>s123.storage.yandex.net</host><path>/resource.mp3</path><ts>1234</ts><s>salt</s></download-info>"
        val result=YandexDownloadInfo.directLink(xml)
        assertTrue(result.startsWith("https://s123.storage.yandex.net/get-mp3/"));assertTrue(result.endsWith("/1234/resource.mp3"))
        assertThrows(SourceFailure::class.java){YandexDownloadInfo.directLink(xml.replace("s123.storage.yandex.net","evil.test"))}
        assertThrows(SourceFailure::class.java){YandexDownloadInfo.directLink("<!DOCTYPE x [<!ENTITY external SYSTEM 'file:///private'>]>$xml")}
        assertThrows(SourceFailure::class.java){YandexDownloadInfo.directLink(xml.replace("/resource.mp3","/../private"))}
    }
    @Test fun `catalog text bounds preserve Unicode and remain exportable for large artist lists`() {
        assertEquals("Артист",CatalogParsers.text("Ар\u0000тист\u0001",300))
        assertEquals("a".repeat(499),CatalogParsers.text("a".repeat(499)+"🎵",500))
        assertEquals("🎵",CatalogParsers.text("🎵",2))
        val metadata=CatalogTrack("yandex","123","Трек",List(20){"🎵".repeat(150)})
        assertTrue(metadata.artist.length<=2048)
        assertFalse(Character.isHighSurrogate(metadata.artist.last()))
    }
    @Test fun `actual song names are not erased as video decorations`() {
        for(title in listOf("Official","Lyrics","HD","4K","Official Audio")) {
            val catalog=CatalogTrack("spotify","123",title,listOf("Artist"),durationMs=180000)
            val candidate=Track("youtube:abcdefghijk","Artist - $title","Artist",durationMs=180000,source="youtube",sourceId="abcdefghijk")
            assertEquals("Actual title $title must remain matchable",candidate,CatalogMatcher.match(catalog,listOf(candidate))?.track)
        }
    }
    @Test fun `official inside the actual title cannot collapse to another song`() {
        val catalog=CatalogTrack("spotify","123","Love Official",listOf("Artist"),durationMs=180000)
        val candidate=Track("youtube:abcdefghijk","Love","Artist",durationMs=180000,source="youtube",sourceId="abcdefghijk")
        assertNull(CatalogMatcher.match(catalog,listOf(candidate)))
        assertNull(CatalogMatcher.match(catalog.copy(title="Love"),listOf(candidate.copy(title="Love Official"))))
    }
    @Test fun `recognizable trailing video annotations still match without changing song identity`() {
        val catalog=CatalogTrack("spotify","123","Love",listOf("Artist"),durationMs=180000)
        for(title in listOf("Artist - Love (Official Audio)","Artist - Love [Lyrics] (HD)","Artist - Love - Official Video")) {
            val candidate=Track("youtube:abcdefghijk",title,"Artist",durationMs=180000,source="youtube",sourceId="abcdefghijk")
            assertEquals(candidate,CatalogMatcher.match(catalog,listOf(candidate))?.track)
        }
    }
}
