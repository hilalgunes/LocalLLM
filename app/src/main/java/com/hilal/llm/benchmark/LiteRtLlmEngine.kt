package com.hilal.llm.benchmark

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class LiteRtLlmEngine(
    private val appContext: Context,
    private val contentResolver: ContentResolver
) : LocalLlmEngine {
    companion object {
        private const val TAG = "LiteRtLlmEngine"
        private const val PERF_TAG = "LITERT_PERF"
        private const val THREAD_TAG = "LITERT_THREAD"
        private const val DEFAULT_TEMPERATURE = 0.7
    }

    override suspend fun generate(
        request: BenchmarkPrompt,
        callback: StreamCallback
    ) {
        var session: Session? = null
        try {
            session = openSession(request.modelFilePath)
            session.generate(request, callback)
        } catch (cancelled: CancellationException) {
            callback.onError(CancellationException("Generation cancelled"))
        } catch (t: Throwable) {
            Log.e(TAG, "LiteRT generation failed", t)
            callback.onError(RuntimeException(classifyError(t), t))
        } finally {
            session?.close()
        }
    }

    suspend fun openSession(modelPathOrUri: String): Session {
        val modelPath = prepareModelPath(modelPathOrUri)
        if (!isSupportedModelFormat(modelPath)) {
            throw IllegalArgumentException(
                "Selected LiteRT model format is not supported by current LiteRT runner."
            )
        }

        val initializeCount = AtomicInteger(0)
        val startedAt = SystemClock.elapsedRealtime()
        Log.d(PERF_TAG, "LITERT_PERF | Engine initialize started")
        return try {
            val engine = withContext(Dispatchers.Default) {
                Log.d(THREAD_TAG, "LITERT_THREAD | Engine create | thread=${Thread.currentThread().name}")
                var created: Engine? = null
                try {
                    created = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.GPU(),
                            visionBackend = null,
                            audioBackend = null,
                            maxNumTokens = null,
                            maxNumImages = null,
                            cacheDir = appContext.cacheDir.absolutePath
                        )
                    )
                    val count = initializeCount.incrementAndGet()
                    Log.d(PERF_TAG, "LITERT_PERF | Engine.initialize() called | count=$count")
                    Log.d(THREAD_TAG, "LITERT_THREAD | Engine initialize | thread=${Thread.currentThread().name}")
                    created.initialize()
                    created
                } catch (error: Throwable) {
                    try {
                        created?.close()
                    } catch (_: Throwable) {
                    }
                    throw error
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.d(PERF_TAG, "LITERT_PERF | Engine initialize completed | ${elapsed.asSeconds()} saniye")
            Session(engine, initializeCount)
        } catch (error: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.e(PERF_TAG, "LITERT_PERF | Engine initialize failed | ${elapsed.asSeconds()} saniye", error)
            throw error
        }
    }

    inner class Session internal constructor(
        private val engine: Engine,
        private val initializeCount: AtomicInteger
    ) {
        private val closed = AtomicBoolean(false)
        private val conversationCount = AtomicInteger(0)

        suspend fun generate(request: BenchmarkPrompt, callback: StreamCallback) {
            check(!closed.get()) { "LiteRT session is already closed." }
            var conversation: Conversation? = null
            var firstTokenSeen = false
            try {
                conversation = withContext(Dispatchers.Default) {
                    val count = conversationCount.incrementAndGet()
                    Log.d(
                        THREAD_TAG,
                        "LITERT_THREAD | Conversation create | count=$count | thread=${Thread.currentThread().name}"
                    )
                    engine.createConversation(conversationConfig(request.maxTokens))
                }
                val activeConversation = conversation

                withContext(Dispatchers.Default) {
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation {
                            try {
                                activeConversation.cancelProcess()
                            } catch (_: Throwable) {
                            }
                        }
                        Log.d(
                            THREAD_TAG,
                            "LITERT_THREAD | sendMessageAsync | thread=${Thread.currentThread().name}"
                        )
                        activeConversation.sendMessageAsync(
                            request.prompt,
                            object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    val chunk = extractTextFromMessage(message)
                                    if (chunk.isEmpty()) return
                                    if (!firstTokenSeen) {
                                        firstTokenSeen = true
                                        callback.onFirstToken()
                                    }
                                    callback.onToken(chunk)
                                }

                                override fun onDone() {
                                    callback.onComplete()
                                    if (continuation.isActive) continuation.resume(Unit)
                                }

                                override fun onError(throwable: Throwable) {
                                    callback.onError(
                                        RuntimeException(
                                            throwable.message ?: "LiteRT generation error",
                                            throwable
                                        )
                                    )
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                            },
                            emptyMap()
                        )
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Default) {
                    try {
                        conversation?.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        suspend fun close() {
            if (!closed.compareAndSet(false, true)) return
            withContext(NonCancellable + Dispatchers.Default) {
                try {
                    engine.close()
                } finally {
                    Log.d(PERF_TAG, "LITERT_PERF | Engine initialize count = ${initializeCount.get()}")
                }
            }
        }
    }

    private fun conversationConfig(maxTokens: Int): ConversationConfig {
        val samplerConfig = SamplerConfig(
            topK = 40,
            topP = 0.95,
            temperature = DEFAULT_TEMPERATURE,
            seed = 0
        )
        return ConversationConfig(
            systemInstruction = null,
            initialMessages = emptyList(),
            tools = emptyList(),
            samplerConfig = samplerConfig,
            automaticToolCalling = false,
            channels = emptyList(),
            extraContext = mapOf("max_output_tokens" to maxTokens)
        )
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

    private fun isSupportedModelFormat(modelPath: String): Boolean {
        val normalized = modelPath.lowercase()
        return normalized.endsWith(".litertlm") ||
            normalized.endsWith(".task") ||
            normalized.endsWith(".tflite") ||
            normalized.endsWith(".zip") ||
            normalized.endsWith(".bundle")
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

    private fun Long.asSeconds(): String = String.format(Locale.ROOT, "%.3f", this / 1000.0)

}
