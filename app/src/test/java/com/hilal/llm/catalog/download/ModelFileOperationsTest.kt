package com.hilal.llm.catalog.download

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ModelFileOperationsTest {
    @Test fun catalogEntryPointsShareMutationLock(): Unit = runBlocking {
        val one = ModelFileOperations.forRoot("shared-test")
        val two = ModelFileOperations.forRoot("shared-test")
        assertSame(one, two)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val download = launch { one.mutate { order += "download"; started.complete(Unit); release.await() } }
        started.await()
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) { two.mutate { order += "delete" } }
        assertEquals(listOf("download"), order)
        release.complete(Unit)
        withTimeout(1000) { download.join(); deletion.join() }
        assertEquals(listOf("download", "delete"), order)
    }

    @Test fun cancelledWaiterDoesNotInterruptActiveDownload(): Unit = runBlocking {
        val operations = ModelFileOperations()
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { operations.mutate { release.await() } }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { operations.mutate { fail("Cancelled request ran") } }
        second.cancelAndJoin()
        assertTrue(first.isActive)
        release.complete(Unit)
        first.join()
        assertEquals(42, operations.mutate { 42 })
    }
}
