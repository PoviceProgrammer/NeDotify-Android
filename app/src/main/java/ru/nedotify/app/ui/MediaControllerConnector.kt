package ru.nedotify.app.ui

import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture

/**
 * The lifecycle of the media controller.
 *
 * The controller future is **one-shot**: once the session is gone the
 * controller can never play again. Every disconnect therefore builds a new
 * controller, otherwise every playback entry point would be a silent no-op
 * (P3-108).
 */
internal class ControllerConnector(
    private val build:()->ListenableFuture<MediaController>,
    private val release:(ListenableFuture<MediaController>)->Unit,
    private val onBuilt:(ListenableFuture<MediaController>)->Unit,
) {
    var future:ListenableFuture<MediaController>?=null
        private set
    /** How many controllers were built. */
    var builds=0
        private set
    /** How many controllers were released. */
    var releases=0
        private set

    /** Starts a connection when none is running. */
    fun connect() {
        if(future!=null)return
        builds++
        val built=build()
        future=built
        onBuilt(built)
    }
    /** The controller is dead: a new one takes its place. */
    fun reconnect() {
        future?.let {release(it);releases++}
        future=null
        connect()
    }
    /** The owner is gone: nothing is rebuilt. */
    fun close() {
        future?.let {release(it);releases++}
        future=null
    }
}
