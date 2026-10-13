package com.hilal.llm.test

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/** The upstream WASM pipeline includes Turkish number/date normalization. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun EmaTtsCard(answer: String, answerReady: Boolean, autoRead: Boolean = false) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (answer.isBlank()) 0.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(if (answer.isBlank()) 1.dp else 128.dp),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val uri = request.url
                                if (uri.host != "appassets.androidplatform.net") return null
                                val name = uri.path?.removePrefix("/ema/").orEmpty()
                                if (uri.scheme != "https" || uri.path != "/ema/$name" || name !in setOf("index.html", "tts.js", "normalizer.js", "player.js")) {
                                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
                                }
                                val mime = if (name.endsWith(".js")) "text/javascript" else "text/html"
                                return WebResourceResponse(mime, "UTF-8", context.assets.open("ema/$name"))
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                val state = view.tag as? Triple<*, *, *> ?: return
                                view.evaluateJavascript("window.setAnswer?.(${JSONObject.quote(state.first as String)}, ${state.second}, ${state.third})", null)
                            }
                        }
                        loadUrl("https://appassets.androidplatform.net/ema/index.html")
                    }
                },
                update = { view ->
                    val text = answer
                    view.tag = Triple(text, autoRead, answerReady)
                    view.evaluateJavascript("window.setAnswer?.(${JSONObject.quote(text)}, $autoRead, $answerReady)", null)
                },
                onRelease = { view ->
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                }
            )
        }
    }
}
