package com.hilal.llm.benchmark

import com.hilal.llm.catalog.EngineType as CatalogEngineType
import com.hilal.llm.catalog.PromptTemplateType
import com.hilal.llm.catalog.RuntimeType

data class BenchmarkPrompt(
    val modelId: String? = null,
    val modelName: String,
    val modelFilePath: String,
    val tokenizerFilePath: String? = null,
    val modelDisplayName: String? = null,
    val runtimeType: RuntimeType? = null,
    val engineType: CatalogEngineType? = null,
    val promptTemplateType: PromptTemplateType? = null,
    val selectionSource: SelectionSource = SelectionSource.MANUAL,
    val prompt: String,
    val maxTokens: Int = 512,
    val createdAt: Long = System.currentTimeMillis()
)
