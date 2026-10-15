package com.hilal.llm.rag

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hilal.llm.MainActivity
import org.junit.Rule
import org.junit.Test

class EmbeddingNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun comparisonIsReachedFromCompareAndNotRag() {
        compose.onNodeWithText("Compare").performClick()
        compose.onNodeWithText("Embedding sayfasını aç").performClick()
        compose.onNodeWithText("Embedding · Görsel arama").assertExists()
        compose.onNodeWithText("Metin → Metin").performClick()
        compose.onNodeWithText("Embedding karşılaştır").assertExists()
        compose.onNodeWithText("Karşılaştırmalara dön").performClick()
        compose.onNodeWithText("Embedding sayfasını aç").assertExists()
        compose.onNodeWithText("RAG").performClick()
        compose.onNodeWithText("İki embedding modelini karşılaştır").assertDoesNotExist()
    }
}
