package ru.nedotify.app.player

import ru.nedotify.app.data.ListeningSession
import java.util.UUID

class ListeningHistory(monotonic:()->Long,private val wallTime:()->Long=System::currentTimeMillis,
    private val publish:(ListeningSession)->Unit) {
    private val clock=ListeningClock(monotonic)
    private var trackId:String?=null
    private var sessionId=""
    private var startedAt=0L
    private var completed=false
    private var sent:ListeningSession?=null
    fun transition(id:String?,completedPrevious:Boolean,forceNewSession:Boolean=true) {
        // Switching the same recording from a signed URL to a local/offline URI is
        // a source refresh, not another listen. Explicit queue sets and repeats opt in.
        if(!forceNewSession && id==trackId) {checkpoint(completedPrevious);return}
        checkpoint(completedPrevious)
        trackId=id;sessionId=UUID.randomUUID().toString();startedAt=wallTime();completed=false;sent=null;clock.reset()
    }
    fun playing(value:Boolean) {clock.playing(value);checkpoint()}
    fun checkpoint(finished:Boolean=false) {
        clock.tick();completed=completed||finished
        val id=trackId?:return
        if(clock.elapsedMs<=0)return
        val record=ListeningSession(sessionId,id,startedAt,clock.elapsedMs,completed)
        if(record!=sent){publish(record);sent=record}
    }
}
