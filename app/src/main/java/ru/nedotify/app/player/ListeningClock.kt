package ru.nedotify.app.player

/** Monotonic elapsed time, excluding buffering, pauses and seeks. */
class ListeningClock(private val now:()->Long) {
    private var last=now()
    private var active=false
    var elapsedMs=0L
        private set
    /**
     * A tick longer than [MAX_TICK_MS] is a suspend, not listening: the desktop caps the
     * same way, so a device that sleeps cannot inflate the measured listen time.
     */
    fun tick() { val time=now();if(active)elapsedMs+=(time-last).coerceIn(0,MAX_TICK_MS);last=time }
    fun playing(value:Boolean) {tick();active=value}
    fun reset() {elapsedMs=0;last=now()}
    companion object { const val MAX_TICK_MS=15_000L }
}
