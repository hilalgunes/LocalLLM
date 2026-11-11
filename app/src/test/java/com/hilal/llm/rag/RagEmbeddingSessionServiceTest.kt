package com.hilal.llm.rag

import com.hilal.llm.catalog.RuntimeType
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RagEmbeddingSessionServiceTest {
    private val model = ResolvedEmbeddingModel("model", "Model", RuntimeType.LLAMA_CPP, "/first", null)
    private class Provider : RagEmbeddingProvider {
        var closed = false
        var operation: suspend () -> FloatArray = { floatArrayOf(1f) }
        override suspend fun embed(text: String, embeddingModelPath: String, tokenizerPath: String?, purpose: RagEmbeddingPurpose) = operation()
        override suspend fun canInitialize(embeddingModelPath: String, tokenizerPath: String?) = true
        override fun close() { closed = true }
    }

    @Test fun reuseAndFileChangeReleaseOldProvider(): Unit = runBlocking {
        val created = mutableListOf<Provider>()
        val service = RagEmbeddingSessionService { Provider().also(created::add) }
        service.embed("a", model, RagEmbeddingPurpose.DOCUMENT)
        service.embed("b", model, RagEmbeddingPurpose.QUERY)
        assertEquals(1, created.size)
        service.embed("c", model.copy(modelPath = "/second"), RagEmbeddingPurpose.QUERY)
        assertTrue(created.first().closed)
        assertFalse(created.last().closed)
        service.close()
        assertTrue(created.last().closed)
    }

    @Test fun closeWaitsForActiveEmbedding(): Unit = runBlocking {
        val provider = Provider()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        provider.operation = { started.complete(Unit); finish.await(); floatArrayOf(1f) }
        val service = RagEmbeddingSessionService { provider }
        val work = launch { service.embed("a", model, RagEmbeddingPurpose.QUERY) }
        started.await()
        val close = launch(start = CoroutineStart.UNDISPATCHED) { service.close() }
        assertFalse(provider.closed)
        assertFalse(close.isCompleted)
        finish.complete(Unit)
        withTimeout(1000) { work.join(); close.join() }
        assertTrue(provider.closed)
    }

    @Test fun cancellationReleasesProviderAndAllowsRetry(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val first = Provider().apply { operation = { started.complete(Unit); awaitCancellation() } }
        var opens = 0
        val service = RagEmbeddingSessionService { if (opens++ == 0) first else Provider() }
        val work = launch { service.embed("a", model, RagEmbeddingPurpose.QUERY) }
        started.await()
        work.cancelAndJoin()
        assertTrue(first.closed)
        assertArrayEquals(floatArrayOf(1f), service.embed("b", model, RagEmbeddingPurpose.QUERY), 0f)
        service.close()
    }
}
