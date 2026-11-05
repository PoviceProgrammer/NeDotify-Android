package ru.nedotify.app.ui

import org.junit.Assert.*
import org.junit.Test
import ru.nedotify.app.data.SourceFailure
import java.io.IOException
import java.io.FileNotFoundException

class PlaybackErrorsTest {
    @Test fun `source login reason survives wrapper exceptions`() {
        assertEquals("YouTube: Выполните вход",playbackFailureMessage(IOException("outer",SourceFailure("YouTube: Выполните вход"))))
    }
    @Test fun `missing local file is not reported as a network problem`() {
        assertTrue(playbackFailureMessage(IOException("outer",FileNotFoundException())).contains("Файл"))
    }
    @Test fun `a session error without a message never clears the channel`() {
        assertTrue(sessionErrorMessage(null).isNotBlank())
        assertTrue(sessionErrorMessage("").isNotBlank())
        assertTrue(sessionErrorMessage("   ").isNotBlank())
        assertEquals("Аудиофайл связан с треком",sessionErrorMessage("Аудиофайл связан с треком"))
    }
    @Test fun `the player error is shown once and never as an empty message`() {
        val error=IllegalStateException("boom")
        assertNotNull(playerErrorMessage(error,null))
        // The same error is not shown twice.
        assertNull(playerErrorMessage(error,playbackFailureMessage(error)))
        // No error never clears the channel.
        assertNull(playerErrorMessage(null,"что-то"))
    }
}
