package com.hilal.llm.rag

import android.app.Application
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.*
import com.google.gson.GsonBuilder
import com.hilal.llm.catalog.ModelCatalogRepository
import com.hilal.llm.catalog.runtime.ModelRunFileMapper
import com.hilal.llm.catalog.storage.ModelStorageResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class ComparisonImage(val uri: String, val caption: String = "")
internal data class ImageQueryResult(val question: String, val expected: Set<Int>, val rankings: List<SourceScore>,
    val recall1: Double, val recall5: Double, val mrr: Double, val ndcg5: Double, val queryMs: Double)
internal data class ImageSideResult(val approach: String, val initializeMs: Double, val indexMs: Double,
    val baselinePssMb: Double, val sampledPeakPssMb: Double, val itemTimesMs: List<Double>, val queries: List<ImageQueryResult>)
internal data class ImageComparisonReport(val createdAt: Long, val images: List<ComparisonImage>, val dimension: Int,
    val results: List<ImageSideResult>, val method: String = "270M: manually supplied image captions; 740M: encoded images directly. CPU/4 threads, normalized vectors, exact cosine ranking. Caption creation cost is NOT measured; this is not a full automatic captioning-pipeline benchmark. Image loading is included in image indexing. PSS is total application memory, sampled after each embedding, not isolated or instantaneous peak memory. No LLM answer generation. Text-to-image only; not image-to-image or audio.")
internal data class ImageComparisonState(val images: List<ComparisonImage> = emptyList(), val queries: String = "",
    val busy: Boolean = false, val progress: String = "", val error: String? = null, val report: ImageComparisonReport? = null)

internal class ImageComparisonViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ImageComparisonState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    fun add(uris: List<Uri>) {
        if (_state.value.busy) return
        _state.update { state -> state.copy(images = (state.images + uris.map { ComparisonImage(it.toString()) }).distinctBy { it.uri }.take(200), error = null) }
    }
    fun caption(index: Int, text: String) { if (!_state.value.busy) _state.update { it.copy(images = it.images.mapIndexed { i, image -> if (i == index) image.copy(caption = text) else image }) } }
    fun queries(text: String) { if (!_state.value.busy) _state.update { it.copy(queries = text) } }
    fun clear() { if (!_state.value.busy) _state.value = ImageComparisonState() }
    fun cancel() { job?.cancel() }
    fun run(dimension: Int) {
        if (_state.value.busy) return
        val input = _state.value
        _state.update { it.copy(busy = true, report = null, error = null) }
        job = viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    require(input.images.size in 2..200) { "2–200 görsel seçin." }
                    require(input.images.all { it.caption.isNotBlank() }) { "270M karşılaştırması için her görsele açıklama girin." }
                    val dataset = parseComparisonDataset(input.images.map { it.caption.replace(Regex("\\s+"), " ") }.joinToString("\n"), input.queries, maxPassages = 200, maxQueries = 100)
                    val app = getApplication<Application>()
                    val storage = ModelStorageResolver(app); val mapper = ModelRunFileMapper(storage)
                    val outputs = mutableListOf<ImageSideResult>()
                    for (id in listOf("embeddinggemma-2-text-270m-litert", "embeddinggemma-2-740m-litert")) {
                        currentCoroutineContext().ensureActive()
                        val model = requireNotNull(ModelCatalogRepository.getModelById(id))
                        require(storage.isModelDownloaded(model)) { "Önce ${model.displayName} modelini indirin." }
                        val vision = id.contains("740m")
                        _state.update { it.copy(progress = "${model.displayName}: başlatılıyor…") }
                        fun pss(): Double { val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info); return info.totalPss / 1024.0 }
                        val baseline = pss(); var peak = baseline
                        val engine = EmbeddingEngine(EmbeddingEngineConfig(
                            modelPath = mapper.toRunFiles(model).modelPath, backend = Backend.CPU(threadCount = 4),
                            visionBackend = if (vision) Backend.CPU(threadCount = 4) else null,
                            cacheDir = app.cacheDir.absolutePath, maxInputLength = 2048,
                            visionTokensPerImage = if (vision) 70 else null))
                        try {
                            var start = SystemClock.elapsedRealtimeNanos(); engine.initialize()
                            val init = elapsed(start); peak = maxOf(peak, pss())
                            fun embedding(data: InputData): FloatArray {
                                val vector = engine.computeEmbedding(listOf(data), EmbeddingOptions(normalize = true, outputSize = dimension)).embedding
                                check(vector.size == dimension && vector.all { it.isFinite() } && vector.any { it != 0f }) { "Geçersiz embedding" }
                                peak = maxOf(peak, pss()); return vector
                            }
                            val vectors = mutableListOf<FloatArray>(); val times = mutableListOf<Double>()
                            val indexStart = SystemClock.elapsedRealtimeNanos()
                            input.images.forEachIndexed { index, image ->
                                currentCoroutineContext().ensureActive()
                                _state.update { it.copy(progress = "${model.displayName}: görsel ${index + 1}/${input.images.size}") }
                                start = SystemClock.elapsedRealtimeNanos()
                                val data = if (vision) {
                                    val bytes = requireNotNull(app.contentResolver.openInputStream(Uri.parse(image.uri))).use { stream ->
                                        val out = java.io.ByteArrayOutputStream()
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            val count = stream.read(buffer)
                                            if (count < 0) break
                                            require(out.size() + count <= 16 * 1024 * 1024) { "Görsel başına en fazla 16 MB." }
                                            out.write(buffer, 0, count)
                                        }
                                        out.toByteArray()
                                    }
                                    InputData.Image(bytes)
                                } else InputData.Text("title: none | text: ${image.caption.trim()}")
                                vectors += embedding(data); times += elapsed(start)
                            }
                            val indexMs = elapsed(indexStart)
                            val queries = dataset.queries.map { query ->
                                currentCoroutineContext().ensureActive()
                                start = SystemClock.elapsedRealtimeNanos()
                                val vector = embedding(InputData.Text("task: question answering | query: ${query.question}"))
                                val ranked = vectors.mapIndexed { index, item -> (index + 1) to cosineSimilarity(vector, item) }.sortedByDescending { it.second }
                                val duration = elapsed(start)
                                val r1 = retrievalScores(ranked.map { it.first }, query.relevant, 1)
                                val r5 = retrievalScores(ranked.map { it.first }, query.relevant, 5)
                                ImageQueryResult(query.question, query.relevant, ranked.map { (i, score) -> SourceScore(i, score, input.images[i - 1].caption, i in query.relevant) },
                                    r1.recall, r5.recall, r5.reciprocalRank, r5.ndcg, duration)
                            }
                            outputs += ImageSideResult(if (vision) "740M · Doğrudan görsel" else "270M · Elle girilen açıklamalar", init, indexMs, baseline, peak, times, queries)
                        } finally { if (engine.isInitialized()) engine.close() }
                    }
                    ImageComparisonReport(System.currentTimeMillis(), input.images, dimension, outputs)
                }
                _state.update { it.copy(report = report, progress = "Görsel karşılaştırması tamamlandı") }
            } catch (_: CancellationException) { _state.update { it.copy(progress = "İptal edildi") } }
            catch (error: Exception) { _state.update { it.copy(error = error.message ?: "Görsel testi başarısız") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun exportJson() = GsonBuilder().setPrettyPrinting().create().toJson(requireNotNull(_state.value.report))
    private fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
}
