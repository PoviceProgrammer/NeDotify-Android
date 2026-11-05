package ru.nedotify.app.player

import com.google.common.util.concurrent.ListenableFuture

/**
 * The gate that decides whether a play may start.
 *
 * Media3 1.11.1 reports a controller's play/pause command to the session at
 * *enqueue* time (`MediaSession.Callback.onPlayerCommandRequest`) and executes
 * it *later*, after the queue the command belongs to is installed
 * (`ConnectedControllersManager.addToCommandQueue` → `flushCommandQueue`). The
 * media button and the notification's play button never go through that
 * callback at all: they call the player directly
 * (`MediaSessionImpl.handleMediaControllerPlayRequestInternal` →
 * `Util.handlePlayButtonAction` → `Player.play()`).
 *
 * The gate therefore keeps two ledgers:
 *
 *  * the **command ledger** — one entry per enqueued play/pause command,
 *    holding the interruption counter of the moment it was enqueued. A command
 *    that was enqueued *before* an interruption is **stale**: it must not
 *    resume the playback that the environment (or another controller) stopped.
 *  * the **fresh plays** — plays that arrived through the media button or the
 *    notification. They never went through the enqueue callback, so they are
 *    never stale and are never refused.
 *
 * A play that is executed while no command is outstanding is a fresh request
 * as well: the gate refuses only what it can *prove* is stale. The refusal is
 * therefore never sticky — the next play is judged on its own merits.
 *
 * The pending *selection* is a separate concern: an interruption must never
 * destroy the queue the user just asked for (REG P2-12), and only a newer
 * selection supersedes a pending one.
 */
internal class PendingSelectionGate {
    private var pending:ListenableFuture<*>?=null
    /** The interruption counter; every interruption advances it. */
    private var epoch=0
    /** The interruption counter of every command that is enqueued but not executed yet. */
    private val commands=ArrayDeque<Int>()
    /** The plays that arrived through the media button or the notification. */
    private var freshPlays=0

    /** A new selection supersedes an older pending one and re-arms the gate. */
    fun begin(future:ListenableFuture<*>) {
        pending?.takeIf {!it.isDone}?.cancel(false)
        pending=future
        commands.clear();freshPlays=0
    }
    fun complete(future:ListenableFuture<*>) {if(pending===future)pending=null}

    /**
     * A play/pause command reached the enqueue callback: Media3 will execute it
     * after the queue is installed. The command carries the epoch of this
     * moment, so an interruption that happens later makes it stale.
     */
    fun expectQueuedPlay() {commands.addLast(epoch);freshPlays=0}

    /**
     * A play that did not go through the enqueue callback (the media button,
     * the notification): it is a fresh request and is never refused.
     */
    fun expectFreshPlay() {freshPlays++}

    /**
     * The controller is gone: the commands it queued will never be executed, so
     * they must not stay in the ledger.
     */
    fun abandonCommands() {commands.clear();freshPlays=0}

    /**
     * An interruption: a focus loss, becoming-noisy, another controller's pause.
     * Every command that was enqueued before it is stale from now on; the
     * pending selection is untouched — the queue the user asked for survives.
     *
     * @return true when an outstanding command was invalidated.
     */
    fun interruptPlay():Boolean {
        val armed=commands.isNotEmpty()
        epoch++
        return armed
    }

    /**
     * The play that is executed now.
     *
     * @return true when the play is stale and must be refused.
     */
    fun evaluatePlay(playWhenReady:Boolean):Boolean {
        if(freshPlays>0) {freshPlays--;return false}
        val command=commands.removeFirstOrNull()?:return false
        return playWhenReady&&command<epoch
    }

    /**
     * The queue may only be installed paused while a command that is still
     * outstanding is stale: the play that follows the install must not resume
     * what the environment stopped.
     */
    val canStartPlayback:Boolean get()=commands.none {it<epoch}
}
