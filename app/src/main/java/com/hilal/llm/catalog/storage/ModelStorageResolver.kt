package com.hilal.llm.catalog.storage

import android.content.Context
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.ModelFileConfig
import java.io.File

class ModelStorageResolver(
    private val context: Context
) {
    fun getModelDir(config: ModelConfig): File {
        val modelsDir = File(context.filesDir, MODELS_DIR_NAME)
        val runtimeDir = File(modelsDir, config.runtimeType.name.lowercase())
        val modelDir = File(runtimeDir, config.id)
        migrateLegacyModelDirIfNeeded(config, modelDir)
        return modelDir
    }

    fun ensureModelDir(config: ModelConfig): File {
        val modelDir = getModelDir(config)
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        return modelDir
    }

    fun resolveFiles(config: ModelConfig): List<ModelLocalFile> {
        val modelDir = getModelDir(config)
        return config.files.map { fileConfig ->
            fileConfig.toLocalFile(modelDir)
        }
    }

    fun isModelDownloaded(config: ModelConfig): Boolean {
        val modelDir = getModelDir(config)
        return config.files
            .filter { it.required }
            .all { fileConfig ->
                val localFile = File(modelDir, fileConfig.fileName)
                localFile.exists() &&
                    localFile.isFile &&
                    localFile.length() > 0L &&
                    fileConfig.minBytes?.let { localFile.length() >= it } != false
            }
    }

    fun deleteModel(config: ModelConfig): Boolean {
        val modelDir = getModelDir(config)
        if (!modelDir.exists()) {
            return true
        }
        return modelDir.deleteRecursively()
    }

    private fun migrateLegacyModelDirIfNeeded(config: ModelConfig, modelDir: File) {
        if (modelDir.exists()) return
        val legacyDir = File(File(context.filesDir, MODELS_DIR_NAME), config.id)
        if (!legacyDir.exists()) return

        modelDir.parentFile?.mkdirs()
        if (!legacyDir.renameTo(modelDir)) {
            legacyDir.copyRecursively(modelDir, overwrite = true)
            legacyDir.deleteRecursively()
        }
    }

    private fun ModelFileConfig.toLocalFile(modelDir: File): ModelLocalFile {
        val localFile = File(modelDir, fileName)
        return ModelLocalFile(
            type = type,
            fileName = fileName,
            localFile = localFile,
            exists = localFile.exists(),
            required = required
        )
    }

    private companion object {
        private const val MODELS_DIR_NAME = "models"
    }
}
