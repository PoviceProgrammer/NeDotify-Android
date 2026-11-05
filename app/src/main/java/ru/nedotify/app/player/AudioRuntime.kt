package ru.nedotify.app.player

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.nedotify.app.data.*

data class AudioRuntimeStatus(
    val sessionActive:Boolean=false,
    val eqSupported:Boolean=false,
    val bassSupported:Boolean=false,
    val virtualizerSupported:Boolean=false,
    val bandMinDb:Float=-12f,
    val bandMaxDb:Float=12f,
    val crossfadeActive:Boolean=false,
    val notice:String="Начните воспроизведение, чтобы проверить звуковые эффекты устройства",
)

/**
 * The main thread of the app.
 *
 * The hardware effects may only be touched on the main looper and the platform
 * reports control changes on a binder thread, so that work is posted back to the
 * main thread. A JVM unit test has no main looper, so the concern is injected:
 * the production default is the real Android main looper.
 */
internal interface MainThread {
    /** Runs [block] on the main thread. */
    fun post(block:()->Unit)
    /** True when the caller already runs on the main thread. */
    fun isCurrent():Boolean
}

/**
 * The production seam: the Android main looper.
 *
 * The [Handler] is created lazily so that merely naming the seam never touches the
 * platform: a JVM unit test may still refer to it to prove that the default really
 * is the Android main looper.
 */
internal object AndroidMainThread:MainThread {
    private val handler by lazy {Handler(Looper.getMainLooper())}
    override fun post(block:()->Unit) {handler.post {block()}}
    override fun isCurrent():Boolean = Looper.myLooper()==Looper.getMainLooper()
}

/** UI observes capabilities; only the service mutates hardware effects on its application looper. */
object AudioRuntime {
    private val mutable=MutableStateFlow(AudioRuntimeStatus())
    val status=mutable.asStateFlow()
    internal fun publish(value:AudioRuntimeStatus,main:MainThread=AndroidMainThread) {check(main.isCurrent());mutable.value=value}
}

/**
 * The cached control state of a platform effect.
 *
 * `Equalizer.hasControl()` is a binder call into audioflinger. The volume is
 * applied on every crossfade tick (25 times a second) and the notification is
 * rebuilt on every transition, so the state is read once and refreshed from the
 * control-status listener the platform effect already installs — never on every
 * read.
 */
internal class CachedControlState(private val read:()->Boolean) {
    var value=false
        private set
    /** How often the platform was actually asked. */
    var reads=0
        private set
    fun refresh() {reads++;value=runCatching{read()}.getOrDefault(false)}
}

