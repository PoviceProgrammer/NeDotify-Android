package ru.nedotify.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningClockTest {
    @Test fun `pause buffering and position jumps do not add listening time`() {
        var now=0L
        val clock=ListeningClock {now}
        now=10_000;clock.tick();assertEquals(0L,clock.elapsedMs)
        clock.playing(true);now+=250;clock.playing(false)
        now+=30_000;clock.tick();assertEquals(250L,clock.elapsedMs)
        clock.playing(true);now+=500;clock.tick();assertEquals(750L,clock.elapsedMs)
        clock.reset();assertEquals(0L,clock.elapsedMs)
        now+=150;clock.playing(false);assertEquals(150L,clock.elapsedMs)
    }
    @Test fun `a tick longer than fifteen seconds is a suspend, not listening`() {
        var now=0L
        val clock=ListeningClock {now}
        clock.playing(true)
        now+=60_000;clock.tick()
        assertEquals(15_000L,clock.elapsedMs)
        now+=1_000;clock.tick()
        assertEquals(16_000L,clock.elapsedMs)
    }
}
