package ru.nedotify.app.player

import org.junit.Assert.*
import org.junit.Test
import ru.nedotify.app.data.SourceFailure

class PlaybackRefusalTest {
    @Test fun `a stale play is refused with a message the user can act on`() {
        val refusal=playbackRefusal(playWhenReady=true,stale=true)
        assertNotNull(refusal)
        assertTrue(refusal!!.isNotBlank())
        assertTrue(refusal.contains("Повторите"))
    }
    @Test fun `a fresh play is never refused`() {
        assertNull(playbackRefusal(playWhenReady=true,stale=false))
    }
    @Test fun `a pause is never refused`() {
        assertNull(playbackRefusal(playWhenReady=false,stale=true))
        assertNull(playbackRefusal(playWhenReady=false,stale=false))
    }
    @Test fun `the refusal names the app's own state, not the user`() {
        val refusal=playbackRefusal(true,true)!!
        assertFalse(refusal.contains("вы",true))
        assertFalse(refusal.contains("нельзя",true))
        assertFalse(refusal.contains("ошибка",true))
    }
    @Test fun `the queue is installed paused only when a play would be refused`() {
        assertTrue(installPaused(playWhenReady=true,canStartPlayback=false))
        assertFalse(installPaused(playWhenReady=true,canStartPlayback=true))
        assertFalse(installPaused(playWhenReady=false,canStartPlayback=false))
    }
    @Test fun `a raw exception message is never forwarded to the user`() {
        assertEquals("Не удалось подготовить очередь",
            failureText(IllegalStateException("jdbc:sqlite:/data/db/queue?token=1"),"Не удалось подготовить очередь"))
        assertEquals("YouTube: Выполните вход",
            failureText(RuntimeException("outer",SourceFailure("YouTube: Выполните вход")),"Не удалось подготовить очередь"))
    }
}
