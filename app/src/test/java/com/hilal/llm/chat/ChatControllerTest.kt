package com.hilal.llm.chat

import com.hilal.llm.catalog.*
import com.hilal.llm.inference.FeatureGenerationSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ChatControllerTest {
    private val selection = BenchmarkModelSelection("model", "Model", RuntimeType.LLAMA_CPP, EngineType.LLAMA_CPP, PromptTemplateType.GENERIC, "/model.gguf", null)
    private class MemoryStore : ChatStore {
        @Volatile var archive = ChatArchive()
        override suspend fun load() = archive
        override suspend fun save(archive: ChatArchive) { this.archive = archive }
    }
    private class FakeSession : FeatureGenerationSession {
        var wait = false
        var fail = false
        var closed = false
        val prompts = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        override suspend fun generate(prompt: String, maxTokens: Int, onText: (String) -> Unit): String {
            prompts += prompt
            onText("Kısmi")
            started.complete(Unit)
            if (wait) awaitCancellation()
            if (fail) error("Model hatası")
            onText("Tam yanıt")
            return "Tam yanıt"
        }
        override suspend fun close() { closed = true }
    }
    private suspend fun ChatController.ready() = withTimeout(2000) { uiState.first { !it.loading } }
    private suspend fun ChatController.finished() = withTimeout(2000) { uiState.first { !it.running } }

    @Test fun conversationPersistsAndRetryDoesNotDuplicateUser() = runBlocking<Unit> {
        val store = MemoryStore()
        val session = FakeSession()
        val chat = ChatController(this, store) { session }
        chat.ready(); chat.selectModel(selection.modelId); chat.updateDraft("İlk soru"); chat.send(selection); chat.finished()
        chat.retry(selection); chat.finished()
        assertEquals(2, chat.uiState.value.activeThread!!.messages.size)
        chat.updateDraft("Devam"); chat.send(selection); chat.finished()
        assertTrue(session.prompts.last().contains("İlk soru"))
        assertTrue(session.prompts.last().contains("Tam yanıt"))
        val active = chat.uiState.value.archive.activeId
        chat.close(); chat.awaitSaved()
        val restored = ChatController(this, store) { FakeSession() }
        restored.ready()
        assertEquals(active, restored.uiState.value.archive.activeId)
        assertEquals(4, restored.uiState.value.activeThread!!.messages.size)
        restored.close(); restored.awaitSaved()
    }

    @Test fun cancellationKeepsPartialReplyAndReleasesSession() = runBlocking<Unit> {
        val store = MemoryStore()
        val session = FakeSession().apply { wait = true }
        val chat = ChatController(this, store) { session }
        chat.ready(); chat.selectModel(selection.modelId); chat.updateDraft("Soru"); chat.send(selection)
        session.started.await(); chat.stop(); chat.finished()
        val message = chat.uiState.value.activeThread!!.messages.last()
        assertEquals("Kısmi", message.text)
        assertEquals(ReplyStatus.INTERRUPTED, message.status)
        assertTrue(session.closed)
        assertNull(chat.uiState.value.error)
        chat.close(); chat.awaitSaved()
        assertEquals(ReplyStatus.INTERRUPTED, store.archive.threads.first().messages.last().status)
    }

    @Test fun failedReplyCanBeRetriedWithoutDuplicatingQuestion() = runBlocking<Unit> {
        val session = FakeSession().apply { fail = true }
        val chat = ChatController(this, MemoryStore()) { session }
        chat.ready(); chat.selectModel(selection.modelId); chat.updateDraft("Soru"); chat.send(selection); chat.finished()
        assertEquals(ReplyStatus.FAILED, chat.uiState.value.activeThread!!.messages.last().status)
        session.fail = false
        chat.retry(selection); chat.finished()
        assertEquals(2, chat.uiState.value.activeThread!!.messages.size)
        assertNull(chat.uiState.value.error)
        chat.close(); chat.awaitSaved()
    }

    @Test fun threadSelectionRenameAndDeleteAreSaved() = runBlocking<Unit> {
        val store = MemoryStore()
        val chat = ChatController(this, store) { FakeSession() }
        chat.ready(); chat.selectModel(selection.modelId)
        val first = chat.uiState.value.archive.activeId!!
        chat.newThread(); val second = chat.uiState.value.archive.activeId!!
        chat.renameThread(first, "İş")
        chat.selectThread(first)
        assertEquals("İş", chat.uiState.value.activeThread!!.title)
        chat.deleteThread(first)
        assertEquals(second, chat.uiState.value.archive.activeId)
        chat.close(); chat.awaitSaved()
        assertEquals(1, store.archive.threads.size)
        assertEquals(selection.modelId, store.archive.threads.first().modelId)
    }

    @Test fun fileRoundTripRecoversInterruptedGeneration() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("llm-chat-test").toFile()
        try {
            val store = FileChatStore(directory.resolve("history.json"))
            val thread = ChatThread(modelId = "model", messages = listOf(ChatMessage(role = ChatRole.ASSISTANT, text = "Kısmi", status = ReplyStatus.GENERATING)))
            store.save(ChatArchive(threads = listOf(thread), activeId = thread.id))
            val loaded = store.load()
            assertEquals(thread.id, loaded.activeId)
            assertEquals(ReplyStatus.INTERRUPTED, loaded.threads.first().messages.first().status)
            assertEquals("Kısmi", loaded.threads.first().messages.first().text)
        } finally { directory.deleteRecursively() }
    }

    @Test fun unreadableHistoryIsNotOverwritten() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("llm-chat-bad").toFile()
        try {
            val file = directory.resolve("history.json").apply { writeText("not json") }
            val chat = ChatController(this, FileChatStore(file)) { FakeSession() }
            chat.ready(); chat.newThread(); chat.selectModel("model"); chat.close(); chat.awaitSaved()
            assertNotNull(chat.uiState.value.storageError)
            assertEquals("not json", file.readText())
        } finally { directory.deleteRecursively() }
    }
}
