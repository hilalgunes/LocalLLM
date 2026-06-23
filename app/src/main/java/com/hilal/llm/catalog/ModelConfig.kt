package com.hilal.llm.catalog

data class ModelConfig(
    val id: String,
    val displayName: String,
    val description: String,
    val runtimeType: RuntimeType,
    val engineType: EngineType,
    val promptTemplateType: PromptTemplateType,
    val files: List<ModelFileConfig>,
    val estimatedSize: String,
    val quantization: String?,
    val isEnabled: Boolean,
    val disabledReason: String?
)
