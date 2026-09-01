package com.hilal.llm.catalog.download

import android.util.Log
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.ModelFileConfig
import com.hilal.llm.catalog.storage.ModelStorageResolver
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
            for (fileConfig in config.files) {
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
                    onStateChanged = onStateChanged
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
        val tempFile = File(targetFile.parentFile, "${fileConfig.fileName}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        var connection: HttpURLConnection? = null
        try {
            val urlConnection = URL(fileConfig.url.trim()).openConnection()
            connection = urlConnection as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.doInput = true
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw IllegalStateException(
                        "Hugging Face authentication is required for ${fileConfig.fileName}. " +
                            "This LiteRT export cannot be downloaded anonymously from the app."
                    )
                }
                throw IllegalStateException(
                    "HTTP $responseCode while downloading ${fileConfig.fileName}"
                )
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            connection.inputStream.use { inputStream ->
                BufferedInputStream(inputStream).use { bufferedInput ->
                    BufferedOutputStream(tempFile.outputStream()).use { outputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloadedBytes = 0L
                        var read = bufferedInput.read(buffer)
                        while (read != -1) {
                            currentCoroutineContext().ensureActive()
                            outputStream.write(buffer, 0, read)
                            downloadedBytes += read
                            val progress = calculateProgress(downloadedBytes, totalBytes)
                            onStateChanged(
                                DownloadState.Downloading(
                                    fileName = fileConfig.fileName,
                                    progress = progress,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes
                                )
                            )
                            read = bufferedInput.read(buffer)
                        }
                        outputStream.flush()
                    }
                }
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
            Log.d(TAG, "Downloaded ${fileConfig.fileName} for ${config.displayName}")
        } catch (cancellation: CancellationException) {
            tempFile.delete()
            throw cancellation
        } catch (throwable: Throwable) {
            tempFile.delete()
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
        } finally {
            connection?.disconnect()
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
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
