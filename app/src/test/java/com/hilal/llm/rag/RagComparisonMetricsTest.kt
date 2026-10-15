package com.hilal.llm.rag

import org.junit.Assert.*
import org.junit.Test

class RagComparisonMetricsTest {
    @Test fun relevanceMetricsIncludeMissingAndMultipleRelevantSources() {
        val metrics = retrievalScores(listOf(3, 1, 4, 2), setOf(1, 2), 3)
        assertEquals(1.0 / 3, metrics.precision, 0.00001)
        assertEquals(0.5, metrics.recall, 0.00001)
        assertEquals(0.5, metrics.reciprocalRank, 0.00001)
        assertTrue(metrics.ndcg in 0.0..1.0)
        assertTrue(metrics.hit)
        val missing = retrievalScores(listOf(3, 4), setOf(1), 1)
        assertEquals(0.0, missing.reciprocalRank, 0.0)
        assertEquals(0.0, missing.ndcg, 0.0)
        assertFalse(missing.hit)
    }
    @Test fun idealRankingHasFullNdcgAndRecall() {
        val metrics = retrievalScores(listOf(2, 1, 3), setOf(1, 2), 2)
        assertEquals(1.0, metrics.ndcg, 0.00001)
        assertEquals(1.0, metrics.recall, 0.0)
    }
    @Test fun referenceOverlapCountsRepeatedWordsAndDoesNotImplyTruth() {
        assertEquals(0.5, referenceTokenF1("izin izin", "izin gün")!!, 0.0)
        assertNull(referenceTokenF1("Cevap", ""))
        assertEquals(1.0, referenceTokenF1("İZİN!", "izin")!!, 0.0)
        // A negation can have high word overlap; UI must keep correctness a human rating.
        assertTrue(referenceTokenF1("Sıvı hasarı garanti kapsamındadır", "Sıvı hasarı garanti kapsamında değildir")!! > 0.5)
    }
    @Test fun invalidGroundTruthIsRejectedBeforeBenchmark() {
        assertThrows(IllegalArgumentException::class.java) { parseComparisonDataset("a\nb", "Soru | 3 | cevap") }
        assertThrows(IllegalArgumentException::class.java) { parseComparisonDataset("a\nb", "Soru") }
        assertEquals(8, parseComparisonDataset(defaultComparisonPassages, defaultComparisonQueries).queries.size)
    }
}
