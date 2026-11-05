package ru.nedotify.app.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalImportBatchTest {
    @Test fun revokedAndUnavailableDocumentsDoNotPreventLaterValidImports()=runTest {
        val saved=mutableListOf<String>()
        val result=importLocalBatch(listOf("revoked","first","unavailable","second")) {
            when(it) {"revoked"->throw SecurityException();"unavailable"->throw IOException();else->saved+=it}
        }
        assertEquals(listOf("first","second"),saved)
        assertEquals(LocalImportResult(2,2),result)
    }
    @Test fun duplicateDocumentsAreOnlyImportedAndCountedOnce()=runTest {
        val saved=mutableListOf<String>()
        assertEquals(LocalImportResult(2,0),importLocalBatch(listOf("one","one","two")) {saved+=it})
        assertEquals(listOf("one","two"),saved)
    }
    @Test fun cancellationIsNotReportedAsAFileFailureAndStopsTheBatch()=runTest {
        val visited=mutableListOf<String>()
        try {
            importLocalBatch(listOf("one","cancel","three")) {visited+=it;if(it=="cancel")throw CancellationException()}
            fail("Cancellation was swallowed")
        } catch(_:CancellationException) {assertEquals(listOf("one","cancel"),visited)}
    }
    @Test fun cancelledNonCooperativeProviderCannotContinueWithAnotherDocument()=runTest {
        val released=CompletableDeferred<Unit>();val visited=mutableListOf<String>()
        val job=launch {importLocalBatch(listOf("slow","later")) {
            visited+=it;withContext(NonCancellable) {released.await();throw IOException("provider died")}
        }}
        runCurrent();job.cancel();released.complete(Unit);runCurrent()
        assertEquals(listOf("slow"),visited)
        assertTrue(job.isCancelled)
    }
}
