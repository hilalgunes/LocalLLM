package com.hilal.llm.benchmark

class BenchmarkRunner(
    private val engine: LocalLlmEngine
) {
    suspend fun run(
        request: BenchmarkPrompt,
        onToken: (String) -> Unit,
        onComplete: (BenchmarkResult) -> Unit
    ) {
        val startedAt = System.currentTimeMillis()
        var firstTokenTimeMs: Long? = null
        val responseBuilder = StringBuilder()

        engine.generate(
            request = request,
            callback = object : StreamCallback {
                override fun onFirstToken() {
                    if (firstTokenTimeMs == null) {
                        firstTokenTimeMs = System.currentTimeMillis() - startedAt
                    }
                }

                override fun onToken(token: String) {
                    responseBuilder.append(token)
                    onToken(responseBuilder.toString())
                }

                override fun onComplete() {
                    val totalTimeMs = System.currentTimeMillis() - startedAt
                    val response = responseBuilder.toString()
                    val tokenCount = estimateTokenCount(response)
                    val result = BenchmarkResult(
                        modelName = request.modelName,
                        modelFilePath = request.modelFilePath,
                        prompt = request.prompt,
                        response = response,
                        firstTokenTimeMs = firstTokenTimeMs,
                        totalTimeMs = totalTimeMs,
                        tokenCount = tokenCount,
                        tokensPerSecond = calculateTokensPerSecond(tokenCount, totalTimeMs),
                        errorMessage = null
                    )
                    onComplete(result)
                }

                override fun onError(throwable: Throwable) {
                    val totalTimeMs = System.currentTimeMillis() - startedAt
                    val response = responseBuilder.toString()
                    val tokenCount = estimateTokenCount(response)
                    val result = BenchmarkResult(
                        modelName = request.modelName,
                        modelFilePath = request.modelFilePath,
                        prompt = request.prompt,
                        response = response,
                        firstTokenTimeMs = firstTokenTimeMs,
                        totalTimeMs = totalTimeMs,
                        tokenCount = tokenCount,
                        tokensPerSecond = calculateTokensPerSecond(tokenCount, totalTimeMs),
                        errorMessage = throwable.message ?: "Unknown generation error"
                    )
                    onComplete(result)
                }
            }
        )
    }
}
