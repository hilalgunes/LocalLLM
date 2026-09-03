package com.hilal.llm.benchmark

import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.download.DownloadState

data class BenchmarkUiState(
    val selectedEngineType: EngineType = EngineType.LLAMA_CPP,
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
    val availableModels: List<ModelConfig> = emptyList(),
    val modelDownloadStates: Map<String, DownloadState> = emptyMap(),
    val selectedModelConfig: ModelConfig? = null,
    val isModelPickerVisible: Boolean = false,
    val activeDownloadModelId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
