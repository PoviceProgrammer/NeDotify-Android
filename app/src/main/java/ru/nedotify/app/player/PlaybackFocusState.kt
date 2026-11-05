package ru.nedotify.app.player

/** Pure focus intent state: a late focus gain can never undo the user's pause. */
internal class PlaybackFocusState {
    var requested=false;private set
    var hasFocus=false;private set
    var suspended=false;private set
    var reason=1;private set
    val canPlay get()=requested && hasFocus && !suspended
    fun userRequest(play:Boolean) {requested=play;reason=1;if(!play)suspended=false}
    fun requestResult(granted:Boolean) {
        hasFocus=granted;suspended=false
        if(!granted){requested=false;reason=2}
    }
    fun transientLoss() {hasFocus=false;suspended=requested}
    fun gain() {hasFocus=requested;suspended=false}
    fun stop(why:Int) {requested=false;reason=why;suspended=false}
    fun abandon() {hasFocus=false;suspended=false}
}
