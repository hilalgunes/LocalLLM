package com.hilal.llm.test

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hilal.llm.benchmark.BenchmarkUiState
import com.hilal.llm.benchmark.EngineType
import com.hilal.llm.benchmark.productionEngineTypes
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.RuntimeType
import java.util.Locale
import com.hilal.llm.catalog.download.DownloadState

@Composable
fun TestScreen(
    testState: TestUiState,
    benchmarkState: BenchmarkUiState,
    onQuestionChanged: (String) -> Unit,
    onClassificationProviderSelected: (ClassificationProvider) -> Unit,
    onRuntimeSelected: (EngineType) -> Unit,
    onModelSelected: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    // Do not persist API keys in saved state or on disk.
    var jevApiKey by remember { mutableStateOf("") }
    var readAloud by rememberSaveable { mutableStateOf(false) }
    var listening by rememberSaveable { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening = false
        if (result.resultCode == Activity.RESULT_OK && !testState.isRunning) {
            val question = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim().orEmpty()
            if (question.isNotEmpty()) {
                onQuestionChanged(question)
                onSubmit(jevApiKey)
            } else voiceError = "Konuşma anlaşılamadı. Tekrar deneyin."
        }
    }
    val selectedModel = benchmarkState.selectedCatalogModel

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("Test", style = MaterialTheme.typography.headlineSmall) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("İK sınıflandırması", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ClassificationProvider.entries.forEach { provider ->
                                FilterChip(
                                    selected = testState.classificationProvider == provider,
                                    onClick = { onClassificationProviderSelected(provider) },
                                    enabled = !testState.isRunning,
                                    label = { Text(provider.label) }
                                )
                            }
                        }
                        if (testState.classificationProvider == ClassificationProvider.JEV) {
                            Text("Soru sınıflandırma için TypeSafe servisine gönderilir. Son cevabı aşağıdaki yerel model üretir.")
                            OutlinedTextField(
                                value = jevApiKey,
                                onValueChange = { jevApiKey = it },
                                label = { Text("TypeSafe API anahtarı") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                enabled = !testState.isRunning,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("Anahtar yalnızca bu ekranın belleğinde tutulur; kaydedilmez.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }


            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Local model", style = MaterialTheme.typography.titleMedium)
                        TestRuntimeDropdown(
                            selectedEngine = benchmarkState.selectedEngineType,
                            onRuntimeSelected = onRuntimeSelected
                        )
                        TestModelDropdown(
                            benchmarkState = benchmarkState,
                            onModelSelected = onModelSelected
                        )
                        benchmarkState.activeDownloadModelId?.let { modelId ->
                            val model = benchmarkState.availableModels.firstOrNull { it.id == modelId }
                            val state = benchmarkState.modelDownloadStates[modelId]
                            Text("İndiriliyor: ${model?.displayName ?: modelId}${downloadProgress(state)}")
                            TextButton(onClick = onCancelDownload) { Text("İndirmeyi iptal et") }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Soru", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            IconToggleButton(checked = readAloud, onCheckedChange = { readAloud = it }) {
                                Icon(
                                    if (readAloud) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                    if (readAloud) "Sesli yanıtı kapat" else "Sesli yanıtı aç",
                                    tint = if (readAloud) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(enabled = !testState.isRunning && !listening, onClick = {
                                voiceError = null
                                if (selectedModel == null) voiceError = "Önce bir local generation modeli seçin."
                                else if (testState.classificationProvider == ClassificationProvider.JEV && jevApiKey.isBlank()) {
                                    voiceError = "Jev için TypeSafe API anahtarını girin."
                                } else try {
                                    readAloud = true
                                    listening = true
                                    speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Sorunuzu söyleyin")
                                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                                    })
                                } catch (_: ActivityNotFoundException) {
                                    listening = false
                                    voiceError = "Bu cihazda konuşma tanıma uygulaması bulunamadı."
                                } catch (_: SecurityException) {
                                    listening = false
                                    voiceError = "Konuşma tanıma uygulamasının mikrofon iznini kontrol edin."
                                }
                            }) { Icon(Icons.Default.Mic, "Soruyu sesli söyle", tint = MaterialTheme.colorScheme.primary) }
                        }
                        OutlinedTextField(
                            value = testState.question,
                            onValueChange = onQuestionChanged,
                            placeholder = { Text("Sorunuzu yazın veya mikrofonla söyleyin…") },
                            minLines = 3, maxLines = 8,
                            enabled = !testState.isRunning && !listening,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (listening) "Dinleniyor…" else if (readAloud) "Yanıt EMA ile otomatik seslendirilecek." else "Mikrofonla soru sorabilir, sesli yanıtı hoparlörden açabilirsiniz.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            item {
                Button(
                    onClick = { onSubmit(jevApiKey) },
                    enabled = !testState.isRunning && !listening,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (testState.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text("Çalışıyor…")
                    } else {
                        Text("Gönder")
                    }
                }
            }

            if (selectedModel == null) {
                item { Text("Önce bir local generation modeli seçin.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            testState.errorMessage?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            if (testState.answer.isNotBlank()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Yanıt", style = MaterialTheme.typography.titleMedium)
                            Text(testState.answer)
                        }
                    }
                }
            }
            if (testState.classificationResult != null || testState.isRunning || testState.errorMessage != null) {
                item {
                    OutlinedButton(
                        onClick = { detailsExpanded = !detailsExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (detailsExpanded) "Detayları gizle" else "Detaylar")
                    }
                }
                if (detailsExpanded) {
                    item { TestDetails(testState) }
                }
            }
        }
        // Keep the engine outside the lazy list so scrolling does not stop speech.
        if (readAloud) {
            EmaTtsCard(
                answer = if (listening || testState.errorMessage != null) "" else testState.answer,
                answerReady = !testState.isRunning,
                autoRead = true
            )
        }
    }
}

