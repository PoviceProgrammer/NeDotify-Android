package ru.nedotify.app.ui

/**
 * The commands of the player and the preparation they may drop.
 *
 * The preparation is the resolution of the track the user tapped; for a
 * catalogue track it can take 20 s. Only a **new selection** supersedes it:
 * the track the user tapped is the one that must play. A transport or queue
 * command acts on the player that is running — cancelling the preparation
 * there silently discarded the user's selection: the tapped track never
 * played, the spinner vanished without an error and the previous track
 * resumed (REG P2-65).
 */
internal enum class PlaybackCommand {
    /** A new track was selected. */
    SELECT,
    /** The transport of the running player: play, pause, next, previous, seek, shuffle, repeat. */
    TRANSPORT,
    /** The queue of the running player: choose, reorder, remove, clear. */
    QUEUE,
    /** The selection became invalid: the track was deleted, the app went offline. */
    INVALIDATE;

    /** Only these commands drop the preparation that is running. */
    val dropsPendingSelection:Boolean get()=this==SELECT||this==INVALIDATE
}

/**
 * The dispatch of the player commands.
 *
 * The preparation is dropped by the *kind* of the command, not by the command
 * that happens to call it: one place decides, so a new transport command
 * cannot silently discard a pending selection.
 */
internal class PlaybackCommands(private val dropPending:()->Unit) {
    /** Run a command that does not suspend. */
    fun run(command:PlaybackCommand,action:()->Unit) {
        if(command.dropsPendingSelection)dropPending()
        action()
    }
    /** Run a command that suspends; the preparation is dropped before the work. */
    suspend fun runSuspending(command:PlaybackCommand,action:suspend()->Unit) {
        if(command.dropsPendingSelection)dropPending()
        action()
    }
}
