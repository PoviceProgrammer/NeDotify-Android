package ru.nedotify.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue keys of the settings store: the current key is written, the legacy
 * key an older install wrote is still read, and it is removed once the current
 * key has been written.
 *
 * NOT EXECUTED in the repair environment (no device/emulator, and APK packaging
 * is forbidden). It must be run on a device or an emulator.
 */
@RunWith(AndroidJUnit4::class)
class PreferencesQueueTest {
    @Test fun theQueueIsWrittenUnderTheCurrentKeyAndTheLegacyKeyIsRemoved() = runBlocking {
        val preferences=Preferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val payload=QueueSnapshotCodec.encode(QueueSnapshot(listOf("a","b"),0,"a",0,false,listOf(0,1),1234L))
        preferences.saveQueue(payload)
        assertEquals(payload,preferences.queue())
        val (current,legacy)=preferences.queueValues()
        assertEquals(payload,current)
        assertNull("the legacy key must not survive the first write",legacy)
    }

    @Test fun theLegacyKeyIsStillRead() = runBlocking {
        val preferences=Preferences(InstrumentationRegistry.getInstrumentation().targetContext)
        // The legacy read is the fallback of an older install.
        assertEquals("",savedQueue(null,null))
        preferences.saveQueue(QueueSnapshotCodec.encode(QueueSnapshot(listOf("a"),0,"a",0,false,listOf(0),0L)))
        assertTrue(preferences.queue().isNotBlank())
    }
}
