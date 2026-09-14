package com.hilal.llm.benchmark

import ai.onnxruntime.genai.Config
import ai.onnxruntime.genai.GenAI
import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Tokenizer
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** CPU-only GenAI runner. Native objects are owned and used under one mutex. */
class OnnxLlmEngine : LocalLlmEngine {
    private val mutex = Mutex()
    private var model: Model? = null
    private var tokenizer: Tokenizer? = null
    private var modelDirectory: String? = null

    suspend fun release() = withContext(Dispatchers.Default) {
        mutex.withLock { closeModel() }
    }

    private fun closeModel() {
        tokenizer?.close()
        tokenizer = null
        model?.close()
        model = null
        modelDirectory = null
    }

    override suspend fun generate(request: BenchmarkPrompt, callback: StreamCallback) {
        try {
            withContext(Dispatchers.Default) {
                mutex.withLock {
                    val configFile = File(request.modelFilePath)
                    require(configFile.name == "genai_config.json" && configFile.isFile) {
                        "Select a downloaded ONNX GenAI model package from the catalog."
                    }
                    val directory = requireNotNull(configFile.parentFile)
                    val modelConfig = JSONObject(configFile.readText()).getJSONObject("model")
                    // Validate before entering JNI. This formatter supports only our text-only Gemma 4 flow.
                    val formatted = OnnxChatPrompt.format(modelConfig.getString("type"), request.prompt)
                    val loadStarted = SystemClock.elapsedRealtime()
                    if (modelDirectory != directory.absolutePath) {
                        closeModel()
                        GenAI.setTelemetry(false)
                        Config(directory.absolutePath).use { config ->
                            config.clearProviders() // CPU provider; no GPU experiment.
                            model = Model(config)
                        }
                        try {
                            tokenizer = Tokenizer(requireNotNull(model))
                            modelDirectory = directory.absolutePath
                        } catch (error: Throwable) {
                            closeModel()
                            throw error
                        }
                    }
                    Log.d("ONNX_PERF", "Model ready | backend=CPU | loadMs=${SystemClock.elapsedRealtime() - loadStarted}")
                    currentCoroutineContext().ensureActive()
                    val activeModel = requireNotNull(model)
                    val activeTokenizer = requireNotNull(tokenizer)
                    activeTokenizer.encode(formatted).use { input ->
                        val inputLength = input.getSequence(0).size
                        val contextLength = modelConfig.getInt("context_length")
                        require(inputLength.toLong() + request.maxTokens <= contextLength) {
                            "Prompt exceeds ONNX model context; no document text was truncated."
                        }
                        val started = SystemClock.elapsedRealtime()
                        Log.d("ONNX_PERF", "Generation | inputTokens=$inputLength | maxNewTokens=${request.maxTokens}")
                        GeneratorParams(activeModel).use { parameters ->
                            parameters.setSearchOption("max_length", (inputLength + request.maxTokens).toDouble())
                            parameters.setSearchOption("do_sample", true)
                            parameters.setSearchOption("temperature", 0.7)
                            parameters.setSearchOption("top_k", 40.0)
                            parameters.setSearchOption("top_p", 0.95)
                            Generator(activeModel, parameters).use { generator ->
                                activeTokenizer.createStream().use { stream ->
                                    generator.appendTokenSequences(input)
                                    val eosTokens = activeTokenizer.eosTokenIds.toSet()
                                    var firstText = true
                                    while (!generator.isDone) {
                                        currentCoroutineContext().ensureActive()
                                        generator.generateNextToken()
                                        val token = generator.getLastTokenInSequence(0)
                                        if (token in eosTokens) break
                                        val text = stream.decode(token)
                                        if (text.isNotEmpty()) {
                                            if (firstText) {
                                                firstText = false
                                                Log.d("ONNX_PERF", "First text | elapsedMs=${SystemClock.elapsedRealtime() - started}")
                                                callback.onFirstToken()
                                            }
                                            callback.onToken(text)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    callback.onComplete()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            callback.onError(error)
        } catch (error: LinkageError) {
            callback.onError(IllegalStateException("ONNX native runtime could not be loaded.", error))
        }
    }
}
