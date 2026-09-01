package com.hilal.llm.rag

private val graphTokenizerRegex = Regex("[^\\p{L}\\p{N}]+")
private val graphStopWords = setOf(
    "ve", "veya", "ile", "bir", "bu", "su", "şu", "icin", "için", "ama",
    "olan", "olarak", "gore", "göre", "kadar", "daha", "az", "cok", "çok",
    "kac", "kaç", "nedir", "ne", "mi", "mı", "mu", "mü",
    "the", "and", "or", "to", "of", "in", "is", "are", "on", "for"
)

internal object RagGraphRetriever {
    private const val DIRECT_ENTITY_WEIGHT = 10
    private const val EXPANDED_ENTITY_WEIGHT = 3
    private const val EXACT_NUMBER_WEIGHT = 8
    private const val EXACT_PHRASE_WEIGHT = 6
    private const val GRAPH_NEIGHBOR_LIMIT = 12

    data class Retrieval(
        val chunks: List<RagChunk>,
        val graphContext: String
    )

    fun retrieve(
        question: String,
        chunks: List<RagChunk>,
        limit: Int = 4
    ): Retrieval {
        if (chunks.isEmpty()) return Retrieval(chunks = emptyList(), graphContext = "Graf bağlamı bulunamadı.")

        val graph = RagKnowledgeGraph.fromChunks(chunks)
        val questionEntities = extractEntities(question)
        if (questionEntities.isEmpty()) {
            val fallbackChunks = rankChunksByKeywordOverlap(question, chunks, limit)
            return Retrieval(
                chunks = fallbackChunks,
                graphContext = buildGraphContext(graph, fallbackChunks, emptySet())
            )
        }

        val questionNumbers = extractNumbers(question)
        val seedChunkKeys = questionEntities
            .flatMap { entity -> graph.entityToChunkKeys[entity].orEmpty() }
            .toSet()

        val expandedEntities = seedChunkKeys
            .take(GRAPH_NEIGHBOR_LIMIT)
            .flatMap { chunkKey -> graph.chunkEntities[chunkKey].orEmpty() }
            .toSet()

        val rankedChunks = chunks
            .map { chunk ->
                val key = chunkEmbeddingKey(chunk)
                val chunkEntities = graph.chunkEntities[key].orEmpty()
                val directMatches = chunkEntities.count { it in questionEntities }
                val expandedMatches = chunkEntities.count { it in expandedEntities && it !in questionEntities }
                val exactNumberMatches = extractNumbers(chunk.text).count { it in questionNumbers }
                val exactPhraseBonus = if (chunk.text.contains(question.trim(), ignoreCase = true)) {
                    EXACT_PHRASE_WEIGHT
                } else {
                    0
                }
                val seedBonus = if (key in seedChunkKeys) 4 else 0
                val score = directMatches * DIRECT_ENTITY_WEIGHT +
                    expandedMatches * EXPANDED_ENTITY_WEIGHT +
                    exactNumberMatches * EXACT_NUMBER_WEIGHT +
                    exactPhraseBonus +
                    seedBonus
                chunk to score
            }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<RagChunk, Int>> { it.second }
                    .thenBy { it.first.documentName }
                    .thenBy { it.first.chunkIndex }
            )
            .map { it.first }
            .take(limit)
            .ifEmpty { rankChunksByKeywordOverlap(question, chunks, limit) }

