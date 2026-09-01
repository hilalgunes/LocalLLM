package com.hilal.llm.rag

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RagVectorStoreSnapshot(
    val documents: List<RagDocument>,
    val chunks: List<RagChunk>
)

internal object RagVectorStoreNative {
    init {
        System.loadLibrary("rag_sqlitevec")
    }

    external fun open(path: String): Long
    external fun close(handle: Long)
    external fun replaceAll(
        handle: Long,
        docIds: Array<String>,
        docNames: Array<String>,
        docTexts: Array<String>,
        docChunkCounts: IntArray,
        chunkDocIds: Array<String>,
        chunkDocNames: Array<String>,
        chunkIndices: IntArray,
        chunkTexts: Array<String>,
        chunkEmbeddings: Array<ByteArray>
    ): Int
    external fun snapshotJson(handle: Long): String
    external fun rank(handle: Long, questionEmbedding: ByteArray, limit: Int): String
}

class RagVectorStoreRepository(context: Context) {
    private val mutex = Mutex()
    private val handle: Long
    private val graphStore = RagGraphStoreRepository(context.applicationContext)
    private var documents: List<RagDocument> = emptyList()
    private var chunks: List<RagChunk> = emptyList()
    private var chunkEmbeddingsByKey: Map<String, FloatArray> = emptyMap()

    init {
        val dbPath = context.applicationContext.getDatabasePath("rag_vector_store.db").absolutePath
        handle = RagVectorStoreNative.open(dbPath)
        loadSnapshotFromNative()
    }

    fun close() {
        if (handle != 0L) {
            RagVectorStoreNative.close(handle)
        }
    }

