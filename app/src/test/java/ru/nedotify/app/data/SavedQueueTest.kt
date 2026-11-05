package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * The queue keys: the current one is written, the legacy one an older install
 * wrote is still read.
 */
class SavedQueueTest {
    @Test fun `the current key wins`() {
        assertEquals("new",savedQueue("new","old"))
    }
    @Test fun `the legacy key is the fallback of an older install`() {
        assertEquals("old",savedQueue(null,"old"))
        assertEquals("old",savedQueue("","old"))
    }
    @Test fun `an empty queue is an empty string`() {
        assertEquals("",savedQueue(null,null))
        assertEquals("",savedQueue("",""))
    }
}
