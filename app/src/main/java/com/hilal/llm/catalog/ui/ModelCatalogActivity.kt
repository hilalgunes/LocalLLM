package com.hilal.llm.catalog.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.EngineType
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.RuntimeType
import com.hilal.llm.catalog.download.DownloadState
import com.hilal.llm.ui.theme.LLMTheme

class ModelCatalogActivity : ComponentActivity() {
    private val viewModel: ModelCatalogViewModel by viewModels {
        ModelCatalogViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.loadModels()
        setContent {
            LLMTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(uiState.errorMessage) {
                    uiState.errorMessage?.let { snackbarHostState.showSnackbar(it) }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    ModelCatalogScreen(
                        uiState = uiState,
                        onDownload = viewModel::downloadModel,
                        onDelete = viewModel::deleteModel,
                        onRunBenchmark = { modelId ->
                            val selection = viewModel.createBenchmarkSelection(modelId)
                            if (selection != null) {
                                setResult(
                                    Activity.RESULT_OK,
                                    Intent().putExtra(
                                        BenchmarkModelSelectionExtras.EXTRA_KEY,
                                        selection
                                    )
                                )
                                finish()
                            }
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCatalogScreen(
    uiState: ModelCatalogUiState,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRunBenchmark: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Model Catalog",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Manage downloadable model packages by runtime.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF6B7280)
                    )
                }
            }

            items(uiState.models, key = { it.id }) { model ->
                ModelCatalogCard(
                    model = model,
                    downloadState = uiState.downloadStates[model.id] ?: DownloadState.Idle,
                    isDownloaded = uiState.downloadStates[model.id] == DownloadState.Success,
                    onDownload = onDownload,
                    onDelete = onDelete,
                    onRunBenchmark = onRunBenchmark
                )
            }
        }
    }
}

@Composable
private fun ModelCatalogCard(
    model: ModelConfig,
    downloadState: DownloadState,
    isDownloaded: Boolean,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRunBenchmark: (String) -> Unit
) {
    val runtimeLabel = when (model.runtimeType) {
        RuntimeType.EXECUTORCH -> "ExecuTorch"
        RuntimeType.LITERT -> "LiteRT"
        RuntimeType.LLAMA_CPP -> "llama.cpp"
    }
    val requiredFilesHaveUrls = model.files
        .filter { it.required }
        .all { it.url.isNotBlank() && !it.url.isPlaceholderUrl() }
    val statusLabel = when {
        !model.isEnabled -> "Disabled"
        isDownloaded -> "Downloaded"
        else -> "Not Downloaded"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF6B7280)
                    )
                }
                StatusBadge(text = statusLabel)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(text = runtimeLabel, background = Color(0xFFE5E7EB), textColor = Color(0xFF374151))
                StatusBadge(text = model.engineType.name, background = Color(0xFFF3F4F6), textColor = Color(0xFF374151))
                StatusBadge(
                    text = fileFormatLabel(model),
                    background = Color(0xFFFEF3C7),
                    textColor = Color(0xFF92400E)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoChip(label = "Size", value = model.estimatedSize)
                InfoChip(label = "Quantization", value = model.quantization ?: "-")
            }

            if (!model.isEnabled && !model.disabledReason.isNullOrBlank()) {
                Text(
                    text = model.disabledReason,
                    color = Color(0xFFB45309),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (model.isEnabled && !requiredFilesHaveUrls) {
                Text(
                    text = "Download unavailable until required file URLs are configured.",
                    color = Color(0xFFB45309),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            DownloadStateRow(downloadState = downloadState, model = model)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onDownload(model.id) },
                    enabled = model.isEnabled &&
                        requiredFilesHaveUrls &&
                        !isDownloaded &&
                        downloadState !is DownloadState.Downloading
                ) {
                    Text(if (isDownloaded) "Downloaded" else "Download")
                }

                OutlinedButton(
                    onClick = { onDelete(model.id) },
                    enabled = isDownloaded
                ) {
                    Text("Delete")
                }

                TextButton(
                    onClick = { onRunBenchmark(model.id) },
                    enabled = model.isEnabled && isDownloaded && downloadState !is DownloadState.Downloading
                ) {
                    Text("Run Benchmark")
                }
            }
        }
    }
}

private object BenchmarkModelSelectionExtras {
    const val EXTRA_KEY = "extra_benchmark_model_selection"
}

private fun String.isPlaceholderUrl(): Boolean {
    val normalized = trim().lowercase()
    return normalized.isBlank() ||
        normalized.contains("example.com") ||
        normalized.contains("placeholder") ||
        normalized == "url" ||
        normalized == "tbd" ||
        normalized == "todo"
}

@Composable
private fun DownloadStateRow(
    downloadState: DownloadState,
    model: ModelConfig
) {
    when (downloadState) {
        DownloadState.Idle -> {
            Text(
                text = if (model.isEnabled) "Ready for download" else "Model disabled",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B7280)
            )
        }
        is DownloadState.Downloading -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Downloading ${downloadState.fileName} • ${downloadState.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF374151)
                )
                LinearProgressIndicator(
                    progress = { downloadState.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        DownloadState.Success -> {
            Text(
                text = "Downloaded",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF166534)
            )
        }
        is DownloadState.Failed -> {
            Text(
                text = downloadState.message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB91C1C)
            )
        }
    }
}

private fun fileFormatLabel(model: ModelConfig): String {
    return when (model.engineType) {
        EngineType.EXECUTORCH_GENERATE -> "PTE + Tokenizer"
        EngineType.EXECUTORCH_FORWARD -> "PTE Forward-only"
        EngineType.LITERT_LM -> "LiteRT Bundle / Task / TFLite"
        EngineType.LLAMA_CPP -> "GGUF / Native Runtime"
    }
}

@Composable
private fun StatusBadge(
    text: String,
    background: Color = Color(0xFFEFF6FF),
    textColor: Color = Color(0xFF1D4ED8)
) {
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun InfoChip(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier
            .background(Color(0xFFF9FAFB), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF6B7280))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF111827))
    }
}
