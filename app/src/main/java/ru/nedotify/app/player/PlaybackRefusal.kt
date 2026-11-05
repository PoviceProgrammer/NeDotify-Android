package ru.nedotify.app.player

import ru.nedotify.app.data.SourceFailure

/** The gate refused the play: the user is told, without blaming them. */
internal const val PLAYBACK_REFUSED="Воспроизведение не начато: звук был прерван. Повторите попытку"

/** The gate refused the play; the controller sees a failed command, not a silent no-op. */
internal class PlaybackRefusedException(message:String):IllegalStateException(message)

/**
 * The reason a play is refused, or null when it may start.
 *
 * A **stale** play — one that was enqueued before an interruption — is
 * refused: it must not undo the pause that the environment applied. A fresh
 * play (the media button, the notification, a command enqueued after the
 * interruption) is never refused.
 */
internal fun playbackRefusal(playWhenReady:Boolean,stale:Boolean):String? =
    if(playWhenReady&&stale)PLAYBACK_REFUSED else null

/**
 * The queue is installed paused when the gate cannot let a play start: the play
 * that follows the install must not resume what the environment stopped.
 */
internal fun installPaused(playWhenReady:Boolean,canStartPlayback:Boolean):Boolean =
    playWhenReady&&!canStartPlayback

/**
 * The user-facing text of a failure.
 *
 * A raw exception message is never forwarded: it can carry a URL, a file path
 * or a SQL fragment. Only the app's own [SourceFailure] text is user-facing.
 */
internal fun failureText(error:Throwable,fallback:String):String =
    generateSequence(error){it.cause}.take(12).filterIsInstance<SourceFailure>()
        .firstOrNull()?.message?.take(240) ?: fallback
