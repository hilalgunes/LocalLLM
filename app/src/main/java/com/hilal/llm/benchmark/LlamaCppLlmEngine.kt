package com.hilal.llm.benchmark

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import org.nehuatl.llamacpp.LlamaHelper
import kotlin.coroutines.resume

class LlamaCppLlmEngine(
    private val appContext: Context,
    private val contentResolver: ContentResolver
) : LocalLlmEngine {
    companion object {
        private const val TAG = "LlamaCppLlmEngine"
        private const val DEFAULT_TEMPERATURE = 0.7
        private const val GENERATION_TIMEOUT_MS = 90_000L
    }

    override suspend fun generate(
        request: BenchmarkPrompt,
        callback: StreamCallback
    ) {
        val modelUri = request.modelFilePath.trim()
        if (modelUri.isEmpty()) {
            callback.onError(IllegalArgumentException("Model file URI is empty."))
            return
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(extraBufferCapacity = 64)
        val llamaHelper = LlamaHelper(contentResolver, scope, llmFlow)
        var firstTokenSent = false
        var generationStoppedByTokenLimit = false
        val maxGenerationTokens = request.maxTokens.coerceIn(1, 2048)

        try {
            val preparedModelPath = prepareModelPath(modelUri)
            val modelFile = File(preparedModelPath)
            if (!preparedModelPath.endsWith(".gguf", ignoreCase = true)) {
                callback.onError(
                    IllegalArgumentException("Selected file is not a GGUF model.")
                )
                return
            }
            validateLocalModelFile(modelFile)
            Log.d(
                TAG,
                "Prepared model file path=$preparedModelPath exists=${modelFile.exists()} size=${modelFile.length()}"
            )

            val loadUri = modelFile.toURI().toString()
            Log.d(TAG, "Llama load start uri=$loadUri")

            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    llamaHelper.load(
                        loadUri,
                        2048,
                        null
                    ) {
                        Log.d(TAG, "Llama load success uri=$loadUri")
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }
            }

            Log.d(
                TAG,
                "Generation started. emit_partial_completion=true maxTokens=$maxGenerationTokens temperature=$DEFAULT_TEMPERATURE"
            )
            withContext(Dispatchers.IO) {
                // Binding exposes partial streaming via emit_partial_completion.
                llamaHelper.predict(
                    withTemperatureHint(request.prompt, DEFAULT_TEMPERATURE),
                    null,
                    true
                )
            }

            withTimeout(GENERATION_TIMEOUT_MS) {
                llmFlow.collect { event ->
                    currentCoroutineContext().ensureActive()
                    when (event) {
                        is LlamaHelper.LLMEvent.Ongoing -> {
                            if (!firstTokenSent) {
                                firstTokenSent = true
                                Log.d(TAG, "First token emitted")
                                callback.onFirstToken()
                            }
                            Log.v(TAG, "Token chunk received len=${event.word.length}")
                            callback.onToken(event.word)
                            if (event.tokenCount >= maxGenerationTokens) {
                                generationStoppedByTokenLimit = true
                                Log.d(
                                    TAG,
                                    "Token limit reached tokenCount=${event.tokenCount}, stopping generation."
                                )
                                llamaHelper.stopPrediction()
                                callback.onComplete()
                                Log.d(TAG, "Generation completed (stopped by max token limit)")
                                throw StreamCompletedException()
                            }
                        }
                        is LlamaHelper.LLMEvent.Done -> {
                            Log.d(
                                TAG,
                                "Generation completed tokenCount=${event.tokenCount} durationMs=${event.duration}"
                            )
                            callback.onComplete()
                            throw StreamCompletedException()
                        }
                        is LlamaHelper.LLMEvent.Error -> {
                            if (generationStoppedByTokenLimit) {
                                // Some backends emit an error-like termination after stopPrediction.
                                Log.d(TAG, "Generation ended after stopPrediction.")
                                throw StreamCompletedException()
                            }
                            callback.onError(
                                RuntimeException(event.message.ifBlank { "Native runtime error" })
                            )
                            throw StreamCompletedException()
                        }
                        else -> Unit
                    }
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Generation timeout after ${GENERATION_TIMEOUT_MS}ms")
            try {
                llamaHelper.stopPrediction()
            } catch (_: Throwable) {
            }
            callback.onError(RuntimeException("Generation timed out after ${GENERATION_TIMEOUT_MS}ms"))
        } catch (cancelled: CancellationException) {
            if (cancelled !is StreamCompletedException) {
                Log.w(TAG, "Generation cancelled", cancelled)
                try {
                    llamaHelper.stopPrediction()
                } catch (_: Throwable) {
                }
                callback.onError(CancellationException("Generation cancelled"))
            }
        } catch (oom: OutOfMemoryError) {
            callback.onError(RuntimeException("Insufficient memory for selected model.", oom))
        } catch (t: Throwable) {
            Log.e(TAG, "Llama load/generation failed", t)
            val message = classifyError(t)
            callback.onError(RuntimeException(message, t))
        } finally {
            scope.cancel()
        }
    }

    private suspend fun prepareModelPath(uriString: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val path = uri.path ?: throw IllegalArgumentException("Invalid file URI.")
            val file = File(path)
            validateLocalModelFile(file)
            return@withContext file.absolutePath
        }
        if (uri.scheme != "content") {
            val file = File(uriString)
            validateLocalModelFile(file)
            return@withContext file.absolutePath
        }

        val fileName = extractFileName(uri) ?: "selected_model.gguf"
        val modelsDir = File(appContext.cacheDir, "llm-models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        val targetFile = File(modelsDir, fileName)
        Log.d(TAG, "Copy model from SAF uri=$uri to cache=${targetFile.absolutePath}")

        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Unable to read selected model URI.")
        validateLocalModelFile(targetFile)
        Log.d(
            TAG,
            "Copy completed cache=${targetFile.absolutePath} exists=${targetFile.exists()} size=${targetFile.length()}"
        )

        return@withContext targetFile.absolutePath
    }

    private fun extractFileName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(index)
                }
            }
        return null
    }

    private fun classifyError(throwable: Throwable): String {
        val raw = throwable.message?.lowercase().orEmpty()
        return when {
            "no content provider" in raw -> "Model load failed. Invalid URI/path was used for model file."
            "gguf" in raw && "invalid" in raw -> "Invalid GGUF file."
            "unsupported" in raw -> "Unsupported model architecture."
            "load" in raw && "model" in raw -> "Model load failure."
            "memory" in raw || "oom" in raw -> "Insufficient memory for selected model."
            raw.isNotBlank() -> "Native runtime error: ${throwable.message}"
            else -> "Unknown runtime error."
        }
    }

    private fun withTemperatureHint(prompt: String, temperature: Double): String {
        return "$prompt\n\n[temperature=$temperature]"
    }

    private fun validateLocalModelFile(file: File) {
        if (!file.exists()) {
            throw IllegalArgumentException("Copied model file does not exist: ${file.absolutePath}")
        }
        if (!file.isFile) {
            throw IllegalArgumentException("Model path is not a regular file: ${file.absolutePath}")
        }
        if (file.length() <= 0L) {
            throw IllegalArgumentException("Copied model file is empty (0 bytes): ${file.absolutePath}")
        }

        // Force regular file access path validation for cached/plain files.
        FileInputStream(file).use { input ->
            input.read()
        }
    }

    private class StreamCompletedException : CancellationException()
}
