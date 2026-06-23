package com.hilal.llm.catalog

import android.content.Intent
import android.os.Build
import java.io.Serializable

data class BenchmarkModelSelection(
    val modelId: String,
    val displayName: String,
    val runtimeType: RuntimeType,
    val engineType: EngineType,
    val promptTemplateType: PromptTemplateType,
    val modelPath: String,
    val tokenizerPath: String?
) : Serializable {
    fun toIntent(): Intent {
        return Intent().putExtra(EXTRA_KEY, this)
    }

    companion object {
        private const val EXTRA_KEY = "extra_benchmark_model_selection"

        fun fromIntent(intent: Intent?): BenchmarkModelSelection? {
            if (intent == null) return null
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(EXTRA_KEY, BenchmarkModelSelection::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(EXTRA_KEY) as? BenchmarkModelSelection
            }
        }

        fun putInto(intent: Intent, selection: BenchmarkModelSelection): Intent {
            return intent.putExtra(EXTRA_KEY, selection)
        }
    }
}
