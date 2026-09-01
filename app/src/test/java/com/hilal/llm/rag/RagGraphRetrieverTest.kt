package com.hilal.llm.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagGraphRetrieverTest {
    @Test
    fun rank_prioritizesChunkConnectedToQuestionEntities() {
        val leaveChunk = RagChunk(
            documentId = "policy",
            documentName = "ik-politikasi.txt",
            chunkIndex = 0,
            text = "5 yıldan fazla 15 yıldan az çalışanlara yıllık izin hakkı 20 gündür."
        )
        val salaryChunk = RagChunk(
            documentId = "policy",
            documentName = "ik-politikasi.txt",
            chunkIndex = 1,
            text = "Maaş ödemeleri her ayın son iş gününde yapılır."
        )
        val chunks = listOf(salaryChunk, leaveChunk)

        val ranked = RagGraphRetriever.rank(
            question = "10 yıl çalışan kaç gün izin alır?",
            chunks = chunks,
            limit = 2
        )

        assertEquals(leaveChunk, ranked.first())
    }

    @Test
    fun retrieve_returnsGraphRelationshipContext() {
        val chunk = RagChunk(
            documentId = "policy",
            documentName = "ik-politikasi.txt",
            chunkIndex = 0,
            text = "5 yıldan fazla 15 yıldan az çalışanlara yıllık izin hakkı 20 gündür."
        )

        val retrieval = RagGraphRetriever.retrieve(
            question = "10 yıl çalışan kaç gün izin alır?",
            chunks = listOf(chunk),
            limit = 1
        )

        assertEquals(chunk, retrieval.chunks.first())
        assertTrue(retrieval.graphContext.contains("Graf ilişkileri"))
        assertTrue(retrieval.graphContext.contains("ayni_baglamda"))
    }

    @Test
    fun rank_prioritizesBirthLeavePhraseOverGenericLeave() {
        val annualLeaveChunk = RagChunk(
            documentId = "policy",
            documentName = "izin-politikasi.txt",
            chunkIndex = 0,
            text = "Yıllık izin hakkı hizmet yılına göre belirlenir ve en az 14 gündür."
        )
        val birthLeaveChunk = RagChunk(
            documentId = "policy",
            documentName = "izin-politikasi.txt",
            chunkIndex = 1,
            text = "Doğum izni doğumdan önce 8 hafta ve doğumdan sonra 8 hafta olmak üzere toplam 112 gündür."
        )

        val ranked = RagGraphRetriever.rank(
            question = "Doğum izni kaç gün?",
            chunks = listOf(annualLeaveChunk, birthLeaveChunk),
            limit = 2
        )

        assertEquals(birthLeaveChunk, ranked.first())
    }

    @Test
    fun keywordRank_prioritizesBirthLeavePhraseOverGenericLeave() {
        val annualLeaveChunk = RagChunk(
            documentId = "policy",
            documentName = "izin-politikasi.txt",
            chunkIndex = 0,
            text = "Yıllık izin hakkı hizmet yılına göre belirlenir ve en az 14 gündür."
        )
        val birthLeaveChunk = RagChunk(
            documentId = "policy",
            documentName = "izin-politikasi.txt",
            chunkIndex = 1,
            text = "Doğum izni doğumdan önce 8 hafta ve doğumdan sonra 8 hafta olmak üzere toplam 112 gündür."
        )

        val ranked = rankChunksByKeywordOverlap(
            question = "dogum izni kac gun",
            chunks = listOf(annualLeaveChunk, birthLeaveChunk),
            limit = 2
        )

        assertEquals(birthLeaveChunk, ranked.first())
    }

    @Test
    fun rank_fallsBackToKeywordOverlapWhenQuestionHasNoGraphEntities() {
        val firstChunk = RagChunk(
            documentId = "a",
            documentName = "a.txt",
            chunkIndex = 0,
            text = "Kısa not."
        )
        val secondChunk = RagChunk(
            documentId = "b",
            documentName = "b.txt",
            chunkIndex = 0,
            text = "Rapor teslim tarihi cuma günüdür."
        )

        val ranked = RagGraphRetriever.rank(
            question = "?",
            chunks = listOf(firstChunk, secondChunk),
            limit = 2
        )

        assertTrue(ranked.isNotEmpty())
    }

    @Test
    fun sanitizeRagOutput_removesInlineSourceNote() {
        val sanitized = sanitizeRagOutput(
            "Doğum izni16 Hafta olup İnsan Kaynakları tarafından onaylanmaktadır. Kaynak notu: [izin.pdf / Parça23]"
        )

        assertEquals("Doğum izni 16 Hafta olup İnsan Kaynakları tarafından onaylanmaktadır.", sanitized)
    }
}
