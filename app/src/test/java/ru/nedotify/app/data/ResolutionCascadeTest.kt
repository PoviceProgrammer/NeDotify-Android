package ru.nedotify.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * The resolution cascade: one wall-clock budget for the whole cascade, and
 * the identity gate that decides whether a second provider may serve a track.
 */
class ResolutionCascadeTest {
    @Test fun `the first attempt gets the whole budget`()=runTest {
        var now=10_000L
        val cascade=ResolutionCascade(5_000,now={now})
        val seen=mutableListOf<Long>()
        val result=cascade.resolve(listOf({remaining->seen+=remaining;"first"}))
        assertEquals("first",result)
        assertEquals(listOf(5_000L),seen)
    }
    @Test fun `a second attempt only gets what is left of the budget`()=runTest {
        var now=10_000L
        val cascade=ResolutionCascade(5_000,now={now})
        val seen=mutableListOf<Long>()
        val result=cascade.resolve(listOf(
            {remaining->seen+=remaining;now+=4_000;null},
            {remaining->seen+=remaining;"second"}
        ))
        assertEquals("second",result)
        assertEquals(listOf(5_000L,1_000L),seen)
    }
    @Test fun `a failed attempt hands the cascade to the next one`()=runTest {
        val cascade=ResolutionCascade(5_000)
        val failure=runCatching {
            cascade.resolve(listOf(
                {_->throw SourceFailure("первый")},
                {_->throw SourceFailure("второй")}
            ))
        }.exceptionOrNull()
        assertEquals("первый",(failure as SourceFailure).message)
    }
    @Test fun `an exhausted budget stops the cascade`()=runTest {
        var now=10_000L
        val cascade=ResolutionCascade(1_000,now={now})
        var secondCalled=false
        val failure=runCatching {
            cascade.resolve(listOf(
                {remaining->now+=2_000;null},
                {remaining->secondCalled=true;"never"}
            ))
        }.exceptionOrNull()
        assertTrue(failure is SourceFailure)
        assertFalse(secondCalled)
    }
    @Test fun `the identity gate admits a candidate of another provider only when the matcher does`() {
        val track=Track("yandex:123","Song Name",artist="Artist Name",durationMs=200_000,source="yandex",sourceId="123")
        val candidate=Track("youtube:abcdefghijk","Artist Name - Song Name",artist="Artist Name",durationMs=200_000,
            source="youtube",sourceId="abcdefghijk")
        val other=Track("youtube:zzzzzzzzzzz","A Completely Different Song",artist="Someone Else",durationMs=200_000,
            source="youtube",sourceId="zzzzzzzzzzz")
        assertEquals(candidate,ResolutionIdentity.admitted(track,listOf(candidate)))
        assertNull(ResolutionIdentity.admitted(track,listOf(other)))
        assertNull(ResolutionIdentity.admitted(track,emptyList()))
    }
    @Test fun `a candidate of the own source is never a fallback`() {
        val track=Track("soundcloud:1","Song Name",artist="Artist Name",durationMs=200_000,source="soundcloud",sourceId="1")
        val same=Track("soundcloud:1","Artist Name - Song Name",artist="Artist Name",durationMs=200_000,source="soundcloud",sourceId="1")
        assertNull(ResolutionIdentity.admitted(track,listOf(same)))
    }
    @Test fun `the fallback is found by searching the other provider`()=runTest {
        // The cascade must be able to fall back: the relation is created on
        // demand, the way the desktop creates it.
        val track=Track("yandex:123","Song Name",artist="Artist Name",durationMs=200_000,source="yandex",sourceId="123")
        val candidate=Track("youtube:abcdefghijk","Artist Name - Song Name",artist="Artist Name",durationMs=200_000,
            source="youtube",sourceId="abcdefghijk")
        val searched=mutableListOf<String>()
        val found=ResolutionIdentity.findFallback(track,emptyList(),5_000,3_000){source->
            searched+=source
            if(source=="youtube")listOf(candidate) else emptyList()
        }
        assertEquals(candidate,found)
        assertEquals(listOf("youtube"),searched)
    }
    @Test fun `the own source of a track is never searched again`() {
        val soundcloud=Track("soundcloud:1","Song Name",artist="Artist Name",source="soundcloud",sourceId="1")
        val youtube=Track("youtube:abcdefghijk","Song Name",artist="Artist Name",source="youtube",sourceId="abcdefghijk")
        val yandex=Track("yandex:1","Song Name",artist="Artist Name",source="yandex",sourceId="1")
        assertEquals(listOf("youtube"),ResolutionIdentity.fallbackSources(soundcloud))
        assertEquals(listOf("soundcloud"),ResolutionIdentity.fallbackSources(youtube))
        assertEquals(listOf("youtube","soundcloud"),ResolutionIdentity.fallbackSources(yandex))
    }
    @Test fun `a search that fails does not break the resolution`()=runTest {
        val track=Track("yandex:123","Song Name",artist="Artist Name",durationMs=200_000,source="yandex",sourceId="123")
        val found=ResolutionIdentity.findFallback(track,emptyList(),5_000,3_000){throw SourceFailure("сеть недоступна")}
        assertNull(found)
    }
    @Test fun `a supplied candidate is admitted without a search`()=runTest {
        val track=Track("yandex:123","Song Name",artist="Artist Name",durationMs=200_000,source="yandex",sourceId="123")
        val candidate=Track("youtube:abcdefghijk","Artist Name - Song Name",artist="Artist Name",durationMs=200_000,
            source="youtube",sourceId="abcdefghijk")
        var searched=false
        val found=ResolutionIdentity.findFallback(track,listOf(candidate),5_000,3_000){searched=true;emptyList()}
        assertEquals(candidate,found)
        assertFalse(searched)
    }
    @Test fun `the budget of the cascade is measured with a monotonic clock`() {
        // A wall clock can be stepped by an NTP correction: the step may make
        // the remaining budget jump to zero and end the cascade silently.
        val cascade=ResolutionCascade(5_000)
        assertTrue(cascade.remaining() in 1..5_000)
    }
}
