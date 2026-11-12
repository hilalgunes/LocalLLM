package com.hilal.llm.lab

import com.hilal.llm.catalog.*
import com.hilal.llm.inference.FeatureGenerationSession
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ExperimentTest {
    private val selection = BenchmarkModelSelection("model", "Model", RuntimeType.LITERT, EngineType.LITERT_LM,
        PromptTemplateType.GENERIC, "/model.litertlm", null)
    private fun definition(mode: String) = ExperimentDefinition("model", "LITERT", "Soru", 32, mode)

    @Test fun coldRequestReleasesCachedBackendBeforeOpening(): Unit = runBlocking {
        var opened = 0
        var closed = 0
        val sessions = com.hilal.llm.inference.ModelSessionService {
            opened++
            object : com.hilal.llm.inference.ModelBackendSession {
                override suspend fun generate(request: com.hilal.llm.benchmark.BenchmarkPrompt, callback: com.hilal.llm.benchmark.StreamCallback) = Unit
                override suspend fun close() { closed++ }
            }
        }
        sessions.openSession(selection).close()
        sessions.openSession(selection).close()
        assertEquals(1, opened)
        sessions.openSession(selection, true).close()
        assertEquals(2, opened)
        assertEquals(1, closed)
        sessions.close()
    }

    @Test fun warmupIsExcludedAndMeasuredOutputIsSaved(): Unit = runBlocking {
        val coldRequests = mutableListOf<Boolean>()
        var calls = 0
        var closes = 0
        var now = 0L
        val runner = ExperimentRunner({ _, cold ->
            coldRequests += cold
            object : FeatureGenerationSession {
                override suspend fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String {
                    val text = "Cevap ${++calls}"
                    onText(text)
                    return text
                }
                override suspend fun close() { closes++ }
            }
        }, { now += 1_000_000; now })
        val streamed = mutableListOf<String>()
        val result = runner.run(selection, definition("warm"), streamed::add)
        assertEquals(listOf(true, false), coldRequests)
        assertEquals(listOf("Cevap 2"), streamed)
        assertEquals("Cevap 2", result.output)
        assertEquals("complete", result.status)
        assertEquals(2, closes)
        assertEquals(result.metrics.totalMs, result.metrics.preparationMs + result.metrics.generationMs, 0.001)
    }

    @Test fun cancellationKeepsPartialOutputAndReleasesLease(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        var closed = false
        var result: ExperimentRecord? = null
        val runner = ExperimentRunner({ _, _ -> object : FeatureGenerationSession {
            override suspend fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String {
                onText("Kısmi")
                started.complete(Unit)
                awaitCancellation()
            }
            override suspend fun close() { yield(); closed = true }
        } })
        val job = launch { result = runner.run(selection, definition("cold")) {} }
        started.await()
        job.cancelAndJoin()
        assertTrue(closed)
        assertEquals("cancelled", result?.status)
        assertEquals("Kısmi", result?.output)
    }

    @Test fun recordRoundTripAndCorruptHistoryProtection() {
        val root = Files.createTempDirectory("experiments").toFile()
        try {
            val file = File(root, "history.json")
            val store = ExperimentStore(file)
            val record = ExperimentRecord(definition = definition("cold"), metrics = ExperimentMetrics(1.0, 2.0, 0.5, 3.0), status = "complete", output = "Yanıt")
            store.append(record)
            assertEquals(record, store.load().single())
            file.writeText("broken")
            assertTrue(runCatching { store.append(record) }.isFailure)
            assertEquals("broken", file.readText())
        } finally { root.deleteRecursively() }
    }
}
