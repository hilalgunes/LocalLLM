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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ModelCatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val storageResolver = ModelStorageResolver(application.applicationContext)
    private val downloadRepository = ModelDownloadRepository(storageResolver)
    private val catalog = com.hilal.llm.catalog.ModelCatalogService(storageResolver)
    private val _uiState = MutableStateFlow(ModelCatalogUiState())
    val uiState: StateFlow<ModelCatalogUiState> = _uiState.asStateFlow()
    private val downloadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    init {
        viewModelScope.launch { downloadRepository.inventory.collect { inventory ->
            _uiState.update { it.copy(downloadStates = it.downloadStates + inventory.mapValues { entry -> entry.value.state }) }
        } }
    }

    fun loadModels() {
        val models = ModelCatalogRepository.getAvailableModels()
        viewModelScope.launch { downloadRepository.refreshInventory(models) }
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
        if (downloadJobs[modelId]?.isCompleted == false || _uiState.value.isLoading) return
        val model = ModelCatalogRepository.getModelById(modelId) ?: run {
            _uiState.update { it.copy(errorMessage = "Model not found: $modelId") }
            return
        }

        downloadJobs[modelId] = viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            try {
                catalog.prepare(model, model.purpose) { state ->
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
                _uiState.update { it.copy(downloadStates = it.downloadStates + (modelId to DownloadState.Idle)) }
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

    fun cancelDownload(modelId: String) { downloadJobs[modelId]?.cancel() }

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
            catalog.resolve(model)
        } catch (throwable: Throwable) {
            _uiState.update {
                it.copy(errorMessage = throwable.message ?: "Unable to prepare benchmark files.")
            }
            null
        }
    }
}
