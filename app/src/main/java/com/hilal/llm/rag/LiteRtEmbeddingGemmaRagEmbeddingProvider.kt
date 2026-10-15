package com.hilal.llm.rag

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Text inference for EmbeddingGemma 2 270M and 740M; bundles include the tokenizer. */
class LiteRtEmbeddingGemmaRagEmbeddingProvider(private val cacheDir: File, private val outputSize: Int = 768) : RagEmbeddingProvider {
    private val lock = Any()
    private var engine: EmbeddingEngine? = null
    private var loadedPath: String? = null

    override suspend fun embed(
        text: String,
        embeddingModelPath: String,
        tokenizerPath: String?,
        purpose: RagEmbeddingPurpose
    ): FloatArray = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val input = when (purpose) {
                RagEmbeddingPurpose.QUERY -> "task: question answering | query: ${text.trim()}"
                RagEmbeddingPurpose.DOCUMENT -> "title: none | text: ${text.trim()}"
            }
            val vector = getOrLoad(embeddingModelPath).computeEmbedding(
                listOf(InputData.Text(input)),
                EmbeddingOptions(normalize = true, outputSize = outputSize)
            ).embedding
            check(vector.size == outputSize && vector.all { it.isFinite() } && vector.any { it != 0f }) {
                "EmbeddingGemma 2 returned an invalid embedding vector."
            }
            vector
        }
    }

    override suspend fun canInitialize(embeddingModelPath: String, tokenizerPath: String?): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                runCatching { getOrLoad(embeddingModelPath); true }.getOrDefault(false)
            }
        }

    override fun close() = synchronized(lock) {
        engine?.close()
        engine = null
        loadedPath = null
    }

    private fun getOrLoad(path: String): EmbeddingEngine {
        if (loadedPath == path) engine?.let { return it }
        require(File(path).isFile && File(path).length() > 0L) {
            "EmbeddingGemma 2 model file is missing: $path"
        }
        close()
        val candidate = EmbeddingEngine(
            EmbeddingEngineConfig(
                modelPath = path,
                backend = Backend.CPU(threadCount = 4),
                cacheDir = cacheDir.absolutePath,
                maxInputLength = 2048
            )
        )
        try {
            candidate.initialize()
        } catch (failure: Throwable) {
            if (candidate.isInitialized()) candidate.close()
            throw failure
        }
        engine = candidate
        loadedPath = path
        return candidate
    }
}
