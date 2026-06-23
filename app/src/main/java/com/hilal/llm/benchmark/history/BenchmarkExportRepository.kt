package com.hilal.llm.benchmark.history

import android.content.Context
import com.hilal.llm.benchmark.BenchmarkResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BenchmarkExportRepository(
    context: Context
) {
    private val exportsDir = File(context.cacheDir, EXPORT_DIR_NAME)

    suspend fun exportAsCsv(results: List<BenchmarkResult>): File = withContext(Dispatchers.IO) {
        ensureExportsDir()
        val file = File(exportsDir, "benchmark_results_${System.currentTimeMillis()}.csv")
        file.writeText(buildCsv(results))
        file
    }

    suspend fun exportAsJson(results: List<BenchmarkResult>): File = withContext(Dispatchers.IO) {
        ensureExportsDir()
        val file = File(exportsDir, "benchmark_results_${System.currentTimeMillis()}.json")
        val array = JSONArray()
        results.forEach { array.put(it.toExportJsonObject()) }
        file.writeText(array.toString(2))
        file
    }

    private fun ensureExportsDir() {
        if (!exportsDir.exists()) {
            exportsDir.mkdirs()
        }
    }

    private fun buildCsv(results: List<BenchmarkResult>): String {
        val columns = listOf(
            "createdAt",
            "modelName",
            "modelId",
            "runtimeType",
            "engineType",
            "promptTemplateType",
            "presetTitle",
            "source",
            "maxTokens",
            "firstTokenTimeMs",
            "totalTimeMs",
            "tokenCount",
            "tokensPerSecond",
            "nativeRamMb",
            "javaRamMb",
            "totalRamMb",
            "responseQuality",
            "qualityNote",
            "prompt",
            "response"
        )

        return buildString {
            appendLine(columns.joinToString(",") { csvEscape(it) })
            results.forEach { result ->
                val values = listOf(
                    result.createdAt.toString(),
                    result.modelName,
                    result.modelId.orEmpty(),
                    result.runtimeType?.name.orEmpty(),
                    result.engineType?.name.orEmpty(),
                    result.promptTemplateType?.name.orEmpty(),
                    result.presetTitle.orEmpty(),
                    result.selectionSource.name,
                    result.maxTokens.toString(),
                    result.firstTokenTimeMs?.toString().orEmpty(),
                    result.totalTimeMs?.toString().orEmpty(),
                    result.tokenCount.toString(),
                    result.tokensPerSecond?.toString().orEmpty(),
                    result.nativeRamMb?.toString().orEmpty(),
                    result.javaRamMb?.toString().orEmpty(),
                    result.totalRamMb?.toString().orEmpty(),
                    result.responseQuality.name,
                    result.qualityNote.orEmpty(),
                    result.prompt,
                    result.response
                )
                appendLine(values.joinToString(",") { csvEscape(it) })
            }
        }
    }

    private fun csvEscape(value: String): String {
        val mustQuote = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        val escaped = value.replace("\"", "\"\"")
        return if (mustQuote) "\"$escaped\"" else escaped
    }

    private fun BenchmarkResult.toExportJsonObject(): JSONObject {
        return JSONObject().apply {
            put("createdAt", createdAt)
            put("modelName", modelName)
            put("modelId", modelId ?: JSONObject.NULL)
            put("runtimeType", runtimeType?.name ?: JSONObject.NULL)
            put("engineType", engineType?.name ?: JSONObject.NULL)
            put("promptTemplateType", promptTemplateType?.name ?: JSONObject.NULL)
            put("presetTitle", presetTitle ?: JSONObject.NULL)
            put("source", selectionSource.name)
            put("maxTokens", maxTokens)
            put("firstTokenTimeMs", firstTokenTimeMs ?: JSONObject.NULL)
            put("totalTimeMs", totalTimeMs ?: JSONObject.NULL)
            put("tokenCount", tokenCount)
            put("tokensPerSecond", tokensPerSecond ?: JSONObject.NULL)
            put("nativeRamMb", nativeRamMb ?: JSONObject.NULL)
            put("javaRamMb", javaRamMb ?: JSONObject.NULL)
            put("totalRamMb", totalRamMb ?: JSONObject.NULL)
            put("responseQuality", responseQuality.name)
            put("qualityNote", qualityNote ?: JSONObject.NULL)
            put("prompt", prompt)
            put("response", response)
            put("errorMessage", errorMessage ?: JSONObject.NULL)
        }
    }

    private companion object {
        private const val EXPORT_DIR_NAME = "exports"
    }
}
