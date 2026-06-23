package com.hilal.llm.catalog.runtime

data class ModelRunFiles(
    val modelPath: String,
    val tokenizerPath: String?,
    val extraFiles: List<String>
)
