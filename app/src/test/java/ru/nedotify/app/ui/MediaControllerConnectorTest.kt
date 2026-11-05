package ru.nedotify.app.ui

import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.*
import org.junit.Test

/**
 * The controller future is one-shot: once the session is gone the controller
 * can never play again. Every disconnect must build a new one, otherwise every
 * playback entry point is a silent no-op (P3-108).
 */
class MediaControllerConnectorTest {
    private fun connector():Pair<ControllerConnector,MutableList<ListenableFuture<MediaController>>> {
        val built=mutableListOf<ListenableFuture<MediaController>>()
        val connector=ControllerConnector(
            build={SettableFuture.create<MediaController>().also {built+=it}},
            release={},
            onBuilt={})
        return connector to built
    }

    @Test fun `a disconnect builds a new controller`() {
        val (connector,built)=connector()
        connector.connect()
        assertEquals(1,connector.builds)
        // The session is gone: the controller is dead.
        connector.reconnect()
        assertEquals(2,connector.builds)
        assertEquals(2,built.size)
        assertNotSame(built[0],built[1])
    }
    @Test fun `a second connect does not build a second controller`() {
        val (connector,_)=connector()
        connector.connect();connector.connect()
        assertEquals(1,connector.builds)
    }
    @Test fun `the dead controller is released`() {
        var released=0
        val built=mutableListOf<ListenableFuture<MediaController>>()
        val connector=ControllerConnector(
            build={SettableFuture.create<MediaController>().also {built+=it}},
            release={released++},
            onBuilt={})
        connector.connect();connector.reconnect()
        assertEquals(1,released)
        assertEquals(2,built.size)
    }
    @Test fun `a closed connector builds nothing again`() {
        val (connector,_)=connector()
        connector.connect();connector.close()
        assertEquals(1,connector.builds)
        assertEquals(0,connector.future?.let {1} ?: 0)
    }
}
