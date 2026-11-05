package ru.nedotify.app.ui

import org.junit.Assert.*
import org.junit.Test

/**
 * The player screen renders the error of the player state.
 *
 * A play that the interruption gate refuses never reaches the player as a
 * media error, so without this policy the refusal would be invisible exactly
 * where the user pressed play.
 */
class PlayerErrorStateTest {
    private val refusal="Воспроизведение не начато: звук был прерван. Повторите попытку"

    @Test fun `a refused command is shown on the player screen`() {
        val errors=PlayerErrorState()
        errors.onCommandRefused("track",refusal)
        assertEquals(refusal,errors.errorFor("track",null))
    }

    @Test fun `the media error wins over a refused command`() {
        val errors=PlayerErrorState()
        errors.onCommandRefused("track",refusal)
        assertEquals("Файл удалён",errors.errorFor("track","Файл удалён"))
    }

    @Test fun `a refusal of another track is not shown`() {
        val errors=PlayerErrorState()
        errors.onCommandRefused("track",refusal)
        assertNull(errors.errorFor("other",null))
    }

    @Test fun `the refusal disappears with the track it was refused on`() {
        val errors=PlayerErrorState()
        errors.onCommandRefused("track",refusal)
        errors.onTrackChanged("other")
        assertNull(errors.errorFor("other",null))
    }

    @Test fun `the refusal stays on the track it was refused on`() {
        val errors=PlayerErrorState()
        errors.onCommandRefused("track",refusal)
        errors.onTrackChanged("track")
        assertEquals(refusal,errors.errorFor("track",null))
    }

    @Test fun `the retry clears the refusal`() {
        val errors=PlayerErrorState()
        errors.onCommandRefused("track",refusal)
        errors.onRetry()
        assertNull(errors.errorFor("track",null))
    }

    @Test fun `a second refusal replaces the first one`() {
        val errors=PlayerErrorState()
        errors.onCommandRefused("track",refusal)
        errors.onCommandRefused("track","Аудиовыход занят. Повторите воспроизведение")
        assertEquals("Аудиовыход занят. Повторите воспроизведение",errors.errorFor("track",null))
    }
}
