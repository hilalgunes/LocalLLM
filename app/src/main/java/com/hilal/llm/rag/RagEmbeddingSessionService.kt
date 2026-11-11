package com.hilal.llm.rag

import com.hilal.llm.catalog.RuntimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ResolvedEmbeddingModel(
    val modelId: String,
    val displayName: String,
    val runtimeType: RuntimeType,
    val modelPath: String,
    val tokenizerPath: String?
)

/** Owns one embedding provider; generation and teardown cannot race each other. */
internal class RagEmbeddingSessionService(
    private val createProvider: (ResolvedEmbeddingModel) -> RagEmbeddingProvider
) {
    private val mutex = Mutex()
    private var active: Pair<ResolvedEmbeddingModel, RagEmbeddingProvider>? = null
    private var closed = false

    suspend fun embed(text: String, model: ResolvedEmbeddingModel, purpose: RagEmbeddingPurpose): FloatArray = mutex.withLock {
        check(!closed) { "Embedding service is closed." }
        if (active?.first != model) {
            releaseActive()
            active = model to createProvider(model)
        }
        try {
            requireNotNull(active).second.embed(text, model.modelPath, model.tokenizerPath, purpose)
        } catch (failure: Throwable) {
            try { releaseActive() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    suspend fun close() = mutex.withLock {
        closed = true
        releaseActive()
    }

    private suspend fun releaseActive() {
        val previous = active
        active = null
        withContext(NonCancellable + Dispatchers.IO) { previous?.second?.close() }
    }
}
