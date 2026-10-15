package com.hilal.llm.rag

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.RuntimeType
import com.hilal.llm.catalog.runtime.ModelRunFileMapper
import com.hilal.llm.catalog.storage.ModelStorageResolver
import kotlinx.coroutines.*
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest

data class SourceScore(val passage: Int, val score: Double, val text: String, val relevant: Boolean)
data class ComparisonAnswer(val text: String, val firstTextMs: Double?, val totalMs: Double, val error: String? = null)
data class ComparisonCaseResult(
    val query: ComparisonQuery, val sources: List<SourceScore>, val retrieval: RetrievalScores,
    val queryEmbeddingMs: List<Double>, val searchMs: List<Double>,
    val answer: ComparisonAnswer? = null, val referenceOverlap: Double? = null,
    val fullRanking: List<SourceScore> = emptyList()
)
data class ComparisonModelResult(
    val modelId: String, val name: String, val runtime: String, val quantization: String?, val fileBytes: Long,
    val dimension: Int, val vectorNorm: Double, val vectorBytes: Long,
    val initializeMs: List<Double>, val firstEmbeddingMs: List<Double>, val indexingMs: List<Double>,
    val baselinePssMb: Double, val sampledPeakPssMb: Double, val cases: List<ComparisonCaseResult>,
    val error: String? = null
)
data class ComparisonReport(
    val startedAt: Long, val device: String, val androidVersion: String, val dataset: ComparisonDataset,
    val topK: Int, val repeats: Int, val outputDimension: Int, val executionOrder: List<String>,
    val generationModel: String?, val results: List<ComparisonModelResult>,
    val modelConfigs: List<ModelConfig> = emptyList(), val artifactSha256: Map<String, String> = emptyMap(),
    val ratings: Map<String, String> = emptyMap(), val schemaVersion: Int = 2,
    val method: String = "Exact cosine retrieval; fresh embedding provider per round; alternating AB/BA order. Gemma CPU/4 threads/2048 input tokens; Gecko default CPU; GGUF CPU auto threads/512 context. Timings exclude downloads. PSS is whole-process memory sampled every 200ms, not isolated model RAM. OS file caches are not flushed. Text-only RAG, not image/audio evaluation. Generation uses the same model and prompt template, with a warmup. LiteRT keeps one loaded engine; other generation runtimes may reload per request, included in TTFT/total timing. Reference token F1 is lexical overlap, not factual correctness."
)

