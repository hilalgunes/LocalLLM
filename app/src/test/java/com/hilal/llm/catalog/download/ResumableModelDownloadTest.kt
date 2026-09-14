package com.hilal.llm.catalog.download

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ResumableModelDownloadTest {
    private fun checkTransfer(status: Int, contentRange: String?, body: String, expected: String?, expectedRange: String = "bytes=3-") {
        val root = Files.createTempDirectory("model-resume").toFile()
        val partial = File(root, "model.tmp").apply { writeText("abc") }
        var range: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            range = exchange.requestHeaders.getFirst("Range")
            contentRange?.let { exchange.responseHeaders.add("Content-Range", it) }
            exchange.sendResponseHeaders(status, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/resolve/${"a".repeat(40)}/model"
            val result = runCatching { runBlocking { ResumableModelDownload.transfer(url, partial) { _, _ -> } } }
            assertEquals(expectedRange, range)
            if (expected == null) {
                assertTrue(result.isFailure)
                assertEquals("abc", partial.readText())
            } else {
                result.getOrThrow()
                assertEquals(expected, partial.readText())
            }
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }

    @Test fun resumesImmutablePartialWithValidatedRange() = checkTransfer(206, "bytes 3-5/6", "def", "abcdef")
    @Test fun restartsSafelyWhenServerIgnoresRange() = checkTransfer(200, null, "abcdef", "abcdef")
    @Test fun rejectsWrongRangeWithoutTouchingPartial() = checkTransfer(206, "bytes 2-4/6", "def", null)
    @Test fun retainsPartialOnHttpFailure() = checkTransfer(503, null, "busy", null)
}
