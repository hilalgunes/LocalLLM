package com.hilal.llm.benchmark

interface FeatureGenerationSession {
    suspend fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit = {}): String
    suspend fun close()
}
