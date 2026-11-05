package ru.nedotify.app.downloads

import org.junit.Assert.*
import org.junit.Test
import ru.nedotify.app.data.SourceFailure
import ru.nedotify.app.data.mediaStatusMessage
import java.io.IOException

/**
 * The classification of a media failure. It must survive the wrapping that
 * Media3 puts around it, because the retry policy and the text of the
 * failure are read from it.
 */
class MediaStatusTest {
    @Test fun `the status of a media failure survives the wrapping of the loader`() {
        val source=SourceFailure(mediaStatusMessage(403)!!,403)
        val wrapped=IOException("HttpDataSourceException",source)
        assertEquals(403,mediaResponseStatus(wrapped))
    }
    @Test fun `a failure without a status has no status`() {
        assertNull(mediaResponseStatus(IOException("connection reset")))
        assertNull(mediaResponseStatus(SourceFailure("Источник вернул пустой ответ")))
    }
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
}
