package com.hilal.llm.benchmark

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.PromptTemplateType
import com.hilal.llm.catalog.RuntimeType
import com.hilal.llm.benchmark.history.BenchmarkResultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BenchmarkViewModel : ViewModel() {
    companion object {
        private const val DEFAULT_MAX_TOKENS = 512
        private const val MIN_MAX_TOKENS = 1
        private const val MAX_MAX_TOKENS = 2048
        private const val TAG = "BenchmarkViewModel"
    }

    private val fakeEngine: LocalLlmEngine = FakeLlmEngine()
    private var llamaEngine: LocalLlmEngine? = null
    private var liteRtEngine: LocalLlmEngine? = null
    private var mediaPipeEngine: LocalLlmEngine? = null
    private var execuTorchEngine: LocalLlmEngine? = null
    private var selectedEngine: LocalLlmEngine = fakeEngine
    private var engineFactory: LocalLlmEngineFactory =
        LocalLlmEngineFactory(
            execuTorchEngine = execuTorchEngine,
            liteRtEngine = liteRtEngine,
            llamaCppEngine = llamaEngine,
            manualFallbackEngine = selectedEngine
        )
    private val presetRepository = BenchmarkPresetRepository()
    private var benchmarkResultRepository: BenchmarkResultRepository? = null
    private val _uiState = MutableStateFlow(BenchmarkUiState())
    val uiState: StateFlow<BenchmarkUiState> = _uiState.asStateFlow()

    fun initializeBenchmarkHistoryRepository(appContext: Context) {
        benchmarkResultRepository = BenchmarkResultRepository(appContext)
    }

    fun initializeLlamaEngine(
        appContext: Context,
        contentResolver: ContentResolver
    ) {
        llamaEngine = LlamaCppLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
        refreshEngineFactory()
    }

    fun initializeLiteRtEngine(
        appContext: Context,
        contentResolver: ContentResolver
    ) {
        liteRtEngine = LiteRtLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
        refreshEngineFactory()
    }

    fun initializeMediaPipeEngine(
        appContext: Context,
        contentResolver: ContentResolver
    ) {
        mediaPipeEngine = MediaPipeLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
    }

    fun initializeExecuTorchEngine(
        appContext: Context,
        contentResolver: ContentResolver
    ) {
        execuTorchEngine = ExecuTorchLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
        refreshEngineFactory()
    }

    fun setEngine(engineType: EngineType) {
        val shouldClearCatalogSelection = _uiState.value.selectedCatalogModel != null
        selectedEngine = when (engineType) {
            EngineType.FAKE -> fakeEngine
            EngineType.LLAMA_CPP -> llamaEngine ?: fakeEngine
            EngineType.LITERT -> liteRtEngine ?: fakeEngine
            EngineType.MEDIAPIPE -> mediaPipeEngine ?: fakeEngine
            EngineType.EXECUTORCH -> execuTorchEngine ?: fakeEngine
        }
        _uiState.update {
            it.copy(
                selectedEngineType = engineType,
                selectedCatalogModel = if (shouldClearCatalogSelection) null else it.selectedCatalogModel
            )
        }
        refreshEngineFactory()
    }

    fun onCatalogModelSelected(selection: BenchmarkModelSelection) {
        val mappedEngineType = mapRuntimeToEngine(selection.runtimeType)
        selectedEngine = engineFactory.create(
            engineType = selection.engineType,
            runtimeType = selection.runtimeType
        )
        _uiState.update {
            it.copy(
                selectedEngineType = mappedEngineType,
                selectedCatalogModel = selection,
                selectedPreset = null,
                selectedModel = LlmModelInfo(
                    modelName = selection.displayName,
                    modelFilePath = selection.modelPath,
                    tokenizerName = selection.tokenizerPath?.let { "catalog-tokenizer" },
                    tokenizerFilePath = selection.tokenizerPath
                ),
                errorMessage = null,
                modelWarningMessage = when (selection.engineType) {
                    com.hilal.llm.catalog.EngineType.EXECUTORCH_FORWARD ->
                        "This model exposes only forward method and requires a custom forward runner."
                    else -> null
                }
            )
        }
        refreshEngineFactory()
    }

    fun onModelFileSelected(displayName: String, uri: String) {
        val warning = when (_uiState.value.selectedEngineType) {
            EngineType.LITERT ->
                if (displayName.endsWith(".litertlm", ignoreCase = true)) null
                else "Selected file is not .litertlm. It is allowed, but .litertlm is preferred for LiteRT."
            EngineType.MEDIAPIPE ->
                if (displayName.endsWith(".task", ignoreCase = true)) null
                else "Selected file is not .task. It is allowed, but .task is preferred for MediaPipe."
            EngineType.EXECUTORCH ->
                if (displayName.endsWith(".pte", ignoreCase = true)) null
                else "Selected file is not .pte. It is allowed, but .pte is preferred for ExecuTorch."
            else ->
                if (displayName.endsWith(".gguf", ignoreCase = true)) null
                else "Selected file is not .gguf. It is allowed for now, but .gguf is preferred."
        }

        _uiState.update {
            it.copy(
                selectedModel = LlmModelInfo(
                    modelName = displayName,
                    modelFilePath = uri,
                    tokenizerName = it.selectedModel?.tokenizerName,
                    tokenizerFilePath = it.selectedModel?.tokenizerFilePath
                ),
                selectedCatalogModel = null,
                selectedPreset = null,
                errorMessage = null,
                modelWarningMessage = warning
            )
        }
    }

    fun onTokenizerFileSelected(displayName: String, uri: String) {
        val warning = if (
            displayName.endsWith(".json", ignoreCase = true) ||
            displayName.endsWith(".model", ignoreCase = true) ||
            displayName.endsWith(".bin", ignoreCase = true)
        ) {
            null
        } else {
            "Selected tokenizer file extension is unusual. tokenizer.json / tokenizer.model / tokenizer.bin is preferred."
        }

        _uiState.update { current ->
            val currentModel = current.selectedModel
            if (currentModel == null) {
                current.copy(
                    errorMessage = "Select model file first, then tokenizer.",
                    modelWarningMessage = warning
                )
            } else {
                current.copy(
                    selectedModel = currentModel.copy(
                        tokenizerName = displayName,
                        tokenizerFilePath = uri
                    ),
                    selectedCatalogModel = null,
                    selectedPreset = null,
                    errorMessage = null,
                    modelWarningMessage = warning
                )
            }
        }
    }

    fun selectPreset(presetId: String) {
        if (presetId.isBlank()) {
            _uiState.update { it.copy(selectedPreset = null) }
            return
        }
        val preset = presetRepository.getPresetById(presetId) ?: run {
            _uiState.update { it.copy(errorMessage = "Preset not found: $presetId") }
            return
        }

        _uiState.update {
            it.copy(
                prompt = preset.prompt,
                maxTokensInput = preset.maxTokens.toString(),
                selectedPreset = preset,
                errorMessage = null
            )
        }
    }

    fun updatePrompt(prompt: String) {
        _uiState.update { it.copy(prompt = prompt, selectedPreset = null) }
    }

    fun updateMaxTokensInput(value: String) {
        _uiState.update {
            it.copy(
                maxTokensInput = value.filter { ch -> ch.isDigit() },
                selectedPreset = null
            )
        }
    }

    fun clear() {
        _uiState.update {
            it.copy(
                maxTokensInput = DEFAULT_MAX_TOKENS.toString(),
                prompt = "",
                response = "",
                firstTokenTimeMs = null,
                totalTimeMs = null,
                tokenCount = 0,
                tokensPerSecond = null,
                errorMessage = null,
                modelWarningMessage = null,
                draftResponseQuality = ResponseQuality.UNKNOWN,
                draftQualityNote = "",
                selectedPreset = null,
                isRunning = false
            )
        }
    }

    fun runBenchmark() {
        val state = _uiState.value
        val model = state.selectedModel
        val catalogSelection = state.selectedCatalogModel
        val prompt = state.prompt.trim()

        if (model == null) {
            _uiState.update { it.copy(errorMessage = "Please select a model file.") }
            return
        }
        if (prompt.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please enter a prompt.") }
            return
        }
        val parsedMaxTokens = state.maxTokensInput.toIntOrNull()
        if (parsedMaxTokens == null) {
            _uiState.update {
                it.copy(errorMessage = "Please enter a valid max tokens value.")
            }
            return
        }
        val catalogValidationError = catalogSelection?.let { validateCatalogSelectionForRun(it) }
        if (catalogValidationError != null) {
            _uiState.update {
                it.copy(
                    errorMessage = catalogValidationError,
                    modelWarningMessage = catalogValidationError
                )
            }
            return
        }
        val maxTokens = parsedMaxTokens.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)
        if (maxTokens != parsedMaxTokens) {
            _uiState.update { it.copy(maxTokensInput = maxTokens.toString()) }
        }

        viewModelScope.launch {
            val selectedEngineForRun = resolveEngineForRun(state.selectedCatalogModel)
            Log.d(
                TAG,
                "Benchmark runtimeType=${state.selectedCatalogModel?.runtimeType ?: "-"} " +
                    "engineType=${state.selectedCatalogModel?.engineType ?: "-"} " +
                    "engineClass=${selectedEngineForRun.javaClass.name}"
            )
            _uiState.update {
                it.copy(
                    isRunning = true,
                    errorMessage = null,
                    response = "",
                    firstTokenTimeMs = null,
                    totalTimeMs = null,
                    tokenCount = 0,
                    tokensPerSecond = null
                )
            }

            val request = BenchmarkPrompt(
                modelId = catalogSelection?.modelId,
                modelName = catalogSelection?.displayName ?: model.modelName,
                modelFilePath = catalogSelection?.modelPath ?: model.modelFilePath,
                tokenizerFilePath = catalogSelection?.tokenizerPath ?: model.tokenizerFilePath,
                modelDisplayName = catalogSelection?.displayName ?: model.modelName,
                runtimeType = catalogSelection?.runtimeType,
                engineType = catalogSelection?.engineType,
                promptTemplateType = catalogSelection?.promptTemplateType
                    ?: when (state.selectedEngineType) {
                        EngineType.EXECUTORCH -> PromptTemplateType.GEMMA
                        else -> PromptTemplateType.GENERIC
                    },
                selectionSource = if (catalogSelection == null) SelectionSource.MANUAL else SelectionSource.CATALOG,
                prompt = prompt,
                maxTokens = maxTokens
            )
            val benchmarkRunner = BenchmarkRunner(selectedEngineForRun)

            benchmarkRunner.run(
                request = request,
                onToken = { partialResponse ->
                    _uiState.update {
                        it.copy(
                            response = partialResponse,
                            tokenCount = estimateTokenCount(partialResponse)
                        )
                    }
                },
                onComplete = { result ->
                    val autoQuality = inferResponseQuality(result.response)
                    val finalized = result.copy(
                        engineName = _uiState.value.selectedEngineType.label,
                        maxTokens = maxTokens,
                        presetId = state.selectedPreset?.id,
                        presetTitle = state.selectedPreset?.title,
                        responseQuality = autoQuality,
                        qualityNote = null
                    )
                    viewModelScope.launch {
                        withContext(Dispatchers.IO) {
                            benchmarkResultRepository?.saveResult(finalized)
                        }
                    }
                    _uiState.update {
                        it.copy(
                            response = finalized.response,
                            firstTokenTimeMs = finalized.firstTokenTimeMs,
                            totalTimeMs = finalized.totalTimeMs,
                            tokenCount = finalized.tokenCount,
                            tokensPerSecond = finalized.tokensPerSecond,
                            errorMessage = finalized.errorMessage,
                            draftResponseQuality = finalized.responseQuality,
                            draftQualityNote = finalized.qualityNote.orEmpty(),
                            isRunning = false,
                            completedResults = listOf(finalized) + it.completedResults
                        )
                    }
                }
            )
        }
    }

    fun updateDraftResponseQuality(quality: ResponseQuality) {
        _uiState.update { it.copy(draftResponseQuality = quality) }
    }

    fun updateDraftQualityNote(note: String) {
        _uiState.update { it.copy(draftQualityNote = note) }
    }

    fun saveLatestResultQuality() {
        val latest = _uiState.value.completedResults.firstOrNull() ?: return
        val quality = _uiState.value.draftResponseQuality
        val note = _uiState.value.draftQualityNote.takeIf { it.isNotBlank() }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                benchmarkResultRepository?.updateQuality(latest.resultId, quality, note)
            }
            _uiState.update { current ->
                current.copy(
                    completedResults = current.completedResults.map { result ->
                        if (result.resultId == latest.resultId) {
                            result.copy(responseQuality = quality, qualityNote = note)
                        } else {
                            result
                        }
                    }
                )
            }
        }
    }

    private fun mapRuntimeToEngine(runtimeType: RuntimeType): EngineType {
        return when (runtimeType) {
            RuntimeType.EXECUTORCH -> EngineType.EXECUTORCH
            RuntimeType.LITERT -> EngineType.LITERT
            RuntimeType.LLAMA_CPP -> EngineType.LLAMA_CPP
        }
    }

    private fun resolveEngineForRun(selection: BenchmarkModelSelection?): LocalLlmEngine {
        return if (selection == null) {
            selectedEngine
        } else {
            engineFactory.create(
                engineType = selection.engineType,
                runtimeType = selection.runtimeType
            )
        }
    }

    private fun validateCatalogSelectionForRun(selection: BenchmarkModelSelection): String? {
        return when (selection.engineType) {
            com.hilal.llm.catalog.EngineType.EXECUTORCH_GENERATE -> {
                val hasPte = selection.modelPath.trim().endsWith(".pte", ignoreCase = true)
                val hasTokenizer = !selection.tokenizerPath.isNullOrBlank()
                when {
                    !hasPte -> "Selected ExecuTorch model must be a .pte file."
                    !hasTokenizer -> "Selected ExecuTorch model requires a tokenizer file."
                    else -> null
                }
            }
            com.hilal.llm.catalog.EngineType.EXECUTORCH_FORWARD ->
                "This ExecuTorch model is forward-only and requires a custom forward runner."

            com.hilal.llm.catalog.EngineType.LITERT_LM -> {
                val normalized = selection.modelPath.trim().lowercase()
                val supported = normalized.endsWith(".litertlm") ||
                    normalized.endsWith(".task") ||
                    normalized.endsWith(".tflite") ||
                    normalized.endsWith(".zip") ||
                    normalized.endsWith(".bundle")
                if (supported) null else "Selected LiteRT model format is not supported by current LiteRT runner."
            }

            com.hilal.llm.catalog.EngineType.LLAMA_CPP -> {
                if (selection.modelPath.trim().endsWith(".gguf", ignoreCase = true)) {
                    null
                } else {
                    "Selected llama.cpp model must be a .gguf file."
                }
            }
        }
    }

    private fun refreshEngineFactory() {
        engineFactory = LocalLlmEngineFactory(
            execuTorchEngine = execuTorchEngine,
            liteRtEngine = liteRtEngine,
            llamaCppEngine = llamaEngine,
            manualFallbackEngine = selectedEngine
        )
    }

}
