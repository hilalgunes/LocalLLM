package com.hilal.llm.benchmark

import ai.onnxruntime.genai.Config
import ai.onnxruntime.genai.GenAI
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the packaged Android JNI/API pair without downloading 8 GB of weights. */
@RunWith(AndroidJUnit4::class)
class OnnxNativeSmokeTest {
    @Test
    fun loadsNativeRuntimeAndParsesGemmaConfiguration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "onnx-native-smoke")
        directory.mkdirs()
        val configFile = File(directory, "genai_config.json")
        try {
            instrumentation.context.assets.open("onnx-smoke/genai_config.json").use { input ->
                configFile.outputStream().use { input.copyTo(it) }
            }
            GenAI.setTelemetry(false)
            Config(directory.absolutePath).use { config ->
                config.clearProviders()
            }
        } finally {
            configFile.delete()
            directory.delete()
        }
    }
}
