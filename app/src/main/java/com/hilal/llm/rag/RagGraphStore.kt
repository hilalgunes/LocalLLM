package com.hilal.llm.rag

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(
    tableName = "graph_nodes",
    indices = [
        Index(value = ["label"], unique = true),
        Index(value = ["type"])
    ]
)
data class RagGraphNodeEntity(
    @PrimaryKey val id: String,
    val label: String,
    val type: String
)

@Entity(
    tableName = "graph_edges",
    indices = [
        Index(value = ["sourceLabel"]),
        Index(value = ["targetLabel"]),
        Index(value = ["documentId", "chunkIndex"])
    ]
)
data class RagGraphEdgeEntity(
    @PrimaryKey val id: String,
    val sourceNodeId: String,
    val sourceLabel: String,
    val relation: String,
    val targetNodeId: String,
    val targetLabel: String,
    val documentId: String,
    val documentName: String,
    val chunkIndex: Int,
    val evidenceText: String
)

@Dao
interface RagGraphDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNodes(nodes: List<RagGraphNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdges(edges: List<RagGraphEdgeEntity>)

    @Query("DELETE FROM graph_edges WHERE documentId = :documentId")
    suspend fun deleteEdgesForDocument(documentId: String)

    @Query(
        """
        DELETE FROM graph_nodes
        WHERE id NOT IN (
            SELECT sourceNodeId FROM graph_edges
            UNION
            SELECT targetNodeId FROM graph_edges
        )
        """
    )
    suspend fun deleteOrphanNodes()

    @Query("DELETE FROM graph_edges")
    suspend fun clearEdges()

    @Query("DELETE FROM graph_nodes")
    suspend fun clearNodes()

    @Query(
        """
        SELECT * FROM graph_edges
        WHERE sourceLabel IN (:labels) OR targetLabel IN (:labels)
        ORDER BY documentName COLLATE NOCASE ASC, chunkIndex ASC
        LIMIT :limit
        """
    )
    suspend fun edgesForLabels(labels: List<String>, limit: Int): List<RagGraphEdgeEntity>

    @Query(
        """
        SELECT * FROM graph_edges
        WHERE documentId = :documentId AND chunkIndex = :chunkIndex
        ORDER BY sourceLabel ASC, targetLabel ASC
        LIMIT :limit
        """
    )
    suspend fun edgesForChunk(documentId: String, chunkIndex: Int, limit: Int): List<RagGraphEdgeEntity>
}

