package com.hilal.llm.benchmark

data class BenchmarkResult(
    val engineName: String = EngineType.FAKE.label,
    val modelName: String,
    val modelFilePath: String,
    val maxTokens: Int = 512,
    val prompt: String,
    val response: String,
    val firstTokenTimeMs: Long?,
    val totalTimeMs: Long?,
    val tokenCount: Int = estimateTokenCount(response),
    val tokensPerSecond: Double? = calculateTokensPerSecond(tokenCount, totalTimeMs),
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isSuccess: Boolean
        get() = errorMessage.isNullOrBlank()
}

fun estimateTokenCount(text: String): Int {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0

    val whitespaceEstimate = trimmed.split(Regex("\\s+")).size
    val charEstimate = (trimmed.length / 4.0).toInt().coerceAtLeast(1)
    return maxOf(whitespaceEstimate, charEstimate)
}

fun calculateTokensPerSecond(tokenCount: Int, totalTimeMs: Long?): Double? {
    if (totalTimeMs == null || totalTimeMs <= 0L) return null
    return tokenCount / (totalTimeMs / 1000.0)
}
