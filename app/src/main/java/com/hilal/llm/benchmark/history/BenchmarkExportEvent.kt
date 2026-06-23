package com.hilal.llm.benchmark.history

import android.net.Uri

sealed class BenchmarkExportEvent {
    data class ShareFile(
        val uri: Uri,
        val mimeType: String,
        val fileName: String
    ) : BenchmarkExportEvent()

    data class Message(
        val text: String
    ) : BenchmarkExportEvent()

    data class Error(
        val text: String
    ) : BenchmarkExportEvent()
}