@Database(
    entities = [
        RagGraphNodeEntity::class,
        RagGraphEdgeEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class RagGraphDatabase : RoomDatabase() {
    abstract fun graphDao(): RagGraphDao

    companion object {
        @Volatile
        private var instance: RagGraphDatabase? = null

        fun getInstance(context: Context): RagGraphDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RagGraphDatabase::class.java,
                    "rag_graph_store.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

internal class RagGraphStoreRepository(context: Context) {
    private val dao = RagGraphDatabase.getInstance(context).graphDao()

    suspend fun upsertDocumentGraph(
        document: RagDocument,
        chunks: List<RagChunk>
    ) = withContext(Dispatchers.IO) {
        dao.deleteEdgesForDocument(document.id)

        val nodes = mutableMapOf<String, RagGraphNodeEntity>()
        val edges = mutableListOf<RagGraphEdgeEntity>()

        chunks.forEach { chunk ->
            val entities = RagGraphRetriever.extractEntities(chunk.text).sorted()
            entities.forEach { label ->
                nodes[nodeId(label)] = RagGraphNodeEntity(
                    id = nodeId(label),
                    label = label,
                    type = entityType(label)
                )
            }

            entities.forEachIndexed { index, source ->
                entities.drop(index + 1).take(8).forEach { target ->
                    edges += RagGraphEdgeEntity(
                        id = edgeId(chunk, source, "ayni_baglamda", target),
                        sourceNodeId = nodeId(source),
                        sourceLabel = source,
                        relation = "ayni_baglamda",
                        targetNodeId = nodeId(target),
                        targetLabel = target,
                        documentId = chunk.documentId,
                        documentName = chunk.documentName,
                        chunkIndex = chunk.chunkIndex,
                        evidenceText = chunk.text
                    )
                }
            }
        }

        if (nodes.isNotEmpty()) dao.upsertNodes(nodes.values.toList())
        if (edges.isNotEmpty()) dao.upsertEdges(edges)
        dao.deleteOrphanNodes()
    }

    suspend fun removeDocumentGraph(documentId: String) = withContext(Dispatchers.IO) {
        dao.deleteEdgesForDocument(documentId)
        dao.deleteOrphanNodes()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dao.clearEdges()
        dao.clearNodes()
    }

    suspend fun retrieve(
        question: String,
        chunks: List<RagChunk>,
        vectorSeedChunks: List<RagChunk>,
        limit: Int = 4
    ): RagGraphRetriever.Retrieval = withContext(Dispatchers.IO) {
        val questionEntities = RagGraphRetriever.extractEntities(question)
        val matchedEdges = if (questionEntities.isEmpty()) {
            emptyList()
        } else {
            dao.edgesForLabels(questionEntities.toList(), limit * 12)
        }
        val selectedChunks = selectChunksFromEdges(
            edges = matchedEdges,
            chunks = chunks,
            questionEntities = questionEntities,
            vectorSeedChunks = vectorSeedChunks,
            limit = limit
        )
            .ifEmpty { vectorSeedChunks.take(limit) }
            .ifEmpty { RagGraphRetriever.rank(question, chunks, limit) }
        val contextEdges = selectedChunks.flatMap { chunk ->
            dao.edgesForChunk(chunk.documentId, chunk.chunkIndex, limit = 16)
        }

        RagGraphRetriever.Retrieval(
            chunks = selectedChunks,
            graphContext = buildStoredGraphContext(
                edges = contextEdges,
                chunks = selectedChunks
            )
        )
    }

    private fun selectChunksFromEdges(
        edges: List<RagGraphEdgeEntity>,
        chunks: List<RagChunk>,
        questionEntities: Set<String>,
        vectorSeedChunks: List<RagChunk>,
        limit: Int
    ): List<RagChunk> {
        if (edges.isEmpty() && questionEntities.isEmpty() && vectorSeedChunks.isEmpty()) return emptyList()

        val chunksByKey = chunks.associateBy { graphChunkKey(it.documentId, it.chunkIndex) }
        val edgeScores = edges
            .groupingBy { edge -> graphChunkKey(edge.documentId, edge.chunkIndex) }
            .eachCount()
        val vectorSeedScores = vectorSeedChunks
            .mapIndexed { index, chunk ->
                graphChunkKey(chunk.documentId, chunk.chunkIndex) to ((vectorSeedChunks.size - index) * 6)
            }
            .toMap()
        val vectorSeedKeys = vectorSeedScores.keys

        val rankedKeys = chunks
            .map { chunk ->
                val key = graphChunkKey(chunk.documentId, chunk.chunkIndex)
                val chunkEntities = RagGraphRetriever.extractEntities(chunk.text)
                val directMatches = chunkEntities.count { it in questionEntities }
                val phraseMatches = chunkEntities.count { it.contains('_') && it in questionEntities }
                val edgeScore = edgeScores[key] ?: 0
                val vectorSeedScore = vectorSeedScores[key] ?: 0
                val neighborBonus = if (key !in vectorSeedKeys && sharesGraphEdgeWithVectorSeed(edges, key, vectorSeedKeys)) {
                    5
                } else {
                    0
                }
                val score = vectorSeedScore + directMatches * 12 + phraseMatches * 18 + edgeScore + neighborBonus
                key to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

        return rankedKeys
            .mapNotNull(chunksByKey::get)
            .take(limit)
    }

    private fun sharesGraphEdgeWithVectorSeed(
        edges: List<RagGraphEdgeEntity>,
        chunkKey: String,
        vectorSeedKeys: Set<String>
    ): Boolean {
        if (vectorSeedKeys.isEmpty()) return false
        val seedLabels = edges
            .filter { edge -> graphChunkKey(edge.documentId, edge.chunkIndex) in vectorSeedKeys }
            .flatMap { edge -> listOf(edge.sourceLabel, edge.targetLabel) }
            .toSet()
        if (seedLabels.isEmpty()) return false

        return edges.any { edge ->
            graphChunkKey(edge.documentId, edge.chunkIndex) == chunkKey &&
                (edge.sourceLabel in seedLabels || edge.targetLabel in seedLabels)
        }
    }

    private fun buildStoredGraphContext(
        edges: List<RagGraphEdgeEntity>,
        chunks: List<RagChunk>
    ): String {
        if (chunks.isEmpty()) return "Graf bağlamı bulunamadı."

        val nodes = edges
            .flatMap { edge -> listOf(edge.sourceLabel, edge.targetLabel) }
            .distinct()
            .sorted()
            .take(24)

        val relationContext = edges
            .distinctBy { it.id }
            .take(16)
            .joinToString(separator = "\n") { edge ->
                "${edge.sourceLabel} --${edge.relation}--> ${edge.targetLabel} " +
                    "[${edge.documentName} / Parça ${edge.chunkIndex + 1}]"
            }
            .ifBlank { "İlişki bulunamadı." }

        val evidenceContext = chunks.joinToString(separator = "\n\n") { chunk ->
            buildString {
                append('[')
                append(chunk.documentName)
                append(" / Parça ")
                append(chunk.chunkIndex + 1)
                append("]\n")
                append(chunk.text.trim())
            }
        }

        return """
            Graf düğümleri:
            ${nodes.joinToString(", ").ifBlank { "yok" }}

            Graf ilişkileri:
            $relationContext

            Kaynak kanıtlar:
            $evidenceContext
        """.trimIndent()
    }

    private fun nodeId(label: String): String = "node:${label.hashCode()}"

    private fun edgeId(
        chunk: RagChunk,
        source: String,
        relation: String,
        target: String
    ): String {
        return "edge:${chunk.documentId}:${chunk.chunkIndex}:${source.hashCode()}:${relation}:${target.hashCode()}"
    }

    private fun graphChunkKey(documentId: String, chunkIndex: Int): String {
        return "$documentId:$chunkIndex"
    }

    private fun entityType(label: String): String {
        return when {
            Regex("""^\d+_(yil|yas|gun|saat|ay)$""").matches(label) -> "numeric"
            else -> "term"
        }
    }
}
