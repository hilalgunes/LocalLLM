package com.hilal.llm.catalog.ui

import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.download.DownloadState

data class ModelCatalogUiState(
    val models: List<ModelConfig> = emptyList(),
    val downloadStates: Map<String, DownloadState> = emptyMap(),
    val errorMessage: String? = null,
    val isLoading: Boolean = false
)
