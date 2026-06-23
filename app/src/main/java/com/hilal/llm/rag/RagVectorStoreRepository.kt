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
    private val embeddingProvider: RagEmbeddingProvider = LlamaCppRagEmbeddingProvider(context.contentResolver)
    private var documents: List<RagDocument> = emptyList()
    private var chunks: List<RagChunk> = emptyList()

    init {
        val dbPath = context.applicationContext.getDatabasePath("rag_vector_store.db").absolutePath
        handle = RagVectorStoreNative.open(dbPath)
        loadSnapshotFromNative()
    }

    fun close() {
        if (handle != 0L) {
            RagVectorStoreNative.close(handle)
        }
        embeddingProvider.close()
    }

    suspend fun initializeFromPersistentStore() = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadSnapshotFromNative()
        }
    }

    suspend fun snapshot(): RagVectorStoreSnapshot = mutex.withLock {
        RagVectorStoreSnapshot(documents = documents, chunks = chunks)
    }

    suspend fun replaceAll(snapshot: RagVectorStoreSnapshot, embeddingModelPath: String) = mutex.withLock {
        documents = snapshot.documents
        chunks = snapshot.chunks
        persistCurrentState(embeddingModelPath)
    }

    suspend fun upsertDocument(document: RagDocument, chunks: List<RagChunk>, embeddingModelPath: String) = mutex.withLock {
        documents = documents.filterNot { it.id == document.id } + document
        this.chunks = this.chunks.filterNot { it.documentId == document.id } + chunks
        persistCurrentState(embeddingModelPath)
    }

    suspend fun removeDocument(documentId: String, embeddingModelPath: String) = mutex.withLock {
        documents = documents.filterNot { it.id == documentId }
        chunks = chunks.filterNot { it.documentId == documentId }
        persistCurrentState(embeddingModelPath)
    }

    suspend fun clear(embeddingModelPath: String) = mutex.withLock {
        documents = emptyList()
        chunks = emptyList()
        persistCurrentState(embeddingModelPath)
    }

    suspend fun reindexAll(embeddingModelPath: String) = mutex.withLock {
        persistCurrentState(embeddingModelPath)
    }

    suspend fun rank(
        backend: RagVectorStoreBackend,
        question: String,
        embeddingModelPath: String,
        limit: Int = 4
    ): List<RagChunk> = when (backend) {
        RagVectorStoreBackend.IN_MEMORY -> rankChunks(
            question = question,
            chunks = chunks,
            embeddingModelPath = embeddingModelPath,
            embeddingProvider = embeddingProvider,
            limit = limit
        )
        RagVectorStoreBackend.SQLITE_VEC -> withContext(Dispatchers.IO) {
            val questionEmbedding = floatArrayToBytes(embeddingProvider.embed(question, embeddingModelPath))
            val json = RagVectorStoreNative.rank(handle, questionEmbedding, limit)
            parseChunksJson(json)
        }
    }

    suspend fun canInitializeEmbeddingModel(embeddingModelPath: String): Boolean {
        return embeddingProvider.canInitialize(embeddingModelPath)
    }

    private suspend fun persistCurrentState(embeddingModelPath: String) {
        val docIds = documents.map { it.id }.toTypedArray()
        val docNames = documents.map { it.displayName }.toTypedArray()
        val docTexts = documents.map { it.text }.toTypedArray()
        val docChunkCounts = documents.map { it.chunkCount }.toIntArray()

        val chunkDocIds = chunks.map { it.documentId }.toTypedArray()
        val chunkDocNames = chunks.map { it.documentName }.toTypedArray()
        val chunkIndices = chunks.map { it.chunkIndex }.toIntArray()
        val chunkTexts = chunks.map { it.text }.toTypedArray()
        val chunkEmbeddings = chunks.map { chunk ->
            floatArrayToBytes(embeddingProvider.embed(chunk.text, embeddingModelPath))
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
