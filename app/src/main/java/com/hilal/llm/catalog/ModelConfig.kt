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
    val disabledReason: String?,
    val family: String = displayName.substringBefore(' '),
    val purpose: ModelPurpose = ModelPurpose.GENERATION,
    val estimatedSizeBytes: Long? = null
) {
    val tokenizerRequired: Boolean
        get() = files.any { file ->
            file.required && (
                file.type == ModelFileType.TOKENIZER_JSON ||
                    file.type == ModelFileType.TOKENIZER_MODEL
                )
        }

    val formatLabel: String
        get() = when (runtimeType) {
            RuntimeType.EXECUTORCH -> "PTE"
            RuntimeType.LITERT -> "LiteRT"
            RuntimeType.ONNX -> "ONNX GenAI"
            RuntimeType.LLAMA_CPP -> "GGUF"
        }

    val hasDownloadArtifacts: Boolean
        get() = files.filter { it.required }.all { file ->
            file.url.isNotBlank() && !file.url.isPlaceholderDownloadUrl()
        }
}

enum class ModelPurpose {
    GENERATION,
    EMBEDDING
}

private fun String.isPlaceholderDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    return normalized.contains("example.com") ||
        normalized.contains("placeholder") ||
        normalized == "url" ||
        normalized == "tbd" ||
        normalized == "todo"
}
