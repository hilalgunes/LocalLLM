package com.hilal.llm.catalog.download

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Retains partial bytes on failure. Appends only after validating Content-Range. */
internal object ResumableModelDownload {
    suspend fun transfer(url: String, partial: File, onProgress: (Long, Long?) -> Unit): Long? {
        partial.parentFile?.mkdirs()
        val metadataFile = File(partial.path + ".resume")
        val metadata = Properties()
        if (metadataFile.isFile) runCatching { metadataFile.inputStream().use(metadata::load) }
        val sameUrl = metadata.getProperty("url") == url
        val validator = if (sameUrl) metadata.getProperty("validator") else null
        // Existing ONNX downloads use an immutable Hugging Face commit URL.
        val immutable = Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(url)
        val offset = if (partial.isFile && ((immutable && (!metadataFile.exists() || sameUrl)) || validator != null)) partial.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=$offset-")
                validator?.let { connection.setRequestProperty("If-Range", it) }
            }
            val status = connection.responseCode
            if (status == 416 && offset > 0) {
                val remoteSize = connection.getHeaderField("Content-Range")
                    ?.removePrefix("bytes */")?.toLongOrNull()
                if (remoteSize == offset) {
                    onProgress(offset, remoteSize)
                    return remoteSize
                }
            }
            check(status == 200 || status == 206) { "HTTP $status while downloading ${partial.name}" }
            val length = connection.contentLengthLong.takeIf { it >= 0 }
            val append = status == 206 && offset > 0
            val total = if (status == 206) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                    .matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                    ?: error("Invalid Content-Range; partial download was preserved")
                val (start, end, size) = range.destructured.toList().map(String::toLong)
                check(start == offset && end >= start && end < size) { "Unexpected download range" }
                check(length == null || length == end - start + 1) { "Inconsistent download length" }
                size
            } else length
            // Persist validation metadata before writing bytes, including after an If-Range fallback.
            val nextMetadata = Properties().apply {
                setProperty("url", url)
                val etag = connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                (etag ?: connection.getHeaderField("Last-Modified"))?.let { setProperty("validator", it) }
            }
            var received = if (append) offset else 0L
            onProgress(received, total)
            connection.inputStream.buffered().use { input ->
                FileOutputStream(partial, append).buffered().use { output ->
                    metadataFile.outputStream().use { nextMetadata.store(it, null) }
                    val buffer = ByteArray(64 * 1024)
                    var lastUpdate = System.nanoTime()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        received += read
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 200_000_000) {
                            onProgress(received, total)
                            lastUpdate = now
                        }
                    }
                }
            }
            check(total == null || received == total) { "Incomplete download: $received of $total bytes; retry to resume" }
            onProgress(received, total)
            return total
        } finally {
            connection.disconnect()
        }
    }
}
