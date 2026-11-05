package ru.nedotify.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

class LibraryWritesTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun concurrentCheckpointFinishesBeforeDeletionAndCannotResurrectAfterwards()=runTest {
        val track=Track("writes-track","Title",source="local")
        val rows=mutableMapOf(track.id to track)
        val sessions=mutableListOf<ListeningSession>()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val writes=LibraryWrites({rows[it]}, {item,session->
            entered.complete(Unit);release.await();rows[item.id]=item;sessions.add(session)
        }, {id->rows.remove(id);sessions.removeAll {it.trackId==id}}, {item->rows[item.id]=item})
        val session=ListeningSession("s",track.id,1,1000,false)
        val recording=async {writes.record(session)}
        entered.await()
        val deleting=async {writes.delete(track.id)}
        runCurrent();assertFalse(deleting.isCompleted)
        release.complete(Unit);recording.await();deleting.await()
        assertTrue(rows.isEmpty());assertTrue(sessions.isEmpty())
        writes.record(session.copy(listenedMs=2000))
        assertTrue(rows.isEmpty());assertTrue(sessions.isEmpty())
    }
}
