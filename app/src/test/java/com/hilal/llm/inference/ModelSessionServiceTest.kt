package com.hilal.llm.inference

import com.hilal.llm.benchmark.BenchmarkPrompt
import com.hilal.llm.benchmark.StreamCallback
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.EngineType
import com.hilal.llm.catalog.PromptTemplateType
import com.hilal.llm.catalog.RuntimeType
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ModelSessionServiceTest {
    private fun selection(id: String = "one") = BenchmarkModelSelection(
        id, id, RuntimeType.LLAMA_CPP, EngineType.LLAMA_CPP,
        PromptTemplateType.GENERIC, "/$id.gguf", null
    )

    private class Backend : ModelBackendSession {
        var closes = 0
        var failure: Throwable? = null
        var started: CompletableDeferred<Unit>? = null
        var finish: CompletableDeferred<Unit>? = null
        override suspend fun generate(request: BenchmarkPrompt, callback: StreamCallback) {
            started?.complete(Unit)
            finish?.await()
            failure?.let { callback.onError(it); return }
            callback.onToken("Mer")
            callback.onToken("haba")
            callback.onComplete()
        }
        override suspend fun close() { closes++ }
    }

    @Test fun reusesWarmModelAndClosesOnSwitch() = runBlocking<Unit> {
        val backends = mutableListOf<Backend>()
        val service = ModelSessionService { Backend().also(backends::add) }
        val first = service.openSession(selection())
        val updates = mutableListOf<String>()
        assertEquals("Merhaba", first.generate("hello", 20, updates::add))
        assertEquals(listOf("Mer", "Merhaba"), updates)
        first.close()
        first.close()
        service.openSession(selection()).close()
        assertEquals(1, backends.size)
        service.openSession(selection("two")).close()
        assertEquals(1, backends[0].closes)
        service.close()
        assertEquals(1, backends[1].closes)
    }

    @Test fun cancelledPreparationDoesNotBlockNextRequest() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        var attempts = 0
        val service = ModelSessionService {
            if (++attempts == 1) { started.complete(Unit); awaitCancellation() }
            Backend()
        }
        val loading = launch { service.openSession(selection()) }
        started.await()
        loading.cancelAndJoin()
        withTimeout(1000) { service.openSession(selection()).close() }
        service.close()
    }

    @Test fun cancelledGenerationCanReleaseItsLease() = runBlocking<Unit> {
        val backend = Backend().apply {
            started = CompletableDeferred()
            finish = CompletableDeferred()
        }
        val service = ModelSessionService { backend }
        val running = launch {
            val session = service.openSession(selection())
            try { session.generate("hello", 20) } finally { session.close() }
        }
        backend.started!!.await()
        running.cancelAndJoin()
        withTimeout(1000) { service.openSession(selection()).close() }
        service.close()
    }

    @Test fun closeWaitsForGenerationAndRejectsFurtherUse() = runBlocking<Unit> {
        val backend = Backend().apply {
            started = CompletableDeferred()
            finish = CompletableDeferred()
        }
        val service = ModelSessionService { backend }
        val session = service.openSession(selection())
        val generating = launch { session.generate("hello", 20) }
        backend.started!!.await()
        val closing = launch { session.close() }
        yield()
        assertFalse(closing.isCompleted)
        backend.finish!!.complete(Unit)
        generating.join()
        closing.join()
        try { session.generate("again", 20); fail("Closed lease accepted generation") }
        catch (_: IllegalStateException) { }
        service.close()
        try { service.openSession(selection()); fail("Closed service accepted a lease") }
        catch (_: IllegalStateException) { }
    }

    @Test fun coldBenchmarkWaitsForLeaseAndDropsWarmModel() = runBlocking<Unit> {
        val backend = Backend()
        val service = ModelSessionService { backend }
        val session = service.openSession(selection())
        val cold = async { service.withColdRuntime { backend.closes } }
        yield()
        assertFalse(cold.isCompleted)
        session.close()
        assertEquals(1, cold.await())
        service.close()
    }

    @Test fun backendFailureIsPropagatedAndDoesNotLeakLease() = runBlocking<Unit> {
        val failure = IllegalArgumentException("runtime failed")
        val backend = Backend().apply { this.failure = failure }
        val service = ModelSessionService { backend }
        val session = service.openSession(selection())
        try { session.generate("hello", 20); fail("Expected runtime failure") }
        catch (actual: IllegalArgumentException) { assertSame(failure, actual) }
        finally { session.close() }
        withTimeout(1000) { service.openSession(selection()).close() }
        service.close()
    }
}
