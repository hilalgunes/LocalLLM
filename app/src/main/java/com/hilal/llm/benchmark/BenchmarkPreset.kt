package com.hilal.llm.benchmark

data class BenchmarkPreset(
    val id: String,
    val title: String,
    val description: String,
    val prompt: String,
    val maxTokens: Int,
    val category: BenchmarkPresetCategory
)
