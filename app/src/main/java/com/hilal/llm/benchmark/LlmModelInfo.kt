package com.hilal.llm.benchmark

data class LlmModelInfo(
    val modelName: String,
    val modelFilePath: String,
    val tokenizerName: String? = null,
    val tokenizerFilePath: String? = null
)
