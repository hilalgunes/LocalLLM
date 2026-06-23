package com.hilal.llm.benchmark

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import com.hilal.llm.catalog.PromptTemplateType
import org.pytorch.executorch.Module
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule

class ExecuTorchLlmEngine(
    private val appContext: Context,
    private val contentResolver: ContentResolver
) : LocalLlmEngine {
    private data class StopTokenMatch(
        val trimmedText: String,
        val matchedToken: String?
    )

    companion object {
        private const val TAG = "ExecuTorchLlmEngine"
        private const val RAW_TAG = "ExecuTorch_RAW"
        private const val DEFAULT_TEMPERATURE = 0.1f
        private const val QWEN_TEMPERATURE = 0.0f
        private const val GEMMA_TEMPERATURE = 0.1f
        private const val GENERIC_TEMPERATURE = 0.1f
        private const val DEFAULT_MIN_SEQ_LEN = 128
        private const val DEFAULT_MAX_SEQ_LEN = 1024
        private const val DEFAULT_NUM_BOS = 0
        private const val DEFAULT_NUM_EOS = 0
    }

    private val recentTokens = ArrayDeque<String>(20)
    private var repetitionStrikes = 0

    override suspend fun generate(
        request: BenchmarkPrompt,
        callback: StreamCallback
    ) {
        var llmModule: LlmModule? = null
        var firstTokenSeen = false
        val generationFinished = kotlinx.coroutines.CompletableDeferred<Unit>()
        recentTokens.clear()
        repetitionStrikes = 0
        try {
            val modelPath = prepareModelPath(request.modelFilePath)
            if (!modelPath.endsWith(".pte", ignoreCase = true)) {
                callback.onError(
                    IllegalArgumentException("ExecuTorch engine requires a .pte model file.")
                )
                return
            }
            val tokenizerPath = request.tokenizerFilePath
                ?.let { prepareTokenizerPath(it) }
                ?: resolveTokenizerPath(modelPath)
            Log.d(
                TAG,
                "Resolved ExecuTorch paths modelPath=$modelPath tokenizerPath=$tokenizerPath " +
                    "modelBytes=${File(modelPath).length()} tokenizerBytes=${File(tokenizerPath).length()}"
            )

            llmModule = withContext(Dispatchers.IO) {
                Log.d(TAG, "Creating LlmModule for modelPath=$modelPath tokenizerPath=$tokenizerPath")
                LlmModule(modelPath, tokenizerPath, DEFAULT_TEMPERATURE)
            }

            val promptTemplateType = resolvePromptTemplateType(
                promptTemplateType = request.promptTemplateType,
                modelName = request.modelName,
                modelPath = request.modelFilePath,
                engineType = request.engineType
            )
            val formattedPrompt = formatPrompt(request.prompt, promptTemplateType)
            val stopTokens = stopTokensFor(promptTemplateType)
            val generationConfig = buildGenerationConfig(
                prompt = formattedPrompt,
                promptTemplateType = promptTemplateType,
                maxNewTokens = request.maxTokens
            )
            val temperature = generationTemperature(promptTemplateType)
            Log.d(
                TAG,
                "Generation config modelName=${request.modelName} " +
                    "modelDisplayName=${request.modelDisplayName ?: "-"} " +
                    "engineType=${request.engineType ?: "-"} " +
                    "promptTemplateType=$promptTemplateType " +
                    "maxTokens=${request.maxTokens} promptChars=${request.prompt.length} " +
                    "formattedPromptChars=${formattedPrompt.length} seqLen=${generationConfig.seqLen} " +
                    "temperature=$temperature"
            )
            Log.v(TAG, "Formatted prompt preview=${previewForLog(formattedPrompt)}")
            val exitCode = withContext(Dispatchers.IO) {
                Log.d(TAG, "ExecuTorch generate() start")
                val generateMethod = llmModule.javaClass.getMethod(
                    "generate",
                    String::class.java,
                    LlmGenerationConfig::class.java,
                    LlmCallback::class.java
                )
                @Suppress("UNCHECKED_CAST")
                generateMethod.invoke(
                    llmModule,
                    formattedPrompt,
                    generationConfig,
                    object : LlmCallback {
                        override fun onResult(result: String) {
                            Log.v(
                                RAW_TAG,
                                "chunkLen=${result.length} preview=${previewForLog(result)}"
                            )
                            if (result.isEmpty()) return
                            if (!firstTokenSeen) {
                                firstTokenSeen = true
                                Log.d(TAG, "First token emitted")
                                callback.onFirstToken()
                            }
                            callback.onToken(result)
                            recentTokens.addLast(result)
                            while (recentTokens.size > 20) {
                                recentTokens.removeFirst()
                            }
                        }

                        override fun onStats(stats: String) {
                            Log.v(TAG, "ExecuTorch stats=$stats")
                            if (!generationFinished.isCompleted) {
                                generationFinished.complete(Unit)
                            }
                        }
                    }
                ) as Int
            }
            if (!generationFinished.isCompleted) {
                withTimeoutOrNull(2_000L) {
                    generationFinished.await()
                }
            }
            if (exitCode != 0) {
                Log.e(TAG, "ExecuTorch generate() returned non-zero exitCode=$exitCode")
                callback.onError(RuntimeException("ExecuTorch generate() failed with code: $exitCode"))
                return
            }
            if (!firstTokenSeen) {
                val availableMethods = runCatching {
                    withContext(Dispatchers.IO) {
                        val module = Module.load(modelPath)
                        try {
                            module.getMethods().joinToString(separator = ", ")
                        } finally {
                            module.destroy()
                        }
                    }
                }.getOrElse { throwable ->
                    Log.w(TAG, "Unable to inspect ExecuTorch module methods", throwable)
                    "<unavailable>"
                }
                val message = buildString {
                    append("ExecuTorch finished without emitting tokens.")
                    append(" The model may not expose a compatible LLM method")
                    append(" or the export may be incomplete.")
                    append(" promptTemplateType=").append(promptTemplateType)
                    append(" modelName=").append(request.modelName)
                    append(" modelPath=").append(request.modelFilePath)
                    append(" availableMethods=").append(availableMethods)
                }
                Log.e(TAG, message)
                callback.onError(IllegalStateException(message))
                return
            }
            Log.d(TAG, "ExecuTorch generate() completed successfully")
            callback.onComplete()
        } catch (_: CancellationException) {
            callback.onError(CancellationException("Generation cancelled"))
        } catch (t: Throwable) {
            Log.e(TAG, "ExecuTorch generation failed", t)
            callback.onError(
                RuntimeException(classifyError(t), t)
            )
        } finally {
            try {
                llmModule?.stop()
            } catch (_: Throwable) {
            }
            try {
                llmModule?.resetNative()
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
            val fileName = readDisplayName(uri) ?: "selected_model.pte"
            val modelsDir = File(appContext.cacheDir, "executorch-models")
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

    private fun resolveTokenizerPath(modelPath: String): String {
        val modelFile = File(modelPath)
        val parent = modelFile.parentFile
            ?: throw IllegalArgumentException("Model directory could not be resolved.")

        val candidateNames = listOf(
            "tokenizer.json",
            "tokenizer.model",
            "tokenizer.bin"
        )
        val tokenizer = candidateNames
            .asSequence()
            .map { File(parent, it) }
            .firstOrNull { it.exists() && it.isFile && it.length() > 0L }

        if (tokenizer != null) {
            return tokenizer.absolutePath
        }

        throw IllegalArgumentException(
            "Tokenizer file not found next to model. Put one of tokenizer.json / tokenizer.model / tokenizer.bin in the same folder as the .pte file."
        )
    }

    private suspend fun prepareTokenizerPath(tokenizerPathOrUri: String): String = withContext(Dispatchers.IO) {
        val raw = tokenizerPathOrUri.trim()
        if (raw.isBlank()) {
            throw IllegalArgumentException("Tokenizer file URI is empty.")
        }
        val uri = Uri.parse(raw)
        if (uri.scheme == "content") {
            val fileName = readDisplayName(uri) ?: "tokenizer.json"
            val modelsDir = File(appContext.cacheDir, "executorch-models")
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
            }
            val copied = File(modelsDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(copied).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalArgumentException("Unable to read selected tokenizer URI.")
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
            "tokenizer" in raw -> "Tokenizer file is missing or invalid for ExecuTorch."
            "model" in raw && "load" in raw -> "ExecuTorch model load failure."
            "unsupported" in raw -> "Unsupported ExecuTorch model or backend."
            "memory" in raw || "oom" in raw -> "Insufficient memory for ExecuTorch model."
            raw.isNotBlank() -> "ExecuTorch runtime error: ${t.message}"
            else -> "Unknown ExecuTorch runtime error."
        }
    }

    private fun resolvePromptTemplateType(
        promptTemplateType: PromptTemplateType?,
        modelName: String,
        modelPath: String,
        engineType: com.hilal.llm.catalog.EngineType?
    ): PromptTemplateType {
        val detected = promptTemplateType ?: detectPromptTemplateType(modelName, modelPath)
        if (detected != PromptTemplateType.GENERIC) {
            return detected
        }

        return when (engineType) {
            com.hilal.llm.catalog.EngineType.EXECUTORCH_GENERATE -> PromptTemplateType.GEMMA
            else -> PromptTemplateType.GENERIC
        }
    }

    private fun formatPrompt(rawPrompt: String, templateType: PromptTemplateType): String {
        return when (templateType) {
            PromptTemplateType.QWEN -> formatPromptForQwen(rawPrompt)
            PromptTemplateType.GEMMA -> formatPromptForGemma(rawPrompt)
            PromptTemplateType.PHI -> formatPromptForPhi(rawPrompt)
            PromptTemplateType.LLAMA -> formatPromptForLlama(rawPrompt)
            PromptTemplateType.GENERIC -> rawPrompt.trim()
        }
    }

    private fun formatPromptForQwen(rawPrompt: String): String {
        val cleaned = rawPrompt.trim()
        return """
<|im_start|>system
You are a helpful assistant. Answer briefly and directly.
<|im_end|>
<|im_start|>user
$cleaned
<|im_end|>
<|im_start|>assistant
""".trimStart()
    }

    private fun formatPromptForGemma(rawPrompt: String): String {
        val cleaned = rawPrompt.trim()
        return "<start_of_turn>user\n$cleaned\n<end_of_turn>\n<start_of_turn>model\n"
    }

    private fun formatPromptForPhi(rawPrompt: String): String {
        val cleaned = rawPrompt.trim()
        return """
<|system|>
You are a helpful assistant. Answer briefly and directly.
<|user|>
/no_think
$cleaned
<|assistant|>
""".trimStart()
    }

    private fun formatPromptForLlama(rawPrompt: String): String {
        val cleaned = rawPrompt.trim()
        return """
<s>[INST] <<SYS>>
You are a helpful assistant. Answer briefly and directly.
<</SYS>>

/no_think
$cleaned [/INST]
""".trimStart()
    }

    private fun generationTemperature(templateType: PromptTemplateType): Float {
        return when (templateType) {
            PromptTemplateType.QWEN -> QWEN_TEMPERATURE
            PromptTemplateType.GEMMA -> GEMMA_TEMPERATURE
            PromptTemplateType.PHI -> GENERIC_TEMPERATURE
            PromptTemplateType.LLAMA -> GENERIC_TEMPERATURE
            PromptTemplateType.GENERIC -> GENERIC_TEMPERATURE
        }
    }

    private fun buildGenerationConfig(
        prompt: String,
        promptTemplateType: PromptTemplateType,
        maxNewTokens: Int
    ): LlmGenerationConfig {
        val seqLen = estimateSeqLen(prompt, maxNewTokens)
        val temperature = generationTemperature(promptTemplateType)
        return LlmGenerationConfig.create()
            .echo(false)
            .maxNewTokens(maxNewTokens)
            .warming(false)
            .seqLen(seqLen)
            .temperature(temperature)
            .numBos(DEFAULT_NUM_BOS)
            .numEos(DEFAULT_NUM_EOS)
            .build()
    }

    private fun estimateSeqLen(prompt: String, maxNewTokens: Int): Int {
        val promptTokens = estimatePromptTokens(prompt)
        return (promptTokens + maxNewTokens + 32).coerceIn(DEFAULT_MIN_SEQ_LEN, DEFAULT_MAX_SEQ_LEN)
    }

    private fun estimatePromptTokens(prompt: String): Int {
        val compact = prompt.trim()
        if (compact.isEmpty()) return 0
        return ((compact.length / 4.0f).toInt() + 1).coerceAtLeast(1)
    }

    private fun detectPromptTemplateType(modelName: String, modelPath: String): PromptTemplateType {
        val key = "$modelName $modelPath".lowercase()
        return when {
            "qwen" in key -> PromptTemplateType.QWEN
            "gemma" in key -> PromptTemplateType.GEMMA
            "phi" in key -> PromptTemplateType.PHI
            "llama" in key -> PromptTemplateType.LLAMA
            else -> PromptTemplateType.GENERIC
        }
    }

    private fun stopTokensFor(templateType: PromptTemplateType): List<String> {
        return when (templateType) {
            PromptTemplateType.QWEN -> listOf("<|im_end|>", "<|endoftext|>", "<|im_start|>")
            PromptTemplateType.GEMMA -> listOf("<end_of_turn>", "<start_of_turn>user", "<start_of_turn>model")
            PromptTemplateType.PHI,
            PromptTemplateType.LLAMA,
            PromptTemplateType.GENERIC -> listOf("</s>", "<|endoftext|>", "<eos>", "<|eot_id|>")
        }
    }

    private fun previewForLog(text: String, maxChars: Int = 120): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= maxChars) compact else compact.take(maxChars) + "..."
    }
}
