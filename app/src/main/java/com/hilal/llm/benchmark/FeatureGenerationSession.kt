package com.hilal.llm.benchmark

interface FeatureGenerationSession {
    suspend fun generate(prompt: String, maxTokens: Int): String
    suspend fun close()
}
