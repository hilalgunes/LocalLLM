package com.hilal.llm.catalog.download

import android.util.Log
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.ModelFileConfig
import com.hilal.llm.catalog.storage.ModelStorageResolver
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ModelDownloadRepository(
    private val storageResolver: ModelStorageResolver
) {
    suspend fun downloadModel(
        config: ModelConfig,
        onStateChanged: (DownloadState) -> Unit
    ) {
        onStateChanged(DownloadState.Idle)
        withContext(Dispatchers.IO) {
            val modelDir = storageResolver.ensureModelDir(config)
            for ((index, fileConfig) in config.files.withIndex()) {
                currentCoroutineContext().ensureActive()
                val localFile = File(modelDir, fileConfig.fileName)
                if (localFile.isUsableFor(fileConfig)) {
                    Log.d(TAG, "Skipping existing file ${fileConfig.fileName} size=${localFile.length()}")
                    continue
                }
                if (localFile.exists()) {
                    Log.w(TAG, "Existing file ${fileConfig.fileName} is incomplete or invalid, re-downloading size=${localFile.length()}")
                }

                val url = fileConfig.url.trim()
                if (url.isBlank() || url.isPlaceholderUrl()) {
                    val message = "Missing download URL for ${config.displayName}: ${fileConfig.fileName}"
                    if (fileConfig.required) {
                        onStateChanged(DownloadState.Failed(message))
                        throw IllegalStateException(message)
                    } else {
                        Log.w(TAG, message)
                        continue
                    }
                }

                downloadSingleFile(
                    config = config,
                    fileConfig = fileConfig,
                    targetFile = localFile,
                    onStateChanged = { state ->
                        onStateChanged(
                            if (state is DownloadState.Downloading) {
                                state.copy(fileIndex = index + 1, fileCount = config.files.size)
                            } else state
                        )
                    }
                )
            }
            onStateChanged(DownloadState.Success)
        }
    }

    fun isModelDownloaded(config: ModelConfig): Boolean {
        return storageResolver.isModelDownloaded(config)
    }

    fun deleteModel(config: ModelConfig): Boolean {
        return storageResolver.deleteModel(config)
    }

    private suspend fun downloadSingleFile(
        config: ModelConfig,
        fileConfig: ModelFileConfig,
        targetFile: File,
        onStateChanged: (DownloadState) -> Unit
    ) {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            val totalBytes = ResumableModelDownload.transfer(fileConfig.url.trim(), tempFile) { downloaded, total ->
                onStateChanged(DownloadState.Downloading(
                    fileName = fileConfig.fileName,
                    progress = calculateProgress(downloaded, total),
                    downloadedBytes = downloaded,
                    totalBytes = total
                ))
            }

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                throw IllegalStateException("Downloaded file is empty: ${fileConfig.fileName}")
            }
            fileConfig.minBytes?.let { minBytes ->
                if (tempFile.length() < minBytes) {
                    throw IllegalStateException(
                        "Downloaded file is incomplete: ${fileConfig.fileName} " +
                            "(${tempFile.length()} bytes, expected at least $minBytes bytes)"
                    )
                }
            }

            if (targetFile.exists() && !targetFile.delete()) {
                throw IllegalStateException("Unable to replace existing file: ${targetFile.name}")
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                if (!tempFile.delete()) {
                    Log.w(TAG, "Unable to delete temporary file ${tempFile.absolutePath}")
                }
            }

            onStateChanged(
                DownloadState.Downloading(
                    fileName = fileConfig.fileName,
                    progress = 100,
                    downloadedBytes = targetFile.length(),
                    totalBytes = totalBytes
                )
            )
            File(tempFile.path + ".resume").delete()
            Log.d(TAG, "Downloaded ${fileConfig.fileName} for ${config.displayName}")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            val message = when {
                throwable.message?.contains("authentication is required", ignoreCase = true) == true ->
                    "Failed to download ${fileConfig.fileName}: ${throwable.message}"
                else ->
                    "Failed to download ${fileConfig.fileName}: ${throwable.message ?: "unknown error"}"
            }
            if (fileConfig.required) {
                onStateChanged(DownloadState.Failed(message))
                throw IllegalStateException(message, throwable)
            } else {
                Log.w(TAG, message, throwable)
            }
        }
    }

    private fun calculateProgress(downloadedBytes: Long, totalBytes: Long?): Int {
        if (totalBytes == null || totalBytes <= 0L) {
            return 0
        }
        return ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    private fun String.isPlaceholderUrl(): Boolean {
        val normalized = trim().lowercase()
        return normalized.contains("example.com") ||
            normalized.contains("placeholder") ||
            normalized == "url" ||
            normalized == "tbd" ||
            normalized == "todo"
    }

    private fun File.isUsableFor(fileConfig: ModelFileConfig): Boolean {
        if (!exists() || !isFile || length() <= 0L) return false
        return fileConfig.minBytes?.let { length() >= it } != false
    }

    private companion object {
        private const val TAG = "ModelDownloadRepository"
    }
}
