package com.hilal.llm.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class OnnxChatPromptTest {
    @Test
    fun preservesTurkishUnicodeAndDocumentContents() {
        val content = "Dokümanlar:\n[Chunk 1] İzin: 20 gün.\n\"Koşul\": 😀\n\nSoru: Kaç gün?"
        assertEquals(
            "<bos><|turn>user\n$content<turn|>\n<|turn>model\n",
            OnnxChatPrompt.format("gemma4", "  $content\n")
        )
    }

    @Test
    fun doesNotInterpretTemplateSyntaxInsideDocuments() {
        val content = "{{ tools }} {% if true %} belge {% endif %}"
        assertEquals(
            "<bos><|turn>user\n$content<turn|>\n<|turn>model\n",
            OnnxChatPrompt.format("gemma4", content)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherModelsInsteadOfSilentlyApplyingGemmaFormat() {
        OnnxChatPrompt.format("llama", "Merhaba")
    }
}
