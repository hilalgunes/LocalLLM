package com.hilal.llm.chat

import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.inference.FeatureGenerationSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChatUiState(
    val archive: ChatArchive = ChatArchive(),
    val loading: Boolean = true,
    val running: Boolean = false,
    val stopping: Boolean = false,
    val draft: String = "",
    val error: String? = null,
    val storageError: String? = null
) {
    val activeThread: ChatThread? get() = archive.threads.firstOrNull { it.id == archive.activeId }
}

class ChatController(
    private val scope: CoroutineScope,
    private val store: ChatStore,
    private val openSession: suspend (BenchmarkModelSelection) -> FeatureGenerationSession
) {
    private val state = MutableStateFlow(ChatUiState())
    val uiState = state.asStateFlow()
    private var generation: Job? = null
    private val writes = Channel<ChatArchive>(Channel.UNLIMITED)

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = persistenceScope.launch {
        try {
            for (archive in writes) {
                try { withContext(NonCancellable) { store.save(archive) } }
                catch (error: Exception) { state.update { it.copy(storageError = "Sohbet kaydedilemedi: ${error.message}") } }
            }
        } finally { persistenceScope.cancel() }
    }

    init {
        scope.launch {
            try {
                val loaded = store.load()
                val archive = if (loaded.threads.isEmpty()) {
                    val thread = ChatThread()
                    loaded.copy(threads = listOf(thread), activeId = thread.id)
                } else loaded.copy(activeId = loaded.activeId?.takeIf { id -> loaded.threads.any { it.id == id } } ?: loaded.threads.first().id)
                state.update { it.copy(archive = archive, loading = false) }
            } catch (error: Exception) {
                // Keep the original file untouched; do not silently replace corrupt history.
                state.update { it.copy(loading = false, storageError = "Sohbet geçmişi açılamadı: ${error.message}") }
            }
        }
    }

    fun close() {
        val active = generation
        active?.cancel()
        if (active == null) writes.close() else active.invokeOnCompletion { writes.close() }
    }

    suspend fun awaitSaved() { writer.join() }

    fun updateDraft(text: String) { state.update { it.copy(draft = text) } }
    fun selectModel(id: String) {
        if (state.value.running || state.value.loading || state.value.activeThread == null) return
        updateThread { it.copy(modelId = id) }
        persist()
    }
    fun newThread() {
        if (state.value.running || state.value.loading || state.value.activeThread == null) return
        val thread = ChatThread(modelId = state.value.activeThread?.modelId)
        state.update { it.copy(archive = it.archive.copy(threads = listOf(thread) + it.archive.threads, activeId = thread.id), draft = "", error = null) }
        persist()
    }
    fun selectThread(id: String) {
        if (state.value.running || state.value.loading || state.value.archive.threads.none { it.id == id }) return
        state.update { it.copy(archive = it.archive.copy(activeId = id), draft = "", error = null) }
        persist()
    }
    fun renameThread(id: String, title: String) {
        if (state.value.running || title.isBlank()) return
        state.update { it.copy(archive = it.archive.copy(threads = it.archive.threads.map { thread ->
            if (thread.id == id) thread.copy(title = title.trim().take(80)) else thread
        })) }
        persist()
    }
    fun deleteThread(id: String) {
        if (state.value.running) return
        state.update { current ->
            val remaining = current.archive.threads.filterNot { it.id == id }.ifEmpty { listOf(ChatThread()) }
            current.copy(archive = current.archive.copy(threads = remaining, activeId = current.archive.activeId?.takeIf { active -> remaining.any { it.id == active } } ?: remaining.first().id), draft = "", error = null)
        }
        persist()
    }
    fun stop() {
        if (!state.value.running) return
        state.update { it.copy(stopping = true) }
        generation?.cancel()
    }
    fun send(selection: BenchmarkModelSelection?) = start(selection, retry = false)
    fun retry(selection: BenchmarkModelSelection?) = start(selection, retry = true)

    private fun start(selection: BenchmarkModelSelection?, retry: Boolean) {
        val current = state.value
        val thread = current.activeThread ?: return
        if (current.running || current.loading) return
        if (selection == null || selection.modelId != thread.modelId) {
            state.update { it.copy(error = "Önce bir model indirip seçin.") }; return
        }
        val lastUser = thread.messages.indexOfLast { it.role == ChatRole.USER }
        val prompt = if (retry) thread.messages.getOrNull(lastUser)?.text.orEmpty() else current.draft.trim()
        if (prompt.isBlank()) return
        val previous = if (retry) thread.messages.take(lastUser) else thread.messages
        val reply = ChatMessage(role = ChatRole.ASSISTANT, text = "", status = ReplyStatus.GENERATING)
        val user = if (retry) thread.messages[lastUser] else ChatMessage(role = ChatRole.USER, text = prompt)
        updateThread { it.copy(
            title = if (it.messages.isEmpty() && it.title == "Yeni sohbet") prompt.take(60) else it.title,
            messages = previous + user + reply
        ) }
        state.update { it.copy(running = true, stopping = false, draft = if (retry) it.draft else "", error = null) }
        persist()
        generation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var session: FeatureGenerationSession? = null
            var lastSaved = System.nanoTime()
            try {
                session = openSession(selection)
                val answer = session.generate(chatPrompt(previous, prompt), 512) { text ->
                    updateReply(reply.id, text, ReplyStatus.GENERATING)
                    // Bounded checkpoints retain partial replies across process termination.
                    if (System.nanoTime() - lastSaved > 1_000_000_000) { persist(); lastSaved = System.nanoTime() }
                }
                ensureActive()
                updateReply(reply.id, answer, ReplyStatus.COMPLETE)
            } catch (_: CancellationException) {
                updateReply(reply.id, status = ReplyStatus.INTERRUPTED)
            } catch (error: Exception) {
                updateReply(reply.id, status = ReplyStatus.FAILED)
                state.update { it.copy(error = error.message ?: "Yanıt oluşturulamadı.") }
            } finally {
                try { withContext(NonCancellable) { session?.close() } }
                catch (error: Exception) { state.update { it.copy(error = error.message ?: "Oturum kapatılamadı.") } }
                state.update { it.copy(running = false, stopping = false) }
                persist()
            }
        }
    }
    private fun updateReply(id: String, text: String? = null, status: ReplyStatus) {
        updateThread { thread -> thread.copy(messages = thread.messages.map {
            if (it.id == id) it.copy(text = text ?: it.text, status = status) else it
        }) }
    }
    private fun updateThread(change: (ChatThread) -> ChatThread) {
        state.update { current -> current.copy(archive = current.archive.copy(threads = current.archive.threads.map {
            if (it.id == current.archive.activeId) change(it).copy(updatedAt = System.currentTimeMillis()) else it
        })) }
    }
    private fun persist() { writes.trySend(state.value.archive) }
}