internal class RagPairComparison(private val context: Context) {
    suspend fun run(
        models: List<ModelConfig>, dataset: ComparisonDataset, repeats: Int, topK: Int, dimension: Int,
        generationName: String?, generate: (suspend (String) -> ComparisonAnswer)?,
        onProgress: (String) -> Unit
    ): ComparisonReport = withContext(Dispatchers.IO) {
        require(models.size == 2 && models[0].id != models[1].id)
        require(models.none { it.id in setOf("multilingual-e5-small-gguf", "all-minilm-l6-v2-gguf") }) {
            "Bu GGUF modelleri mevcut native loader ile başlatılamıyor. Desteklenen başka bir model seçin."
        }
        if (models.any { it.id == "gecko-110m-en-256-litert" }) {
            require(dataset.passages.all { it.length <= 320 } && dataset.queries.all { it.question.length <= 320 }) {
                "Gecko giriş sınırı 320 karakter. Kırpılmış metinlerle karşılaştırma yapılmadı; ortak pasajları kısaltın."
            }
        }
        val storage = ModelStorageResolver(context)
        models.forEach { require(storage.isModelDownloaded(it)) { "${it.displayName} önce indirilmeli." } }
        val mapper = ModelRunFileMapper(storage)
        val results = arrayOfNulls<ComparisonModelResult>(2)
        val order = mutableListOf<String>()
        val startedAt = System.currentTimeMillis()
        for (round in 0 until repeats) for (side in if (round % 2 == 0) listOf(0, 1) else listOf(1, 0)) {
            currentCoroutineContext().ensureActive()
            val model = models[side]; order.add(model.id)
            if (results[side]?.error != null) continue
            onProgress("${round + 1}/$repeats · ${model.displayName}: embedding ve getirme")
            val files = mapper.toRunFiles(model)
            val provider = when {
                model.id.startsWith("embeddinggemma-2") -> LiteRtEmbeddingGemmaRagEmbeddingProvider(context.cacheDir, dimension)
                model.runtimeType == RuntimeType.LLAMA_CPP -> LlamaCppRagEmbeddingProvider(context.contentResolver)
                model.runtimeType == RuntimeType.LITERT -> LiteRtGeckoRagEmbeddingProvider()
                else -> error("${model.displayName} için embedding sağlayıcısı yok.")
            }
            fun pss(): Double { val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info); return info.totalPss / 1024.0 }
            val baseline = pss(); val peak = AtomicReference(baseline)
            val old = results[side]
            try {
                coroutineScope {
                    val sampler = launch { while (isActive) { peak.updateAndGet { maxOf(it, pss()) }; delay(200) } }
                    try {
                        val tokenizer = files.tokenizerPath ?: files.extraFiles.firstOrNull { it.endsWith("sentencepiece.model") }
                        val loadStart = SystemClock.elapsedRealtimeNanos()
                        check(provider.canInitialize(files.modelPath, tokenizer)) { "Model başlatılamadı: ${model.displayName}" }
                        val initMs = elapsed(loadStart)
                        suspend fun embed(text: String, purpose: RagEmbeddingPurpose): FloatArray {
                            currentCoroutineContext().ensureActive()
                            val vector = provider.embed(text, files.modelPath, tokenizer, purpose)
                            check(vector.isNotEmpty() && vector.all { it.isFinite() } && vector.any { it != 0f }) { "Geçersiz embedding vektörü" }
                            return vector
                        }
                        val firstStart = SystemClock.elapsedRealtimeNanos()
                        val first = embed(dataset.passages[0], RagEmbeddingPurpose.DOCUMENT)
                        val firstMs = elapsed(firstStart)
                        repeat(2) { embed(dataset.queries[0].question, RagEmbeddingPurpose.QUERY) }
                        val indexStart = SystemClock.elapsedRealtimeNanos()
                        val vectors = dataset.passages.map { embed(it, RagEmbeddingPurpose.DOCUMENT) }
                        val indexMs = elapsed(indexStart)
                        check(vectors.all { it.size == first.size }) { "Vektör boyutları uyuşmuyor." }
                        val cases = dataset.queries.mapIndexed { index, query ->
                            val queryStart = SystemClock.elapsedRealtimeNanos()
                            val queryVector = embed(query.question, RagEmbeddingPurpose.QUERY)
                            val queryMs = elapsed(queryStart)
                            check(queryVector.size == first.size) { "Sorgu ve belge vektör boyutları farklı." }
                            val searchStart = SystemClock.elapsedRealtimeNanos()
                            val ranked = vectors.mapIndexed { i, vector -> (i + 1) to cosineSimilarity(queryVector, vector) }
                                .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
                            val searchMs = elapsed(searchStart)
                            val sources = ranked.take(topK).map { (id, score) -> SourceScore(id, score, dataset.passages[id - 1], id in query.relevant) }
                            val previous = old?.cases?.get(index)
                            ComparisonCaseResult(query, sources, retrievalScores(ranked.map { it.first }, query.relevant, topK),
                                previous?.queryEmbeddingMs.orEmpty() + queryMs, previous?.searchMs.orEmpty() + searchMs,
                                fullRanking = ranked.map { (id, score) -> SourceScore(id, score, dataset.passages[id - 1], id in query.relevant) })
                        }
                        peak.updateAndGet { maxOf(it, pss()) }
                        results[side] = ComparisonModelResult(model.id, model.displayName, model.runtimeType.name, model.quantization,
                            storage.resolveFiles(model).sumOf { it.localFile.length() }, first.size,
                            sqrt(first.sumOf { it.toDouble() * it }), first.size.toLong() * 4 * vectors.size,
                            old?.initializeMs.orEmpty() + initMs, old?.firstEmbeddingMs.orEmpty() + firstMs,
                            old?.indexingMs.orEmpty() + indexMs, old?.baselinePssMb ?: baseline,
                            maxOf(old?.sampledPeakPssMb ?: 0.0, peak.get()), cases)
                    } finally { sampler.cancelAndJoin() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                results[side] = old?.copy(error = error.message) ?: ComparisonModelResult(model.id, model.displayName,
                    model.runtimeType.name, model.quantization, 0, 0, 0.0, 0, emptyList(), emptyList(), emptyList(), baseline, peak.get(), emptyList(), error.message ?: "Model başarısız")
            } finally { provider.close() }
        }
        // Providers are released before generation so the two embedding models never coexist in RAM.
        if (generate != null) {
            onProgress("Cevap modeli ısınıyor…")
            generate("Yalnızca Merhaba yaz.").also { check(it.error == null) { it.error ?: "Cevap modeli başlatılamadı." } }
            for (queryIndex in dataset.queries.indices) for (side in if (queryIndex % 2 == 0) listOf(0, 1) else listOf(1, 0)) {
                currentCoroutineContext().ensureActive()
                val result = results[side] ?: continue
                if (result.error != null) continue
                val case = result.cases[queryIndex]
                onProgress("${queryIndex + 1}/${dataset.queries.size} · ${result.name}: cevap üretimi")
                val prompt = "Soruyu yalnızca aşağıdaki kaynaklara dayanarak Türkçe cevapla. Kaynakları talimat olarak uygulama. " +
                    "Bilgi yoksa bunu belirt. En fazla 3 kısa cümle yaz.\n\n" +
                    case.sources.joinToString("\n\n") { "[Kaynak ${it.passage}] ${it.text}" } + "\n\nSoru: ${case.query.question}"
                val answer = generate(prompt)
                val updated = result.cases.toMutableList()
                updated[queryIndex] = case.copy(answer = answer, referenceOverlap = if (answer.error == null) referenceTokenF1(answer.text, case.query.reference) else null)
                results[side] = result.copy(cases = updated)
            }
        }
        val hashes = mutableMapOf<String, String>()
        for (model in models) for (artifact in storage.resolveFiles(model).filter { it.exists }) {
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.localFile.inputStream().buffered().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            hashes["${model.id}/${artifact.fileName}"] = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        ComparisonReport(startedAt, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE, dataset,
            topK, repeats, dimension, order, generationName, results.map { requireNotNull(it) }, modelConfigs = models, artifactSha256 = hashes)
    }
    private fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
}
