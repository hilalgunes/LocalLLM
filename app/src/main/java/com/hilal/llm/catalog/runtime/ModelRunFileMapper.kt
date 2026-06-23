package com.hilal.llm.catalog.runtime

import com.hilal.llm.catalog.EngineType
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.ModelFileType
import com.hilal.llm.catalog.storage.ModelLocalFile
import com.hilal.llm.catalog.storage.ModelStorageResolver

class ModelRunFileMapper(
    private val storageResolver: ModelStorageResolver
) {
    fun toRunFiles(config: ModelConfig): ModelRunFiles {
        val localFiles = storageResolver.resolveFiles(config)
        val mainFile = resolveMainModelFile(config, localFiles)
        val tokenizerFile = resolveTokenizerFile(config, localFiles)

        return ModelRunFiles(
            modelPath = mainFile.localFile.absolutePath,
            tokenizerPath = tokenizerFile?.localFile?.absolutePath,
            extraFiles = localFiles
                .filter { it.exists }
                .map { it.localFile.absolutePath }
                .filterNot { path ->
                    path == mainFile.localFile.absolutePath || path == tokenizerFile?.localFile?.absolutePath
                }
        )
    }

    private fun resolveMainModelFile(
        config: ModelConfig,
        localFiles: List<ModelLocalFile>
    ): ModelLocalFile {
        val requiredTypes = when (config.engineType) {
            EngineType.EXECUTORCH_GENERATE,
            EngineType.EXECUTORCH_FORWARD -> listOf(ModelFileType.EXECUTORCH_PTE)

            EngineType.LITERT_LM -> listOf(
                ModelFileType.LITERT_TASK,
                ModelFileType.LITERT_TFLITE,
                ModelFileType.LITERT_BUNDLE
            )

            EngineType.LLAMA_CPP -> listOf(ModelFileType.LLAMA_GGUF)
        }

        val selected = localFiles.firstOrNull { localFile ->
            localFile.exists && localFile.type in requiredTypes
        }

        if (selected == null) {
            throw IllegalStateException(buildString {
                append("No compatible model file found for ")
                append(config.displayName)
                append(" (engineType=").append(config.engineType).append("). ")
                append("Expected one of: ").append(requiredTypes.joinToString())
            })
        }

        if (!selected.exists) {
            throw IllegalStateException(
                "Required model file is missing for ${config.displayName}: ${selected.fileName}"
            )
        }

        return selected
    }

    private fun resolveTokenizerFile(
        config: ModelConfig,
        localFiles: List<ModelLocalFile>
    ): ModelLocalFile? {
        return when (config.engineType) {
            EngineType.EXECUTORCH_GENERATE,
            EngineType.EXECUTORCH_FORWARD -> {
                val tokenizer = localFiles.firstOrNull {
                    it.exists && (
                        it.type == ModelFileType.TOKENIZER_JSON ||
                            it.type == ModelFileType.TOKENIZER_MODEL
                        )
                } ?: throw IllegalStateException(
                    "ExecuTorch model requires a tokenizer file, but none was declared for ${config.displayName}."
                )

                if (!tokenizer.exists) {
                    throw IllegalStateException(
                        "Required tokenizer file is missing for ${config.displayName}: ${tokenizer.fileName}"
                    )
                }

                tokenizer
            }

            EngineType.LITERT_LM,
            EngineType.LLAMA_CPP -> null
        }
    }
}
