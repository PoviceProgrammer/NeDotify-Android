package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test

class QueueSnapshotCodecTest {
    private fun snapshot(position:Long=1000L)=QueueSnapshot(listOf("a","b","c"),1,"b",2,true,listOf(1,2,0),position)

    @Test fun `a written snapshot reads back unchanged`() {
        val decoded=QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(snapshot()))
        assertNotNull(decoded)
        assertEquals(listOf("a","b","c"),decoded!!.ids)
        assertEquals(1,decoded.index);assertEquals("b",decoded.currentId)
        assertEquals(2,decoded.repeat);assertTrue(decoded.shuffle)
        assertEquals(listOf(1,2,0),decoded.shuffleOrder)
        assertEquals(1000L,decoded.positionMs)
    }
    @Test fun `the payload carries the version it writes`() {
        val json=org.json.JSONObject(QueueSnapshotCodec.encode(snapshot()))
        assertEquals(QueueSnapshotCodec.VERSION,json.optInt("version"))
    }
    @Test fun `an unknown version is refused instead of restored`() {
        assertNull(QueueSnapshotCodec.decode("""{"version":99,"ids":["a"]}"""))
        assertNull(QueueSnapshotCodec.decode("""{"ids":["a"]}"""))
        assertNull(QueueSnapshotCodec.decode("not json"))
        assertNull(QueueSnapshotCodec.decode(""))
    }
    @Test fun `the legacy flat payload still restores`() {
        val legacy="""{"version":2,"ids":["a","b"],"index":1,"currentId":"b","position":4200,"repeat":1,"shuffle":false,"shuffleOrder":[1,0]}"""
        val decoded=QueueSnapshotCodec.decode(legacy)
        assertNotNull(decoded)
        assertEquals(listOf("a","b"),decoded!!.ids)
        assertEquals(1,decoded.index);assertEquals("b",decoded.currentId)
        assertEquals(4200L,decoded.positionMs)
        assertEquals(1,decoded.repeat);assertFalse(decoded.shuffle)
        assertEquals(listOf(1,0),decoded.shuffleOrder)
    }
    @Test fun `the identity ignores the position but not the queue`() {
        assertEquals(QueueSnapshotCodec.identity(snapshot(0L)),QueueSnapshotCodec.identity(snapshot(999_000L)))
        assertNotEquals(QueueSnapshotCodec.identity(snapshot()),QueueSnapshotCodec.identity(snapshot().copy(ids=listOf("a","c"))))
        assertNotEquals(QueueSnapshotCodec.identity(snapshot()),QueueSnapshotCodec.identity(snapshot().copy(index=2)))
        assertNotEquals(QueueSnapshotCodec.identity(snapshot()),QueueSnapshotCodec.identity(snapshot().copy(shuffle=false)))
        assertNotEquals(QueueSnapshotCodec.identity(snapshot()),QueueSnapshotCodec.identity(snapshot().copy(shuffleOrder=listOf(0,1,2))))
    }
    @Test fun `a malformed shuffle order is not shortened`() {
        val legacy="""{"version":2,"ids":["a","b"],"index":0,"shuffleOrder":[0,-1]}"""
        assertEquals(listOf(0,-1),QueueSnapshotCodec.decode(legacy)!!.shuffleOrder)
    }
    @Test fun `without an index the current item decides where the queue resumes`() {
        assertEquals(1,QueueSnapshotCodec.decode("""{"version":2,"ids":["a","b"],"currentId":"b"}""")!!.index)
        assertEquals(0,QueueSnapshotCodec.decode("""{"version":2,"ids":["a","b"],"currentId":"zz"}""")!!.index)
    }
}
