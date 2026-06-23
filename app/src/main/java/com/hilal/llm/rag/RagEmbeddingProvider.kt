package com.hilal.llm.rag

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid
import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface RagEmbeddingProvider {
    suspend fun embed(text: String, embeddingModelPath: String): FloatArray
    suspend fun canInitialize(embeddingModelPath: String): Boolean
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
        val modelUri: String
    )

    private val llamaAndroid = LlamaAndroid(contentResolver)
    private val loadedModels = ConcurrentHashMap<String, LoadedEmbeddingModel>()

    override suspend fun embed(text: String, embeddingModelPath: String): FloatArray = withContext(Dispatchers.IO) {
        val modelUri = embeddingModelPath.toFileUriString()
        val loadedModel = loadedModels[modelUri] ?: loadModel(modelUri)
        val contextId = loadedModel.contextId
        val result = llamaAndroid.embedding(contextId, text).first()
        extractEmbeddingVector(result)
    }

    override suspend fun canInitialize(embeddingModelPath: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val modelUri = embeddingModelPath.toFileUriString()
            if (loadedModels.containsKey(modelUri)) {
                true
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
    }

    private fun loadModel(modelUri: String): LoadedEmbeddingModel {
        if (!isLikelyGguf(modelUri)) {
            throw IllegalArgumentException("Embedding model must be a GGUF file: $modelUri")
        }

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
                    "use_mmap" to false,
                    "vocab_only" to false
                )
            ) { _ -> }
                ?: throw IllegalStateException("Embedding model initialization failed.")

            val contextId = (result["contextId"] as? Number)?.toInt()
                ?: throw IllegalStateException("Embedding context id missing from native result.")

            Log.d(TAG, "Embedding model initialized modelUri=$modelUri contextId=$contextId resultKeys=${result.keys}")

            val loadedModel = LoadedEmbeddingModel(contextId = contextId, modelUri = modelUri)
            loadedModels[modelUri] = loadedModel
            loadedModel
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to load embedding model: $modelUri fd=$modelFd", throwable)
            runCatching {
                pfd.close()
            }
            throw throwable
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

    private fun extractEmbeddingVector(result: Map<String, Any>): FloatArray {
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
            val value = result[key] ?: continue
            val vector = coerceFloatArray(value)
            if (vector != null && vector.isNotEmpty()) {
                return vector
            }
        }

        if (result.size == 1) {
            val onlyValue = result.values.firstOrNull()
            val vector = onlyValue?.let { coerceFloatArray(it) }
            if (vector != null && vector.isNotEmpty()) {
                return vector
            }
        }

        throw IllegalStateException("Embedding vector was not found in result keys=${result.keys}")
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
