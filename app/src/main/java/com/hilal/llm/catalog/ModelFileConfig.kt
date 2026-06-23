package com.hilal.llm.catalog

data class ModelFileConfig(
    val type: ModelFileType,
    val fileName: String,
    val url: String,
    val required: Boolean,
    val description: String? = null
)
