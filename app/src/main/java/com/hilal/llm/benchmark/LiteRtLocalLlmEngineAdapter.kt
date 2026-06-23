package com.hilal.llm.benchmark

class LiteRtLocalLlmEngineAdapter(
    private val delegate: LocalLlmEngine
) : LocalLlmEngine {
    override suspend fun generate(
        request: BenchmarkPrompt,
        callback: StreamCallback
    ) {
        if (!isSupportedModelPath(request.modelFilePath)) {
            callback.onError(
                IllegalArgumentException(
                    "Selected LiteRT model format is not supported by current LiteRT runner."
                )
            )
            return
        }
        delegate.generate(request, callback)
    }

    private fun isSupportedModelPath(modelPath: String): Boolean {
        val normalized = modelPath.lowercase()
        return normalized.endsWith(".litertlm") ||
            normalized.endsWith(".task") ||
            normalized.endsWith(".tflite") ||
            normalized.endsWith(".zip") ||
            normalized.endsWith(".bundle")
    }
}
