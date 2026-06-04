package com.hilal.llm.benchmark

interface LocalLlmEngine {
    suspend fun generate(
        request: BenchmarkPrompt,
        callback: StreamCallback
    )
}

interface StreamCallback {
    fun onFirstToken()
    fun onToken(token: String)
    fun onComplete()
    fun onError(throwable: Throwable)
}
