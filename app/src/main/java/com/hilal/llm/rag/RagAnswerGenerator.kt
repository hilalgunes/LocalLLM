package com.hilal.llm.rag

import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.inference.FeatureGenerationSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** RAG owns a request lease; engine creation and warm model lifetime belong to the shared runtime. */
class RagAnswerGenerator(
    private val openSession: suspend (BenchmarkModelSelection) -> FeatureGenerationSession
) {
    suspend fun generate(
        selection: BenchmarkModelSelection,
        prompt: String,
        maxTokens: Int,
        onText: (String) -> Unit
    ): String {
        val session = openSession(selection)
        return try {
            session.generate(prompt, maxTokens, onText)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }
}
