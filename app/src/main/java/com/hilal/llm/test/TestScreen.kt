package com.hilal.llm.test

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hilal.llm.benchmark.BenchmarkUiState
import com.hilal.llm.benchmark.EngineType
import com.hilal.llm.benchmark.displayLabel
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
    onRuntimeSelected: (EngineType) -> Unit,
    onModelSelected: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var modelPickerVisible by rememberSaveable { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedModel = benchmarkState.selectedCatalogModel

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Test", style = MaterialTheme.typography.headlineSmall) }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Local model", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        productionEngineTypes.forEach { engine ->
                            val selected = benchmarkState.selectedEngineType == engine
                            if (selected) {
                                Button(onClick = { onRuntimeSelected(engine) }, modifier = Modifier.weight(1f)) {
                                    Text(engine.label)
                                }
                            } else {
                                OutlinedButton(onClick = { onRuntimeSelected(engine) }, modifier = Modifier.weight(1f)) {
                                    Text(engine.label)
                                }
                            }
                        }
                    }
                    Text("Runtime: ${benchmarkState.selectedEngineType.label}")
                    Text("Model: ${selectedModel?.displayName ?: "Seçilmedi"}")
                    Button(onClick = { modelPickerVisible = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Model seç")
                    }
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
            OutlinedTextField(
                value = testState.question,
                onValueChange = onQuestionChanged,
                label = { Text("Soru") },
                minLines = 4,
                maxLines = 8,
                enabled = !testState.isRunning,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Button(
                onClick = onSubmit,
                enabled = !testState.isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (testState.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
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
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

    if (modelPickerVisible) {
        TestModelPicker(
            benchmarkState = benchmarkState,
            onDismiss = { modelPickerVisible = false },
            onModelSelected = { modelId ->
                modelPickerVisible = false
                onModelSelected(modelId)
            }
        )
    }
}

@Composable
private fun TestDetails(state: TestUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

@Composable
private fun TestModelPicker(
    benchmarkState: BenchmarkUiState,
    onDismiss: () -> Unit,
    onModelSelected: (String) -> Unit
) {
    val runtime = runtimeFor(benchmarkState.selectedEngineType)
    val models = remember(benchmarkState.availableModels, runtime) {
        benchmarkState.availableModels.filter { it.runtimeType == runtime }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model seç") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (models.isEmpty()) item { Text("Bu runtime için model bulunamadı.") }
                items(models, key = ModelConfig::id) { model ->
                    val downloadState = benchmarkState.modelDownloadStates[model.id] ?: DownloadState.Idle
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${model.quantization ?: "-"} • ${model.estimatedSize}")
                            Text(modelStatus(model, downloadState))
                            Button(
                                onClick = { onModelSelected(model.id) },
                                enabled = model.isEnabled && model.hasDownloadArtifacts && downloadState !is DownloadState.Downloading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (downloadState is DownloadState.Success) "Kullan" else "İndir ve kullan")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } }
    )
}

private fun runtimeFor(engineType: EngineType): RuntimeType? = when (engineType) {
    EngineType.LLAMA_CPP -> RuntimeType.LLAMA_CPP
    EngineType.LITERT -> RuntimeType.LITERT
    EngineType.EXECUTORCH -> RuntimeType.EXECUTORCH
    EngineType.FAKE -> null
}

private fun modelStatus(model: ModelConfig, state: DownloadState): String = when {
    !model.isEnabled -> model.disabledReason ?: "Kullanılamıyor"
    state is DownloadState.Success -> "İndirildi"
    state is DownloadState.Downloading -> "İndiriliyor: ${state.progress}%"
    state is DownloadState.Failed -> "İndirme başarısız: ${state.message}"
    else -> "İndirilmedi"
}

private fun downloadProgress(state: DownloadState?): String =
    (state as? DownloadState.Downloading)?.let { " • ${it.progress}%" }.orEmpty()

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
