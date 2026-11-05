package ru.nedotify.app.data

/**
 * The diagnostic sink of the preferences layer is swapped for the duration of
 * a block.
 *
 * The production sink is the app's log, and `android.util.Log` is not
 * implemented in a JVM unit test: a test that observes a diagnostic installs
 * its own recorder and puts the old sink back afterwards.
 */
internal suspend fun withDiagnostics(sink: PreferenceDiagnostics, block: suspend () -> Unit) {
    val previous = PreferenceLog.diagnostics
    PreferenceLog.diagnostics = sink
    try { block() } finally { PreferenceLog.diagnostics = previous }
}
