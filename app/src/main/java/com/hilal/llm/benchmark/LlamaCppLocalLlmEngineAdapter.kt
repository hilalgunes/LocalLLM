package com.hilal.llm.benchmark

class LlamaCppLocalLlmEngineAdapter(
    private val delegate: LocalLlmEngine
) : LocalLlmEngine {
    override suspend fun generate(
        request: BenchmarkPrompt,
        callback: StreamCallback
    ) {
        if (!request.modelFilePath.trim().endsWith(".gguf", ignoreCase = true)) {
            callback.onError(
                IllegalArgumentException("Selected llama.cpp model must be a .gguf file.")
            )
            return
        }

        delegate.generate(request, callback)
    }
}
