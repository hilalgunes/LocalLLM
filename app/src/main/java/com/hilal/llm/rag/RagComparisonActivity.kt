package com.hilal.llm.rag

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.ModelPurpose
import com.hilal.llm.catalog.RuntimeType
import com.hilal.llm.ui.theme.LLMTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class RagComparisonActivity : ComponentActivity() {
    private val viewModel: RagComparisonViewModel by viewModels()
    private val images: ImageComparisonViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LLMTheme { RagComparisonRoute(viewModel, images) } }
    }
}

@Composable
internal fun TextComparisonScreen(vm: RagComparisonViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expandedDataset by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                val report = vm.exportJson()
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(report.toByteArray()) } }
                exportMessage = "Rapor kaydedildi."
            } catch (error: Exception) { exportMessage = error.message }
        }
    }
    val embeddings = vm.models.filter { it.id in ragEmbeddingModelIds && it.id !in setOf("multilingual-e5-small-gguf", "all-minilm-l6-v2-gguf") && it.isEnabled && it.runtimeType in setOf(RuntimeType.LITERT, RuntimeType.LLAMA_CPP) }
    val generation = vm.models.filter { it.purpose == ModelPurpose.GENERATION && it.isEnabled && it.id in ragGenerationModelIds }
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (onBack != null) TextButton(onClick = onBack) { Text("Karşılaştırmalara dön") }
                Text("Embedding karşılaştır", style = MaterialTheme.typography.headlineSmall)
                Text("Aynı pasajlar, sorular, cevap modeli ve ayarlar. Sonuçlar bu cihazda gerçek çalıştırmadan alınır.")
                Text("Arama: bellek içi tam kosinüs. Mevcut RAG kayıtları değiştirilmez.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModelDropdown("Embedding A", state.modelA, embeddings, state.downloaded, !state.busy) { id -> vm.edit { it.copy(modelA = id) } }
                        ModelDropdown("Embedding B", state.modelB, embeddings, state.downloaded, !state.busy) { id -> vm.edit { it.copy(modelB = id) } }
                        Row { Checkbox(state.includeAnswers, { checked -> vm.edit { it.copy(includeAnswers = checked) } }, enabled = !state.busy); Text("Son RAG yanıtlarını da üret", Modifier.padding(top = 12.dp)) }
                        if (state.includeAnswers) ModelDropdown("İkisinin de kullanacağı cevap modeli", state.generationId, generation, state.downloaded, !state.busy) { id -> vm.edit { it.copy(generationId = id) } }
                        Text("740M paketi görsel/ses bileşenlerini de içerir; bu karşılaştırma yalnızca metin RAG'ını ölçer.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ortak test ayarları", style = MaterialTheme.typography.titleMedium)
                        ChoiceRow("Embedding tekrar sayısı", listOf(1, 3, 5), state.repeats, !state.busy) { n -> vm.edit { it.copy(repeats = n) } }
                        ChoiceRow("Getirilecek kaynak (top-k)", listOf(1, 3, 5), state.topK, !state.busy) { n -> vm.edit { it.copy(topK = n) } }
                        ChoiceRow("Gemma vektör boyutu", listOf(128, 256, 512, 768), state.dimension, !state.busy) { n -> vm.edit { it.copy(dimension = n) } }
                        Text("Diğer modellerin doğal vektör boyutu kullanılır. Her turda yeni embedding oturumu açılır; sıra A/B ve B/A olarak değişir. Cevaplar sorgu başına bir kez üretilir.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { expandedDataset = !expandedDataset }) { Text(if (expandedDataset) "Test verisini gizle" else "Pasajları ve beklenen cevapları düzenle") }
                        if (expandedDataset) {
                            Row {
                                TextButton(enabled = !state.busy, onClick = { vm.edit { it.copy(passages = defaultComparisonPassages, queries = defaultComparisonQueries) } }) { Text("Örnek veri") }
                            }
                            Text("Her satır bir kaynak: ilk satır kaynak 1, ikinci satır kaynak 2…")
                            OutlinedTextField(state.passages, { value -> vm.edit { it.copy(passages = value) } }, label = { Text("Pasajlar") }, enabled = !state.busy, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                            Text("Her sorgu: soru | ilgili kaynak numaraları (1,2) | referans cevap. Referans cevap boş bırakılabilir.")
                            OutlinedTextField(state.queries, { value -> vm.edit { it.copy(queries = value) } }, label = { Text("Sorular ve beklenen sonuçlar") }, enabled = !state.busy, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::downloadSelected, enabled = !state.busy) { Text("Eksik modelleri indir") }
                    Button(onClick = vm::run, enabled = !state.busy) { Text("Karşılaştır") }
                }
                if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = vm::cancel) { Text("İptal et") } }
                Text(state.progress)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                exportMessage?.let { Text(it) }
            }
            state.report?.let { report ->
                item {
                    Button(onClick = { export.launch("rag-comparison-${report.startedAt}.json") }) { Text("Tüm ölçümleri JSON olarak kaydet") }
                    Text("${report.device} · Android ${report.androidVersion}")
                    Text("${report.dataset.passages.size} kaynak · ${report.dataset.queries.size} soru · ${report.repeats} tur · top-${report.topK}")
                    Text("Bellek: tüm uygulamanın 200 ms aralıklarla örneklenen PSS değeri. Gerçek anlık tepe/modelin tek başına RAM'i değildir. Dosya önbelleği temizlenmez.", style = MaterialTheme.typography.bodySmall)
                }
                item { Text("Son tamamlanan koşunun ölçümleri", style = MaterialTheme.typography.titleMedium); MetricsTable(report) }
                report.dataset.queries.forEachIndexed { index, query ->
                    item {
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("${index + 1}. ${query.question}", fontWeight = FontWeight.SemiBold)
                                Text("Beklenen kaynaklar: ${query.relevant.sorted().joinToString()}\nReferans: ${query.reference.ifBlank { "Girilmedi" }}")
                                report.results.forEach { model ->
                                    model.cases.getOrNull(index)?.let { case ->
                                        HorizontalDivider()
                                        Text(model.name, style = MaterialTheme.typography.titleSmall)
                                        Text("Sorgu ${ms(case.queryEmbeddingMs)} · Arama ${ms(case.searchMs)} · Recall ${percent(case.retrieval.recall)} · MRR ${number(case.retrieval.reciprocalRank)}")
                                        var allSources by remember(model.modelId, index, report.startedAt) { mutableStateOf(false) }
                                        TextButton(onClick = { allSources = !allSources }) { Text(if (allSources) "Yalnızca seçilen kaynakları göster" else "Tüm kaynak sıralamasını göster") }
                                        (if (allSources) case.fullRanking else case.sources).forEachIndexed { sourceIndex, source ->
                                            Text("${sourceIndex + 1}. Kaynak ${source.passage} · kosinüs ${number(source.score)} · ${if (source.relevant) "beklenen kaynak" else "beklenenler dışında"}", fontWeight = FontWeight.SemiBold)
                                            Text(source.text, style = MaterialTheme.typography.bodySmall)
                                        }
                                        case.answer?.let { answer ->
                                            Text("Yanıt", fontWeight = FontWeight.SemiBold)
                                            Text(answer.error ?: answer.text.ifBlank { "Boş yanıt" }, color = if (answer.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                            Text("İlk metin: ${answer.firstTextMs?.let { "${number(it)} ms" } ?: "—"} · Toplam: ${number(answer.totalMs)} ms")
                                            Text("Referans kelime örtüşmesi (F1): ${case.referenceOverlap?.let(::percent) ?: "—"} · Doğruluk puanı değildir.", style = MaterialTheme.typography.bodySmall)
                                            Text("Yanıt doğruluğu (senin değerlendirmen)")
                                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                listOf("Doğru", "Kısmen doğru", "Yanlış").forEach { rating ->
                                                    FilterChip(report.ratings["${model.modelId}:$index"] == rating, { vm.rate(model.modelId, index, rating) }, label = { Text(rating) })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ModelDropdown(label: String, selected: String?, models: List<ModelConfig>, downloaded: Set<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${models.firstOrNull { it.id == selected }?.displayName ?: "Model seç"}")
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
            models.forEach { model -> DropdownMenuItem(text = { Text("${model.displayName}\n${model.estimatedSize} · ${model.quantization}\n${if (model.id in downloaded) "İndirildi" else "İndirilmedi"}") }, onClick = { expanded = false; onSelect(model.id) }) }
        }
    }
}
@Composable private fun ChoiceRow(label: String, options: List<Int>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Text(label)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { value -> FilterChip(selected == value, { onSelect(value) }, enabled = enabled, label = { Text("$value") }) }
    }
}
private fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value)
private fun percent(value: Double) = "${number(value * 100)}%"
private fun median(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sorted().let { if (it.size % 2 == 0) (it[it.size / 2 - 1] + it[it.size / 2]) / 2 else it[it.size / 2] }
private fun ms(values: List<Double>) = if (values.isEmpty()) "—" else "${number(median(values))} ms"

@Composable private fun MetricsTable(report: ComparisonReport) {
    val a = report.results[0]; val b = report.results[1]
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable fun row(label: String, left: String, right: String) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                    Text(if (a.error != null && a.cases.isEmpty() && label !in setOf("Ölçüm", "Runtime / quant", "Hata", "Tamamlanan turlar")) "—" else left, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(if (b.error != null && b.cases.isEmpty() && label !in setOf("Ölçüm", "Runtime / quant", "Hata", "Tamamlanan turlar")) "—" else right, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
            row("Ölçüm", a.name, b.name)
            row("Runtime / quant", "${a.runtime}\n${a.quantization}", "${b.runtime}\n${b.quantization}")
            row("Model dosyaları", "${number(a.fileBytes / 1e6)} MB", "${number(b.fileBytes / 1e6)} MB")
            row("Boyut / norm", "${a.dimension} / ${number(a.vectorNorm)}", "${b.dimension} / ${number(b.vectorNorm)}")
            row("Ham vektör deposu", "${a.vectorBytes} bayt", "${b.vectorBytes} bayt")
            row("Tamamlanan turlar", "${a.initializeMs.size}/${report.repeats}", "${b.initializeMs.size}/${report.repeats}")
            row("Başlatma medyan", ms(a.initializeMs), ms(b.initializeMs))
            row("İlk embedding", ms(a.firstEmbeddingMs), ms(b.firstEmbeddingMs))
            row("Pasaj vektör üretimi", ms(a.indexingMs), ms(b.indexingMs))
            fun queryTimes(model: ComparisonModelResult) = model.cases.flatMap { it.queryEmbeddingMs }
            fun throughput(model: ComparisonModelResult) = if (model.indexingMs.isEmpty()) "—" else "${number(report.dataset.passages.size * 1000 / median(model.indexingMs))} pasaj/sn"
            row("İndeksleme hızı", throughput(a), throughput(b))
            row("Sorgu medyan", ms(queryTimes(a)), ms(queryTimes(b)))
            fun p95(model: ComparisonModelResult): String { val times = queryTimes(model).sorted(); return if (times.isEmpty()) "—" else "${number(times[(kotlin.math.ceil(times.size * .95).toInt() - 1).coerceIn(times.indices)])} ms" }
            row("Sorgu p95", p95(a), p95(b))
            row("RAM başlangıç / tepe örneği", "${number(a.baselinePssMb)} / ${number(a.sampledPeakPssMb)} MB", "${number(b.baselinePssMb)} / ${number(b.sampledPeakPssMb)} MB")
            fun avg(model: ComparisonModelResult, metric: (ComparisonCaseResult) -> Double) = if (model.cases.isEmpty()) "—" else percent(model.cases.map(metric).average())
            row("Hit@${report.topK}", avg(a) { if (it.retrieval.hit) 1.0 else 0.0 }, avg(b) { if (it.retrieval.hit) 1.0 else 0.0 })
            row("Precision / Recall", "${avg(a) { it.retrieval.precision }} / ${avg(a) { it.retrieval.recall }}", "${avg(b) { it.retrieval.precision }} / ${avg(b) { it.retrieval.recall }}")
            row("MRR / nDCG", "${avg(a) { it.retrieval.reciprocalRank }} / ${avg(a) { it.retrieval.ndcg }}", "${avg(b) { it.retrieval.reciprocalRank }} / ${avg(b) { it.retrieval.ndcg }}")
            row("Üretim ilk metin medyan", ms(a.cases.mapNotNull { it.answer?.firstTextMs }), ms(b.cases.mapNotNull { it.answer?.firstTextMs }))
            row("Üretim toplam medyan", ms(a.cases.mapNotNull { it.answer?.takeIf { answer -> answer.error == null }?.totalMs }), ms(b.cases.mapNotNull { it.answer?.takeIf { answer -> answer.error == null }?.totalMs }))
            fun ratings(model: ComparisonModelResult) = report.ratings.filterKeys { it.startsWith(model.modelId + ":") }.values.let { values -> "${values.count { it == "Doğru" }} doğru / ${values.size} değerlendirildi" }
            row("Yanıt doğruluğu", ratings(a), ratings(b))
            if (a.error != null || b.error != null) row("Hata", a.error ?: "—", b.error ?: "—")
        }
    }
}
