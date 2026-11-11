package com.hilal.llm.rag

import com.hilal.llm.catalog.*
import com.hilal.llm.inference.*
import com.hilal.llm.benchmark.BenchmarkPrompt
import com.hilal.llm.benchmark.StreamCallback
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RagAnswerGeneratorTest {
    private val selection = BenchmarkModelSelection("model", "Model", RuntimeType.LLAMA_CPP,
        EngineType.LLAMA_CPP, PromptTemplateType.GENERIC, "/model.gguf", null)

    @Test fun ragWaitsForChatAndReusesSharedBackend(): Unit = runBlocking {
        var opens = 0
        val service = ModelSessionService {
            opens++
            object : ModelBackendSession {
                override suspend fun generate(request: BenchmarkPrompt, callback: StreamCallback) {
                    assertEquals("Belge sorusu", request.prompt)
                    callback.onToken("Mer"); callback.onToken("haba"); callback.onComplete()
                }
                override suspend fun close() = Unit
            }
        }
        val chatLease = service.openSession(selection)
        val updates = mutableListOf<String>()
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            RagAnswerGenerator(service::openSession).generate(selection, "Belge sorusu", 512, updates::add)
        }
        assertFalse(reply.isCompleted)
        chatLease.close()
        assertEquals("Merhaba", withTimeout(1000) { reply.await() })
        assertEquals(listOf("Mer", "Merhaba"), updates)
        assertEquals(1, opens)
        withTimeout(1000) { service.openSession(selection).close() }
        service.close()
    }

    @Test fun cancelledRequestStillAwaitsLeaseCleanup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var closed = false
        val generator = RagAnswerGenerator {
            object : FeatureGenerationSession {
                override suspend fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String {
                    onText("Kısmi cevap")
                    started.complete(Unit)
                    awaitCancellation()
                }
                override suspend fun close() {
                    yield()
                    currentCoroutineContext().ensureActive()
                    closed = true
                }
            }
        }
        val job = launch { generator.generate(selection, "Soru", 20) {} }
        started.await()
        job.cancelAndJoin()
        assertTrue(closed)
    }

    @Test fun generationFailureReleasesLeaseAndPropagatesError() = runBlocking {
        var closed = false
        val failure = IllegalStateException("Model hatası")
        val generator = RagAnswerGenerator {
            object : FeatureGenerationSession {
                override suspend fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String = throw failure
                override suspend fun close() { closed = true }
            }
        }
        val result = runCatching { generator.generate(selection, "Soru", 20) {} }
        assertSame(failure, result.exceptionOrNull())
        assertTrue(closed)
    }
}
