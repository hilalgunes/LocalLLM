package com.hilal.llm.benchmark

data class BenchmarkPrompt(
    val modelName: String,
    val modelFilePath: String,
    val tokenizerFilePath: String? = null,
    val prompt: String,
    val maxTokens: Int = 512,
    val createdAt: Long = System.currentTimeMillis()
)
