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
        val initialRamSnapshot = RamUsageReader.readSnapshot()
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
                    val finalRamSnapshot = RamUsageReader.readSnapshot()
                    logRamSnapshot(initialRamSnapshot, finalRamSnapshot)
                    val result = BenchmarkResult(
                        modelId = request.modelId,
                        modelName = request.modelName,
                        modelFilePath = request.modelFilePath,
                        prompt = request.prompt,
                        response = response,
                        firstTokenTimeMs = firstTokenTimeMs,
                        totalTimeMs = totalTimeMs,
                        tokenCount = tokenCount,
                        tokensPerSecond = calculateTokensPerSecond(tokenCount, totalTimeMs),
                        errorMessage = null,
                        selectionSource = request.selectionSource,
                        runtimeType = request.runtimeType,
                        engineType = request.engineType,
                        promptTemplateType = request.promptTemplateType,
                        totalRamMb = finalRamSnapshot?.totalRamMb,
                        nativeRamMb = finalRamSnapshot?.nativeRamMb,
                        javaRamMb = finalRamSnapshot?.javaRamMb
                    )
                    onComplete(result)
                }

                override fun onError(throwable: Throwable) {
                    val totalTimeMs = System.currentTimeMillis() - startedAt
                    val response = responseBuilder.toString()
                    val tokenCount = estimateTokenCount(response)
                    val finalRamSnapshot = RamUsageReader.readSnapshot()
                    logRamSnapshot(initialRamSnapshot, finalRamSnapshot)
                    val result = BenchmarkResult(
                        modelId = request.modelId,
                        modelName = request.modelName,
                        modelFilePath = request.modelFilePath,
                        prompt = request.prompt,
                        response = response,
                        firstTokenTimeMs = firstTokenTimeMs,
                        totalTimeMs = totalTimeMs,
                        tokenCount = tokenCount,
                        tokensPerSecond = calculateTokensPerSecond(tokenCount, totalTimeMs),
                        errorMessage = throwable.message ?: "Unknown generation error",
                        selectionSource = request.selectionSource,
                        runtimeType = request.runtimeType,
                        engineType = request.engineType,
                        promptTemplateType = request.promptTemplateType,
                        totalRamMb = finalRamSnapshot?.totalRamMb,
                        nativeRamMb = finalRamSnapshot?.nativeRamMb,
                        javaRamMb = finalRamSnapshot?.javaRamMb
                    )
                    onComplete(result)
                }
            }
        )
    }

    private fun logRamSnapshot(initial: RamSnapshot?, final: RamSnapshot?) {
        if (final == null) return
        android.util.Log.d(
            TAG,
            "RAM initial(java=${initial?.javaRamMb ?: "-"}MB, native=${initial?.nativeRamMb ?: "-"}MB, total=${initial?.totalRamMb ?: "-"}MB) " +
                "final(java=${final.javaRamMb}MB, native=${final.nativeRamMb}MB, total=${final.totalRamMb}MB)"
        )
    }

    private companion object {
        private const val TAG = "BenchmarkRunner"
    }
}
