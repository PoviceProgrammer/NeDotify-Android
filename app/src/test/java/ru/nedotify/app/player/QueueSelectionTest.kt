package ru.nedotify.app.player

import org.junit.Assert.*
import org.junit.Test
import ru.nedotify.app.data.SourceFailure
import ru.nedotify.app.data.Track

class QueueSelectionTest {
    @Test fun `Flow additions follow every existing shuffled window and keep old traversal`() {
        val previous=intArrayOf(2,0,3,1)
        val appended=appendedShuffleTraversal(previous,4,3)
        assertArrayEquals(intArrayOf(2,0,3,1,4,5,6),appended)
        assertArrayEquals(intArrayOf(2,0,3,1),previous)
        val current=1
        assertEquals(listOf(4,5,6),appended.drop(appended.indexOf(current)+1))
    }
    @Test fun `corrupt shuffle orders fall back to a complete deterministic permutation`() {
        assertArrayEquals(intArrayOf(0,1,2,3),appendedShuffleTraversal(intArrayOf(1,1),2,2))
        assertArrayEquals(intArrayOf(0,1),appendedShuffleTraversal(intArrayOf(),0,2))
        assertArrayEquals(intArrayOf(1,0),appendedShuffleTraversal(intArrayOf(1,0),2,0))
    }
    @Test fun `offline refresh rebinds future completed downloads while preserving duplicate slots and missing metadata`() {
        data class Binding(val id:String,val source:String)
        val local=Binding("local","content")
        val old=Binding("download","nedotify")
        val unknown=Binding("missing","nedotify")
        val cached=old.copy(source="nedotify-offline")
        val items=listOf(local,old,unknown,old)
        val changes=planQueueSourceRefresh(items,mapOf("local" to local,"download" to cached),0) {it.id}
        assertEquals(listOf(1,3),changes.map {it.index})
        val rebound=items.toMutableList().also {rows->changes.forEach {rows[it.index]=it.item}}
        assertEquals(listOf(local,cached,unknown,cached),rebound)
        assertEquals(items.map {it.id},rebound.map {it.id})
    }
    @Test fun `offline refresh updates the audible source last without changing its queue index`() {
        val items=listOf("a:online","b:online","c:online")
        val changes=planQueueSourceRefresh(items,mapOf("a" to "a:offline","b" to "b:offline","c" to "c:offline"),1) {it.substringBefore(':')}
        assertEquals(listOf(0,2,1),changes.map {it.index})
        assertEquals("b:offline",changes.last().item)
    }
    @Test fun `clicked track absent from a refreshed list becomes the first item`() {
        val clicked=Track("new","New",source="local");val other=Track("other","Other",source="local")
        assertEquals(listOf(clicked,other),playbackQueue(clicked,listOf(other,other)))
        assertEquals(listOf(clicked),playbackQueue(clicked,emptyList()))
    }
    @Test fun `existing selected item keeps the surrounding queue order`() {
        val a=Track("a","A",source="local");val b=Track("b","B",source="local")
        assertEquals(listOf(a,b),playbackQueue(b,listOf(a,b,b)))
    }
    @Test fun `missing preceding track preserves selected track and its position`() {
        val selected=selectQueue(listOf("missing","b","c"),mapOf("b" to "B","c" to "C"),1)
        assertEquals(listOf("B","C"),selected.items);assertEquals(0,selected.startIndex);assertTrue(selected.keptRequestedStart)
    }
    @Test fun `missing selected track moves to next available and resets position`() {
        val selected=selectQueue(listOf("a","missing","c"),mapOf("a" to "A","c" to "C"),1)
        assertEquals(1,selected.startIndex);assertFalse(selected.keptRequestedStart)
    }
    @Test fun `repeated ids retain requested occurrence`() {
        val selected=selectQueue(listOf("a","missing","a"),mapOf("a" to "A"),2)
        assertEquals(1,selected.startIndex);assertEquals(listOf(0,2),selected.originalIndices)
    }
    @Test fun `large queue retains order`() {
        val ids=(0 until 3000).map(Int::toString)
        val selected=selectQueue(ids,ids.filter {it!="1"}.associateWith {it},2000)
        assertEquals("2000",selected.items[selected.startIndex]);assertEquals(2999,selected.items.size)
    }
    @Test(expected=SourceFailure::class) fun `all missing queue has a clear error`() {selectQueue(listOf("missing"),emptyMap<String,String>(),0)}
    @Test fun `explicit empty queue clears without choosing an unavailable track`() {
        val selected=selectQueue(emptyList(),mapOf("old" to "Old"),Int.MAX_VALUE)
        assertTrue(selected.items.isEmpty());assertTrue(selected.originalIndices.isEmpty())
        assertEquals(0,selected.startIndex);assertFalse(selected.keptRequestedStart)
    }
}
