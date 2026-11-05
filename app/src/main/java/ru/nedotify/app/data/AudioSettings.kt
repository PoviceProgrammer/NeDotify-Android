package ru.nedotify.app.data

import kotlin.math.ln
import kotlin.math.pow

data class AudioSettings(
    val eqEnabled:Boolean=false,
    val bandGainsDb:List<Float> = List(5){0f},
    val bassEnabled:Boolean=false,
    val bassStrength:Float=.3f,
    val virtualizerEnabled:Boolean=false,
    val virtualizerStrength:Float=.3f,
    val crossfadeSeconds:Int=0,
    val autoplay:Boolean=false,
    val quality:String="high",
    val cacheLimitMb:Int=256,
) {
    fun normalized()=copy(
        bandGainsDb=List(5){bandGainsDb.getOrNull(it)?.takeIf(Float::isFinite)?.coerceIn(-12f,12f)?:0f},
        bassStrength=bassStrength.takeIf(Float::isFinite)?.coerceIn(0f,1f)?:0f,
        virtualizerStrength=virtualizerStrength.takeIf(Float::isFinite)?.coerceIn(0f,1f)?:0f,
        crossfadeSeconds=crossfadeSeconds.coerceIn(0,12),
        quality=quality.takeIf {it in setOf("high","standard","dataSaver")}?:"high",
        cacheLimitMb=cacheLimitMb.takeIf {it in setOf(64,128,256,512,1024)}?:256,
    )
    companion object {val frequenciesHz=listOf(60f,230f,910f,3600f,14000f)}
}

/** Log-frequency interpolation maps a stable five-band UI to the actual device bands. */
fun equalizerGainAt(frequencyHz:Float,gains:List<Float>):Float {
    val values=List(5){gains.getOrNull(it)?.takeIf(Float::isFinite)?.coerceIn(-12f,12f)?:0f}
    val frequencies=AudioSettings.frequenciesHz
    if(!frequencyHz.isFinite() || frequencyHz<=frequencies.first())return values.first()
    if(frequencyHz>=frequencies.last())return values.last()
    val upper=frequencies.indexOfFirst {it>=frequencyHz}
    val fraction=(ln(frequencyHz)-ln(frequencies[upper-1]))/(ln(frequencies[upper])-ln(frequencies[upper-1]))
    return values[upper-1]+(values[upper]-values[upper-1])*fraction
}

/** Reserve headroom for EQ boosts. This is not a loudness-normalization claim. */
fun equalizerHeadroom(settings:AudioSettings):Float = if(!settings.eqEnabled)1f
    else 10.0.pow(-settings.normalized().bandGainsDb.maxOrNull()!!.coerceAtLeast(0f)/20.0).toFloat()
