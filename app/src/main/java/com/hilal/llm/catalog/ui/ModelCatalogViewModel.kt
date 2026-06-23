package com.hilal.llm.catalog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.ModelCatalogRepository
import com.hilal.llm.catalog.download.DownloadState
import com.hilal.llm.catalog.download.ModelDownloadRepository
import com.hilal.llm.catalog.runtime.ModelRunFileMapper
import com.hilal.llm.catalog.storage.ModelStorageResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ModelCatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val storageResolver = ModelStorageResolver(application.applicationContext)
    private val downloadRepository = ModelDownloadRepository(storageResolver)
    private val runFileMapper = ModelRunFileMapper(storageResolver)
    private val _uiState = MutableStateFlow(ModelCatalogUiState())
    val uiState: StateFlow<ModelCatalogUiState> = _uiState.asStateFlow()

    fun loadModels() {
        val models = ModelCatalogRepository.getAvailableModels()
        val downloadStates = models.associate { model ->
            model.id to if (downloadRepository.isModelDownloaded(model)) {
                DownloadState.Success
            } else {
                DownloadState.Idle
            }
        }

        _uiState.update { current ->
            current.copy(models = models, downloadStates = downloadStates, errorMessage = null)
        }
    }

    fun downloadModel(modelId: String) {
        val model = ModelCatalogRepository.getModelById(modelId) ?: run {
            _uiState.update { it.copy(errorMessage = "Model not found: $modelId") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            try {
                downloadRepository.downloadModel(model) { state ->
                    _uiState.update { current ->
                        current.copy(
                            downloadStates = current.downloadStates + (modelId to state),
                            errorMessage = when (state) {
                                is DownloadState.Failed -> state.message
                                else -> current.errorMessage
                            }
                        )
                    }
                }
                _uiState.update { current ->
                    current.copy(
                        downloadStates = current.downloadStates + (modelId to DownloadState.Success)
                    )
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        errorMessage = throwable.message ?: "Unknown download error",
                        downloadStates = it.downloadStates + (
                            modelId to DownloadState.Failed(throwable.message ?: "Unknown download error")
                        )
                    )
                }
            }
        }
    }

    fun deleteModel(modelId: String) {
        val model = ModelCatalogRepository.getModelById(modelId) ?: run {
            _uiState.update { it.copy(errorMessage = "Model not found: $modelId") }
            return
        }

        val deleted = downloadRepository.deleteModel(model)
        if (deleted) {
            _uiState.update { current ->
                current.copy(
                    downloadStates = current.downloadStates + (modelId to DownloadState.Idle)
                )
            }
        } else {
            _uiState.update { it.copy(errorMessage = "Unable to delete model: ${model.displayName}") }
        }
    }

    fun isModelDownloaded(modelId: String): Boolean {
        val model = ModelCatalogRepository.getModelById(modelId) ?: return false
        return downloadRepository.isModelDownloaded(model)
    }

    fun createBenchmarkSelection(modelId: String): BenchmarkModelSelection? {
        val model = ModelCatalogRepository.getModelById(modelId) ?: run {
            _uiState.update { it.copy(errorMessage = "Model not found: $modelId") }
            return null
        }

        if (!model.isEnabled) {
            _uiState.update { it.copy(errorMessage = "${model.displayName} is disabled.") }
            return null
        }

        if (!downloadRepository.isModelDownloaded(model)) {
            _uiState.update { it.copy(errorMessage = "${model.displayName} is not downloaded yet.") }
            return null
        }

        return try {
            val runFiles = runFileMapper.toRunFiles(model)
            BenchmarkModelSelection(
                modelId = model.id,
                displayName = model.displayName,
                runtimeType = model.runtimeType,
                engineType = model.engineType,
                promptTemplateType = model.promptTemplateType,
                modelPath = runFiles.modelPath,
                tokenizerPath = runFiles.tokenizerPath
            )
        } catch (throwable: Throwable) {
            _uiState.update {
                it.copy(errorMessage = throwable.message ?: "Unable to prepare benchmark files.")
            }
            null
        }
    }
}
