package com.hilal.llm.rag

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import com.hilal.llm.benchmark.*
import com.hilal.llm.catalog.*
import com.hilal.llm.catalog.download.ModelDownloadRepository
import com.hilal.llm.catalog.runtime.ModelRunFileMapper
import com.hilal.llm.catalog.storage.ModelStorageResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

internal data class ComparisonUiState(
    val modelA: String = "embeddinggemma-2-text-270m-litert",
    val modelB: String = "embeddinggemma-2-740m-litert",
    val generationId: String? = null, val includeAnswers: Boolean = false,
    val passages: String = defaultComparisonPassages, val queries: String = defaultComparisonQueries,
    val repeats: Int = 3, val topK: Int = 3, val dimension: Int = 768,
    val busy: Boolean = false, val progress: String = "", val error: String? = null,
    val downloaded: Set<String> = emptySet(), val report: ComparisonReport? = null
)

internal class RagComparisonViewModel(app: Application) : AndroidViewModel(app) {
    val models = ModelCatalogRepository.getAvailableModels()
    private val storage = ModelStorageResolver(app)
    private val downloads = ModelDownloadRepository(storage)
    private val mapper = ModelRunFileMapper(storage)
    private val _state = MutableStateFlow(ComparisonUiState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    init {
        refreshDownloads()
        val file = File(app.filesDir, "rag-pair-comparison.json")
        val saved = runCatching {
            GsonBuilder().create().fromJson(file.readText(), ComparisonReport::class.java)
        }.getOrNull()?.takeIf { it.schemaVersion == 2 && it.results.size == 2 }
        if (saved != null) _state.update { state -> state.copy(
            modelA = saved.results[0].modelId, modelB = saved.results[1].modelId,
            report = saved, repeats = saved.repeats, topK = saved.topK, dimension = saved.outputDimension,
            includeAnswers = saved.generationModel != null,
            generationId = models.firstOrNull { it.displayName == saved.generationModel }?.id,
            passages = saved.dataset.passages.joinToString("\n"),
            queries = saved.dataset.queries.joinToString("\n") { "${it.question} | ${it.relevant.sorted().joinToString(",")} | ${it.reference}" }
        ) }
    }
    fun edit(transform: (ComparisonUiState) -> ComparisonUiState) { if (!_state.value.busy) _state.update { transform(it).copy(error = null) } }
    fun refreshDownloads() { _state.update { it.copy(downloaded = models.filter(storage::isModelDownloaded).map { model -> model.id }.toSet()) } }
    fun cancel() { job?.cancel() }
    fun downloadSelected() {
        if (_state.value.busy) return
        val current = _state.value
        val ids = listOfNotNull(current.modelA, current.modelB, current.generationId.takeIf { current.includeAnswers }).distinct()
        launchWork {
            for (id in ids) {
                val model = models.first { it.id == id }
                if (storage.isModelDownloaded(model)) continue
                downloads.downloadModel(model) { progress -> _state.update { it.copy(progress = "${model.displayName}: $progress") } }
                refreshDownloads()
            }
            _state.update { it.copy(progress = "Seçilen modeller hazır.") }
        }
    }
    fun run() {
        if (_state.value.busy) return
        val current = _state.value
        launchWork {
            require(current.modelA != current.modelB) { "İki farklı embedding model seçin." }
            val dataset = parseComparisonDataset(current.passages, current.queries)
            val pair = listOf(current.modelA, current.modelB).map { id -> models.first { it.id == id } }
            val generation = if (current.includeAnswers) models.firstOrNull { it.id == current.generationId } else null
            require(!current.includeAnswers || generation != null) { "Son yanıtları karşılaştırmak için bir cevap modeli seçin." }
            if (generation != null) require(storage.isModelDownloaded(generation)) { "Cevap modelini önce indirin." }
            // LiteRT retains one engine and creates a new conversation per answer; other runtimes use their usual lifecycle.
            var generationSession: LiteRtLlmEngine.Session? = null
            val generate: (suspend (String) -> ComparisonAnswer)? = generation?.let { model ->
                val app = getApplication<Application>()
                val factoryEngine = LocalLlmEngineFactory(
                    execuTorchEngine = ExecuTorchLlmEngine(app, app.contentResolver),
                    liteRtEngine = LiteRtLlmEngine(app, app.contentResolver),
                    llamaCppEngine = LlamaCppLlmEngine(app, app.contentResolver),
                    manualFallbackEngine = UnsupportedRuntimeEngine("Cevap modeli desteklenmiyor.")
                ).create(model.engineType, model.runtimeType)
                val files = mapper.toRunFiles(model)
                var loadedGenerationEngine: LocalLlmEngine? = null
                val callback: suspend (String) -> ComparisonAnswer = { prompt ->
                    val engine = loadedGenerationEngine ?: if (model.runtimeType == RuntimeType.LITERT) {
                        val session = LiteRtLlmEngine(app, app.contentResolver).openSession(files.modelPath)
                        generationSession = session
                        object : LocalLlmEngine {
                            override suspend fun generate(request: BenchmarkPrompt, callback: StreamCallback) = session.generate(request, callback)
                        }
                    } else factoryEngine
                    loadedGenerationEngine = engine
                    generateAnswer(engine, model, files.modelPath, files.tokenizerPath, prompt)
                }
                callback
            }
            _state.update { it.copy(report = null) }
            val report = try {
                RagPairComparison(getApplication()).run(pair, dataset, current.repeats, current.topK, current.dimension,
                generation?.displayName, generate) { progress -> _state.update { it.copy(progress = progress) } }
            } finally {
                withContext(NonCancellable) { generationSession?.close() }
            }
            _state.update { it.copy(report = report, progress = "Karşılaştırma tamamlandı") }
            persist()
        }
    }
    private fun launchWork(action: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (_: CancellationException) { _state.update { it.copy(progress = "İptal edildi. Tamamlanmamış sonuçlar raporlanmadı.") } }
            catch (error: Exception) { _state.update { it.copy(error = error.message ?: "Karşılaştırma başarısız.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun rate(modelId: String, caseIndex: Int, rating: String) {
        _state.update { state -> state.copy(report = state.report?.let { it.copy(ratings = if (rating.isBlank()) it.ratings - "$modelId:$caseIndex" else it.ratings + ("$modelId:$caseIndex" to rating)) }) }
        persist()
    }
    fun exportJson(): String = GsonBuilder().setPrettyPrinting().create().toJson(requireNotNull(_state.value.report))
    private fun persist() {
        val report = _state.value.report ?: return
        val app = getApplication<Application>()
        val file = File(app.filesDir, "rag-pair-comparison.json")
        val temp = File(app.filesDir, "rag-pair-comparison.json.tmp")
        temp.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        check(temp.renameTo(file)) { "Rapor kaydedilemedi." }
    }
    private suspend fun generateAnswer(engine: LocalLlmEngine, model: ModelConfig, path: String, tokenizer: String?, prompt: String): ComparisonAnswer {
        val start = SystemClock.elapsedRealtimeNanos()
        val text = StringBuilder(); var first: Double? = null; var failure: String? = null
        try {
            engine.generate(BenchmarkPrompt(modelId = model.id, modelName = model.displayName, modelFilePath = path,
                tokenizerFilePath = tokenizer, runtimeType = model.runtimeType, engineType = model.engineType,
                promptTemplateType = model.promptTemplateType, selectionSource = SelectionSource.CATALOG, prompt = prompt, maxTokens = 192),
                object : StreamCallback {
                    override fun onFirstToken() = Unit
                    override fun onToken(token: String) { if (token.isNotEmpty()) { if (first == null) first = (SystemClock.elapsedRealtimeNanos() - start) / 1e6; text.append(token) } }
                    override fun onComplete() = Unit
                    override fun onError(throwable: Throwable) { failure = throwable.message ?: "Cevap üretimi başarısız." }
                })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "Cevap üretimi başarısız." }
        currentCoroutineContext().ensureActive()
        if (text.isBlank() && failure == null) failure = "Cevap modeli boş yanıt döndürdü."
        return ComparisonAnswer(text.toString(), first, (SystemClock.elapsedRealtimeNanos() - start) / 1e6, failure)
    }
}
