package com.hilal.llm.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hilal.llm.inference.ModelSessionService
import java.io.File

class ChatViewModel(store: ChatStore, private val sessions: ModelSessionService) : ViewModel() {
    val chat = ChatController(viewModelScope, store, sessions::openSession)
    suspend fun withReleasedRuntime(block: suspend () -> Unit) = sessions.withColdRuntime(block)
    override fun onCleared() { chat.close(); super.onCleared() }

    class Factory(private val filesDir: File, private val sessions: ModelSessionService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java))
            return ChatViewModel(FileChatStore(File(filesDir, "chat-history.json")), sessions) as T
        }
    }
}
