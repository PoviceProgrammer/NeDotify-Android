package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * The text of a media failure. It must survive the wrapping that Media3 puts
 * around it, because the user is told the text of the failure and not the
 * text of the wrapper.
 */
class MediaStatusTest {
    @Test fun `an expired signature and a refused host say different things`() {
        val expired=mediaStatusMessage(401);val refused=mediaStatusMessage(403)
        assertNotNull(expired);assertNotNull(refused)
        assertNotEquals(expired,refused)
        assertTrue(expired!!.contains("Повторите"))
        assertTrue(refused!!.contains("Повторите"))
        assertNull(mediaStatusMessage(404))
        assertNull(mediaStatusMessage(500))
    }
    @Test fun `the text of a status is what the user is told`() {
        assertEquals("Ссылка на аудио истекла. Повторите попытку",mediaStatusMessage(401))
        assertEquals("Источник ограничил доступ к аудио. Повторите попытку",mediaStatusMessage(403))
    }
    @Test fun `the text of a media failure reaches the user through the wrapping`() {
        val refused=SourceFailure(mediaStatusMessage(403)!!,403)
        val wrapped=IOException("HttpDataSourceException",refused)
        assertEquals(mediaStatusMessage(403),mediaSessionError(wrapped))
        assertEquals(mediaStatusMessage(401),mediaSessionError(SourceFailure(mediaStatusMessage(401)!!,401)))
    }
    @Test fun `a failure that is not a media failure has no text`() {
        assertNull(mediaSessionError(IOException("connection reset")))
        assertNull(mediaSessionError(RuntimeException("other")))
    }
}
