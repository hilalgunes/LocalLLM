package com.hilal.llm.chat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hilal.llm.catalog.ui.GenerationModelViewModel
import com.hilal.llm.inference.ModelSessionService
import com.hilal.llm.ui.theme.LLMTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ChatScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var owner: ChatViewModel

    @After fun close() { if (::owner.isInitialized) owner.chat.close() }

    @Test fun savedConversationCanBeRenamedOpenedAndDeleted() {
        val thread = ChatThread(title = "İlk konuşma", messages = listOf(
            ChatMessage(role = ChatRole.USER, text = "Merhaba"),
            ChatMessage(role = ChatRole.ASSISTANT, text = "Kayıtlı cevap")
        ))
        val store = object : ChatStore {
            var archive = ChatArchive(threads = listOf(thread), activeId = thread.id)
            override suspend fun load() = archive
            override suspend fun save(archive: ChatArchive) { this.archive = archive }
        }
        compose.setContent {
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val models = androidx.compose.runtime.remember { GenerationModelViewModel(application) }
            owner = androidx.compose.runtime.remember { ChatViewModel(store, ModelSessionService { error("No model needed for history navigation") }) }
            LLMTheme { ChatRoute(owner, models) }
        }
        compose.onNodeWithText("Kayıtlı cevap").assertIsDisplayed()
        compose.onNodeWithText("Model seç").performClick()
        compose.onNodeWithText("Sohbet modelleri").assertIsDisplayed()
        compose.onNodeWithText("Boş alan:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Kapat").performClick()
        compose.onNodeWithText("Geçmiş").performClick()
        compose.onNodeWithText("Adlandır").performClick()
        compose.onAllNodes(hasSetTextAction()).filter(hasText("İlk konuşma")).onFirst().performTextReplacement("Yeni ad")
        compose.onNodeWithText("Kaydet").performClick()
        compose.onNodeWithText("Yeni ad").assertExists()
        compose.onNodeWithText("Yeni", substring = false).performClick()
        compose.onNodeWithText("Ne hakkında konuşalım?").assertExists()
        compose.onNodeWithText("Geçmiş").performClick()
        compose.onNodeWithText("Yeni ad").performClick()
        compose.onNodeWithText("Kayıtlı cevap").assertIsDisplayed()
        compose.onNodeWithText("Geçmiş").performClick()
        compose.onNodeWithTag("delete-thread-${thread.id}").performClick()
        compose.onNodeWithText("Sohbet silinsin mi?").assertExists()
        compose.onNodeWithText("Sil", substring = false).performClick()
        compose.onNodeWithText("Kayıtlı cevap").assertDoesNotExist()
    }
}
