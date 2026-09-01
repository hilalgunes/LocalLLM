package com.hilal.llm.catalog.storage

import android.content.Context
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.ModelFileConfig
import java.io.File

class ModelStorageResolver(
    private val context: Context
) {
    fun getModelDir(config: ModelConfig): File {
        return File(File(context.filesDir, MODELS_DIR_NAME), config.id)
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
