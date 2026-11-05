package ru.nedotify.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeMetadataTest {
    private fun text(value:String)=JSONObject().put("text",value)
    private fun artist(value:String)=text(value).put("navigationEndpoint",JSONObject().put("browseEndpoint",JSONObject().put("browseId","UC_artist")))
    @Test fun `typed song metadata does not become part of the artist`() {
        assertEquals("Unknown Brain и Chris Linton",youtubeArtist(listOf(text("Композиция"),text(" • "),artist("Unknown Brain"),text(" и "),artist("Chris Linton"))))
    }
    @Test fun `artist without type prefix is not truncated`() {
        assertEquals("Исполнитель",youtubeArtist(listOf(artist("Исполнитель"),text(" • "),text("Альбом"))))
    }
    @Test fun `plain fallback recognizes typed metadata and keeps artist only metadata`() {
        assertEquals("Artist",youtubeArtist(listOf(text("Song"),text(" • "),text("Artist"),text(" • "),text("Album"))))
        assertEquals("Два имени",youtubeArtist(listOf(text("Два имени"))))
    }
}
