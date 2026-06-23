package com.hilal.llm.benchmark.history

import android.content.Context
import com.hilal.llm.benchmark.BenchmarkResult
import com.hilal.llm.benchmark.SelectionSource
import com.hilal.llm.benchmark.ResponseQuality
import com.hilal.llm.catalog.EngineType as CatalogEngineType
import com.hilal.llm.catalog.PromptTemplateType
import com.hilal.llm.catalog.RuntimeType
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BenchmarkResultRepository(
    context: Context
) {
    private val resultsFile = File(context.filesDir, FILE_NAME)

    fun loadResults(): List<BenchmarkResult> {
        if (!resultsFile.exists()) return emptyList()
        val content = resultsFile.readText()
        if (content.isBlank()) return emptyList()

        val array = JSONArray(content)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(item.toBenchmarkResult())
            }
        }
    }

    fun saveResult(result: BenchmarkResult) {
        val updated = listOf(result) + loadResults()
        saveResults(updated)
    }

    suspend fun updateQuality(resultId: String, quality: ResponseQuality, note: String?) {
        withContext(Dispatchers.IO) {
            val updated = loadResults().map { result ->
                if (result.resultId == resultId) {
                    result.copy(
                        responseQuality = quality,
                        qualityNote = note?.takeIf { it.isNotBlank() }
                    )
                } else {
                    result
                }
            }
            saveResults(updated)
        }
    }

    fun saveResults(results: List<BenchmarkResult>) {
        val array = JSONArray()
        results.forEach { array.put(it.toJsonObject()) }
        resultsFile.writeText(array.toString())
    }

    fun clear(): Boolean {
        return if (resultsFile.exists()) resultsFile.delete() else true
    }

    private fun BenchmarkResult.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("engineName", engineName)
            put("modelId", modelId ?: JSONObject.NULL)
            put("modelName", modelName)
            put("modelFilePath", modelFilePath)
            put("maxTokens", maxTokens)
            put("prompt", prompt)
            put("response", response)
            put("firstTokenTimeMs", firstTokenTimeMs ?: JSONObject.NULL)
            put("totalTimeMs", totalTimeMs ?: JSONObject.NULL)
            put("tokenCount", tokenCount)
            put("tokensPerSecond", tokensPerSecond ?: JSONObject.NULL)
            put("errorMessage", errorMessage ?: JSONObject.NULL)
            put("selectionSource", selectionSource.name)
            put("presetId", presetId ?: JSONObject.NULL)
            put("presetTitle", presetTitle ?: JSONObject.NULL)
            put("resultId", resultId)
            put("responseQuality", responseQuality.name)
            put("qualityNote", qualityNote ?: JSONObject.NULL)
            put("runtimeType", runtimeType?.name ?: JSONObject.NULL)
            put("engineType", engineType?.name ?: JSONObject.NULL)
            put("promptTemplateType", promptTemplateType?.name ?: JSONObject.NULL)
            put("totalRamMb", totalRamMb ?: JSONObject.NULL)
            put("nativeRamMb", nativeRamMb ?: JSONObject.NULL)
            put("javaRamMb", javaRamMb ?: JSONObject.NULL)
            put("createdAt", createdAt)
        }
    }

    private fun JSONObject.toBenchmarkResult(): BenchmarkResult {
        return BenchmarkResult(
            engineName = optString("engineName", ""),
            modelId = if (has("modelId") && !isNull("modelId")) optString("modelId") else null,
            modelName = optString("modelName", ""),
            modelFilePath = optString("modelFilePath", ""),
            maxTokens = optInt("maxTokens", 512),
            prompt = optString("prompt", ""),
            response = optString("response", ""),
            firstTokenTimeMs = optLongOrNull("firstTokenTimeMs"),
            totalTimeMs = optLongOrNull("totalTimeMs"),
            tokenCount = optInt("tokenCount", 0),
            tokensPerSecond = optDoubleOrNull("tokensPerSecond"),
            errorMessage = if (has("errorMessage") && !isNull("errorMessage")) {
                optString("errorMessage")
            } else {
                null
            },
            selectionSource = runCatching {
                SelectionSource.valueOf(optString("selectionSource", SelectionSource.MANUAL.name))
            }.getOrDefault(SelectionSource.MANUAL),
            presetId = if (has("presetId") && !isNull("presetId")) optString("presetId") else null,
            presetTitle = if (has("presetTitle") && !isNull("presetTitle")) optString("presetTitle") else null,
            resultId = if (has("resultId") && !isNull("resultId")) optString("resultId") else optLong("createdAt", System.currentTimeMillis()).toString(),
            responseQuality = runCatching {
                ResponseQuality.valueOf(optString("responseQuality", ResponseQuality.UNKNOWN.name))
            }.getOrDefault(ResponseQuality.UNKNOWN),
            qualityNote = if (has("qualityNote") && !isNull("qualityNote")) optString("qualityNote") else null,
            runtimeType = optEnumOrNull<RuntimeType>("runtimeType"),
            engineType = optEnumOrNull<CatalogEngineType>("engineType"),
            promptTemplateType = optEnumOrNull<PromptTemplateType>("promptTemplateType"),
            totalRamMb = optDoubleOrNull("totalRamMb"),
            nativeRamMb = optDoubleOrNull("nativeRamMb"),
            javaRamMb = optDoubleOrNull("javaRamMb"),
            createdAt = optLong("createdAt", System.currentTimeMillis())
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key)
    }

    private inline fun <reified T : Enum<T>> JSONObject.optEnumOrNull(key: String): T? {
        if (!has(key) || isNull(key)) return null
        return runCatching { enumValueOf<T>(optString(key)) }.getOrNull()
    }

    private companion object {
        private const val FILE_NAME = "benchmark_results.json"
    }
}
