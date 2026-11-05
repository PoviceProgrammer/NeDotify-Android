package ru.nedotify.app.data

/**
 * An allocation failure is an `Error`, not an `Exception`.
 *
 * It used to escape every `catch (Exception)` and killed the process: a document that was too
 * large for the heap of the device was a crash instead of a failed import. Every boundary that
 * reads or writes a document of an unknown size converts it into an ordinary, user facing
 * failure.
 *
 * The block is inlined, so a suspending call may be made inside it at a suspending call site.
 */
internal inline fun <T> allocationSafe(message:String, block:()->T):T =
    try { block() } catch (_:OutOfMemoryError) { throw SourceFailure(message) }
