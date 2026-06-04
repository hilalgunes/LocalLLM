package com.hilal.llm.benchmark

data class BenchmarkUiState(
    val selectedEngineType: EngineType = EngineType.FAKE,
    val selectedModel: LlmModelInfo? = null,
    val maxTokensInput: String = "512",
    val prompt: String = "",
    val response: String = "",
    val firstTokenTimeMs: Long? = null,
    val totalTimeMs: Long? = null,
    val tokenCount: Int = 0,
    val tokensPerSecond: Double? = null,
    val errorMessage: String? = null,
    val modelWarningMessage: String? = null,
    val isRunning: Boolean = false,
    val completedResults: List<BenchmarkResult> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)
