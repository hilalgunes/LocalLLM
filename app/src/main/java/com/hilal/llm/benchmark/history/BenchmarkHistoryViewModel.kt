package com.hilal.llm.benchmark.history

import android.app.Application
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilal.llm.benchmark.BenchmarkResult
import com.hilal.llm.benchmark.ResponseQuality
import com.hilal.llm.benchmark.SelectionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharedFlow

class BenchmarkHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BenchmarkResultRepository(application.applicationContext)
    private val exportRepository = BenchmarkExportRepository(application.applicationContext)
    private val _exportEvents = MutableSharedFlow<BenchmarkExportEvent>(extraBufferCapacity = 1)
    val exportEvents: SharedFlow<BenchmarkExportEvent> = _exportEvents.asSharedFlow()
    private val _uiState = MutableStateFlow(BenchmarkHistoryUiState())
    val uiState: StateFlow<BenchmarkHistoryUiState> = _uiState.asStateFlow()

    fun loadResults() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.loadResults()
                }
            }.onSuccess { results ->
                applyResults(results)
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = throwable.message ?: "Unable to load benchmark history."
                    )
                }
            }
        }
    }

    fun setRuntimeFilter(runtimeType: com.hilal.llm.catalog.RuntimeType?) {
        _uiState.update { current ->
            val updated = current.copy(selectedRuntimeFilter = runtimeType)
            recalculateState(updated)
        }
    }

    fun setSourceFilter(selectionSource: SelectionSource?) {
        _uiState.update { current ->
            val updated = current.copy(selectedSourceFilter = selectionSource)
            recalculateState(updated)
        }
    }

    fun toggleCompareSelection(result: BenchmarkResult) {
        _uiState.update { current ->
            val keys = current.compareSelectionKeys.toMutableList()
            val key = result.createdAt
            if (keys.contains(key)) {
                keys.remove(key)
            } else if (keys.size < 2) {
                keys.add(key)
            } else {
                keys.removeAt(0)
                keys.add(key)
            }
            val selectedResults = current.results.filter { it.createdAt in keys }
            current.copy(
                compareSelectionKeys = keys,
                compareLeft = selectedResults.firstOrNull(),
                compareRight = selectedResults.getOrNull(1)
            )
        }
    }

    fun clearCompareSelection() {
        _uiState.update {
            it.copy(compareSelectionKeys = emptyList(), compareLeft = null, compareRight = null)
        }
    }

    fun exportCsv() {
        exportCurrentResults("text/csv") { results ->
            exportRepository.exportAsCsv(results)
        }
    }

    fun exportJson() {
        exportCurrentResults("application/json") { results ->
            exportRepository.exportAsJson(results)
        }
    }

    fun updateQuality(resultId: String, quality: ResponseQuality, note: String? = null) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.updateQuality(resultId, quality, note)
                }
            }.onSuccess {
                loadResults()
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        errorMessage = throwable.message ?: "Unable to update quality."
                    )
                }
            }
        }
    }

    private fun applyResults(results: List<BenchmarkResult>) {
        _uiState.update { current ->
            recalculateState(
                current.copy(
                    results = results,
                    isLoading = false,
                    errorMessage = null
                )
            )
        }
    }

    private fun exportCurrentResults(
        mimeType: String,
        exportBlock: suspend (List<BenchmarkResult>) -> java.io.File
    ) {
        val results = _uiState.value.filteredResults
        if (results.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No benchmark results to export.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, errorMessage = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    exportBlock(results)
                }
            }.onSuccess { file ->
                val uri = FileProvider.getUriForFile(
                    getApplication<Application>(),
                    "${getApplication<Application>().packageName}.fileprovider",
                    file
                )
                _exportEvents.tryEmit(
                    BenchmarkExportEvent.ShareFile(
                        uri = uri,
                        mimeType = mimeType,
                        fileName = file.name
                    )
                )
                _uiState.update { it.copy(isExporting = false) }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = throwable.message ?: "Unable to export benchmark results."
                    )
                }
                _exportEvents.tryEmit(BenchmarkExportEvent.Error(throwable.message ?: "Unable to export benchmark results."))
            }
        }
    }

    private fun recalculateState(state: BenchmarkHistoryUiState): BenchmarkHistoryUiState {
        val filteredResults = state.results.filter { result ->
            matchesRuntimeFilter(result, state.selectedRuntimeFilter) &&
                matchesSourceFilter(result, state.selectedSourceFilter)
        }
        val analytics = BenchmarkAnalyticsCalculator.calculate(filteredResults)
        val selectedResults = state.results.filter { it.createdAt in state.compareSelectionKeys }
        return state.copy(
            filteredResults = filteredResults,
            analytics = analytics,
            compareLeft = selectedResults.firstOrNull(),
            compareRight = selectedResults.getOrNull(1)
        )
    }

    private fun matchesRuntimeFilter(
        result: BenchmarkResult,
        runtimeType: com.hilal.llm.catalog.RuntimeType?
    ): Boolean {
        if (runtimeType == null) return true
        return inferRuntimeType(result) == runtimeType
    }

    private fun matchesSourceFilter(
        result: BenchmarkResult,
        selectionSource: SelectionSource?
    ): Boolean {
        if (selectionSource == null) return true
        return result.selectionSource == selectionSource
    }

    private fun inferRuntimeType(result: BenchmarkResult): com.hilal.llm.catalog.RuntimeType? {
        result.runtimeType?.let { return it }
        val key = "${result.engineName} ${result.modelFilePath}".lowercase()
        return when {
            "executorch" in key -> com.hilal.llm.catalog.RuntimeType.EXECUTORCH
            "litert" in key -> com.hilal.llm.catalog.RuntimeType.LITERT
            "llama" in key -> com.hilal.llm.catalog.RuntimeType.LLAMA_CPP
            else -> null
        }
    }
}
