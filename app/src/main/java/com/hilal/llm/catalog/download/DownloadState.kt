package com.hilal.llm.catalog.download

sealed class DownloadState {
    data object Idle : DownloadState()

    data class Downloading(
        val fileName: String,
        val progress: Int,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val fileIndex: Int = 1,
        val fileCount: Int = 1
    ) : DownloadState() {
        val progressLabel: String
            get() = "Dosya $fileIndex/$fileCount • %$progress"
    }

    data object Success : DownloadState()

    data class Failed(val message: String) : DownloadState()
}
