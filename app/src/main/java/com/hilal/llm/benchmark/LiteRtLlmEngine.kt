package com.hilal.llm.benchmark

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class LiteRtLlmEngine(
    private val appContext: Context,
    private val contentResolver: ContentResolver
) : LocalLlmEngine {
    companion object {
        private const val TAG = "LiteRtLlmEngine"
        private const val DEFAULT_TEMPERATURE = 0.7
    }

    override suspend fun generate(
        request: BenchmarkPrompt,
        callback: StreamCallback
    ) {
        var engine: Engine? = null
        var conversation: Conversation? = null
        var firstTokenSeen = false

        try {
            val modelPath = prepareModelPath(request.modelFilePath)
            if (!modelPath.endsWith(".litertlm", ignoreCase = true)) {
                callback.onError(
                    IllegalArgumentException(
                        "LiteRT engine requires a .litertlm model file."
                    )
                )
                return
            }

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = null,
                audioBackend = null,
                // Keep defaults from LiteRT runtime for engine-level context sizing.
                // Per-request behavior is controlled by sampler/conversation config.
                maxNumTokens = null,
                maxNumImages = null,
                cacheDir = appContext.cacheDir.absolutePath
            )
            engine = Engine(engineConfig)
            withContext(Dispatchers.IO) {
                engine.initialize()
            }

            val samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.95,
                temperature = DEFAULT_TEMPERATURE,
                seed = 0
            )
            val conversationConfig = ConversationConfig(
                systemInstruction = null,
                initialMessages = emptyList(),
                tools = emptyList(),
                samplerConfig = samplerConfig,
                automaticToolCalling = false,
                channels = emptyList(),
                extraContext = mapOf("max_output_tokens" to request.maxTokens)
            )
            conversation = engine.createConversation(conversationConfig)

            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    try {
                        conversation.cancelProcess()
                    } catch (_: Throwable) {
                    }
                }

                conversation.sendMessageAsync(
                    request.prompt,
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val chunk = extractTextFromMessage(message)
                            if (chunk.isBlank()) return
                            if (!firstTokenSeen) {
                                firstTokenSeen = true
                                callback.onFirstToken()
                            }
                            callback.onToken(chunk)
                        }

                        override fun onDone() {
                            callback.onComplete()
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            callback.onError(
                                RuntimeException(
                                    throwable.message ?: "LiteRT generation error",
                                    throwable
                                )
                            )
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }
                    },
                    emptyMap()
                )
            }
        } catch (cancelled: CancellationException) {
            callback.onError(CancellationException("Generation cancelled"))
        } catch (t: Throwable) {
            Log.e(TAG, "LiteRT generation failed", t)
            callback.onError(RuntimeException(classifyError(t), t))
        } finally {
            try {
                conversation?.close()
            } catch (_: Throwable) {
            }
            try {
                engine?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun prepareModelPath(modelPathOrUri: String): String = withContext(Dispatchers.IO) {
        val raw = modelPathOrUri.trim()
        if (raw.isBlank()) {
            throw IllegalArgumentException("Model file URI is empty.")
        }
        val uri = Uri.parse(raw)
        if (uri.scheme == "content") {
            val fileName = readDisplayName(uri) ?: "selected_model.litertlm"
            val modelsDir = File(appContext.cacheDir, "litert-models")
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
            }
            val copied = File(modelsDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(copied).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalArgumentException("Unable to read selected model URI.")
            validateModelFile(copied)
            return@withContext copied.absolutePath
        }

        val file = if (uri.scheme == "file") {
            File(uri.path ?: "")
        } else {
            File(raw)
        }
        validateModelFile(file)
        return@withContext file.absolutePath
    }

    private fun validateModelFile(file: File) {
        if (!file.exists()) {
            throw IllegalArgumentException("Model file does not exist: ${file.absolutePath}")
        }
        if (!file.isFile) {
            throw IllegalArgumentException("Model path is not a file: ${file.absolutePath}")
        }
        if (file.length() <= 0L) {
            throw IllegalArgumentException("Model file is empty (0 bytes): ${file.absolutePath}")
        }
    }

    private fun readDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return null
    }

    private fun classifyError(t: Throwable): String {
        val raw = t.message?.lowercase().orEmpty()
        return when {
            "runtime/executor" in raw -> "LiteRT executor initialization failed on this device/model/backend."
            "litertlm" in raw && "model" in raw -> "LiteRT model load failure."
            "unsupported" in raw -> "Unsupported LiteRT model or backend."
            "memory" in raw || "oom" in raw -> "Insufficient memory for LiteRT model."
            raw.isNotBlank() -> "LiteRT runtime error: ${t.message}"
            else -> "Unknown LiteRT runtime error."
        }
    }

    private fun extractTextFromMessage(message: Message): String {
        return message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }
    }
}
