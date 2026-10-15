package com.hilal.llm.rag

import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.download.DownloadState

enum class RagModelRole {
    GENERATION,
    EMBEDDING
}

enum class RagVectorStoreBackend(val displayName: String, val description: String) {
    IN_MEMORY(
        displayName = "Bellek içi",
        description = "Mevcut akış. Doküman parçaları uygulama belleğinde tutulur."
    ),
    SQLITE_VEC(
        displayName = "sqlite-vec",
        description = "Gerçek sqlite-vec vec0 tablosu üzerinden KNN araması yapar."
    ),
    GRAPH_RAG(
        displayName = "Graph RAG",
        description = "Embedding ile bulunan adayları Room'daki varlık ve ilişki ağıyla genişletir."
    )
}

val ragGenerationModelIds = setOf(
    "lfm2-5-2-6b-litert-lm",
    "qwen3-0.6b-litert",
    "qwen3-0.6b-executorch-xnnpack",
    "gemma3-1b-executorch-xnnpack",
    "gemma-litert-example",
    "qwen3-5-2b-litert-lm",
    "llama-cpp-example",
    "qwen3-5-2b-gguf"
)

val ragEmbeddingModelIds = setOf(
    "embeddinggemma-2-text-270m-litert",
    "embeddinggemma-2-740m-litert",
    "qwen3-embedding-0.6b-gguf",
    "multilingual-e5-small-gguf",
    "all-minilm-l6-v2-gguf",
    "gecko-110m-en-256-litert"
)

data class RagDocument(
    val id: String,
    val displayName: String,
    val text: String,
    val chunkCount: Int
)

data class RagChunk(
    val documentId: String,
    val documentName: String,
    val chunkIndex: Int,
    val text: String
)

data class RagUiState(
    val models: List<ModelConfig> = emptyList(),
    val downloadStates: Map<String, DownloadState> = emptyMap(),
    val selectedGenerationModelId: String? = null,
    val selectedEmbeddingModelId: String? = null,
    val selectedVectorStoreBackend: RagVectorStoreBackend = RagVectorStoreBackend.GRAPH_RAG,
    val pendingDownloadModelId: String? = null,
    val pendingDownloadRole: RagModelRole? = null,
    val documents: List<RagDocument> = emptyList(),
    val documentChunks: List<RagChunk> = emptyList(),
    val isIndexingDocument: Boolean = false,
    val indexingDocumentName: String? = null,
    val question: String = "",
    val answer: String = "",
    val answerMetrics: RagAnswerMetrics? = null,
    val highlightedSources: List<RagChunk> = emptyList(),
    val isGenerating: Boolean = false,
    val errorMessage: String? = null
)

data class RagAnswerMetrics(
    val backendName: String,
    val generationModelName: String? = null,
    val embeddingModelName: String? = null,
    val sourceCount: Int,
    val retrievalTimeMs: Long,
    val answerMode: String,
    val firstTokenLatencyMs: Long? = null,
    val totalTimeMs: Long? = null
)
