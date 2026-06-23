package com.hilal.llm.benchmark

class UnsupportedRuntimeEngine(
    private val message: String
) : LocalLlmEngine {
    override suspend fun generate(
        request: BenchmarkPrompt,
        callback: StreamCallback
    ) {
        callback.onError(IllegalStateException(message))
    }
}
