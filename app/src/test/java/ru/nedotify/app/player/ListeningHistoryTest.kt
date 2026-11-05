package ru.nedotify.app.player

import org.junit.Assert.*
import org.junit.Test
import ru.nedotify.app.data.ListeningSession

class ListeningHistoryTest {
    @Test fun `same recording source replacement keeps one measured session but explicit replay starts another`() {
        var time=0L;val records=mutableListOf<ListeningSession>()
        val history=ListeningHistory({time},{1000},records::add)
        history.transition("a",false);history.playing(true)
        time=100;history.transition("a",false,forceNewSession=false)
        time=200;history.checkpoint()
        assertEquals(1,records.map {it.id}.distinct().size)
        assertEquals(200L,records.last().listenedMs)
        time=300;history.transition("a",false)
        time=400;history.playing(false)
        val sessions=records.groupBy {it.id}.values.map {it.last()}
        assertEquals(2,sessions.size);assertEquals(listOf(300L,100L),sessions.map {it.listenedMs})
        assertEquals(listOf("a","a"),sessions.map {it.trackId})
    }
    @Test fun `automatic advance completes old session and repeat creates a new session`() {
        var time=0L;val records=mutableListOf<ListeningSession>()
        val history=ListeningHistory({time},{1000},records::add)
        history.transition("a",false);history.playing(true);time=1000;history.transition("b",true)
        time=1500;history.transition("b",true);time=1750;history.playing(false)
        val sessions=records.groupBy {it.id}.values.map {it.last()}
        assertEquals(listOf("a","b","b"),sessions.map {it.trackId})
        assertEquals(listOf(1000L,500L,250L),sessions.map {it.listenedMs})
        assertEquals(listOf(true,true,false),sessions.map {it.completed})
    }
    @Test fun `paused and ended sessions do not write unchanged snapshots`() {
        var time=0L;val records=mutableListOf<ListeningSession>()
        val history=ListeningHistory({time},{1000},records::add)
        history.transition("a",false);history.playing(true);time=100;history.playing(false);history.checkpoint(true)
        val writes=records.size
        repeat(100){time+=5000;history.checkpoint()}
        assertEquals(writes,records.size);assertTrue(records.last().completed)
    }
}
