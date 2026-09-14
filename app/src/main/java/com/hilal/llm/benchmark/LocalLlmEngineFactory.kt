package com.hilal.llm.benchmark

import com.hilal.llm.catalog.EngineType as CatalogEngineType
import com.hilal.llm.catalog.RuntimeType

class LocalLlmEngineFactory(
    private val execuTorchEngine: LocalLlmEngine?,
    private val liteRtEngine: LocalLlmEngine?,
    private val llamaCppEngine: LocalLlmEngine?,
    private val onnxEngine: LocalLlmEngine? = null,
    private val manualFallbackEngine: LocalLlmEngine
) {
    fun create(
        engineType: CatalogEngineType?,
        runtimeType: RuntimeType?
    ): LocalLlmEngine {
        return when (engineType) {
            CatalogEngineType.EXECUTORCH_GENERATE -> execuTorchEngine ?: manualFallbackEngine
            CatalogEngineType.EXECUTORCH_FORWARD ->
                UnsupportedRuntimeEngine("This ExecuTorch model exposes only forward method and requires a custom forward runner.")

            CatalogEngineType.LITERT_LM ->
                liteRtEngine?.let { LiteRtLocalLlmEngineAdapter(it) }
                    ?: UnsupportedRuntimeEngine("LiteRT catalog execution is not connected yet.")

            CatalogEngineType.ONNX_GENAI -> onnxEngine ?: UnsupportedRuntimeEngine("ONNX engine is not initialized.")
            CatalogEngineType.LLAMA_CPP ->
                llamaCppEngine?.let { LlamaCppLocalLlmEngineAdapter(it) }
                    ?: manualFallbackEngine

            null -> createFallback(runtimeType)
        }
    }

    private fun createFallback(runtimeType: RuntimeType?): LocalLlmEngine {
        return when (runtimeType) {
            RuntimeType.EXECUTORCH -> execuTorchEngine ?: manualFallbackEngine
            RuntimeType.LITERT -> liteRtEngine ?: manualFallbackEngine
            RuntimeType.ONNX -> onnxEngine ?: UnsupportedRuntimeEngine("ONNX engine is not initialized.")
            RuntimeType.LLAMA_CPP -> llamaCppEngine ?: manualFallbackEngine
            null -> manualFallbackEngine
        }
    }
}
