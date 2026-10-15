package com.hilal.llm.rag

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun RagComparisonRoute(textVm: RagComparisonViewModel, imageVm: ImageComparisonViewModel, onBack: (() -> Unit)? = null) {
    var imageMode by rememberSaveable { mutableStateOf(true) }
    val text by textVm.state.collectAsStateWithLifecycle()
    val image by imageVm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!imageMode, { imageMode = false }, enabled = !text.busy && !image.busy, label = { Text("Metin → Metin") })
            FilterChip(imageMode, { imageMode = true }, enabled = !text.busy && !image.busy, label = { Text("Metin → Görsel") })
        }
        Box(Modifier.weight(1f)) {
            if (imageMode) ImageComparisonScreen(imageVm, textVm, onBack) else TextComparisonScreen(textVm, onBack)
        }
    }
}

@Composable
private fun ImageComparisonScreen(vm: ImageComparisonViewModel, downloads: RagComparisonViewModel, onBack: (() -> Unit)?) {
    val state by vm.state.collectAsStateWithLifecycle()
    val downloadState by downloads.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dimension by rememberSaveable { mutableStateOf(768) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        vm.add(uris)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                val json = vm.exportJson()
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(json.toByteArray()) } }
                message = "Rapor kaydedildi."
            } catch (error: Exception) { message = error.message }
        }
    }
    val busy = state.busy || downloadState.busy
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (onBack != null) TextButton(onClick = onBack) { Text("Karşılaştırmalara dön") }
            Text("Embedding · Görsel arama", style = MaterialTheme.typography.headlineSmall)
            Text("270M: görsel açıklamalarında metin araması\n740M: doğrudan görsellerde multimodal arama")
            Text("270M açıklamaları bu deneyde elle girilir. Açıklama üretme maliyeti ölçülmez; uçtan uca otomatik açıklama sistemi karşılaştırması değildir.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedButton(enabled = !busy, onClick = {
                downloads.edit { it.copy(modelA = "embeddinggemma-2-text-270m-litert", modelB = "embeddinggemma-2-740m-litert", includeAnswers = false) }
                downloads.downloadSelected()
            }) { Text("270M ve 740M modellerini indir") }
            if (downloadState.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = downloads::cancel) { Text("İndirmeyi iptal et") } }
            if (downloadState.progress.isNotBlank()) Text(downloadState.progress)
            downloadState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { picker.launch(arrayOf("image/*")) }) { Text("Görselleri seç") }
                TextButton(enabled = !busy, onClick = vm::clear) { Text("Temizle") }
            }
            Text("${state.images.size}/200 görsel · En fazla 100 sorgu")
        }
        state.images.forEachIndexed { index, item -> item(key = item.uri) {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Görsel ${index + 1}")
                    val thumbnail by produceState<android.graphics.Bitmap?>(null, item.uri) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
                                val options = BitmapFactory.Options().apply {
                                    inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 480).coerceAtLeast(1)
                                }
                                context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { BitmapFactory.decodeStream(it, null, options) }
                            }.getOrNull()
                        }
                    }
                    thumbnail?.let { Image(it.asImageBitmap(), "Görsel ${index + 1}", Modifier.fillMaxWidth().height(120.dp), contentScale = ContentScale.Fit) }
                    OutlinedTextField(item.caption, { vm.caption(index, it) }, label = { Text("270M için açıklama") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                }
            }
        } }
        item {
            Text("Her satır: sorgu | doğru görsel numaraları | boş bırakılabilir referans")
            OutlinedTextField(state.queries, vm::queries, label = { Text("Sorgular ve doğru görseller") }, placeholder = { Text("Bahçedeki sarı çiçeği bul | 2 |") }, enabled = !busy, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(128, 256, 512, 768).forEach { size -> FilterChip(dimension == size, { dimension = size }, enabled = !busy, label = { Text("$size") }) } }
            Button(enabled = !busy, onClick = { vm.run(dimension) }, modifier = Modifier.fillMaxWidth()) { Text("Görsel karşılaştırmasını çalıştır") }
            if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = vm::cancel) { Text("İptal et") } }
            Text(state.progress)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it) }
        }
        state.report?.let { report ->
            item { Button(onClick = { export.launch("image-embedding-${report.createdAt}.json") }) { Text("Görsel raporunu kaydet") } }
            report.results.forEach { result -> item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(result.approach, style = MaterialTheme.typography.titleMedium)
                        fun f(value: Double) = String.format(Locale.ROOT, "%.2f", value)
                        Text("Başlatma: ${f(result.initializeMs)} ms · İndeksleme: ${f(result.indexMs)} ms")
                        Text("İndeksleme hızı: ${f(report.images.size * 1000 / result.indexMs)} görsel/sn")
                        Text("Uygulama PSS başlangıç / en yüksek örnek: ${f(result.baselinePssMb)} / ${f(result.sampledPeakPssMb)} MB")
                        Text("Recall@1: ${f(result.queries.map { it.recall1 }.average())} · Recall@5: ${f(result.queries.map { it.recall5 }.average())}")
                        Text("MRR: ${f(result.queries.map { it.mrr }.average())} · nDCG@5: ${f(result.queries.map { it.ndcg5 }.average())}")
                        result.queries.forEach { query ->
                            HorizontalDivider(); Text(query.question)
                            Text("Sorgu + arama: ${f(query.queryMs)} ms · Beklenen: ${query.expected.joinToString()}")
                            query.rankings.take(5).forEach { source -> Text("Görsel ${source.passage} · ${f(source.score)} · ${if (source.relevant) "Doğru görsel" else "Diğer görsel"}") }
                        }
                    }
                }
            } }
        }
    }
}
