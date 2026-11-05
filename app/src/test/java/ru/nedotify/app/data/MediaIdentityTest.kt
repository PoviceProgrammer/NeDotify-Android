package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * The identity of a media resource.
 *
 * The identity is what makes a re-resolution reuse the bytes that were
 * already fetched. Every test here fails when the signature is put back
 * into the key.
 */
class MediaIdentityTest {
    private val yandexFirst="https://storage.mds.yandex.net/get-mp3/0123456789abcdef/1700000000/x/xx/track.mp3"
    private val yandexNext="https://storage.mds.yandex.net/get-mp3/fedcba9876543210/1700000123/x/xx/track.mp3"
    private val soundCloudFirst="https://playback.media-streaming.soundcloud.cloud/abc/aac_160k/1/playlist.m3u8?Policy=old&Signature=old&Key-Pair-Id=a"
    private val soundCloudNext="https://playback.media-streaming.soundcloud.cloud/abc/aac_160k/1/playlist.m3u8?Policy=new&Signature=new&Key-Pair-Id=b"
    private val youTubeFirst="https://rr1---sn-abc.googlevideo.com/videoplayback?id=1&itag=140&expire=100&sig=a"
    private val youTubeNext="https://rr1---sn-abc.googlevideo.com/videoplayback?id=1&itag=140&expire=200&sig=b"

    @Test fun `the signed yandex path does not change the identity`() {
        assertEquals(mediaIdentity(yandexFirst),mediaIdentity(yandexNext))
        assertNotEquals(mediaIdentity(yandexFirst),mediaIdentity(yandexFirst.replace("/x/xx/track.mp3","/x/xx/other.mp3")))
    }
    @Test fun `the signed query of a known cdn does not change the identity`() {
        assertEquals(mediaIdentity(soundCloudFirst),mediaIdentity(soundCloudNext))
        assertEquals(mediaIdentity(youTubeFirst),mediaIdentity(youTubeNext))
    }
    @Test fun `the recording itself stays part of the identity`() {
        assertNotEquals(mediaIdentity(soundCloudFirst),mediaIdentity(soundCloudFirst.replace("aac_160k","aac_320k")))
        assertNotEquals(mediaIdentity(youTubeFirst),mediaIdentity(youTubeFirst.replace("itag=140","itag=251")))
        assertNotEquals(mediaIdentity(youTubeFirst),mediaIdentity(youTubeFirst.replace("id=1","id=2")))
        assertNotEquals(mediaIdentity(yandexFirst),mediaIdentity(yandexFirst.replace("storage.mds","cdn.music")))
    }
    @Test fun `an unknown host keeps its whole query`() {
        assertNotEquals(mediaIdentity("https://unknown.test/a?expire=1"),mediaIdentity("https://unknown.test/a?expire=2"))
        assertNotEquals(mediaIdentity("https://evil-sndcdn.com/a?sig=1"),mediaIdentity("https://evil-sndcdn.com/a?sig=2"))
    }
    @Test fun `the key of a track is namespaced by the track`() {
        assertNotEquals(mediaCacheKey("track",soundCloudFirst),mediaCacheKey("other",soundCloudFirst))
        assertEquals(mediaCacheKey("track",soundCloudFirst),mediaCacheKey("track",soundCloudNext))
        assertNotEquals(mediaCacheKey("track",soundCloudFirst),mediaCacheKey("track",soundCloudFirst.replace("aac_160k","aac_320k")))
    }
    @Test fun `a resource without a track has the identity alone`() {
        assertEquals(mediaIdentity(soundCloudFirst),mediaCacheKey(null,soundCloudFirst))
        assertTrue(mediaCacheKey(null,soundCloudFirst).startsWith("https://"))
    }
    @Test fun `the namespace of a track cannot escape its directory`() {
        assertEquals(65,mediaNamespace("../Очень длинное название/CON").length)
        assertTrue(mediaNamespace("id").matches(Regex("[a-f0-9]{64}\\|")))
        assertNotEquals(mediaNamespace("source:1"),mediaNamespace("source:2"))
    }
    @Test fun `the streaming key of a manifest is the url and the key of a segment is stable`() {
        // The manifest carries the signatures of its segments: it is fetched again.
        assertEquals(soundCloudFirst,streamingCacheKey(HLS_MANIFEST_KEY_PREFIX+"track",soundCloudFirst))
        // A segment keeps its bytes across the re-resolution.
        val segment="https://playback.media-streaming.soundcloud.cloud/abc/aac_160k/1/data000.m4s?Policy=old&Signature=old"
        val renewed="https://playback.media-streaming.soundcloud.cloud/abc/aac_160k/1/data000.m4s?Policy=new&Signature=new"
        assertEquals(streamingCacheKey(null,segment),streamingCacheKey(null,renewed))
        // The media of a track is namespaced by the track.
        assertEquals(mediaCacheKey("track",soundCloudFirst),streamingCacheKey("track",soundCloudFirst))
        assertNotEquals(streamingCacheKey("track",soundCloudFirst),streamingCacheKey("other",soundCloudFirst))
    }
    @Test fun `the unnormalised identity is still readable for the packages of the old format`() {
        // The old format kept the signature: that is why a package of it is
        // only found under the URL of the attempt that downloaded it.
        assertNotEquals(mediaIdentity(yandexFirst,normaliseSignedPath=false),mediaIdentity(yandexNext,normaliseSignedPath=false))
        assertNotEquals(mediaIdentity(yandexFirst),mediaIdentity(yandexFirst,normaliseSignedPath=false))
    }
}
