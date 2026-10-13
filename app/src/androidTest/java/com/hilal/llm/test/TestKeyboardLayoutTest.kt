package com.hilal.llm.test

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.hilal.llm.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TestKeyboardLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun keyboardDoesNotLeaveUnusedSpaceBelowForm() {
        compose.onNodeWithText("Test").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performClick()
        var keyboardHeight = 0
        compose.waitUntil(10_000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                keyboardHeight = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            }
            keyboardHeight > 0
        }
        compose.waitForIdle()
        val listBottom = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot.bottom
        var keyboardTop = 0
        compose.runOnIdle {
            keyboardTop = compose.activity.window.decorView.height - keyboardHeight
        }
        val allowedGap = 40 * compose.activity.resources.displayMetrics.density
        assertTrue("Form bottom=$listBottom, keyboard top=$keyboardTop", keyboardTop - listBottom < allowedGap)
    }
}
