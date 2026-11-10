package com.hilal.llm.catalog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilal.llm.benchmark.EngineType
import com.hilal.llm.catalog.*
import com.hilal.llm.catalog.download.*
import com.hilal.llm.catalog.runtime.ModelRunFileMapper
import com.hilal.llm.catalog.storage.ModelStorageResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class GenerationModelUiState(
    val availableModels: List<ModelConfig> = emptyList(),
    val selectedEngineType: EngineType = EngineType.LITERT,
    val selectedCatalogModel: BenchmarkModelSelection? = null,
    val modelDownloadStates: Map<String, DownloadState> = emptyMap(),
    val activeDownloadModelId: String? = null,
    val errorMessage: String? = null
)

/** Model selection for product features; benchmark settings are deliberately independent. */
class GenerationModelViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = ModelStorageResolver(application)
    private val downloads = ModelDownloadRepository(storage)
    private val mapper = ModelRunFileMapper(storage)
    private val state = MutableStateFlow(GenerationModelUiState())
    val uiState = state.asStateFlow()
    private var download: Job? = null
    private var requestId = 0L

    init { refresh() }
    fun refresh() {
        val models = ModelCatalogRepository.getAvailableModels().filter { it.purpose == ModelPurpose.GENERATION }
        state.update { it.copy(availableModels = models, modelDownloadStates = models.associate { model ->
            model.id to if (downloads.isModelDownloaded(model)) DownloadState.Success else DownloadState.Idle
        }) }
    }
    fun setEngine(type: EngineType) {
        cancelDownload()
        state.update { it.copy(selectedEngineType = type, selectedCatalogModel = null, errorMessage = null) }
    }
    fun restoreModel(id: String?) {
        cancelDownload()
        val model = id?.let(ModelCatalogRepository::getModelById)
        state.update { it.copy(selectedCatalogModel = null, errorMessage = null) }
        if (model != null) {
            state.update { it.copy(selectedEngineType = engineFor(model.runtimeType)) }
            if (downloads.isModelDownloaded(model)) {
                try { state.update { it.copy(selectedCatalogModel = selection(model)) } }
                catch (error: Exception) { state.update { it.copy(errorMessage = error.message) } }
            } else state.update { it.copy(errorMessage = "${model.displayName} bu cihazda hazır değil. Yeniden indirip seçebilirsiniz.") }
        }
    }
    fun selectModel(id: String, onSelected: (BenchmarkModelSelection) -> Unit = {}) {
        val model = ModelCatalogRepository.getModelById(id) ?: return
        if (!model.isEnabled || !model.hasDownloadArtifacts || model.purpose != ModelPurpose.GENERATION) {
            state.update { it.copy(errorMessage = model.disabledReason ?: "Model kullanılamıyor.") }; return
        }
        cancelDownload()
        val currentRequest = ++requestId
        state.update { it.copy(selectedEngineType = engineFor(model.runtimeType), errorMessage = null, activeDownloadModelId = id) }
        download = viewModelScope.launch {
            try {
                if (!downloads.isModelDownloaded(model)) downloads.downloadModel(model) { progress ->
                    if (requestId == currentRequest) state.update { it.copy(modelDownloadStates = it.modelDownloadStates + (id to progress)) }
                }
                ensureActive()
                val selected = selection(model)
                if (requestId == currentRequest) {
                    state.update { it.copy(selectedCatalogModel = selected, activeDownloadModelId = null, modelDownloadStates = it.modelDownloadStates + (id to DownloadState.Success)) }
                    onSelected(selected)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (requestId == currentRequest) state.update { it.copy(activeDownloadModelId = null, errorMessage = error.message ?: "Model hazırlanamadı.", modelDownloadStates = it.modelDownloadStates + (id to DownloadState.Failed(error.message ?: "İndirme başarısız."))) }
            }
        }
    }
    fun cancelDownload() {
        requestId++
        download?.cancel()
        download = null
        state.update { current -> current.copy(activeDownloadModelId = null, modelDownloadStates = current.activeDownloadModelId?.let { current.modelDownloadStates + (it to DownloadState.Idle) } ?: current.modelDownloadStates) }
    }
    private fun selection(model: ModelConfig): BenchmarkModelSelection {
        val files = mapper.toRunFiles(model)
        return BenchmarkModelSelection(model.id, model.displayName, model.runtimeType, model.engineType, model.promptTemplateType, files.modelPath, files.tokenizerPath)
    }
    private fun engineFor(runtime: RuntimeType) = when (runtime) {
        RuntimeType.LITERT -> EngineType.LITERT
        RuntimeType.LLAMA_CPP -> EngineType.LLAMA_CPP
        RuntimeType.EXECUTORCH -> EngineType.EXECUTORCH
        RuntimeType.ONNX -> EngineType.ONNX
    }
}
