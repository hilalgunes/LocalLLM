package com.hilal.llm.benchmark

import com.hilal.llm.catalog.BenchmarkModelSelection

data class BenchmarkUiState(
    val selectedEngineType: EngineType = EngineType.FAKE,
    val selectedModel: LlmModelInfo? = null,
    val selectedCatalogModel: BenchmarkModelSelection? = null,
    val selectedPreset: BenchmarkPreset? = null,
    val maxTokensInput: String = "512",
    val prompt: String = "",
    val response: String = "",
    val firstTokenTimeMs: Long? = null,
    val totalTimeMs: Long? = null,
    val tokenCount: Int = 0,
    val tokensPerSecond: Double? = null,
    val errorMessage: String? = null,
    val modelWarningMessage: String? = null,
    val draftResponseQuality: ResponseQuality = ResponseQuality.UNKNOWN,
    val draftQualityNote: String = "",
    val isRunning: Boolean = false,
    val completedResults: List<BenchmarkResult> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)
