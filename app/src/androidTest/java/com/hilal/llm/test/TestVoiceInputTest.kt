package com.hilal.llm.test

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.core.app.ActivityOptionsCompat
import com.hilal.llm.MainActivity
import com.hilal.llm.benchmark.BenchmarkUiState
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.EngineType
import com.hilal.llm.catalog.PromptTemplateType
import com.hilal.llm.catalog.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TestVoiceInputTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recognizedQuestionIsSubmittedOnceAndCancellationDoesNotSubmit() {
        var question = ""
        val sent = mutableListOf<String>()
        var resultCode = Activity.RESULT_OK
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(compose.activity, input)
                assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
                assertEquals("tr-TR", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
                dispatchResult(requestCode, resultCode, Intent().putStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS, arrayListOf("  Yıllık izin kaç gün?  ")))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.runOnIdle {
            compose.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    TestScreen(
                        testState = TestUiState(),
                        benchmarkState = BenchmarkUiState(selectedCatalogModel = BenchmarkModelSelection(
                            "test", "Test model", RuntimeType.LLAMA_CPP, EngineType.LLAMA_CPP,
                            PromptTemplateType.GENERIC, "/unused", null)),
                        onQuestionChanged = { question = it },
                        onClassificationProviderSelected = {}, onRuntimeSelected = {},
                        onModelSelected = {}, onCancelDownload = {},
                        onSubmit = { sent.add(question) }
                    )
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Soruyu sesli söyle"))
        compose.onNodeWithContentDescription("Soruyu sesli söyle").performClick()
        compose.runOnIdle { assertEquals(listOf("Yıllık izin kaç gün?"), sent); resultCode = Activity.RESULT_CANCELED }
        compose.onNodeWithContentDescription("Soruyu sesli söyle").performClick()
        compose.runOnIdle { assertEquals(1, sent.size) }
    }
}
