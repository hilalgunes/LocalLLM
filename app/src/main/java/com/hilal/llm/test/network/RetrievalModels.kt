package com.hilal.llm.test.network

import com.google.gson.annotations.SerializedName

data class RetrievalQueryRequest(
    val query: String,
    val mode: String = "product",
    @SerializedName("top_k") val topK: Int = 3,
    val retrieval: RetrievalOptions = RetrievalOptions()
)

data class RetrievalOptions(
    val engine: String = "qdrant",
    @SerializedName("embedding_model") val embeddingModel: String = "multilingual-e5-large"
)

data class RetrievalQueryResponse(
    @SerializedName("rag_request_id") val requestId: String?,
    val mode: String?,
    val runs: List<RetrievalRunResponse>?
)

data class RetrievalRunResponse(
    val results: List<RetrievedChunkResponse>?
)

data class RetrievedChunkResponse(
    val text: String?,
    @SerializedName("document_name") val documentName: String?,
    val score: Double?
)

data class RetrievedChunk(
    val text: String,
    val source: String? = null,
    val score: Double? = null
)

data class RetrievalResult(
    val chunks: List<RetrievedChunk>
)
