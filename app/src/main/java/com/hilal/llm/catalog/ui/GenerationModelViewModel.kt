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
    val storedBytes: Map<String, Long> = emptyMap(),
    val freeBytes: Long = 0,
    val deletingModelId: String? = null,
    val isCancelling: Boolean = false,
    val errorMessage: String? = null
)

/** Model selection for product features; benchmark settings are deliberately independent. */
class GenerationModelViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = ModelStorageResolver(application)
    private val downloads = ModelDownloadRepository(storage)
    private val catalog = ModelCatalogService(storage)
    private val state = MutableStateFlow(GenerationModelUiState())
    val uiState = state.asStateFlow()
    private var download: Job? = null
    private var requestId = 0L

    init {
        viewModelScope.launch {
            downloads.inventory.collect { inventory ->
                state.update { current -> current.copy(
                    storedBytes = current.storedBytes + inventory.mapValues { it.value.storedBytes },
                    modelDownloadStates = current.modelDownloadStates + inventory.mapValues { it.value.state },
                    selectedCatalogModel = current.selectedCatalogModel?.takeUnless { inventory[it.modelId]?.let { entry -> !entry.ready && entry.state == DownloadState.Idle } == true }
                ) }
            }
        }
        refresh()
    }
    fun refresh() {
        viewModelScope.launch { refreshStorage() }
    }
    private suspend fun refreshStorage() {
        val models = ModelCatalogRepository.getAvailableModels().filter { it.purpose == ModelPurpose.GENERATION }
        downloads.refreshInventory(models)
        val inventory = withContext(Dispatchers.IO) {
            models.associate { it.id to (downloads.isModelDownloaded(it) to storage.storedBytes(it)) }
        }
        state.update { current -> current.copy(availableModels = models,
            selectedCatalogModel = current.selectedCatalogModel?.takeIf { inventory[it.modelId]?.first == true },
            storedBytes = inventory.mapValues { it.value.second },
            freeBytes = getApplication<Application>().filesDir.usableSpace,
            modelDownloadStates = models.associate { model ->
                model.id to if (current.activeDownloadModelId == model.id) current.modelDownloadStates[model.id] ?: DownloadState.Idle
                else if (inventory[model.id]?.first == true) DownloadState.Success
                else current.modelDownloadStates[model.id]?.takeIf { it is DownloadState.Failed } ?: DownloadState.Idle
            }) }
    }
    fun deleteModel(id: String, withReleasedRuntime: suspend (suspend () -> Unit) -> Unit) {
        if (state.value.activeDownloadModelId != null || state.value.deletingModelId != null) {
            state.update { it.copy(errorMessage = "Devam eden model işlemi bitince tekrar deneyin.") }; return
        }
        val model = ModelCatalogRepository.getModelById(id) ?: return
        state.update { it.copy(deletingModelId = id, errorMessage = null) }
        viewModelScope.launch {
            try {
                withReleasedRuntime {
                    check(downloads.deleteModel(model)) { "Model dosyaları silinemedi." }
                }
                state.update { it.copy(selectedCatalogModel = it.selectedCatalogModel?.takeUnless { selection -> selection.modelId == id }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { state.update { it.copy(errorMessage = error.message) } }
            finally {
                withContext(NonCancellable) { refreshStorage() }
                state.update { it.copy(deletingModelId = null) }
            }
        }
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
        if (state.value.activeDownloadModelId != null || state.value.deletingModelId != null) return
        val model = ModelCatalogRepository.getModelById(id) ?: return
        if (!model.isEnabled || !model.hasDownloadArtifacts || model.purpose != ModelPurpose.GENERATION) {
            state.update { it.copy(errorMessage = model.disabledReason ?: "Model kullanılamıyor.") }; return
        }
        cancelDownload()
        val currentRequest = ++requestId
        state.update { it.copy(selectedEngineType = engineFor(model.runtimeType), errorMessage = null, activeDownloadModelId = id) }
        download = viewModelScope.launch {
            try {
                val selected = catalog.prepare(model, ModelPurpose.GENERATION) { progress ->
                    if (requestId == currentRequest) state.update { it.copy(modelDownloadStates = it.modelDownloadStates + (id to progress)) }
                }
                ensureActive()
                if (requestId == currentRequest) {
                    state.update { it.copy(selectedCatalogModel = selected, modelDownloadStates = it.modelDownloadStates + (id to DownloadState.Success)) }
                    onSelected(selected)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (requestId == currentRequest) state.update { it.copy(errorMessage = error.message ?: "Model hazırlanamadı.", modelDownloadStates = it.modelDownloadStates + (id to DownloadState.Failed(error.message ?: "İndirme başarısız."))) }
            } finally {
                withContext(NonCancellable) { refreshStorage() }
                if (requestId == currentRequest) state.update { it.copy(activeDownloadModelId = null, isCancelling = false) }
            }
        }
    }
    fun cancelDownload() {
        val job = download ?: return
        if (!job.isActive) return
        val id = state.value.activeDownloadModelId
        state.update { it.copy(isCancelling = true) }
        job.cancel()
        viewModelScope.launch {
            job.join()
            if (download === job) {
                download = null
                state.update { it.copy(activeDownloadModelId = null, isCancelling = false,
                    modelDownloadStates = if (id == null) it.modelDownloadStates else it.modelDownloadStates + (id to DownloadState.Idle)) }
                refreshStorage()
            }
        }
    }
    private fun selection(model: ModelConfig) = catalog.resolve(model)
    private fun engineFor(runtime: RuntimeType) = when (runtime) {
        RuntimeType.LITERT -> EngineType.LITERT
        RuntimeType.LLAMA_CPP -> EngineType.LLAMA_CPP
        RuntimeType.EXECUTORCH -> EngineType.EXECUTORCH
        RuntimeType.ONNX -> EngineType.ONNX
    }
}
