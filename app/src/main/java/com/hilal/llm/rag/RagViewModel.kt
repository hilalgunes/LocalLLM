package com.hilal.llm.rag

import android.app.Application
import android.content.ContentResolver
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilal.llm.benchmark.BenchmarkPrompt
import com.hilal.llm.benchmark.FakeLlmEngine
import com.hilal.llm.benchmark.LocalLlmEngine
import com.hilal.llm.benchmark.LocalLlmEngineFactory
import com.hilal.llm.benchmark.SelectionSource
import com.hilal.llm.benchmark.StreamCallback
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.ModelCatalogRepository
import com.hilal.llm.catalog.download.DownloadState
import com.hilal.llm.catalog.download.ModelDownloadRepository
import com.hilal.llm.catalog.runtime.ModelRunFileMapper
import com.hilal.llm.catalog.storage.ModelStorageResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock

class RagViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val DEFAULT_MAX_TOKENS = 512
        private const val TAG = "RagViewModel"
        private val EMBEDDING_MODEL_FALLBACK_IDS = listOf(
            "all-minilm-l6-v2-gguf",
            "multilingual-e5-small-gguf",
            "qwen3-embedding-0.6b-gguf"
        )
    }

    private val appContext = application.applicationContext
    private val storageResolver = ModelStorageResolver(appContext)
    private val downloadRepository = ModelDownloadRepository(storageResolver)
    private val runFileMapper = ModelRunFileMapper(storageResolver)
    private val vectorStoreRepository = RagVectorStoreRepository(appContext)

    private val fakeEngine: LocalLlmEngine = FakeLlmEngine()
    private var llamaEngine: LocalLlmEngine? = null
    private var liteRtEngine: LocalLlmEngine? = null
    private var mediaPipeEngine: LocalLlmEngine? = null
    private var execuTorchEngine: LocalLlmEngine? = null
    private var contentResolver: ContentResolver? = null
    private var engineFactory: LocalLlmEngineFactory = LocalLlmEngineFactory(
        execuTorchEngine = execuTorchEngine,
        liteRtEngine = liteRtEngine,
        llamaCppEngine = llamaEngine,
        manualFallbackEngine = fakeEngine
    )

    private val _uiState = MutableStateFlow(RagUiState())
    val uiState: StateFlow<RagUiState> = _uiState.asStateFlow()

    fun initializeEngines(contentResolver: ContentResolver) {
        this.contentResolver = contentResolver
        initializeLlamaEngine(contentResolver)
        initializeLiteRtEngine(contentResolver)
        initializeMediaPipeEngine(contentResolver)
        initializeExecuTorchEngine(contentResolver)
        viewModelScope.launch {
            try {
                vectorStoreRepository.initializeFromPersistentStore()
                val snapshot = vectorStoreRepository.snapshot()
                _uiState.update {
                    it.copy(
                        documents = snapshot.documents,
                        documentChunks = snapshot.chunks,
                        errorMessage = null
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to load vector store.")
                }
            }
        }
    }

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
            current.copy(
                models = models,
                downloadStates = downloadStates,
                errorMessage = null
            )
        }
    }

    fun reportError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun selectGenerationModel(modelId: String) {
        selectModel(modelId, RagModelRole.GENERATION)
    }

    fun selectEmbeddingModel(modelId: String) {
        val selected = selectModel(modelId, RagModelRole.EMBEDDING)
        if (!selected) return

        viewModelScope.launch {
            try {
                val embeddingModelPath = resolveWorkingEmbeddingModelPath(modelId)
                vectorStoreRepository.reindexAll(embeddingModelPath)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Embedding index could not be rebuilt.")
                }
            }
        }
    }

    fun selectVectorStoreBackend(backend: RagVectorStoreBackend) {
        _uiState.update {
            it.copy(
                selectedVectorStoreBackend = backend,
                errorMessage = null
            )
        }
    }

    private fun selectModel(modelId: String, role: RagModelRole): Boolean {
        val model = ModelCatalogRepository.getModelById(modelId) ?: run {
            _uiState.update { it.copy(errorMessage = "Model not found: $modelId") }
            return false
        }

        val isRoleCompatible = when (role) {
            RagModelRole.GENERATION -> model.id in ragGenerationModelIds
            RagModelRole.EMBEDDING -> model.id in ragEmbeddingModelIds
        }
        if (!isRoleCompatible) {
            _uiState.update {
                it.copy(
                    errorMessage = when (role) {
                        RagModelRole.GENERATION -> "${model.displayName} üretici model olarak kullanılamaz."
                        RagModelRole.EMBEDDING -> "${model.displayName} embedding modeli olarak kullanılamaz."
                    }
                )
            }
            return false
        }

        if (!model.isEnabled) {
            _uiState.update { it.copy(errorMessage = model.disabledReason ?: "${model.displayName} is disabled.") }
            return false
        }

        if (!downloadRepository.isModelDownloaded(model)) {
            _uiState.update {
                it.copy(
                    pendingDownloadModelId = modelId,
                    pendingDownloadRole = role,
                    errorMessage = null
                )
            }
            return false
        }

        _uiState.update {
            when (role) {
                RagModelRole.GENERATION -> it.copy(
                    selectedGenerationModelId = modelId,
                    pendingDownloadModelId = null,
                    pendingDownloadRole = null,
                    errorMessage = null
                )
                RagModelRole.EMBEDDING -> it.copy(
                    selectedEmbeddingModelId = modelId,
                    pendingDownloadModelId = null,
                    pendingDownloadRole = null,
                    errorMessage = null
                )
            }
        }
        return true
    }

    private fun buildDetailedErrorMessage(prefix: String, throwable: Throwable): String {
        val errorType = throwable::class.simpleName ?: "Throwable"
        val message = throwable.message?.takeIf { it.isNotBlank() } ?: "neden bilinmiyor"
        return "$prefix ($errorType): $message"
    }

    fun dismissDownloadDialog() {
        _uiState.update { it.copy(pendingDownloadModelId = null, pendingDownloadRole = null) }
    }

    fun confirmSelectedModelDownload() {
        val modelId = _uiState.value.pendingDownloadModelId ?: return
        val role = _uiState.value.pendingDownloadRole ?: RagModelRole.GENERATION
        val model = ModelCatalogRepository.getModelById(modelId) ?: run {
            _uiState.update {
                it.copy(
                    errorMessage = "Model not found: $modelId",
                    pendingDownloadModelId = null,
                    pendingDownloadRole = null
                )
            }
            return
        }

        if (!model.isEnabled) {
            _uiState.update {
                it.copy(
                    errorMessage = model.disabledReason ?: "${model.displayName} is disabled.",
                    pendingDownloadModelId = null,
                    pendingDownloadRole = null
                )
            }
            return
        }

        viewModelScope.launch {
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
                        downloadStates = current.downloadStates + (modelId to DownloadState.Success),
                        selectedGenerationModelId = if (role == RagModelRole.GENERATION) modelId else current.selectedGenerationModelId,
                        selectedEmbeddingModelId = if (role == RagModelRole.EMBEDDING) modelId else current.selectedEmbeddingModelId,
                        pendingDownloadModelId = null,
                        pendingDownloadRole = null,
                        errorMessage = null
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        pendingDownloadModelId = null,
                        pendingDownloadRole = null,
                        errorMessage = throwable.message ?: "Unknown download error",
                        downloadStates = it.downloadStates + (
                            modelId to DownloadState.Failed(throwable.message ?: "Unknown download error")
                        )
                    )
                }
            }
        }
    }

    fun addDocument(displayName: String, text: String) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            _uiState.update { it.copy(errorMessage = "$displayName is empty.") }
            return
        }

        val document = RagDocument(
            id = createDocumentId(),
            displayName = displayName,
            text = trimmedText,
            chunkCount = chunkText(trimmedText).size
        )
        val chunks = buildChunksFromDocument(document.id, displayName, trimmedText)

        viewModelScope.launch {
            try {
                val embeddingModelPath = resolveCurrentEmbeddingModelPath()
                vectorStoreRepository.upsertDocument(
                    document = document,
                    chunks = chunks,
                    embeddingModelPath = embeddingModelPath
                )
                refreshVectorStoreSnapshot()
            } catch (throwable: Throwable) {
                runCatching { refreshVectorStoreSnapshot() }
                _uiState.update {
                    it.copy(
                        errorMessage = buildDetailedErrorMessage(
                            prefix = "Belge kaydedildi ama embedding indeksi oluşturulamadı",
                            throwable = throwable
                        )
                    )
                }
            }
        }
    }

    fun removeDocument(documentId: String) {
        viewModelScope.launch {
            try {
                val embeddingModelPath = resolveCurrentEmbeddingModelPath()
                vectorStoreRepository.removeDocument(documentId, embeddingModelPath)
                refreshVectorStoreSnapshot()
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Document could not be removed.")
                }
            }
        }
    }

    fun clearDocuments() {
        viewModelScope.launch {
            try {
                val embeddingModelPath = resolveCurrentEmbeddingModelPath()
                vectorStoreRepository.clear(embeddingModelPath)
                _uiState.update {
                    it.copy(
                        documents = emptyList(),
                        documentChunks = emptyList(),
                        highlightedSources = emptyList(),
                        errorMessage = null
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Documents could not be cleared.")
                }
            }
        }
    }

    fun updateQuestion(question: String) {
        _uiState.update { it.copy(question = question) }
    }

    fun askQuestion() {
        val current = _uiState.value
        if (current.isGenerating) return

        val question = current.question.trim()
        if (question.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please type a question first.") }
            return
        }

        if (current.documentChunks.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please add at least one PDF or TXT document.") }
            return
        }

        val generationModelId = current.selectedGenerationModelId ?: run {
            _uiState.update { it.copy(errorMessage = "Please select a generation model first.") }
            return
        }

        val embeddingModelId = current.selectedEmbeddingModelId ?: run {
            _uiState.update { it.copy(errorMessage = "Please select an embedding model first.") }
            return
        }

        val generationModel = current.models.firstOrNull { it.id == generationModelId } ?: run {
            _uiState.update { it.copy(errorMessage = "Selected generation model is no longer available.") }
            return
        }

        val embeddingModel = current.models.firstOrNull { it.id == embeddingModelId } ?: run {
            _uiState.update { it.copy(errorMessage = "Selected embedding model is no longer available.") }
            return
        }

        if (!downloadRepository.isModelDownloaded(generationModel)) {
            _uiState.update {
                it.copy(
                    pendingDownloadModelId = generationModelId,
                    pendingDownloadRole = RagModelRole.GENERATION,
                    errorMessage = "${generationModel.displayName} is not downloaded yet."
                )
            }
            return
        }

        if (!downloadRepository.isModelDownloaded(embeddingModel)) {
            _uiState.update {
                it.copy(
                    pendingDownloadModelId = embeddingModelId,
                    pendingDownloadRole = RagModelRole.EMBEDDING,
                    errorMessage = "${embeddingModel.displayName} is not downloaded yet."
                )
            }
            return
        }

        viewModelScope.launch {
            val requestStartedAt = SystemClock.elapsedRealtime()
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    answer = "",
                    answerMetrics = null,
                    highlightedSources = emptyList(),
                    errorMessage = null
                )
            }

            try {
                val runFiles = withContext(Dispatchers.IO) { runFileMapper.toRunFiles(generationModel) }
                val selection = BenchmarkModelSelection(
                    modelId = generationModel.id,
                    displayName = generationModel.displayName,
                    runtimeType = generationModel.runtimeType,
                    engineType = generationModel.engineType,
                    promptTemplateType = generationModel.promptTemplateType,
                    modelPath = runFiles.modelPath,
                    tokenizerPath = runFiles.tokenizerPath
                )

                val retrievalStartedAt = SystemClock.elapsedRealtime()
                val embeddingModelPath = resolveWorkingEmbeddingModelPath(embeddingModelId)
                val relevantChunks = vectorStoreRepository.rank(
                    backend = current.selectedVectorStoreBackend,
                    question = question,
                    embeddingModelPath = embeddingModelPath
                )
                val retrievalFinishedAt = SystemClock.elapsedRealtime()
                val strictAnswer = buildStrictRagAnswer(question, relevantChunks)
                val prompt = buildRagPrompt(question, relevantChunks)
                val engine = engineFactory.create(selection.engineType, selection.runtimeType)

                _uiState.update {
                    it.copy(
                        highlightedSources = strictAnswer?.sourceChunks ?: relevantChunks,
                        answerMetrics = RagAnswerMetrics(
                            backendName = current.selectedVectorStoreBackend.displayName,
                            sourceCount = (strictAnswer?.sourceChunks ?: relevantChunks).size,
                            retrievalTimeMs = retrievalFinishedAt - retrievalStartedAt,
                            answerMode = strictAnswer?.answerMode ?: "LLM"
                        )
                    )
                }

                if (strictAnswer != null) {
                    val totalTimeMs = SystemClock.elapsedRealtime() - requestStartedAt
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            answer = strictAnswer.answer,
                            answerMetrics = it.answerMetrics?.copy(totalTimeMs = totalTimeMs)
                        )
                    }
                    return@launch
                }

                val generationStartedAt = SystemClock.elapsedRealtime()

                engine.generate(
                    BenchmarkPrompt(
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
                        maxTokens = DEFAULT_MAX_TOKENS
                    ),
                    object : StreamCallback {
                        override fun onFirstToken() {
                            val firstTokenLatency = SystemClock.elapsedRealtime() - generationStartedAt
                            _uiState.update { currentState ->
                                currentState.copy(
                                    answerMetrics = currentState.answerMetrics?.copy(
                                        firstTokenLatencyMs = firstTokenLatency
                                    )
                                )
                            }
                        }

                        override fun onToken(token: String) {
                            _uiState.update { currentState ->
                                val rawAnswer = currentState.answer + token
                                currentState.copy(answer = sanitizeRagOutput(rawAnswer))
                            }
                        }

                        override fun onComplete() {
                            val totalTimeMs = SystemClock.elapsedRealtime() - requestStartedAt
                            _uiState.update { currentState ->
                                currentState.copy(
                                    isGenerating = false,
                                    answerMetrics = currentState.answerMetrics?.copy(totalTimeMs = totalTimeMs)
                                )
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            _uiState.update {
                                it.copy(
                                    isGenerating = false,
                                    answerMetrics = it.answerMetrics?.copy(totalTimeMs = SystemClock.elapsedRealtime() - requestStartedAt),
                                    errorMessage = throwable.message ?: "Question answering failed."
                                )
                            }
                        }
                    }
                )
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        answerMetrics = it.answerMetrics?.copy(totalTimeMs = SystemClock.elapsedRealtime() - requestStartedAt),
                        errorMessage = throwable.message ?: "Question answering failed."
                    )
                }
            }
        }
    }

    private suspend fun refreshVectorStoreSnapshot() {
        val snapshot = vectorStoreRepository.snapshot()
        _uiState.update {
            it.copy(
                documents = snapshot.documents,
                documentChunks = snapshot.chunks,
                highlightedSources = emptyList(),
                errorMessage = null
            )
        }
    }

    private suspend fun resolveCurrentEmbeddingModelPath(): String {
        val embeddingModelId = _uiState.value.selectedEmbeddingModelId
            ?: throw IllegalStateException("Önce bir embedding modeli seç.")
        return resolveWorkingEmbeddingModelPath(embeddingModelId)
    }

    private suspend fun resolveEmbeddingModelPath(modelId: String): String {
        val model = ModelCatalogRepository.getModelById(modelId)
            ?: throw IllegalStateException("Model not found: $modelId")
        val runFiles = withContext(Dispatchers.IO) { runFileMapper.toRunFiles(model) }
        return runFiles.modelPath
    }

    private suspend fun resolveWorkingEmbeddingModelPath(modelId: String): String {
        val candidateIds = buildList {
            add(modelId)
            EMBEDDING_MODEL_FALLBACK_IDS.forEach { fallbackId ->
                if (fallbackId != modelId) add(fallbackId)
            }
        }

        val errors = mutableListOf<String>()
        for (candidateId in candidateIds) {
            val model = ModelCatalogRepository.getModelById(candidateId)
            if (model == null || !downloadRepository.isModelDownloaded(model)) {
                errors += "$candidateId:not-downloaded"
                continue
            }

            try {
                val runFiles = withContext(Dispatchers.IO) { runFileMapper.toRunFiles(model) }
                if (vectorStoreRepository.canInitializeEmbeddingModel(runFiles.modelPath)) {
                    if (candidateId != modelId) {
                        Log.w(TAG, "Embedding model fallback selected: $modelId -> $candidateId")
                    }
                    return runFiles.modelPath
                }
            } catch (throwable: Throwable) {
                errors += "$candidateId:${throwable.message ?: throwable::class.simpleName}"
            }
        }

        throw IllegalStateException(
            "No usable embedding model found. Tried=${errors.joinToString()}"
        )
    }

    private fun refreshEngineFactory() {
        engineFactory = LocalLlmEngineFactory(
            execuTorchEngine = execuTorchEngine,
            liteRtEngine = liteRtEngine,
            llamaCppEngine = llamaEngine,
            manualFallbackEngine = fakeEngine
        )
    }

    private fun initializeLlamaEngine(contentResolver: ContentResolver) {
        llamaEngine = com.hilal.llm.benchmark.LlamaCppLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
        refreshEngineFactory()
    }

    private fun initializeLiteRtEngine(contentResolver: ContentResolver) {
        liteRtEngine = com.hilal.llm.benchmark.LiteRtLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
        refreshEngineFactory()
    }

    private fun initializeMediaPipeEngine(contentResolver: ContentResolver) {
        mediaPipeEngine = com.hilal.llm.benchmark.MediaPipeLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
    }

    private fun initializeExecuTorchEngine(contentResolver: ContentResolver) {
        execuTorchEngine = com.hilal.llm.benchmark.ExecuTorchLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
        refreshEngineFactory()
    }

    override fun onCleared() {
        vectorStoreRepository.close()
        super.onCleared()
    }
}