@Composable
private fun TestDetails(state: TestUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sınıflandırma yöntemi: ${(state.resultProvider ?: state.classificationProvider).label}")
            state.jevModel?.let { Text("Jev modeli: $it") }
            state.jevConfidence?.let { Text("Jev güven skoru: ${String.format(Locale.ROOT, "%.3f", it)}") }
            state.jevHrProbability?.let { Text("İK olasılığı: ${String.format(Locale.ROOT, "%.3f", it)}") }
            Text("Classification: ${state.classificationResult ?: if (state.isRunning) "Çalışıyor" else "-"}")
            Text("Classification süresi: ${formatDuration(state.classificationTimeMs)}")
            Text("Retrieval: ${retrievalLabel(state.retrievalStatus)}")
            Text("Retrieval süresi: ${formatDuration(state.retrievalTimeMs)}")
            Text("Chunks: ${state.retrievedChunks.size}")
            Text("Final generation: ${formatDuration(state.finalGenerationTimeMs)}")
            Text("Toplam: ${formatDuration(state.totalTimeMs)}")
            if (state.retrievedChunks.isNotEmpty()) {
                Text("Dönen chunk'lar", style = MaterialTheme.typography.titleSmall)
                state.retrievedChunks.forEachIndexed { index, chunk ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Chunk ${index + 1}", style = MaterialTheme.typography.labelLarge)
                            Text("Document: ${chunk.source ?: "-"}")
                            Text("Text: ${chunk.text}")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestRuntimeDropdown(
    selectedEngine: EngineType,
    onRuntimeSelected: (EngineType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedEngine.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Runtime") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            productionEngineTypes.forEach { engine ->
                DropdownMenuItem(
                    text = { Text(engine.label) },
                    onClick = {
                        expanded = false
                        onRuntimeSelected(engine)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestModelDropdown(
    benchmarkState: BenchmarkUiState,
    onModelSelected: (String) -> Unit
) {
    val runtime = runtimeFor(benchmarkState.selectedEngineType)
    var expanded by remember(runtime) { mutableStateOf(false) }
    val models = remember(benchmarkState.availableModels, runtime) {
        benchmarkState.availableModels.filter { it.runtimeType == runtime }
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = benchmarkState.selectedCatalogModel?.displayName ?: "Model seç",
            onValueChange = {},
            readOnly = true,
            label = { Text("Local model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 400.dp)
        ) {
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Bu runtime için model bulunamadı.") },
                    onClick = {},
                    enabled = false
                )
            }
            models.forEach { model ->
                val state = benchmarkState.modelDownloadStates[model.id] ?: DownloadState.Idle
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${model.quantization ?: "-"} • ${model.estimatedSize}", style = MaterialTheme.typography.bodySmall)
                            Text(modelStatus(model, state), style = MaterialTheme.typography.bodySmall)
                            if (model.isEnabled && model.hasDownloadArtifacts && state !is DownloadState.Downloading) {
                                Text(if (state is DownloadState.Success) "Kullan" else "İndir ve kullan", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onModelSelected(model.id)
                    },
                    enabled = model.isEnabled && model.hasDownloadArtifacts && state !is DownloadState.Downloading
                )
            }
        }
    }
}

private fun runtimeFor(engineType: EngineType): RuntimeType? = when (engineType) {
    EngineType.ONNX -> RuntimeType.ONNX
    EngineType.LLAMA_CPP -> RuntimeType.LLAMA_CPP
    EngineType.LITERT -> RuntimeType.LITERT
    EngineType.EXECUTORCH -> RuntimeType.EXECUTORCH
    EngineType.FAKE -> null
}

private fun modelStatus(model: ModelConfig, state: DownloadState): String = when {
    !model.isEnabled -> model.disabledReason ?: "Kullanılamıyor"
    state is DownloadState.Success -> "İndirildi"
    state is DownloadState.Downloading -> "${state.progressLabel} • ${state.fileName}"
    state is DownloadState.Failed -> "İndirme başarısız: ${state.message}"
    else -> "İndirilmedi"
}

private fun downloadProgress(state: DownloadState?): String =
    (state as? DownloadState.Downloading)?.let { "\n${it.progressLabel}\n${it.fileName}" }.orEmpty()

private fun retrievalLabel(status: RetrievalStatus): String = when (status) {
    RetrievalStatus.Idle -> "Bekliyor"
    RetrievalStatus.Running -> "Çalışıyor"
    RetrievalStatus.Success -> "Success"
    RetrievalStatus.Ignored -> "Kullanılmadı"
    is RetrievalStatus.Failed -> "Failed: ${status.message}"
}

private fun formatDuration(value: Long?): String = value?.let { milliseconds ->
    val totalSeconds = milliseconds / 1000.0
    if (totalSeconds < 60.0) {
        "${String.format(Locale.ROOT, "%.3f", totalSeconds)} saniye"
    } else {
        val minutes = milliseconds / 60_000
        val remainingSeconds = (milliseconds % 60_000) / 1000.0
        "$minutes dakika ${String.format(Locale.ROOT, "%.3f", remainingSeconds)} saniye"
    }
} ?: "-"
