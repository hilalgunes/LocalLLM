package com.hilal.llm.lab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.inference.ModelSessionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class ExperimentState(val prompt: String = "Yerel dil modellerinin kullanımını üç maddede anlat.", val maxTokens: String = "256",
    val mode: String = "cold", val running: Boolean = false, val loading: Boolean = true,
    val output: String = "", val records: List<ExperimentRecord> = emptyList(), val error: String? = null)

class ExperimentViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ExperimentStore(File(app.filesDir, "experiments-v1.json"))
    private val mutable = MutableStateFlow(ExperimentState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    init {
        viewModelScope.launch {
            try { val records = withContext(Dispatchers.IO) { store.load() }; mutable.update { it.copy(records = records, loading = false) } }
            catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
        }
    }
    fun prompt(value: String) { if (!state.value.running) mutable.update { it.copy(prompt = value) } }
    fun tokens(value: String) { if (!state.value.running) mutable.update { it.copy(maxTokens = value) } }
    fun mode(value: String) { if (!state.value.running) mutable.update { it.copy(mode = value) } }
    fun stop() { job?.cancel() }
    fun restore(record: ExperimentRecord) {
        if (!state.value.running) mutable.update { it.copy(prompt = record.definition.prompt, maxTokens = record.definition.maxTokens.toString(), mode = record.definition.mode) }
    }
    fun run(selection: BenchmarkModelSelection, sessions: ModelSessionService) {
        val current = state.value
        if (current.running || current.loading) return
        val tokens = current.maxTokens.toIntOrNull()
        if (tokens == null || tokens !in 1..2048 || current.prompt.isBlank()) {
            mutable.update { it.copy(error = "Soru girin; token sınırı 1–2048 arasında olmalı.") }; return
        }
        val definition = ExperimentDefinition(selection.modelId, selection.runtimeType.name, current.prompt, tokens, current.mode)
        mutable.update { it.copy(running = true, output = "", error = null) }
        job = viewModelScope.launch {
            try {
                val result = ExperimentRunner(sessions::openSession).run(selection, definition) { text -> mutable.update { it.copy(output = text) } }
                val records = withContext(NonCancellable + Dispatchers.IO) { store.append(result) }
                mutable.update { it.copy(records = records, error = result.error) }
            } catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
            finally { mutable.update { it.copy(running = false) } }
        }
    }
}
