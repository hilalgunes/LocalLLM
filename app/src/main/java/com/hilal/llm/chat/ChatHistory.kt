package com.hilal.llm.chat

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class ChatRole { USER, ASSISTANT }
enum class ReplyStatus { COMPLETE, GENERATING, INTERRUPTED, FAILED }
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val status: ReplyStatus = ReplyStatus.COMPLETE
)
data class ChatThread(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Yeni sohbet",
    val modelId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)
data class ChatArchive(val version: Int = 1, val threads: List<ChatThread> = emptyList(), val activeId: String? = null)

interface ChatStore {
    suspend fun load(): ChatArchive
    suspend fun save(archive: ChatArchive)
}

/** Atomic replacement preserves the last valid archive if a write is interrupted. */
class FileChatStore(private val file: File) : ChatStore {
    private val gson = Gson()
    override suspend fun load(): ChatArchive = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext ChatArchive()
        val archive = requireNotNull(gson.fromJson(file.readText(), ChatArchive::class.java)) { "Sohbet kaydı okunamadı." }
        require(archive.version == 1) { "Sohbet kaydı sürümü desteklenmiyor." }
        archive.copy(threads = archive.threads.map { thread ->
            thread.copy(messages = thread.messages.map { message ->
                if (message.status == ReplyStatus.GENERATING) message.copy(status = ReplyStatus.INTERRUPTED) else message
            })
        })
    }
    override suspend fun save(archive: ChatArchive) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.outputStream().use { stream ->
            stream.write(gson.toJson(archive).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}

/** Only completed replies are included; interrupted text remains visible in history. */
internal fun chatPrompt(messages: List<ChatMessage>, question: String): String {
    val history = messages.filter { it.status == ReplyStatus.COMPLETE }.takeLast(8)
        .joinToString("\n") { "${if (it.role == ChatRole.USER) "Kullanıcı" else "Asistan"}: ${it.text.take(2000)}" }
    return "Türkçe konuşan, açık ve yardımcı bir asistansın.\n\nSon konuşma:\n$history\n\nKullanıcının yeni mesajı:\n$question"
}
