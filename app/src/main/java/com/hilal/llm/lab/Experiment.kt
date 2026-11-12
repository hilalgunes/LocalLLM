package com.hilal.llm.lab

import com.google.gson.GsonBuilder
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.RuntimeType
import com.hilal.llm.inference.FeatureGenerationSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class ExperimentDefinition(
    val modelId: String, val runtime: String, val prompt: String, val maxTokens: Int,
    val mode: String, val modelRevision: String? = null, val temperature: Double? = null,
    val chunkSize: Int? = null, val chunkOverlap: Int? = null, val topK: Int? = null
)
data class ExperimentMetrics(val preparationMs: Double, val generationMs: Double, val firstTokenMs: Double?, val totalMs: Double)
data class ExperimentRecord(
    val schemaVersion: Int = 1, val id: String = UUID.randomUUID().toString(),
    val createdAtEpochMs: Long = System.currentTimeMillis(), val platform: String = "android",
    val definition: ExperimentDefinition, val metrics: ExperimentMetrics,
    val status: String, val output: String, val error: String? = null
)

class ExperimentRunner(
    private val openSession: suspend (BenchmarkModelSelection, Boolean) -> FeatureGenerationSession,
    private val clock: () -> Long = System::nanoTime
) {
    suspend fun run(selection: BenchmarkModelSelection, definition: ExperimentDefinition, onText: (String) -> Unit): ExperimentRecord {
        require(definition.modelId == selection.modelId && definition.runtime == selection.runtimeType.name) { "Deney modeli seçili modelle uyuşmuyor." }
        require(definition.prompt.isNotBlank() && definition.maxTokens in 1..2048) { "Soru ve token sınırını kontrol edin." }
        require(definition.mode in setOf("cold", "warm")) { "Geçersiz ölçüm modu." }
        require(definition.mode != "warm" || selection.runtimeType == RuntimeType.LITERT) { "Sıcak oturum ölçümü Android’de LiteRT modelleri için destekleniyor." }
        var session: FeatureGenerationSession? = null
        var output = ""
        var status = "complete"
        var error: String? = null
        var start = clock()
        var prepared: Long? = null
        var first: Double? = null
        var end = start
        try {
            if (definition.mode == "warm") {
                session = openSession(selection, true)
                session.generate(definition.prompt, definition.maxTokens) {}
                withContext(NonCancellable) { session.close() }
                session = null
            }
            start = clock()
            session = openSession(selection, definition.mode == "cold")
            prepared = clock()
            output = session.generate(definition.prompt, definition.maxTokens) { text ->
                if (first == null && text.isNotEmpty()) first = (clock() - prepared) / 1_000_000.0
                output = text
                onText(text)
            }
        } catch (cancelled: CancellationException) { status = "cancelled" }
        catch (failure: Exception) { status = "failed"; error = failure.message }
        finally { end = clock(); withContext(NonCancellable) { session?.close() } }
        return ExperimentRecord(definition = definition, status = status, output = output, error = error,
            metrics = ExperimentMetrics(((prepared ?: end) - start) / 1_000_000.0,
                prepared?.let { (end - it) / 1_000_000.0 } ?: 0.0, first, (end - start) / 1_000_000.0))
    }
}

class ExperimentStore(private val file: File) {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    @Synchronized fun load(): List<ExperimentRecord> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        val tree = com.google.gson.JsonParser.parseString(text)
        require(tree.isJsonArray) { "Deney listesi okunamadı." }
        tree.asJsonArray.forEach { element ->
            require(element.isJsonObject) { "Geçersiz deney kaydı." }
            val item = element.asJsonObject
            require(listOf("schemaVersion", "id", "createdAtEpochMs", "platform", "definition", "metrics", "status", "output").all { item.has(it) && !item[it].isJsonNull }) { "Eksik deney kaydı." }
            require(item["definition"].isJsonObject && item["metrics"].isJsonObject) { "Deney ayarları okunamadı." }
            require(listOf("modelId", "runtime", "prompt", "maxTokens", "mode").all { item["definition"].asJsonObject.has(it) && !item["definition"].asJsonObject[it].isJsonNull }) { "Eksik deney ayarları." }
        }
        val records = requireNotNull(gson.fromJson(text, Array<ExperimentRecord>::class.java)) { "Deney kaydı okunamadı." }.toList()
        require(records.all { it.schemaVersion == 1 }) { "Desteklenmeyen deney kayıt sürümü." }
        return records
    }
    @Synchronized fun append(record: ExperimentRecord): List<ExperimentRecord> {
        val records = listOf(record) + load()
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(gson.toJson(records))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return records
    }
}
