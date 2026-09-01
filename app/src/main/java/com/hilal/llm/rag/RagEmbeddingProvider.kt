package com.hilal.llm.rag

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid
import org.nehuatl.llamacpp.LlamaContext
import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

enum class RagEmbeddingPurpose {
    QUERY,
    DOCUMENT
}

interface RagEmbeddingProvider {
    suspend fun embed(
        text: String,
        embeddingModelPath: String,
        tokenizerPath: String?,
        purpose: RagEmbeddingPurpose
    ): FloatArray

    suspend fun canInitialize(embeddingModelPath: String, tokenizerPath: String?): Boolean
    fun close()
}

class LlamaCppRagEmbeddingProvider(
    private val contentResolver: ContentResolver
) : RagEmbeddingProvider {
    companion object {
        private const val TAG = "RagEmbeddingProvider"
        private const val DEFAULT_CONTEXT_LENGTH = 512
        private const val DEFAULT_BATCH_SIZE = 128
    }

    private data class LoadedEmbeddingModel(
        val contextId: Int,
        val modelUri: String,
        val llamaContext: LlamaContext
    )

    private val llamaAndroid = LlamaAndroid(contentResolver)
    private val loadedModels = ConcurrentHashMap<String, LoadedEmbeddingModel>()
    private val failedModelMessages = ConcurrentHashMap<String, String>()

    override suspend fun embed(
        text: String,
        embeddingModelPath: String,
        tokenizerPath: String?,
        purpose: RagEmbeddingPurpose
    ): FloatArray = withContext(Dispatchers.IO) {
        @Suppress("UNUSED_VARIABLE")
        val ignoredTokenizerPath = tokenizerPath
        @Suppress("UNUSED_VARIABLE")
        val ignoredPurpose = purpose
        val modelUri = embeddingModelPath.toFileUriString()
        failedModelMessages[modelUri]?.let { message ->
            throw IllegalStateException(message)
        }
        val loadedModel = loadedModels[modelUri] ?: loadModel(modelUri)
        val result = LlamaCppEmbeddingCompat.embedding(
            llamaContext = loadedModel.llamaContext,
            contextPtr = loadedModel.llamaContext.context,
            text = text
        )
        extractEmbeddingVector(result)
    }

    override suspend fun canInitialize(embeddingModelPath: String, tokenizerPath: String?): Boolean = withContext(Dispatchers.IO) {
        @Suppress("UNUSED_VARIABLE")
        val ignoredTokenizerPath = tokenizerPath
        runCatching {
            val modelUri = embeddingModelPath.toFileUriString()
            if (loadedModels.containsKey(modelUri)) {
                true
            } else if (failedModelMessages.containsKey(modelUri)) {
                false
            } else {
                val loadedModel = loadModel(modelUri)
                loadedModels[modelUri] = loadedModel
                true
            }
        }.getOrDefault(false)
    }

    override fun close() {
        loadedModels.values.forEach { model ->
            runCatching {
                llamaAndroid.releaseContext(model.contextId)
            }
        }
        loadedModels.clear()
        failedModelMessages.clear()
    }

    private fun loadModel(modelUri: String): LoadedEmbeddingModel {
        if (!isLikelyGguf(modelUri)) {
            throw IllegalArgumentException("Embedding model must be a GGUF file: $modelUri")
        }

        releaseOtherModels(modelUri)

        val modelFileUri = Uri.parse(modelUri)
        val pfd = openReadOnlyDescriptor(modelFileUri)
            ?: throw IllegalStateException("Unable to open embedding model file: $modelUri")
        val modelFd = pfd.detachFd()
        Log.d(
            TAG,
            "Loading embedding model uri=$modelUri fd=$modelFd size=${runCatching { File(modelFileUri.path ?: modelUri).length() }.getOrNull()}"
        )

        return try {
            val result = llamaAndroid.startEngine(
                mapOf(
                    "model" to modelUri,
                    "model_fd" to modelFd,
                    "embedding" to true,
                    "n_ctx" to DEFAULT_CONTEXT_LENGTH,
                    "n_batch" to DEFAULT_BATCH_SIZE,
                    "n_threads" to 0,
                    "n_gpu_layers" to 0,
                    "use_mlock" to false,
                    "use_mmap" to true,
                    "vocab_only" to false
                )
            ) { _ -> }
                ?: throw IllegalStateException("Embedding model initialization failed.")

            val contextId = (result["contextId"] as? Number)?.toInt()
                ?: throw IllegalStateException("Embedding context id missing from native result.")

            Log.d(TAG, "Embedding model initialized modelUri=$modelUri contextId=$contextId resultKeys=${result.keys}")

            val loadedModel = LoadedEmbeddingModel(
                contextId = contextId,
                modelUri = modelUri,
                llamaContext = getLoadedContext(contextId)
            )
            loadedModels[modelUri] = loadedModel
            loadedModel
        } catch (throwable: Throwable) {
            failedModelMessages[modelUri] = throwable.message ?: "Embedding model initialization failed."
            Log.e(TAG, "Failed to load embedding model: $modelUri fd=$modelFd", throwable)
            throw throwable
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getLoadedContext(contextId: Int): LlamaContext {
        val field = LlamaAndroid::class.java.getDeclaredField("contexts")
        field.isAccessible = true
        val contexts = field.get(llamaAndroid) as? ConcurrentHashMap<Int, LlamaContext>
            ?: throw IllegalStateException("Embedding context registry is unavailable.")
        return contexts[contextId]
            ?: throw IllegalStateException("Embedding context was not found for id=$contextId.")
    }

    private fun releaseOtherModels(keepModelUri: String) {
        loadedModels.entries.removeIf { entry ->
            if (entry.key == keepModelUri) {
                return@removeIf false
            }

            runCatching {
                llamaAndroid.releaseContext(entry.value.contextId)
            }
            true
        }
    }

    private fun openReadOnlyDescriptor(uri: Uri): ParcelFileDescriptor? {
        return when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> contentResolver.openFileDescriptor(uri, "r")
            ContentResolver.SCHEME_FILE -> {
                val path = uri.path ?: return null
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            }
            null -> {
                val path = uri.toString()
                if (path.isBlank()) return null
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            }
            else -> {
                val path = uri.toString()
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
    }

    private fun extractEmbeddingVector(result: Any?): FloatArray {
        val directVector = result?.let { coerceFloatArray(it) }
        if (directVector != null && directVector.isNotEmpty()) {
            return directVector
        }

        val resultMap = result as? Map<*, *>
            ?: throw IllegalStateException("Embedding vector was not returned by the selected model.")
        val candidateKeys = listOf(
            "embedding",
            "embeddings",
            "vector",
            "result_embd_pooled",
            "result_embd",
            "data",
            "values"
        )

        for (key in candidateKeys) {
            val value = resultMap[key] ?: continue
            val vector = coerceFloatArray(value)
            if (vector != null && vector.isNotEmpty()) {
                return vector
            }
        }

        if (resultMap.size == 1) {
            val onlyValue = resultMap.values.firstOrNull()
            val vector = onlyValue?.let { coerceFloatArray(it) }
            if (vector != null && vector.isNotEmpty()) {
                return vector
            }
        }

        throw IllegalStateException("Embedding vector was not found in result keys=${resultMap.keys}")
    }

    private fun coerceFloatArray(value: Any): FloatArray? {
        return when (value) {
            is FloatArray -> value
            is DoubleArray -> value.map { it.toFloat() }.toFloatArray()
            is IntArray -> value.map { it.toFloat() }.toFloatArray()
            is LongArray -> value.map { it.toFloat() }.toFloatArray()
            is List<*> -> {
                val numbers = value.mapNotNull { it as? Number }
                if (numbers.size == value.size && numbers.isNotEmpty()) {
                    numbers.map { it.toFloat() }.toFloatArray()
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun String.toFileUriString(): String {
        val trimmed = trim()
        return if (trimmed.startsWith("file://")) {
            trimmed
        } else if (trimmed.startsWith("content://")) {
            trimmed
        } else {
            Uri.fromFile(File(trimmed)).toString()
        }
    }

    private fun isLikelyGguf(modelUri: String): Boolean {
        return modelUri.endsWith(".gguf", ignoreCase = true) ||
            modelUri.endsWith(".gguf/", ignoreCase = true)
    }
}

class LiteRtGeckoRagEmbeddingProvider : RagEmbeddingProvider {
    companion object {
        private const val MAX_GECKO_256_INPUT_CHARS = 320
    }

    private data class LoadedGeckoModel(
        val modelPath: String,
        val tokenizerPath: String?,
        val model: GeckoEmbeddingModel
    )

    private var loadedModel: LoadedGeckoModel? = null

    override suspend fun embed(
        text: String,
        embeddingModelPath: String,
        tokenizerPath: String?,
        purpose: RagEmbeddingPurpose
    ): FloatArray = withContext(Dispatchers.IO) {
        val model = getOrLoadModel(embeddingModelPath, tokenizerPath).model
        val taskType = when (purpose) {
            RagEmbeddingPurpose.QUERY -> EmbedData.TaskType.RETRIEVAL_QUERY
            RagEmbeddingPurpose.DOCUMENT -> EmbedData.TaskType.RETRIEVAL_DOCUMENT
        }
        val request = EmbeddingRequest.create(
            listOf(EmbedData.create(text.prepareGecko256Input(), taskType))
        )
        model.getEmbeddings(request)
            .get()
            .map { it.toFloat() }
            .toFloatArray()
    }

    override suspend fun canInitialize(embeddingModelPath: String, tokenizerPath: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            getOrLoadModel(embeddingModelPath, tokenizerPath)
            true
        }.getOrDefault(false)
    }

    override fun close() {
        loadedModel = null
    }

    private fun getOrLoadModel(
        embeddingModelPath: String,
        tokenizerPath: String?
    ): LoadedGeckoModel {
        val current = loadedModel
        if (current != null &&
            current.modelPath == embeddingModelPath &&
            current.tokenizerPath == tokenizerPath
        ) {
            return current
        }

        val modelFile = File(embeddingModelPath)
        if (!modelFile.exists() || modelFile.length() <= 0L) {
            throw IllegalArgumentException("LiteRT embedding model file is missing: $embeddingModelPath")
        }
        val tokenizerFile = tokenizerPath?.let(::File)
        if (tokenizerFile == null || !tokenizerFile.exists() || tokenizerFile.length() <= 0L) {
            throw IllegalArgumentException("LiteRT Gecko embedding requires sentencepiece.model tokenizer.")
        }

        val model = GeckoEmbeddingModel(
            modelFile.absolutePath,
            Optional.of(tokenizerFile.absolutePath),
            false
        )
        return LoadedGeckoModel(
            modelPath = modelFile.absolutePath,
            tokenizerPath = tokenizerFile.absolutePath,
            model = model
        ).also { loadedModel = it }
    }

    private fun String.prepareGecko256Input(): String {
        return trim()
            .take(MAX_GECKO_256_INPUT_CHARS)
            .trim()
    }
}
