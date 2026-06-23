package com.hilal.llm.catalog.download

sealed class DownloadState {
    data object Idle : DownloadState()

    data class Downloading(
        val fileName: String,
        val progress: Int,
        val downloadedBytes: Long,
        val totalBytes: Long?
    ) : DownloadState()

    data object Success : DownloadState()

    data class Failed(val message: String) : DownloadState()
}
