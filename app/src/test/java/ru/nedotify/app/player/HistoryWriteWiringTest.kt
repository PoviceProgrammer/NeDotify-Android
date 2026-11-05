package ru.nedotify.app.player

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The wiring of the checkpoint write in the player.
 *
 * The fence between a checkpoint and the removal of a track lives in the
 * repository - but a player that writes the checkpoint itself never reaches
 * it, and the write would be unfenced again. The behaviour of the service
 * cannot be observed by a unit test (a MediaSessionService needs a device),
 * so the wiring is pinned on the source of the callback.
 *
 * The race the wiring protects against is pinned behaviourally in
 * `LibraryWriteFenceTest`.
 */
class HistoryWriteWiringTest {
    @Test fun theCheckpointOfThePlayerGoesThroughTheFence() {
        val block=historyBlock()
        assertTrue("the checkpoint must be written through the fenced path",
            block.contains("repository.recordSession(record)"))
        assertFalse("the player must not look the track up on its own",block.contains("repository.track("))
        assertFalse("the player must not remember the track on its own",block.contains("dao.remember"))
        assertFalse("the player must not write the session on its own",block.contains("dao.putSession"))
    }
    @Test fun theFailureOfTheCheckpointIsStillReported() {
        val block=historyBlock()
        assertTrue("the cancellation must not be swallowed",
            block.contains("catch(cancelled:CancellationException){throw cancelled}"))
        assertTrue("the failure must be logged",block.contains("\"Listening history could not be saved\""))
        assertTrue("the failure must reach the controller",block.contains(
            "session.sendError(SessionError(SessionError.ERROR_IO,\"Не удалось сохранить историю прослушивания. Проверьте свободное место\"))"))
        assertTrue("a destroyed service must stay silent",block.contains("if(!destroyed && ::session.isInitialized)"))
    }
    /** The history property of the service: the callback that writes a checkpoint. */
    private fun historyBlock():String {
        val service=source("ru/nedotify/app/player/PlaybackService.kt")
        val block=Regex("private val history=.*?(?=\\n    private )",RegexOption.DOT_MATCHES_ALL).find(service)?.value
        assertNotNull("the history property of the player was not found",block)
        return block!!
    }
    /** The source of a file of the module, found from the working directory. */
    private fun source(path:String):String {
        var root=File("").absoluteFile
        while(root.parentFile!=null) {
            val file=File(root,"src/main/java/$path")
            if(file.isFile)return file.readText()
            root=root.parentFile
        }
        throw AssertionError("the source file $path was not found")
    }
}
