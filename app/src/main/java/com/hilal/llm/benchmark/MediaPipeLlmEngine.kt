package com.hilal.llm.benchmark

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.google.common.util.concurrent.MoreExecutors
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MediaPipeLlmEngine(
    private val appContext: Context,
    private val contentResolver: ContentResolver
) : LocalLlmEngine {
    companion object {
        private const val TAG = "MediaPipeLlmEngine"
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.95f
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val DEFAULT_SEED = 0
    }

    override suspend fun generate(
        request: BenchmarkPrompt,
        callback: StreamCallback
    ) {
        var inference: LlmInference? = null
        var session: LlmInferenceSession? = null
        var firstTokenSeen = false

        try {
            if (request.modelName.contains("-web.task", ignoreCase = true)) {
                callback.onError(
                    IllegalArgumentException(
                        "Selected model is web-only (-web.task). " +
                            "Use an Android MediaPipe-compatible .task model."
                    )
                )
                return
            }
            val modelPath = prepareModelPath(request.modelFilePath)
            if (!modelPath.endsWith(".task", ignoreCase = true)) {
                callback.onError(
                    IllegalArgumentException("MediaPipe engine requires a .task model file.")
                )
                return
            }
            if (modelPath.contains("-web.task", ignoreCase = true)) {
                callback.onError(
                    IllegalArgumentException(
                        "Selected model appears to be a web-targeted .task package (-web.task). " +
                            "Use an Android MediaPipe-compatible .task model."
                    )
                )
                return
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(request.maxTokens)
                .setMaxTopK(DEFAULT_TOP_K)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            inference = LlmInference.createFromOptions(appContext, options)

            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(DEFAULT_TOP_K)
                .setTopP(DEFAULT_TOP_P)
                .setTemperature(DEFAULT_TEMPERATURE)
                .setRandomSeed(DEFAULT_SEED)
                .build()
            session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
            session.addQueryChunk(request.prompt)

            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    try {
                        session.cancelGenerateResponseAsync()
                    } catch (_: Throwable) {
                    }
                }

                val future = session.generateResponseAsync(
                    ProgressListener<String> { partialResult, done ->
                        if (!partialResult.isNullOrEmpty()) {
                            if (!firstTokenSeen) {
                                firstTokenSeen = true
                                callback.onFirstToken()
                            }
                            callback.onToken(partialResult)
                        }
                        if (done) {
                            callback.onComplete()
                        }
                    }
                )
                future.addListener(
                    {
                        try {
                            future.get()
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (t: Throwable) {
                            callback.onError(
                                RuntimeException(t.message ?: "MediaPipe generation failed", t)
                            )
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    },
                    MoreExecutors.directExecutor()
                )
            }
        } catch (cancelled: CancellationException) {
            callback.onError(CancellationException("Generation cancelled"))
        } catch (t: Throwable) {
            Log.e(TAG, "MediaPipe generation failed", t)
            callback.onError(RuntimeException(classifyError(t), t))
        } finally {
            try {
                session?.close()
            } catch (_: Throwable) {
            }
            try {
                inference?.close()
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
            val fileName = readDisplayName(uri) ?: "selected_model.task"
            val modelsDir = File(appContext.cacheDir, "mediapipe-models")
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
            }
            val copied = File(modelsDir, fileName)
            val tempFile = File(modelsDir, "$fileName.part")
            val expectedSize = readSize(uri)
            Log.d(TAG, "Copying MediaPipe model uri=$uri expectedSize=$expectedSize to=${copied.absolutePath}")

            openInputStreamSafely(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            } ?: throw IllegalArgumentException("Unable to read selected model URI.")
            if (copied.exists()) copied.delete()
            if (!tempFile.renameTo(copied)) {
                throw IllegalStateException("Failed to finalize copied model file.")
            }
            if (expectedSize != null && expectedSize > 0L && copied.length() != expectedSize) {
                throw IllegalStateException(
                    "Copied model size mismatch. expected=$expectedSize actual=${copied.length()}"
                )
            }
            validateModelFile(copied)
            Log.d(TAG, "MediaPipe model ready path=${copied.absolutePath} size=${copied.length()}")
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

    private fun openInputStreamSafely(uri: Uri): InputStream? {
        val pfd: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
        if (pfd != null) {
            return FileInputStream(pfd.fileDescriptor)
        }
        return contentResolver.openInputStream(uri)
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

    private fun readSize(uri: Uri): Long? {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                return cursor.getLong(idx)
            }
        }
        return null
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
        try {
            ZipFile(file).use { zip ->
                if (zip.entries().hasMoreElements().not()) {
                    throw IllegalArgumentException("Model .task zip archive has no entries.")
                }
            }
        } catch (zipError: ZipException) {
            throw IllegalArgumentException(
                "Selected .task file is not a valid MediaPipe task archive (zip). " +
                    "Please verify model download integrity.",
                zipError
            )
        }
    }

    private fun classifyError(t: Throwable): String {
        val raw = t.message?.lowercase().orEmpty()
        return when {
            "web.task" in raw -> {
                "Selected model is a web-targeted .task package. Please use Android MediaPipe-compatible .task."
            }
            "unable to open zip archive" in raw -> {
                "MediaPipe could not open the .task archive. " +
                    "Likely wrong variant (-web.task) or corrupted download."
            }
            "task" in raw && "model" in raw -> "MediaPipe model load failure for .task file."
            "unsupported" in raw -> "Unsupported MediaPipe model/device backend."
            "memory" in raw || "oom" in raw -> "Insufficient memory for MediaPipe model."
            raw.isNotBlank() -> "MediaPipe runtime error: ${t.message}"
            else -> "Unknown MediaPipe runtime error."
        }
    }
}