        return Retrieval(
            chunks = rankedChunks,
            graphContext = buildGraphContext(graph, rankedChunks, questionEntities)
        )
    }

    fun rank(
        question: String,
        chunks: List<RagChunk>,
        limit: Int = 4
    ): List<RagChunk> {
        return retrieve(question = question, chunks = chunks, limit = limit).chunks
    }

    private data class RagKnowledgeGraph(
        val chunkEntities: Map<String, Set<String>>,
        val entityToChunkKeys: Map<String, Set<String>>,
        val relations: List<RagGraphRelation>
    ) {
        companion object {
            fun fromChunks(chunks: List<RagChunk>): RagKnowledgeGraph {
                val chunkEntities = chunks.associate { chunk ->
                    chunkEmbeddingKey(chunk) to extractEntities(chunk.text)
                }
                val entityToChunkKeys = mutableMapOf<String, MutableSet<String>>()
                chunkEntities.forEach { (chunkKey, entities) ->
                    entities.forEach { entity ->
                        entityToChunkKeys.getOrPut(entity) { mutableSetOf() }.add(chunkKey)
                    }
                }
                val relations = chunks.flatMap { chunk ->
                    val entities = chunkEntities[chunkEmbeddingKey(chunk)].orEmpty().toList().sorted()
                    entities.flatMapIndexed { index, source ->
                        entities.drop(index + 1).take(8).map { target ->
                            RagGraphRelation(
                                source = source,
                                relation = "ayni_baglamda",
                                target = target,
                                chunk = chunk
                            )
                        }
                    }
                }
                return RagKnowledgeGraph(
                    chunkEntities = chunkEntities,
                    entityToChunkKeys = entityToChunkKeys,
                    relations = relations
                )
            }
        }
    }

    private data class RagGraphRelation(
        val source: String,
        val relation: String,
        val target: String,
        val chunk: RagChunk
    )

    private fun buildGraphContext(
        graph: RagKnowledgeGraph,
        chunks: List<RagChunk>,
        questionEntities: Set<String>
    ): String {
        if (chunks.isEmpty()) return "Graf bağlamı bulunamadı."

        val selectedChunkKeys = chunks.map(::chunkEmbeddingKey).toSet()
        val selectedEntities = chunks
            .flatMap { chunk -> graph.chunkEntities[chunkEmbeddingKey(chunk)].orEmpty() }
            .toSet()

        val relevantRelations = graph.relations
            .filter { relation ->
                chunkEmbeddingKey(relation.chunk) in selectedChunkKeys &&
                    (
                        relation.source in questionEntities ||
                            relation.target in questionEntities ||
                            relation.source in selectedEntities ||
                            relation.target in selectedEntities
                        )
            }
            .distinctBy { relation ->
                "${relation.source}:${relation.relation}:${relation.target}:${chunkEmbeddingKey(relation.chunk)}"
            }
            .take(16)

        val relationContext = if (relevantRelations.isEmpty()) {
            "İlişki bulunamadı."
        } else {
            relevantRelations.joinToString(separator = "\n") { relation ->
                "${relation.source} --${relation.relation}--> ${relation.target} " +
                    "[${relation.chunk.documentName} / Parça ${relation.chunk.chunkIndex + 1}]"
            }
        }

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
            ${selectedEntities.sorted().take(24).joinToString(", ").ifBlank { "yok" }}

            Graf ilişkileri:
            $relationContext

            Kaynak kanıtlar:
            $evidenceContext
        """.trimIndent()
    }

    internal fun extractEntities(text: String): Set<String> {
        val normalized = normalizeGraphText(text)
        if (normalized.isBlank()) return emptySet()

        val tokens = graphTokenizerRegex
            .split(normalized)
            .asSequence()
            .map { it.trim() }
            .map(::normalizeEntityToken)
            .filter { token ->
                token.length >= 3 &&
                    token !in graphStopWords &&
                    !token.all(Char::isDigit)
            }
            .toList()

        val wordEntities = tokens.toSet()
        val phraseEntities = buildPhraseEntities(tokens)

        val numericEntities = Regex("""\b\d+\s*(yil|yas|gun|saat|ay)\b""")
            .findAll(normalized)
            .map { match -> match.value.replace(Regex("""\s+"""), "_") }
            .toSet()

        return wordEntities + phraseEntities + numericEntities
    }

    private fun extractNumbers(text: String): Set<String> {
        return Regex("""\d+""")
            .findAll(text)
            .map { it.value }
            .toSet()
    }

    private fun normalizeGraphText(text: String): String {
        return text.lowercase()
            .replace("ı", "i")
            .replace("İ", "i")
            .replace("ş", "s")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ö", "o")
            .replace("ç", "c")
    }

    private fun normalizeEntityToken(token: String): String {
        return when {
            token.endsWith("leri") && token.length > 6 -> token.dropLast(4)
            token.endsWith("lari") && token.length > 6 -> token.dropLast(4)
            token.endsWith("inin") && token.length > 6 -> token.dropLast(4)
            token.endsWith("unun") && token.length > 6 -> token.dropLast(4)
            token.endsWith("nin") && token.length > 5 -> token.dropLast(3)
            token.endsWith("nun") && token.length > 5 -> token.dropLast(3)
            token.endsWith("sin") && token.length > 5 -> token.dropLast(3)
            token.endsWith("sı") && token.length > 4 -> token.dropLast(2)
            token.endsWith("si") && token.length > 4 -> token.dropLast(2)
            token.endsWith("su") && token.length > 4 -> token.dropLast(2)
            token.endsWith("i") && token.length > 4 -> token.dropLast(1)
            token.endsWith("u") && token.length > 4 -> token.dropLast(1)
            else -> token
        }
    }

    private fun buildPhraseEntities(tokens: List<String>): Set<String> {
        if (tokens.size < 2) return emptySet()

        val phrases = mutableSetOf<String>()
        for (index in 0 until tokens.lastIndex) {
            phrases += tokens[index] + "_" + tokens[index + 1]
            if (index + 2 < tokens.size) {
                phrases += tokens[index] + "_" + tokens[index + 1] + "_" + tokens[index + 2]
            }
        }
        return phrases
    }
}

fun buildGraphRagPrompt(
    question: String,
    graphContext: String
): String {
    return """
        Sen bir Graph RAG belge soru-cevap asistanısın.
        Cevabı yalnızca aşağıdaki graf düğümleri, ilişkileri ve kaynak kanıtlarına göre ver.
        Önce graf ilişkilerini esas al; kaynak kanıt metni ilişkiyi doğrulamak için kullan.
        Cevabın tamamını Türkçe yaz.
        Bilgi graf bağlamında veya kaynak kanıtlarda yoksa bunu açıkça söyle.
        Tahmin etme, belge dışı bilgi ekleme.
        Cevap kısa ve doğrudan olsun.
        Başlık, madde işareti, numaralandırma, "Cevap:" etiketi, düşünme metni ve kaynak notu yazma.

        Graph RAG bağlamı:
        $graphContext

        Soru:
        ${question.trim()}
    """.trimIndent()
}