    suspend fun initializeFromPersistentStore() = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadSnapshotFromNative()
        }
    }

    suspend fun snapshot(): RagVectorStoreSnapshot = mutex.withLock {
        RagVectorStoreSnapshot(documents = documents, chunks = chunks)
    }

    suspend fun replaceAll(
        snapshot: RagVectorStoreSnapshot,
        chunkEmbeddings: Map<String, FloatArray>
    ) = mutex.withLock {
        documents = snapshot.documents
        chunks = snapshot.chunks
        chunkEmbeddingsByKey = chunkEmbeddings
        persistCurrentState()
        snapshot.documents.forEach { document ->
            graphStore.upsertDocumentGraph(
                document = document,
                chunks = chunks.filter { it.documentId == document.id }
            )
        }
    }

    suspend fun upsertDocument(
        document: RagDocument,
        chunks: List<RagChunk>,
        chunkEmbeddings: List<FloatArray>
    ) = mutex.withLock {
        documents = documents.filterNot { it.id == document.id } + document
        this.chunks = this.chunks.filterNot { it.documentId == document.id } + chunks
        chunkEmbeddingsByKey = chunkEmbeddingsByKey.toMutableMap().apply {
            chunks.zip(chunkEmbeddings).forEach { (chunk, embedding) ->
                put(chunkEmbeddingKey(chunk), embedding)
            }
        }
        persistCurrentState()
        graphStore.upsertDocumentGraph(document, chunks)
    }

    suspend fun upsertGraphDocument(
        document: RagDocument,
        chunks: List<RagChunk>
    ) = mutex.withLock {
        documents = documents.filterNot { it.id == document.id } + document
        this.chunks = this.chunks.filterNot { it.documentId == document.id } + chunks
        persistCurrentState()
        graphStore.upsertDocumentGraph(document, chunks)
    }

    suspend fun removeDocument(documentId: String) = mutex.withLock {
        documents = documents.filterNot { it.id == documentId }
        val removedChunkKeys = chunks.filter { it.documentId == documentId }.map(::chunkEmbeddingKey).toSet()
        chunks = chunks.filterNot { it.documentId == documentId }
        chunkEmbeddingsByKey = chunkEmbeddingsByKey.filterKeys { key -> key !in removedChunkKeys }
        persistCurrentState()
        graphStore.removeDocumentGraph(documentId)
    }

    suspend fun clear() = mutex.withLock {
        documents = emptyList()
        chunks = emptyList()
        chunkEmbeddingsByKey = emptyMap()
        persistCurrentState()
        graphStore.clear()
    }

    suspend fun reindexAll(chunkEmbeddings: Map<String, FloatArray>) = mutex.withLock {
        chunkEmbeddingsByKey = chunkEmbeddings
        persistCurrentState()
    }

    suspend fun rebuildGraphIndex() = mutex.withLock {
        documents.forEach { document ->
            graphStore.upsertDocumentGraph(
                document = document,
                chunks = chunks.filter { it.documentId == document.id }
            )
        }
    }

    suspend fun rank(
        backend: RagVectorStoreBackend,
        question: String,
        questionEmbedding: FloatArray?,
        limit: Int = 4
    ): List<RagChunk> = when (backend) {
        RagVectorStoreBackend.IN_MEMORY -> rankChunksWithEmbeddings(
            questionEmbedding = requireNotNull(questionEmbedding) {
                "Embedding vector is required for in-memory vector RAG."
            },
            chunks = chunks,
            chunkEmbeddings = chunkEmbeddingsByKey,
            limit = limit
        )
        RagVectorStoreBackend.SQLITE_VEC -> withContext(Dispatchers.IO) {
            val vector = requireNotNull(questionEmbedding) {
                "Embedding vector is required for sqlite-vec RAG."
            }
            rankSqliteVec(vector, limit)
        }
        RagVectorStoreBackend.GRAPH_RAG -> retrieveGraph(
            question = question,
            questionEmbedding = requireNotNull(questionEmbedding) {
                "Embedding vector is required for Graph RAG."
            },
            limit = limit
        ).chunks
    }

    internal suspend fun retrieveGraph(
        question: String,
        questionEmbedding: FloatArray,
        limit: Int = 4
    ): RagGraphRetriever.Retrieval = mutex.withLock {
        val vectorSeedChunks = withContext(Dispatchers.IO) {
            rankSqliteVec(questionEmbedding, limit * 3)
        }
        graphStore.retrieve(
            question = question,
            chunks = chunks,
            vectorSeedChunks = vectorSeedChunks,
            limit = limit
        )
    }

    private fun rankSqliteVec(
        questionEmbedding: FloatArray,
        limit: Int
    ): List<RagChunk> {
        val json = RagVectorStoreNative.rank(handle, floatArrayToBytes(questionEmbedding), limit)
        return parseChunksJson(json)
    }

    private suspend fun persistCurrentState() {
        val docIds = documents.map { it.id }.toTypedArray()
        val docNames = documents.map { it.displayName }.toTypedArray()
        val docTexts = documents.map { it.text }.toTypedArray()
        val docChunkCounts = documents.map { it.chunkCount }.toIntArray()

        val chunkDocIds = chunks.map { it.documentId }.toTypedArray()
        val chunkDocNames = chunks.map { it.documentName }.toTypedArray()
        val chunkIndices = chunks.map { it.chunkIndex }.toIntArray()
        val chunkTexts = chunks.map { it.text }.toTypedArray()
        val chunkEmbeddings = chunks.map { chunk ->
            val key = chunkEmbeddingKey(chunk)
            val embedding = chunkEmbeddingsByKey[key]
                ?: throw IllegalStateException("Embedding missing for ${chunk.documentName} / parça ${chunk.chunkIndex + 1}")
            floatArrayToBytes(embedding)
        }.toTypedArray()

        val rc = RagVectorStoreNative.replaceAll(
            handle = handle,
            docIds = docIds,
            docNames = docNames,
            docTexts = docTexts,
            docChunkCounts = docChunkCounts,
            chunkDocIds = chunkDocIds,
            chunkDocNames = chunkDocNames,
            chunkIndices = chunkIndices,
            chunkTexts = chunkTexts,
            chunkEmbeddings = chunkEmbeddings
        )
        if (rc != 0) {
            throw IllegalStateException("sqlite-vec store update failed with code $rc")
        }
    }

    private fun loadSnapshotFromNative() {
        val json = RagVectorStoreNative.snapshotJson(handle)
        val parsed = JSONObject(json)

        documents = parsed.optJSONArray("documents")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        RagDocument(
                            id = item.getString("documentId"),
                            displayName = item.getString("displayName"),
                            text = item.getString("text"),
                            chunkCount = item.getInt("chunkCount")
                        )
                    )
                }
            }
        } ?: emptyList()

        chunks = parsed.optJSONArray("chunks")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        RagChunk(
                            documentId = item.getString("documentId"),
                            documentName = item.getString("documentName"),
                            chunkIndex = item.getInt("chunkIndex"),
                            text = item.getString("text")
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private fun parseChunksJson(json: String): List<RagChunk> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    RagChunk(
                        documentId = item.getString("documentId"),
                        documentName = item.getString("documentName"),
                        chunkIndex = item.getInt("chunkIndex"),
                        text = item.getString("text")
                    )
                )
            }
        }
    }
}
