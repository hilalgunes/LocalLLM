package com.hilal.llm.benchmark

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.PromptTemplateType
import com.hilal.llm.catalog.RuntimeType
import com.hilal.llm.catalog.ModelCatalogRepository
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.ModelPurpose
import com.hilal.llm.catalog.download.DownloadState
import com.hilal.llm.catalog.download.ModelDownloadRepository
import com.hilal.llm.catalog.runtime.ModelRunFileMapper
import com.hilal.llm.catalog.storage.ModelStorageResolver
import com.hilal.llm.benchmark.history.BenchmarkResultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

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
    private var modelDownloadRepository: ModelDownloadRepository? = null
    private var modelRunFileMapper: ModelRunFileMapper? = null
    private var activeDownloadJob: Job? = null
    private val featureGenerationMutex = Mutex()
    private var cachedFeatureSession: LiteRtLlmEngine.Session? = null
    private var cachedFeatureSelection: BenchmarkModelSelection? = null

    override fun onCleared() {
        super.onCleared()
        // Finish any active lease before releasing native model memory.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            featureGenerationMutex.withLock {
                cachedFeatureSession?.close()
                cachedFeatureSession = null
                cachedFeatureSelection = null
            }
        }
    }
    private val _uiState = MutableStateFlow(BenchmarkUiState())
    val uiState: StateFlow<BenchmarkUiState> = _uiState.asStateFlow()

    fun initializeBenchmarkHistoryRepository(appContext: Context) {
        benchmarkResultRepository = BenchmarkResultRepository(appContext)
    }

    fun initializeModelCatalog(appContext: Context) {
        val storageResolver = ModelStorageResolver(appContext.applicationContext)
        val downloadRepository = ModelDownloadRepository(storageResolver)
        modelDownloadRepository = downloadRepository
        modelRunFileMapper = ModelRunFileMapper(storageResolver)

        val models = ModelCatalogRepository.getAvailableModels()
            .filter { it.purpose == ModelPurpose.GENERATION }
        val states = models.associate { model ->
            model.id to if (downloadRepository.isModelDownloaded(model)) {
                DownloadState.Success
            } else {
                DownloadState.Idle
            }
        }
        _uiState.update {
            it.copy(availableModels = models, modelDownloadStates = states)
        }
    }

    fun initializeLlamaEngine(
        appContext: Context,
        contentResolver: ContentResolver
    ) {
        llamaEngine = LlamaCppLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
        if (_uiState.value.selectedEngineType == EngineType.LLAMA_CPP) selectedEngine = llamaEngine!!
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
        if (_uiState.value.selectedEngineType == EngineType.LITERT) selectedEngine = liteRtEngine!!
        refreshEngineFactory()
    }

    fun initializeExecuTorchEngine(
        appContext: Context,
        contentResolver: ContentResolver
    ) {
        execuTorchEngine = ExecuTorchLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
        if (_uiState.value.selectedEngineType == EngineType.EXECUTORCH) selectedEngine = execuTorchEngine!!
        refreshEngineFactory()
    }

    fun setEngine(engineType: EngineType) {
        val targetRuntime = runtimeFor(engineType)
        val shouldClearCatalogSelection = _uiState.value.selectedModelConfig?.runtimeType != targetRuntime
        val activeDownloadModel = _uiState.value.activeDownloadModelId?.let(ModelCatalogRepository::getModelById)
        if (activeDownloadModel != null && activeDownloadModel.runtimeType != targetRuntime) {
            activeDownloadJob?.cancel()
        }
        selectedEngine = when (engineType) {
            EngineType.FAKE -> fakeEngine
            EngineType.LLAMA_CPP -> llamaEngine ?: fakeEngine
            EngineType.LITERT -> liteRtEngine ?: fakeEngine
            EngineType.EXECUTORCH -> execuTorchEngine ?: fakeEngine
        }
        _uiState.update {
            it.copy(
                selectedEngineType = engineType,
                selectedCatalogModel = if (shouldClearCatalogSelection) null else it.selectedCatalogModel,
                selectedModel = if (shouldClearCatalogSelection) null else it.selectedModel,
                selectedModelConfig = if (shouldClearCatalogSelection) null else it.selectedModelConfig,
                activeDownloadModelId = if (shouldClearCatalogSelection) null else it.activeDownloadModelId,
                errorMessage = null
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
                selectedModelConfig = ModelCatalogRepository.getModelById(selection.modelId),
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

    fun showModelPicker() {
        _uiState.update { it.copy(isModelPickerVisible = true, errorMessage = null) }
    }

    fun hideModelPicker() {
        _uiState.update { it.copy(isModelPickerVisible = false) }
    }

    fun selectCatalogModel(modelId: String) {
        val model = ModelCatalogRepository.getModelById(modelId) ?: run {
            _uiState.update { it.copy(errorMessage = "Model not found: $modelId") }
            return
        }
        val expectedRuntime = runtimeFor(_uiState.value.selectedEngineType)
        if (model.runtimeType != expectedRuntime || model.purpose != ModelPurpose.GENERATION) {
            _uiState.update { it.copy(errorMessage = "${model.displayName} is not compatible with the selected runtime.") }
            return
        }
        if (!model.isEnabled || !model.hasDownloadArtifacts) {
            _uiState.update {
                it.copy(errorMessage = model.disabledReason ?: "Download artifacts are unavailable for ${model.displayName}.")
            }
            return
        }

        _uiState.update { it.copy(isModelPickerVisible = false, errorMessage = null) }
        val repository = modelDownloadRepository ?: run {
            _uiState.update { it.copy(errorMessage = "Model downloader is not initialized.") }
            return
        }
        if (repository.isModelDownloaded(model)) {
            prepareDownloadedModel(model)
            return
        }

        activeDownloadJob?.cancel()
        activeDownloadJob = viewModelScope.launch {
            _uiState.update { it.copy(activeDownloadModelId = model.id, errorMessage = null) }
            try {
                repository.downloadModel(model) { state ->
                    _uiState.update { current ->
                        current.copy(modelDownloadStates = current.modelDownloadStates + (model.id to state))
                    }
                }
                if (runtimeFor(_uiState.value.selectedEngineType) == model.runtimeType) {
                    prepareDownloadedModel(model)
                }
            } catch (cancellation: CancellationException) {
                _uiState.update {
                    it.copy(
                        activeDownloadModelId = null,
                        modelDownloadStates = it.modelDownloadStates + (model.id to DownloadState.Idle)
                    )
                }
                throw cancellation
            } catch (throwable: Throwable) {
                val message = throwable.message ?: "Model download failed."
                _uiState.update {
                    it.copy(
                        activeDownloadModelId = null,
                        errorMessage = message,
                        modelDownloadStates = it.modelDownloadStates + (model.id to DownloadState.Failed(message))
                    )
                }
            }
        }
    }

    fun cancelModelDownload() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
    }

    private fun prepareDownloadedModel(model: ModelConfig) {
        val mapper = modelRunFileMapper ?: run {
            _uiState.update { it.copy(activeDownloadModelId = null, errorMessage = "Model file mapper is not initialized.") }
            return
        }
        try {
            val runFiles = mapper.toRunFiles(model)
            onCatalogModelSelected(
                BenchmarkModelSelection(
                    modelId = model.id,
                    displayName = model.displayName,
                    runtimeType = model.runtimeType,
                    engineType = model.engineType,
                    promptTemplateType = model.promptTemplateType,
                    modelPath = runFiles.modelPath,
                    tokenizerPath = runFiles.tokenizerPath
                )
            )
            _uiState.update {
                it.copy(
                    selectedModelConfig = model,
                    activeDownloadModelId = null,
                    modelDownloadStates = it.modelDownloadStates + (model.id to DownloadState.Success)
                )
            }
        } catch (throwable: Throwable) {
            _uiState.update {
                it.copy(activeDownloadModelId = null, errorMessage = throwable.message ?: "Unable to prepare model files.")
            }
        }
    }

    private fun runtimeFor(engineType: EngineType): RuntimeType? = when (engineType) {
        EngineType.LLAMA_CPP -> RuntimeType.LLAMA_CPP
        EngineType.LITERT -> RuntimeType.LITERT
        EngineType.EXECUTORCH -> RuntimeType.EXECUTORCH
        EngineType.FAKE -> null
    }

    fun onModelFileSelected(displayName: String, uri: String) {
        val warning = when (_uiState.value.selectedEngineType) {
            EngineType.LITERT ->
                if (displayName.endsWith(".litertlm", ignoreCase = true)) null
                else "Selected file is not .litertlm. It is allowed, but .litertlm is preferred for LiteRT."
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

    suspend fun generateForFeature(
        selection: BenchmarkModelSelection,
        prompt: String,
        maxTokens: Int
    ): String = featureGenerationMutex.withLock {
        validateCatalogSelectionForRun(selection)?.let { throw IllegalStateException(it) }
        val engine = resolveEngineForRun(selection)
        generateFeatureText(selection, prompt, maxTokens, engine::generate)
    }

    suspend fun openFeatureGenerationSession(
        selection: BenchmarkModelSelection
    ): FeatureGenerationSession {
        validateCatalogSelectionForRun(selection)?.let { throw IllegalStateException(it) }
        featureGenerationMutex.lock()
        try {
            if (cachedFeatureSelection != selection) {
                cachedFeatureSession?.close()
                cachedFeatureSession = null
                cachedFeatureSelection = null
            }
            val reusableLiteRtSession = if (selection.runtimeType == RuntimeType.LITERT) {
                cachedFeatureSession?.also {
                    Log.d("TEST_FLOW", "TEST_FLOW | Model session reused | ${selection.displayName}")
                } ?: ((liteRtEngine as? LiteRtLlmEngine)?.openSession(selection.modelPath)
                    ?: throw IllegalStateException("LiteRT engine is not initialized.")).also {
                    cachedFeatureSession = it
                    cachedFeatureSelection = selection
                }
            } else {
                null
            }
            val closed = AtomicBoolean(false)
            return object : FeatureGenerationSession {
                override suspend fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String {
                    check(!closed.get()) { "Feature generation session is already closed." }
                    val generateRequest: suspend (BenchmarkPrompt, StreamCallback) -> Unit =
                        reusableLiteRtSession?.let { session -> session::generate }
                            ?: resolveEngineForRun(selection)::generate
                    return generateFeatureText(
                        selection = selection,
                        prompt = prompt,
                        maxTokens = maxTokens,
                        generateRequest = generateRequest,
                        onText = onText
                    )
                }

                override suspend fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    // Release the request lease; keep the model warm for the next question.
                    featureGenerationMutex.unlock()
                }
            }
        } catch (error: Throwable) {
            featureGenerationMutex.unlock()
            throw error
        }
    }

    private suspend fun generateFeatureText(
        selection: BenchmarkModelSelection,
        prompt: String,
        maxTokens: Int,
        generateRequest: suspend (BenchmarkPrompt, StreamCallback) -> Unit,
        onText: (String) -> Unit = {}
    ): String {
        val output = StringBuffer()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        Log.d("TEST_FLOW", "TEST_FLOW | Generation started | model=${selection.displayName} | runtime=${selection.runtimeType} | promptChars=${prompt.length} | maxTokens=$maxTokens")
        var generationError: Throwable? = null
        val request = BenchmarkPrompt(
                modelId = selection.modelId,
                modelName = selection.displayName,
                modelFilePath = selection.modelPath,
                tokenizerFilePath = selection.tokenizerPath,
                modelDisplayName = selection.displayName,
                runtimeType = selection.runtimeType,
                engineType = selection.engineType,
                promptTemplateType = selection.promptTemplateType,
                selectionSource = SelectionSource.CATALOG,
                prompt = prompt,
                maxTokens = maxTokens.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)
            )
        val callback = object : StreamCallback {
                override fun onFirstToken() {
                    Log.d("TEST_FLOW", "TEST_FLOW | First text | elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                }

                override fun onToken(token: String) {
                    output.append(token)
                    onText(output.toString())
                }

                override fun onComplete() = Unit

                override fun onError(throwable: Throwable) {
                    generationError = throwable
                }
            }
        generateRequest(request, callback)
        generationError?.let { throw it }
        return output.toString().trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Local model boş yanıt üretti.")
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