@Suppress("DEPRECATION")
internal class AudioEffectsController(
    /** The main thread the effects live on; the default is the Android main looper. */
    private val main:MainThread=AndroidMainThread,
    private val onChanged:()->Unit={},
) {
    private val chains=mutableMapOf<Int,Chain>()
    private var activeSession=0
    private var settings=AudioSettings()
    private var fading=false
    private var closed=false

    fun session(slot:Int,id:Int,active:Boolean) {
        checkMain()
        if(closed)return
        val old=chains[slot]
        if(old?.id!=id) {old?.release();chains.remove(slot);if(id!=0)chains[slot]=Chain(id)}
        if(active)activeSession=id
        chains[slot]?.apply(settings);publish()
    }
    fun active(slot:Int) {checkMain();activeSession=chains[slot]?.id?:0;publish()}
    fun releaseSlot(slot:Int) {checkMain();chains.remove(slot)?.release();publish()}
    fun configure(value:AudioSettings) {checkMain();settings=value.normalized();chains.values.forEach {it.apply(settings)};publish()}
    fun crossfade(value:Boolean) {checkMain();fading=value;publish()}
    fun close() {checkMain();closed=true;fading=false;chains.values.forEach(Chain::release);chains.clear();activeSession=0;publish()}
    /**
     * The equaliser control state, read from the platform effect itself. The published
     * [AudioRuntime.status] is a UI snapshot and may be stale.
     *
     * The state is **cached**: `Equalizer.hasControl()` is a binder call into
     * audioflinger and the volume is applied on every crossfade tick (25 times a
     * second). The cache is refreshed by the control-status listener the
     * platform effect already installs.
     */
    val eqControlled:Boolean get() {
        val chain=chains.values.firstOrNull {it.id==activeSession} ?: return false
        return chain.eqControlled
    }
    /** The crossfade hint of this controller; the published copy is a side effect. */
    val crossfadeActive:Boolean get()=fading
    private fun checkMain()=check(main.isCurrent())
    private fun publish() {
        val active=chains.values.firstOrNull {it.id==activeSession}
        val eqControlled=active?.eqControlled==true
        AudioRuntime.publish(AudioRuntimeStatus(activeSession!=0,eqControlled,
            active?.bassControlled==true,active?.virtualizerControlled==true,
            active?.minDb?:-12f,active?.maxDb?:12f,fading,
            when {
                activeSession==0->"Начните воспроизведение, чтобы проверить звуковые эффекты устройства"
                active?.eq==null->"Эквалайзер не поддерживается текущим аудиовыходом"
                !eqControlled->"Звуковым эффектом управляет другое приложение"
                else->"Эффекты применяются к текущему аудиовыходу"
            }),main)
        onChanged()
    }
    /**
     * The platform effect reports a control change on its own thread; the settings may
     * only be applied on the main thread, so the work is posted through the seam.
     */
    internal fun onControlStatus(work:()->Unit) {main.post(work)}
    private inner class Chain(val id:Int) {
        var eq:Equalizer?=runCatching {Equalizer(0,id)}.getOrNull()
        var bass:BassBoost?=runCatching {BassBoost(0,id)}.getOrNull()
        var virtualizer:Virtualizer?=runCatching {Virtualizer(0,id)}.getOrNull()
        val minDb=runCatching {eq?.bandLevelRange?.get(0)?.div(100f)}.getOrNull()?:-12f
        val maxDb=runCatching {eq?.bandLevelRange?.get(1)?.div(100f)}.getOrNull()?:12f
        private var released=false
        /** The number of bands is fixed for the lifetime of the effect. */
        private val bands:Int=runCatching {eq?.numberOfBands?.toInt()?:0}.getOrDefault(0)
        /** The cached control states; see [CachedControlState]. */
        val eqControl=CachedControlState {eq?.hasControl()==true}
        val bassControl=CachedControlState {bass?.hasControl()==true}
        val virtualizerControl=CachedControlState {virtualizer?.hasControl()==true}
        /** The equaliser is controlled when the platform says so and it has bands. */
        val eqControlled:Boolean get()=eqControl.value&&bands>0
        val bassControlled:Boolean get()=bassControl.value
        val virtualizerControlled:Boolean get()=virtualizerControl.value
        init {
            listOfNotNull(eq,bass,virtualizer).forEach {effect->
                effect.setControlStatusListener {_,_->onControlStatus {if(!closed&&!released){apply(settings);publish()}}}
            }
            eqControl.refresh();bassControl.refresh();virtualizerControl.refresh()
        }
        fun apply(value:AudioSettings) {
            eq?.let {effect->runCatching {
                eqControl.refresh()
                if(eqControl.value) {
                    for(band in 0 until bands) {
                        val gain=equalizerGainAt(effect.getCenterFreq(band.toShort())/1000f,value.bandGainsDb).coerceIn(minDb,maxDb)
                        effect.setBandLevel(band.toShort(),(gain*100).toInt().toShort())
                    }
                    effect.enabled=value.eqEnabled
                }
            }.onFailure {runCatching {effect.release()};eq=null}}
            bass?.let {effect->runCatching {
                bassControl.refresh()
                if(bassControl.value){if(effect.strengthSupported)effect.setStrength((value.bassStrength*1000).toInt().toShort());effect.enabled=value.bassEnabled}
            }.onFailure {runCatching {effect.release()};bass=null}}
            virtualizer?.let {effect->runCatching {
                virtualizerControl.refresh()
                if(virtualizerControl.value){if(effect.strengthSupported)effect.setStrength((value.virtualizerStrength*1000).toInt().toShort());effect.enabled=value.virtualizerEnabled}
            }.onFailure {runCatching {effect.release()};virtualizer=null}}
        }
        fun release() {released=true;listOfNotNull(eq,bass,virtualizer).forEach {runCatching {it.setControlStatusListener(null);it.release()}};eq=null;bass=null;virtualizer=null}
    }
}
