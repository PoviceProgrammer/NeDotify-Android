package ru.nedotify.app.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The preparation of a tapped track must survive a transport or queue command.
 *
 * For a catalogue track the preparation takes up to 20 s. Cancelling it there
 * discarded the user's selection silently: the tapped track never played, the
 * spinner vanished without an error and the previous track resumed (REG P2-65).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCommandsTest {
    private fun dispatch():Pair<PlaybackCommands,MutableList<PlaybackCommand>> {
        val dropped=mutableListOf<PlaybackCommand>()
        val commands=PlaybackCommands {dropped+=PlaybackCommand.SELECT}
        return commands to dropped
    }

    @Test fun `a transport command does not drop the pending preparation`() {
        val (commands,dropped)=dispatch()
        commands.run(PlaybackCommand.TRANSPORT){}
        assertTrue("The transport must not discard the pending selection",dropped.isEmpty())
    }

    @Test fun `a queue command does not drop the pending preparation`() {
        val (commands,dropped)=dispatch()
        commands.run(PlaybackCommand.QUEUE){}
        assertTrue("The queue command must not discard the pending selection",dropped.isEmpty())
    }

    @Test fun `a new selection drops the preparation that is running`() {
        val (commands,dropped)=dispatch()
        commands.run(PlaybackCommand.SELECT){}
        assertEquals(1,dropped.size)
    }

    @Test fun `an invalidation drops the preparation that is running`() {
        val (commands,dropped)=dispatch()
        commands.run(PlaybackCommand.INVALIDATE){}
        assertEquals(1,dropped.size)
    }

    @Test fun `the command always runs`() {
        val (commands,_)=dispatch()
        var ran=false
        commands.run(PlaybackCommand.TRANSPORT){ran=true}
        assertTrue(ran)
    }

    /**
     * The end-to-end shape of the defect: the preparation is submitted, a
     * transport command arrives, and the tapped track still plays.
     */
    @Test fun `the tapped track still plays after a transport command`()=runTest {
        val preparation=PlaybackPreparation<String>(this)
        val played=mutableListOf<String>()
        preparation.submit(load={"tapped"},onReady={played+=it},onFailure={fail()})
        runCurrent()
        // The transport command must not cancel the preparation.
        PlaybackCommands{preparation.cancel()}.run(PlaybackCommand.TRANSPORT){}
        runCurrent()
        assertEquals(listOf("tapped"),played)
    }
}
