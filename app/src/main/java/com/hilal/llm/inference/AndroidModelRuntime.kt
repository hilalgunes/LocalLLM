package com.hilal.llm.inference

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.hilal.llm.benchmark.*
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.RuntimeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Platform composition root. Engines live across Activity recreation, outside feature ViewModels. */
class AndroidModelRuntime(application: Application) {
    private val llama = LlamaCppLlmEngine(application, application.contentResolver)
    private val liteRt = LiteRtLlmEngine(application, application.contentResolver)
    private val execuTorch = ExecuTorchLlmEngine(application, application.contentResolver)
    private val onnx = OnnxLlmEngine()
    private val fake = FakeLlmEngine()
    private val factory = LocalLlmEngineFactory(
        execuTorchEngine = execuTorch,
        liteRtEngine = liteRt,
        llamaCppEngine = llama,
        onnxEngine = onnx,
        manualFallbackEngine = UnsupportedRuntimeEngine("Model runtime is unavailable.")
    )

    val sessions = ModelSessionService { selection ->
        val warmLiteRt = if (selection.runtimeType == RuntimeType.LITERT) {
            liteRt.openSession(selection.modelPath)
        } else null
        val engine = factory.create(selection.engineType, selection.runtimeType)
        object : ModelBackendSession {
            override suspend fun generate(request: BenchmarkPrompt, callback: StreamCallback) {
                if (warmLiteRt != null) warmLiteRt.generate(request, callback)
                else engine.generate(request, callback)
            }
            override suspend fun close() {
                try { warmLiteRt?.close() } finally { onnx.release() }
            }
        }
    }

    fun benchmarkEngine(selection: BenchmarkModelSelection?, type: EngineType): LocalLlmEngine {
        val engine = selection?.let { factory.create(it.engineType, it.runtimeType) } ?: when (type) {
            EngineType.LLAMA_CPP -> llama
            EngineType.LITERT -> liteRt
            EngineType.EXECUTORCH -> execuTorch
            EngineType.ONNX -> onnx
            EngineType.FAKE -> fake
        }
        return object : LocalLlmEngine {
            override suspend fun generate(request: BenchmarkPrompt, callback: StreamCallback) {
                sessions.withColdRuntime {
                    try { engine.generate(request, callback) }
                    finally { withContext(NonCancellable) { onnx.release() } }
                }
            }
        }
    }
}

class ModelRuntimeViewModel(application: Application) : AndroidViewModel(application) {
    val runtime = AndroidModelRuntime(application)

    override fun onCleared() {
        super.onCleared()
        // Independent cleanup scope: the owning ViewModel scope is already cancelled here.
        CoroutineScope(Dispatchers.IO).launch { runtime.sessions.close() }
    }
}
