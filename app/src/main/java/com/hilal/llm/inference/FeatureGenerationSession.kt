package com.hilal.llm.inference

/** Exclusive request lease. Cancel the calling coroutine to stop generation; always close in finally. */
interface FeatureGenerationSession {
    suspend fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit = {}): String
    suspend fun close()
}
