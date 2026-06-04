package com.hilal.llm.benchmark

import kotlinx.coroutines.delay

class FakeLlmEngine : LocalLlmEngine {
    override suspend fun generate(
        request: BenchmarkPrompt,
        callback: StreamCallback
    ) {
        try {
            val responseText =
                "Mocked response from ${request.modelName}. Prompt received and benchmark completed successfully."
            val tokens = responseText.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) {
                callback.onComplete()
                return
            }

            delay(250L)
            callback.onFirstToken()

            tokens.forEachIndexed { index, token ->
                delay(65L)
                val value = if (index == 0) token else " $token"
                callback.onToken(value)
            }
            callback.onComplete()
        } catch (throwable: Throwable) {
            callback.onError(throwable)
        }
    }
}
