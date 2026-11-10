package com.hilal.llm.rag

fun buildChunksFromDocument(
    documentId: String,
    displayName: String,
    text: String,
    chunkSize: Int = 900,
    overlap: Int = 150
): List<RagChunk> {
    val chunks = RagTextChunker.split(text, chunkSize = chunkSize, overlap = overlap)
    return chunks.mapIndexed { index, chunk ->
        RagChunk(
            documentId = documentId,
            documentName = displayName,
            chunkIndex = index,
            text = chunk
        )
    }
}

/** Pure text splitting; each caller keeps its embedding model's chunk budget. */
object RagTextChunker {
    fun split(
        text: String,
        chunkSize: Int = 900,
        overlap: Int = 150
    ): List<String> {
        require(chunkSize > 0) { "Chunk size must be positive." }
        require(overlap in 0 until chunkSize) { "Overlap must be non-negative and smaller than chunk size." }
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isBlank()) return emptyList()
        if (normalized.length <= chunkSize) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            val end = (start + chunkSize).coerceAtMost(normalized.length)
            val chunk = normalized.substring(start, end).trim()
            if (chunk.isNotBlank()) {
                chunks.add(chunk)
            }
            if (end >= normalized.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return chunks
    }
}
