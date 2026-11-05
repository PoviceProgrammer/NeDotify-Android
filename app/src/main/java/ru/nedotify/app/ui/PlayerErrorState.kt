package ru.nedotify.app.ui

/**
 * The error the player screen shows.
 *
 * Two sources feed it:
 * - the **media error** of the player (`player.playerError`);
 * - the error of a command the session **refused** — a play that the
 *   interruption gate declined is a command failure, not a media failure, so
 *   the player never carries it and the player screen would stay silent.
 *
 * The media error wins. A refused command belongs to the track it was refused
 * on: it disappears with that track and with the retry that follows it.
 */
internal class PlayerErrorState {
    private var commandError:String?=null
    private var commandTrackId:String=""

    /** The session refused a command for [trackId]. */
    fun onCommandRefused(trackId:String,text:String) {
        commandError=text
        commandTrackId=trackId
    }

    /** The user retried: the refusal is no longer news. */
    fun onRetry() {
        commandError=null
        commandTrackId=""
    }

    /** The track changed: a refusal of the old track must not be shown. */
    fun onTrackChanged(trackId:String) { if(trackId!=commandTrackId)onRetry() }

    /** The error to render for [trackId]; the media error always wins. */
    fun errorFor(trackId:String,mediaError:String?):String? =
        mediaError ?: commandError.takeIf { commandTrackId==trackId }
}
