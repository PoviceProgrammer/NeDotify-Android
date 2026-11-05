package ru.nedotify.app.downloads

import org.junit.Assert.*
import org.junit.Test

class ForegroundFailureTest {
    private class ForegroundServiceStartNotAllowedException:RuntimeException()
    @Test fun wrappedPlatformRestrictionHasActionableMessageAndNoRetry() {
        val error=IllegalStateException("worker failed",ForegroundServiceStartNotAllowedException())
        assertTrue(foregroundStartRestricted(error))
        assertTrue(failureText(error,null).contains("Android"))
        assertTrue(failureText(error,null).contains("Откройте приложение"))
        assertFalse(downloadRetry(error,0,null))
    }
    @Test fun unrelatedWorkerFailureIsNotForegroundQuota() {
        assertFalse(foregroundStartRestricted(IllegalStateException("bad package")))
    }
}
