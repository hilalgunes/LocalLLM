package com.hilal.llm.test

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.hilal.llm.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Network smoke test: real upstream graphs, normalization, WAV and answer bridge. */
class EmaTtsSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun generatesTurkishAudioInWebView() {
        compose.onNodeWithText("Test").performClick()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), "test-chat-preview.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Sesli yanıtı aç"))
        compose.onNodeWithContentDescription("Sesli yanıtı aç").performClick()
        lateinit var web: WebView
        compose.runOnIdle { web = requireNotNull(findWebView(compose.activity.window.decorView)) }
        val deadline = System.currentTimeMillis() + 240_000
        while (evaluate(web, "typeof window.setAnswer === 'function'") != "true") {
            check(System.currentTimeMillis() < deadline) { "EMA page did not load" }
            Thread.sleep(200)
        }
        evaluate(web, "window.setAnswer(\"Merhaba, bugün birlikte Türkçe ses\", true, false); true")
        while (evaluate(web, "document.getElementById('status').textContent.includes('Ses çalıyor')") != "true") {
            check(System.currentTimeMillis() < deadline) { "Partial answer did not start playback" }
            Thread.sleep(200)
        }
        assertTrue("Replay must wait for final answer", evaluate(web, "document.getElementById('audio').getAttribute('src') === null") == "true")
        evaluate(web, "window.setAnswer(\"Merhaba, bugün birlikte Türkçe ses üretimini deniyoruz.\", true); true")
        var result: String
        do {
            Thread.sleep(1000)
            result = evaluate(web, "JSON.stringify({status:document.getElementById('status').textContent,error:document.getElementById('error').textContent,src:document.getElementById('audio').getAttribute('src')})")
            check(System.currentTimeMillis() < deadline) { "EMA timeout: $result" }
        } while (!result.contains("blob:") && !result.contains("Seslendirme başarısız"))
        assertTrue("EMA did not generate WAV: $result", result.contains("blob:"))
        while (evaluate(web, "document.getElementById('status').textContent.includes('Ses çalıyor')") != "true") {
            check(System.currentTimeMillis() < deadline) { "EMA audio did not start automatically" }
            Thread.sleep(100)
        }
        val audioSource = evaluate(web, "document.getElementById('audio').getAttribute('src')")
        evaluate(web, "window.setAnswer(\"Merhaba, bugün birlikte Türkçe ses üretimini deniyoruz.\", true); true")
        assertTrue("Repeated state must not restart speech", audioSource == evaluate(web, "document.getElementById('audio').getAttribute('src')"))
        evaluate(web, "document.getElementById('stop').click(); true")
        assertTrue(evaluate(web, "document.getElementById('audio').getAttribute('src') === null") == "true")
    }

    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        compose.activity.runOnUiThread { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        check(latch.await(10, TimeUnit.SECONDS)) { "WebView JavaScript timed out" }
        return result
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
