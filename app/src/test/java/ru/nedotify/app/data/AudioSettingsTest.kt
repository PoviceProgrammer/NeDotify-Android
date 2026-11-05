package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class AudioSettingsTest {
    @Test fun hostilePersistedValuesAreClampedAndRepaired() {
        val value=AudioSettings(bandGainsDb=listOf(Float.NaN,99f,-99f),bassStrength=Float.POSITIVE_INFINITY,
            virtualizerStrength=-4f,crossfadeSeconds=99).normalized()
        assertEquals(listOf(0f,12f,-12f,0f,0f),value.bandGainsDb)
        assertEquals(0f,value.bassStrength,0f);assertEquals(0f,value.virtualizerStrength,0f)
        assertEquals(12,value.crossfadeSeconds)
    }
    @Test fun hardwareBandsUseLogFrequencyInterpolation() {
        val gains=listOf(0f,4f,0f,-4f,0f)
        assertEquals(2f,equalizerGainAt(sqrt(60f*230f),gains),.0001f)
        assertEquals(4f,equalizerGainAt(230f,gains),.0001f)
        assertEquals(0f,equalizerGainAt(99_000f,gains),.0001f)
    }
    @Test fun boostedEqualizerReservesHeadroomWithoutAmplifyingNegativePreset() {
        assertEquals(1f,equalizerHeadroom(AudioSettings()),0f)
        assertEquals(.5011872f,equalizerHeadroom(AudioSettings(eqEnabled=true,bandGainsDb=listOf(6f,0f,0f,0f,0f))),.0001f)
        assertEquals(1f,equalizerHeadroom(AudioSettings(eqEnabled=true,bandGainsDb=List(5){-3f})),0f)
    }
    @Test fun invalidQualityAndCacheSettingsUseSafeDefaults() {
        val invalid=AudioSettings(quality="unlimited",cacheLimitMb=Int.MAX_VALUE).normalized()
        assertEquals("high",invalid.quality);assertEquals(256,invalid.cacheLimitMb)
        assertEquals("high",AudioSettings(quality="HIGH",cacheLimitMb=-1).normalized().quality)
        assertEquals(256,AudioSettings(cacheLimitMb=0).normalized().cacheLimitMb)
    }
    @Test fun allSupportedQualityAndCacheValuesSurviveNormalization() {
        for(quality in listOf("high","standard","dataSaver"))for(limit in listOf(64,128,256,512,1024)) {
            val value=AudioSettings(quality=quality,cacheLimitMb=limit).normalized()
            assertEquals(quality,value.quality);assertEquals(limit,value.cacheLimitMb)
        }
    }
}
