package com.hilal.llm.rag

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class RagDocumentProcessingTest {
    @Test fun splitPreservesOverlapAndFinalBoundary() {
        assertEquals(listOf("abcd", "defg", "ghij"), RagTextChunker.split("abcdefghij", 4, 1))
        assertEquals(listOf("abcd", "ef"), RagTextChunker.split("abcdef", 4, 0))
    }

    @Test fun blankAndShortDocumentsDoNotProduceEmptyChunks() {
        assertEquals(emptyList<String>(), RagTextChunker.split(" \n\t "))
        assertEquals(listOf("Türkçe içerik"), RagTextChunker.split("  Türkçe içerik \n"))
        assertEquals(listOf("a\nb"), RagTextChunker.split("a\r\nb"))
    }

    @Test fun invalidBudgetsAreRejectedEvenForEmptyText() {
        listOf(0 to 0, -1 to 0, 4 to -1, 4 to 4, 4 to 5).forEach { (size, overlap) ->
            assertThrows(IllegalArgumentException::class.java) { RagTextChunker.split("", size, overlap) }
        }
    }

    @Test fun documentMetadataAndChunkOrderSurviveSplitting() {
        val chunks = buildChunksFromDocument("doc-1", "notlar.txt", "abcdefghij", 4, 1)
        assertEquals(listOf(0, 1, 2), chunks.map { it.chunkIndex })
        assertTrue(chunks.all { it.documentId == "doc-1" && it.documentName == "notlar.txt" })
        assertEquals(listOf("abcd", "defg", "ghij"), chunks.map { it.text })
    }

    @Test fun textReaderDecodesUtf8AndTrimsOuterWhitespace() {
        val input = ByteArrayInputStream(" \nİstanbul, öğüt ve çığ.\n ".toByteArray(Charsets.UTF_8))
        assertEquals("İstanbul, öğüt ve çığ.", RagDocumentReader.parse(input, "notlar.md", "text/markdown"))
    }

    @Test fun pdfIsRecognizedByExtensionOrProviderMimeType() {
        assertTrue(RagDocumentReader.isPdf("DOSYA.PDF", null))
        assertTrue(RagDocumentReader.isPdf("document", "application/pdf"))
        assertFalse(RagDocumentReader.isPdf("notlar.txt", "text/plain"))
    }

    @Test fun readFailureIsNotConvertedIntoEmptyDocument() {
        val input = object : InputStream() { override fun read(): Int = throw IOException("read failed") }
        assertThrows(IOException::class.java) { RagDocumentReader.parse(input, "notlar.txt", null) }
    }
}
