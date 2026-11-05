package ru.nedotify.app.data

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogImportControllerTest {
    @get:Rule val temp=TemporaryFolder()
    private fun item(id:String)=CatalogTrack("spotify",id,"Title $id",listOf("Artist"),durationMs=120_000)
    private fun track(item:CatalogTrack)=Track("youtube:${item.id}",item.title,"Artist",source="youtube",sourceId=item.id)
    private class MemoryJournal:CatalogImportJournal {
        var value:CatalogImportCheckpoint?=null
        var largestItems=0
        override fun read()=value
        override fun write(checkpoint:CatalogImportCheckpoint){
            value=checkpoint
            if(checkpoint.items.size>largestItems)largestItems=checkpoint.items.size
        }
        override fun clear(){value=null}
    }
    @Test fun completedItemsAreDurableBeforeCancellationAndProcessRecreationResumesRemainingItems()=runTest {
        val journal=MemoryJournal();val commits=linkedSetOf<String>();val requests=mutableListOf<String>()
        val firstScope=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val first=CatalogImportController(firstScope,journal,{item->
            requests+=item.id;if(item.id=="b")awaitCancellation();track(item)
        },{_,track->commits+=track.id})
        first.start("spotify","Playlist",listOf(item("a"),item("b"),item("c")))
        runCurrent();assertEquals(setOf("youtube:a"),commits);assertEquals(1,journal.value!!.next)
        val playlist=journal.value!!.playlistId
        firstScope.cancel();runCurrent()
        val second=CatalogImportController(backgroundScope,journal,{item->requests+=item.id;track(item)},{request,track->
            assertEquals(playlist,request.playlistId);commits+=track.id
        })
        runCurrent();assertTrue(second.state.value.paused);assertEquals(1,second.state.value.completed)
        second.resume();runCurrent()
        assertEquals(setOf("youtube:a","youtube:b","youtube:c"),commits)
        // The finished item is never requested again: the resumed import starts
        // where the interrupted one stopped.
        assertEquals("A committed item must not be requested again",1,requests.count {it=="a"})
        assertTrue("The unfinished items must be retried",requests.count {it=="b"}>=2)
        assertEquals(3,second.state.value.completed);assertFalse(second.state.value.paused);assertNull(journal.value)
        second.clearMessage();assertNull(second.state.value.message)
    }
    @Test fun classifiedAuthFailureStopsImmediatelyAndRetainsAnActionableResumableCheckpoint()=runTest {
        val journal=MemoryJournal();var calls=0
        val controller=CatalogImportController(backgroundScope,journal,{calls++;throw SourceFailure("Повторите вход в Spotify",401)}, {_,_->fail("No commit")})
        controller.start("spotify","List",(1..10).map{item("t$it")});runCurrent()
        // The classified failure stops the request; only the bounded window was started.
        assertTrue("The request must stop at the first classified failure",calls<=CatalogImportController.DEFAULT_CONCURRENCY)
        assertEquals(0,journal.value!!.next)
        assertTrue(controller.state.value.paused);assertTrue(controller.state.value.error!!.contains("вход"))
        assertEquals(401,controller.state.value.errorCode)
        assertEquals("A classified failure is not an unavailable track",0,controller.state.value.unavailable)
    }
    @Test fun aProviderLimitKeepsItsCodeAndIsNotCollapsedIntoTheUnavailableCounter()=runTest {
        val journal=MemoryJournal()
        val controller=CatalogImportController(backgroundScope,journal,{throw SourceFailure("Сервис ограничил запросы",429)},{_,_->fail("No commit")})
        controller.start("spotify","List",listOf(item("a")));runCurrent()
        assertEquals(429,controller.state.value.errorCode)
        assertTrue(controller.state.value.error!!.contains("ограничил"))
        assertEquals(0,controller.state.value.unavailable)
        assertTrue(controller.state.value.paused);assertEquals(0,journal.value!!.next)
    }
    @Test fun theRequestIsCappedAtFiveHundredItemsAndTheTruncationIsAnnounced()=runTest {
        val journal=MemoryJournal();val committed=mutableListOf<String>()
        val controller=CatalogImportController(backgroundScope,journal,{track(it)},{_,value->committed+=value.id})
        controller.start("spotify","List",(1..600).map{item("t$it")});runCurrent()
        assertEquals(CatalogImportController.MAX_ITEMS,committed.size)
        assertEquals(CatalogImportController.MAX_ITEMS,journal.largestItems)
        assertTrue(controller.state.value.message!!.contains("первые 500"))
    }
    @Test fun anUnreachableProviderPausesTheImportAndTheUserCanResumeIt()=runTest {
        val journal=MemoryJournal();val committed=mutableListOf<String>();var unreachable=true
        val controller=CatalogImportController(backgroundScope,journal,{entry->
            if(unreachable)throw CatalogImportBlocked("Источники воспроизведения недоступны. Повторите позже")
            track(entry)
        },{_,value->committed+=value.id})
        controller.start("spotify","List",listOf(item("a"),item("b"),item("c")));runCurrent()
        // A provider that does not answer pauses the request; the list is not burned.
        assertTrue(committed.isEmpty())
        assertTrue(controller.state.value.paused)
        assertEquals("Источники воспроизведения недоступны. Повторите позже",controller.state.value.error)
        assertEquals(0,journal.value!!.next)
        unreachable=false
        controller.resume();runCurrent()
        assertEquals(listOf("youtube:a","youtube:b","youtube:c"),committed)
        assertFalse(controller.state.value.paused);assertNull(journal.value)
    }
    @Test fun offlineModePausesTheRequestBeforeAnythingIsAdded()=runTest {
        val journal=MemoryJournal();var offline=true;val committed=mutableListOf<String>()
        val controller=CatalogImportController(backgroundScope,journal,{track(it)},{_,value->committed+=value.id},
            blocker={if(offline)"Включён офлайн-режим. Импорт приостановлен" else null})
        controller.start("spotify","List",listOf(item("a")));runCurrent()
        assertTrue(controller.state.value.paused)
        assertEquals("Включён офлайн-режим. Импорт приостановлен",controller.state.value.error)
        assertTrue("Nothing may be added while the app is offline",committed.isEmpty())
        assertEquals(0,controller.state.value.completed);assertEquals(0,journal.value!!.next)
        offline=false
        controller.resume();runCurrent()
        assertEquals(1,controller.state.value.completed);assertEquals(listOf("youtube:a"),committed);assertNull(journal.value)
    }
    @Test fun theProviderWorkIsBoundedButEveryItemIsCommittedInOrder()=runTest {
        val journal=MemoryJournal();val committed=mutableListOf<String>()
        val active=AtomicInteger(0);val peak=AtomicInteger(0)
        val controller=CatalogImportController(backgroundScope,journal,{entry->
            val now=active.incrementAndGet();peak.updateAndGet {previous->maxOf(previous,now)}
            delay(50);active.decrementAndGet();track(entry)
        },{_,value->committed+=value.id},concurrency=3)
        controller.start("spotify","List",(1..6).map{item("t$it")})
        repeat(20){advanceTimeBy(50);runCurrent()}
        assertEquals(listOf("youtube:t1","youtube:t2","youtube:t3","youtube:t4","youtube:t5","youtube:t6"),committed)
        assertEquals("The provider work must be bounded",3,peak.get())
    }
    @Test fun explicitPauseKeepsAcceptedItemsAndDiscardOnlyClearsRemainingWork()=runTest {
        val journal=MemoryJournal();val committed=mutableListOf<Track>()
        val controller=CatalogImportController(backgroundScope,journal,{entry->if(entry.id=="b")awaitCancellation();track(entry)}, {_,track->committed+=track})
        controller.start("spotify","List",listOf(item("a"),item("b")));runCurrent()
        controller.pause();runCurrent()
        assertTrue(controller.state.value.paused);assertEquals(1,controller.state.value.completed)
        controller.discard();runCurrent()
        assertNull(journal.value);assertEquals(1,committed.size);assertFalse(controller.state.value.paused)
    }
    @Test fun ownItemTimeoutSkipsOneItemAndOverallBudgetPausesInsteadOfLosingTheRequest()=runTest {
        val journal=MemoryJournal()
        val controller=CatalogImportController(backgroundScope,journal,{entry->delay(1_000);track(entry)}, {_,_->},itemTimeoutMs=100,runBudgetMs=250)
        controller.start("spotify","List",(1..10).map{item("t$it")});runCurrent()
        advanceTimeBy(251);runCurrent()
        val next=journal.value!!.next
        assertTrue("The budget must stop the request before the list is done",next in 1..9)
        assertEquals("Every skipped item is counted as unavailable",next,controller.state.value.unavailable)
        assertTrue(controller.state.value.paused);assertTrue(controller.state.value.message!!.contains("лимиту"))
    }
    @Test fun commitBeforeCheckpointReplayKeepsTheSamePlaylistIdentity()=runTest {
        val memory=MemoryJournal();var failCheckpoint=true
        val journal=object:CatalogImportJournal {
            override fun read()=memory.read()
            override fun clear()=memory.clear()
            override fun write(checkpoint:CatalogImportCheckpoint) {
                if(checkpoint.next==1 && failCheckpoint){failCheckpoint=false;throw java.io.IOException("disk temporarily unavailable")}
                memory.write(checkpoint)
            }
        }
        val keys=mutableListOf<Pair<String,String>>();val saved=linkedSetOf<Pair<String,String>>()
        val controller=CatalogImportController(backgroundScope,journal,{track(it)},{request,track->
            val key=request.playlistId to track.id;keys+=key;saved+=key
        })
        controller.start("spotify","List",listOf(item("a")));runCurrent()
        assertEquals(0,memory.value!!.next);assertTrue(controller.state.value.paused)
        controller.resume();runCurrent()
        assertEquals(2,keys.size);assertEquals(keys.first(),keys.last());assertEquals(1,saved.size)
    }
    @Test fun completeRequestSurvivesAnAtomicJournalRoundTrip() {
        val file=File(temp.newFolder(),"import.json")
        val original=CatalogImportCheckpoint("playlist","spotify","Мой список",listOf(item("a"),item("b")),1,setOf("youtube:a"),0,1234)
        FileCatalogImportJournal(file).write(original)
        assertEquals(original,FileCatalogImportJournal(file).read())
        assertFalse(File(file.parentFile,file.name+".tmp").exists())
        FileCatalogImportJournal(file).clear();assertNull(FileCatalogImportJournal(file).read())
    }
    @Test fun aJournalWithMoreThanTheCapIsRejected() {
        val file=File(temp.newFolder(),"import.json")
        val oversized=CatalogImportCheckpoint("playlist","spotify","Мой список",(1..CatalogImportController.MAX_ITEMS+1).map{item("t$it")})
        FileCatalogImportJournal(file).write(oversized)
        assertThrows(SourceFailure::class.java){FileCatalogImportJournal(file).read()}
    }
    @Test fun anotherStartWhileActiveReportsItsReasonWithoutCancellingTheCurrentImport()=runTest {
        val journal=MemoryJournal()
        val controller=CatalogImportController(backgroundScope,journal,{awaitCancellation()},{_,_->})
        controller.start("spotify","First",listOf(item("a")));runCurrent()
        controller.start("spotify","Second",listOf(item("b")));runCurrent()
        assertEquals("First",controller.state.value.title);assertTrue(controller.state.value.importing)
        assertTrue(controller.state.value.error!!.contains("уже выполняется"))
        assertEquals("a",journal.value!!.items.single().id)
    }
}
