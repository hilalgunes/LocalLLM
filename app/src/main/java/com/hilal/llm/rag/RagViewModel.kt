package com.hilal.llm.rag

import android.app.Application
import android.content.ContentResolver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilal.llm.benchmark.BenchmarkPrompt
import com.hilal.llm.benchmark.LocalLlmEngine
import com.hilal.llm.benchmark.LocalLlmEngineFactory
import com.hilal.llm.benchmark.SelectionSource
import com.hilal.llm.benchmark.StreamCallback
import com.hilal.llm.benchmark.UnsupportedRuntimeEngine
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.ModelCatalogRepository
import com.hilal.llm.catalog.RuntimeType
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
import java.util.concurrent.atomic.AtomicReference

class RagViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val DEFAULT_MAX_TOKENS = 512
        private const val TAG = "RagViewModel"
        private val rnLlamaUnsafeEmbeddingModelIds = setOf(
            "multilingual-e5-small-gguf",
            "all-minilm-l6-v2-gguf"
        )
    }

    private val appContext = application.applicationContext
    private val storageResolver = ModelStorageResolver(appContext)
    private val downloadRepository = ModelDownloadRepository(storageResolver)
    private val runFileMapper = ModelRunFileMapper(storageResolver)
    private val vectorStoreRepository = RagVectorStoreRepository(appContext)
    private data class ResolvedEmbeddingModel(
        val modelId: String,
        val displayName: String,
        val runtimeType: RuntimeType,
        val modelPath: String,
        val tokenizerPath: String?
    )

    private data class ActiveEmbeddingProvider(
        val modelId: String,
        val runtimeType: RuntimeType,
        val provider: RagEmbeddingProvider
    )

    private var indexedEmbeddingModelId: String? = null

    private data class ChunkingConfig(
        val chunkSize: Int,
        val overlap: Int
    )

    private val embeddingProviderRef = AtomicReference<ActiveEmbeddingProvider?>(null)

    private val unavailableEngine: LocalLlmEngine = UnsupportedRuntimeEngine(
        "Seçilen LLM runtime hazır değil; sahte cevap üretilmedi."
    )
    private var llamaEngine: LocalLlmEngine? = null
    private var liteRtEngine: LocalLlmEngine? = null
    private var execuTorchEngine: LocalLlmEngine? = null
    private var contentResolver: ContentResolver? = null
    private var engineFactory: LocalLlmEngineFactory = LocalLlmEngineFactory(
        execuTorchEngine = execuTorchEngine,
        liteRtEngine = liteRtEngine,
        llamaCppEngine = llamaEngine,
        manualFallbackEngine = unavailableEngine
    )

    private val _uiState = MutableStateFlow(RagUiState())
    val uiState: StateFlow<RagUiState> = _uiState.asStateFlow()

    fun initializeEngines(contentResolver: ContentResolver) {
        this.contentResolver = contentResolver
        initializeLlamaEngine(contentResolver)
        initializeLiteRtEngine(contentResolver)
        initializeExecuTorchEngine(contentResolver)
        viewModelScope.launch {
            try {
                vectorStoreRepository.initializeFromPersistentStore()
                if (_uiState.value.selectedVectorStoreBackend == RagVectorStoreBackend.GRAPH_RAG) {
                    vectorStoreRepository.rebuildGraphIndex()
                }
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
                selectedEmbeddingModelId = current.selectedEmbeddingModelId
                    ?.takeIf { it in ragEmbeddingModelIds },
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
        val previousModelId = _uiState.value.selectedEmbeddingModelId
        if (selectModel(modelId, RagModelRole.EMBEDDING)) {
            viewModelScope.launch {
                try {
                    if (previousModelId != modelId) {
                        rebuildCurrentEmbeddings(requireEmbeddingModel = _uiState.value.documentChunks.isNotEmpty())
                    }
                    if (_uiState.value.selectedVectorStoreBackend == RagVectorStoreBackend.GRAPH_RAG) {
                        vectorStoreRepository.rebuildGraphIndex()
                    }
                } catch (throwable: Throwable) {
                    _uiState.update {
                        it.copy(
                            errorMessage = buildDetailedErrorMessage("Embedding model initialization failed", throwable)
                        )
                    }
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
        viewModelScope.launch {
            try {
                if (backend == RagVectorStoreBackend.GRAPH_RAG) {
                    vectorStoreRepository.rebuildGraphIndex()
                    refreshVectorStoreSnapshot()
                } else {
                    refreshVectorStoreSnapshot()
                }
            } catch (throwable: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        errorMessage = if (backend == RagVectorStoreBackend.GRAPH_RAG) {
                            throwable.message ?: "Graph index could not be rebuilt."
                        } else {
                            throwable.message ?: "Embedding reindex failed."
                        }
                    )
                }
            }
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

        val documentId = createDocumentId()

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isIndexingDocument = true,
                    indexingDocumentName = displayName,
                    errorMessage = null
                )
            }
            try {
                val state = _uiState.value
                val embeddingModel = resolveSelectedEmbeddingModel(state.models)
                if (embeddingModel == null) {
                    _uiState.update {
                        it.copy(
                            isIndexingDocument = false,
                            indexingDocumentName = null,
                            errorMessage = "Önce bir embedding model seç."
                        )
                    }
                    return@launch
                }
                val (document, chunks) = buildDocumentAndChunksForEmbeddingModel(
                    documentId = documentId,
                    displayName = displayName,
                    text = trimmedText,
                    embeddingModel = embeddingModel
                )
                val chunkEmbeddings = buildChunkEmbeddings(chunks, embeddingModel)
                vectorStoreRepository.upsertDocument(
                    document = document,
                    chunks = chunks,
                    chunkEmbeddings = chunks.map { chunk ->
                        chunkEmbeddings[chunkEmbeddingKey(chunk)]
                            ?: error("Embedding missing for ${chunk.documentName} / parça ${chunk.chunkIndex + 1}")
                    }
                )
                refreshVectorStoreSnapshot()
                indexedEmbeddingModelId = embeddingModel.modelId
                _uiState.update {
                    it.copy(
                        isIndexingDocument = false,
                        indexingDocumentName = null
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isIndexingDocument = false,
                        indexingDocumentName = null,
                        errorMessage = buildDetailedErrorMessage("Belge kaydedilemedi", throwable)
                    )
                }
            }
        }
    }

    fun removeDocument(documentId: String) {
        viewModelScope.launch {
            try {
                vectorStoreRepository.removeDocument(documentId)
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
                vectorStoreRepository.clear()
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

        val generationModel = current.models.firstOrNull { it.id == generationModelId } ?: run {
            _uiState.update { it.copy(errorMessage = "Selected generation model is no longer available.") }
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
                val embeddingModel = resolveSelectedEmbeddingModel(current.models) ?: run {
                    _uiState.update { it.copy(errorMessage = "Önce bir embedding model seç.") }
                    return@launch
                }
                val questionEmbedding = embedText(
                    text = question,
                    embeddingModel = embeddingModel,
                    purpose = RagEmbeddingPurpose.QUERY
                )
                val graphRetrieval = if (current.selectedVectorStoreBackend == RagVectorStoreBackend.GRAPH_RAG) {
                    vectorStoreRepository.retrieveGraph(
                        question = question,
                        questionEmbedding = questionEmbedding
                    )
                } else {
                    null
                }
                val relevantChunks = graphRetrieval?.chunks
                    ?: vectorStoreRepository.rank(
                        backend = current.selectedVectorStoreBackend,
                        question = question,
                        questionEmbedding = questionEmbedding
                    )
                val retrievalFinishedAt = SystemClock.elapsedRealtime()
                val prompt = if (graphRetrieval != null) {
                    buildGraphRagPrompt(
                        question = question,
                        graphContext = graphRetrieval.graphContext
                    )
                } else {
                    buildRagPrompt(question, relevantChunks)
                }
                val engine = engineFactory.create(selection.engineType, selection.runtimeType)

                _uiState.update {
                    it.copy(
                        highlightedSources = relevantChunks,
                        answerMetrics = RagAnswerMetrics(
                            backendName = current.selectedVectorStoreBackend.displayName,
                            generationModelName = generationModel.displayName,
                            embeddingModelName = embeddingModel.displayName,
                            sourceCount = relevantChunks.size,
                            retrievalTimeMs = retrievalFinishedAt - retrievalStartedAt,
                            answerMode = answerModeFor(current.selectedVectorStoreBackend)
                        )
                    )
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

    private suspend fun buildChunkEmbeddings(
        chunks: List<RagChunk>,
        embeddingModel: ResolvedEmbeddingModel
    ): Map<String, FloatArray> {
        if (chunks.isEmpty()) return emptyMap()

        return chunks.associate { chunk ->
            chunkEmbeddingKey(chunk) to embedText(
                text = chunk.text,
                embeddingModel = embeddingModel,
                purpose = RagEmbeddingPurpose.DOCUMENT
            )
        }
    }

    private suspend fun rebuildCurrentEmbeddings(requireEmbeddingModel: Boolean = false) {
        val state = _uiState.value
        val embeddingModel = resolveSelectedEmbeddingModel(state.models)
            ?: if (requireEmbeddingModel) {
                throw IllegalStateException("Önce seçili embedding modelini indirip seçmelisin.")
            } else {
                return
            }
        if (state.documents.isEmpty()) {
            vectorStoreRepository.reindexAll(emptyMap())
            return
        }

        val rebuilt = state.documents.map { document ->
            buildDocumentAndChunksForEmbeddingModel(
                documentId = document.id,
                displayName = document.displayName,
                text = document.text,
                embeddingModel = embeddingModel
            )
        }
        val rebuiltDocuments = rebuilt.map { it.first }
        val rebuiltChunks = rebuilt.flatMap { it.second }
        val chunkEmbeddings = buildChunkEmbeddings(rebuiltChunks, embeddingModel)
        vectorStoreRepository.replaceAll(
            snapshot = RagVectorStoreSnapshot(
                documents = rebuiltDocuments,
                chunks = rebuiltChunks
            ),
            chunkEmbeddings = chunkEmbeddings
        )
        indexedEmbeddingModelId = embeddingModel.modelId
    }

    private fun buildDocumentAndChunksForEmbeddingModel(
        documentId: String,
        displayName: String,
        text: String,
        embeddingModel: ResolvedEmbeddingModel
    ): Pair<RagDocument, List<RagChunk>> {
        val chunking = chunkingConfigFor(embeddingModel)
        val chunks = buildChunksFromDocument(
            documentId = documentId,
            displayName = displayName,
            text = text,
            chunkSize = chunking.chunkSize,
            overlap = chunking.overlap
        )
        val document = RagDocument(
            id = documentId,
            displayName = displayName,
            text = text,
            chunkCount = chunks.size
        )
        return document to chunks
    }

    private fun chunkingConfigFor(embeddingModel: ResolvedEmbeddingModel): ChunkingConfig {
        return when (embeddingModel.modelId) {
            "gecko-110m-en-256-litert" -> ChunkingConfig(chunkSize = 320, overlap = 80)
            "qwen3-embedding-0.6b-gguf" -> ChunkingConfig(chunkSize = 1800, overlap = 200)
            else -> ChunkingConfig(chunkSize = 1200, overlap = 150)
        }
    }

    private suspend fun embedText(
        text: String,
        embeddingModel: ResolvedEmbeddingModel,
        purpose: RagEmbeddingPurpose
    ): FloatArray {
        if (embeddingModel.modelId in rnLlamaUnsafeEmbeddingModelIds) {
            throw IllegalStateException(
                "${embeddingModel.displayName} mevcut llamacpp-kotlin/RNLlama native loader ile cihazda crash ediyor. " +
                    "Model listede kalıyor ama bu runtime ile güvenli çalıştırılamıyor; farklı embedding runtime bağlanmalı."
            )
        }
        return createEmbeddingProvider(embeddingModel).embed(
            text = text,
            embeddingModelPath = embeddingModel.modelPath,
            tokenizerPath = embeddingModel.tokenizerPath,
            purpose = purpose
        )
    }

    private fun createEmbeddingProvider(embeddingModel: ResolvedEmbeddingModel): RagEmbeddingProvider {
        embeddingProviderRef.get()?.let { active ->
            if (active.modelId == embeddingModel.modelId && active.runtimeType == embeddingModel.runtimeType) {
                return active.provider
            }
        }

        val provider = when (embeddingModel.runtimeType) {
            RuntimeType.LLAMA_CPP -> {
                val resolver = contentResolver ?: throw IllegalStateException("Embedding provider is not initialized.")
                LlamaCppRagEmbeddingProvider(resolver)
            }
            RuntimeType.LITERT -> if (embeddingModel.modelId.startsWith("embeddinggemma-2")) {
                LiteRtEmbeddingGemmaRagEmbeddingProvider(appContext.cacheDir)
            } else LiteRtGeckoRagEmbeddingProvider()
            RuntimeType.ONNX -> throw IllegalStateException("ONNX embedding provider is not configured.")
            RuntimeType.EXECUTORCH -> throw IllegalStateException(
                "${embeddingModel.displayName} için ExecuTorch embedding provider bağlı değil."
            )
        }
        val activeProvider = ActiveEmbeddingProvider(
            modelId = embeddingModel.modelId,
            runtimeType = embeddingModel.runtimeType,
            provider = provider
        )
        val previous = embeddingProviderRef.getAndSet(activeProvider)
        previous?.provider?.close()
        return activeProvider.provider
    }

    private fun resolveSelectedEmbeddingModel(currentModels: List<com.hilal.llm.catalog.ModelConfig>): ResolvedEmbeddingModel? {
        val selectedModelId = _uiState.value.selectedEmbeddingModelId ?: return null
        val selectedModel = currentModels.firstOrNull { it.id == selectedModelId } ?: return null
        if (!downloadRepository.isModelDownloaded(selectedModel)) return null
        val runFiles = runCatching { runFileMapper.toRunFiles(selectedModel) }.getOrNull() ?: return null
        val tokenizerPath = runFiles.tokenizerPath
            ?: runFiles.extraFiles.firstOrNull { it.endsWith("sentencepiece.model", ignoreCase = true) }
        return ResolvedEmbeddingModel(
            modelId = selectedModel.id,
            displayName = selectedModel.displayName,
            runtimeType = selectedModel.runtimeType,
            modelPath = runFiles.modelPath,
            tokenizerPath = tokenizerPath
        )
    }

    private fun answerModeFor(backend: RagVectorStoreBackend): String {
        return when (backend) {
            RagVectorStoreBackend.IN_MEMORY -> "Vektörel RAG"
            RagVectorStoreBackend.SQLITE_VEC -> "sqlite-vec RAG"
            RagVectorStoreBackend.GRAPH_RAG -> "Graph RAG"
        }
    }

    private fun refreshEngineFactory() {
        engineFactory = LocalLlmEngineFactory(
            execuTorchEngine = execuTorchEngine,
            liteRtEngine = liteRtEngine,
            llamaCppEngine = llamaEngine,
            manualFallbackEngine = unavailableEngine
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

    private fun initializeExecuTorchEngine(contentResolver: ContentResolver) {
        execuTorchEngine = com.hilal.llm.benchmark.ExecuTorchLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
        refreshEngineFactory()
    }

    override fun onCleared() {
        embeddingProviderRef.getAndSet(null)?.provider?.close()
        vectorStoreRepository.close()
        super.onCleared()
    }
}
