package com.hilal.llm.inference

import com.hilal.llm.benchmark.BenchmarkPrompt
import com.hilal.llm.benchmark.SelectionSource
import com.hilal.llm.benchmark.StreamCallback
import com.hilal.llm.catalog.BenchmarkModelSelection
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runtime adapter; implementations own native resources, never UI state. */
interface ModelBackendSession {
    suspend fun generate(request: BenchmarkPrompt, callback: StreamCallback)
    suspend fun close()
}

/** Shares a warm model across requests and serializes benchmark/feature access. */
class ModelSessionService(
    private val openBackend: suspend (BenchmarkModelSelection) -> ModelBackendSession
) {
    private val mutex = Mutex()
    private var cachedSelection: BenchmarkModelSelection? = null
    private var cachedBackend: ModelBackendSession? = null
    private var closed = false

    suspend fun openSession(selection: BenchmarkModelSelection): FeatureGenerationSession = openSession(selection, false)

    suspend fun openSession(selection: BenchmarkModelSelection, startCold: Boolean): FeatureGenerationSession {
        validateCatalogSelectionForRun(selection)?.let { throw IllegalArgumentException(it) }
        mutex.lock()
        try {
            check(!closed) { "Model service is closed." }
            if (startCold || cachedSelection != selection) {
                releaseCachedModel()
                cachedBackend = openBackend(selection)
                cachedSelection = selection
            }
            currentCoroutineContext().ensureActive()
            val backend = checkNotNull(cachedBackend)
            return object : FeatureGenerationSession {
                private val operation = Mutex()
                private var released = false

                override suspend fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String =
                    operation.withLock {
                        check(!released) { "Model session is already closed." }
                        currentCoroutineContext().ensureActive()
                        val output = StringBuffer()
                        var failure: Throwable? = null
                        backend.generate(
                            BenchmarkPrompt(
                                modelId = selection.modelId,
                                modelName = selection.displayName,
                                modelFilePath = selection.modelPath,
                                tokenizerFilePath = selection.tokenizerPath,
                                modelDisplayName = selection.displayName,
                                runtimeType = selection.runtimeType,
                                engineType = selection.engineType,
                                promptTemplateType = selection.promptTemplateType,
                                selectionSource = SelectionSource.CATALOG,
                                prompt = prompt,
                                maxTokens = maxTokens.coerceIn(1, 2048)
                            ),
                            object : StreamCallback {
                                override fun onFirstToken() = Unit
                                override fun onToken(token: String) {
                                    output.append(token)
                                    onText(output.toString())
                                }
                                override fun onComplete() = Unit
                                override fun onError(throwable: Throwable) { failure = throwable }
                            }
                        )
                        currentCoroutineContext().ensureActive()
                        failure?.let { throw it }
                        output.toString().trim().takeIf { it.isNotEmpty() }
                            ?: throw IllegalStateException("Local model boş yanıt üretti.")
                    }

                override suspend fun close() = withContext(NonCancellable) {
                    operation.withLock {
                        if (!released) {
                            released = true
                            mutex.unlock()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            // A failed/cancelled preparation must never retain a lease or partial cache.
            try { releaseCachedModel() } finally { mutex.unlock() }
            throw error
        }
    }

    /** Benchmarks intentionally start cold; wait for active requests before freeing the warm model. */
    suspend fun <T> withColdRuntime(block: suspend () -> T): T = mutex.withLock {
        check(!closed) { "Model service is closed." }
        releaseCachedModel()
        block()
    }

    suspend fun unload() = withContext(NonCancellable) {
        mutex.withLock { releaseCachedModel() }
    }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            closed = true
            releaseCachedModel()
        }
    }

    private suspend fun releaseCachedModel() = withContext(NonCancellable) {
        val previous = cachedBackend
        cachedBackend = null
        cachedSelection = null
        previous?.close()
    }
}
