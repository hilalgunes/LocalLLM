package com.hilal.llm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hilal.llm.benchmark.BenchmarkResult
import com.hilal.llm.benchmark.BenchmarkPresetRepository
import com.hilal.llm.benchmark.BenchmarkUiState
import com.hilal.llm.benchmark.BenchmarkViewModel
import com.hilal.llm.benchmark.EngineType
import com.hilal.llm.benchmark.ResponseQuality
import com.hilal.llm.benchmark.displayLabel
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.RuntimeType
import com.hilal.llm.benchmark.history.BenchmarkHistoryActivity
import com.hilal.llm.catalog.ui.ModelCatalogActivity
import com.hilal.llm.rag.RagActivity
import com.hilal.llm.ui.theme.LLMTheme

class MainActivity : ComponentActivity() {
    private val viewModel: BenchmarkViewModel by viewModels()

    private val modelPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val uri = data.data ?: return@registerForActivityResult

            val takeFlags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: SecurityException) {
                // Some providers do not support persisted permissions.
            }

            val displayName = readDisplayName(uri) ?: "model-file"
            viewModel.onModelFileSelected(displayName = displayName, uri = uri.toString())
        }

    private val tokenizerPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val uri = data.data ?: return@registerForActivityResult

            val takeFlags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: SecurityException) {
                // Some providers do not support persisted permissions.
            }

            val displayName = readDisplayName(uri) ?: "tokenizer-file"
            viewModel.onTokenizerFileSelected(displayName = displayName, uri = uri.toString())
        }

    private val catalogLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val selection = BenchmarkModelSelection.fromIntent(result.data) ?: return@registerForActivityResult
            viewModel.onCatalogModelSelected(selection)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initializeLlamaEngine(
            appContext = applicationContext,
            contentResolver = contentResolver
        )
        viewModel.initializeLiteRtEngine(
            appContext = applicationContext,
            contentResolver = contentResolver
        )
        viewModel.initializeMediaPipeEngine(
            appContext = applicationContext,
            contentResolver = contentResolver
        )
        viewModel.initializeExecuTorchEngine(
            appContext = applicationContext,
            contentResolver = contentResolver
        )
        viewModel.initializeBenchmarkHistoryRepository(applicationContext)
        enableEdgeToEdge()
        setContent {
            LLMTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BenchmarkScreen(
                        uiState = uiState,
                        onEngineSelected = viewModel::setEngine,
                        onPresetSelected = viewModel::selectPreset,
                        onSelectModelFile = ::openModelPicker,
                        onSelectTokenizerFile = ::openTokenizerPicker,
                        onMaxTokensChanged = viewModel::updateMaxTokensInput,
                        onPromptChanged = viewModel::updatePrompt,
                        onRunBenchmark = viewModel::runBenchmark,
                        onClear = viewModel::clear,
                        onQualityChanged = viewModel::updateDraftResponseQuality,
                        onQualityNoteChanged = viewModel::updateDraftQualityNote,
                        onSaveQuality = viewModel::saveLatestResultQuality,
                        onOpenCatalog = ::openModelCatalog,
                        onOpenHistory = ::openBenchmarkHistory,
                        onOpenRag = ::openRagPage,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun openModelPicker() {
        openDocumentPicker(modelPickerLauncher)
    }

    private fun openTokenizerPicker() {
        openDocumentPicker(tokenizerPickerLauncher)
    }

    private fun openModelCatalog() {
        catalogLauncher.launch(Intent(this, ModelCatalogActivity::class.java))
    }

    private fun openBenchmarkHistory() {
        startActivity(Intent(this, BenchmarkHistoryActivity::class.java))
    }

    private fun openRagPage() {
        startActivity(Intent(this, RagActivity::class.java))
    }

    private fun openDocumentPicker(
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
            )
        }
        launcher.launch(intent)
    }

    private fun readDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }
}

@Composable
private fun BenchmarkScreen(
    uiState: BenchmarkUiState,
    onEngineSelected: (EngineType) -> Unit,
    onPresetSelected: (String) -> Unit,
    onSelectModelFile: () -> Unit,
    onSelectTokenizerFile: () -> Unit,
    onMaxTokensChanged: (String) -> Unit,
    onPromptChanged: (String) -> Unit,
    onRunBenchmark: () -> Unit,
    onClear: () -> Unit,
    onQualityChanged: (ResponseQuality) -> Unit,
    onQualityNoteChanged: (String) -> Unit,
    onSaveQuality: () -> Unit,
    onOpenCatalog: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenRag: () -> Unit,
    modifier: Modifier = Modifier
) {
    val presetRepository = remember { BenchmarkPresetRepository() }
    val presets = remember { presetRepository.getAvailablePresets() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenCatalog) {
                        Text("Open Model Catalog")
                    }
                    TextButton(onClick = onOpenHistory) {
                        Text("Open History")
                    }
                    TextButton(onClick = onOpenRag) {
                        Text("Open RAG")
                    }
                }
            }

        item {
            Text(
                text = "Local LLM Benchmark",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Engine", style = MaterialTheme.typography.titleMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        EngineType.entries.forEach { engineType ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                RadioButton(
                                    selected = uiState.selectedEngineType == engineType,
                                    onClick = { onEngineSelected(engineType) }
                                )
                                Text(
                                    text = engineType.label,
                                    modifier = Modifier.padding(top = 12.dp, end = 8.dp)
                                )
                            }
                        }
                    }
                    Button(onClick = onSelectModelFile) {
                        Text("Select Model File")
                    }
                    Button(onClick = onSelectTokenizerFile) {
                        Text("Select Tokenizer File")
                    }
                    SelectedModelSummary(uiState = uiState)
                    if (!uiState.modelWarningMessage.isNullOrBlank()) {
                        Text(
                            text = "Warning: ${uiState.modelWarningMessage}",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Benchmark Presets", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Preset: ${uiState.selectedPreset?.title ?: "Custom"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            PresetChip(
                                title = "Custom",
                                description = "Manual prompt",
                                selected = uiState.selectedPreset == null,
                                onClick = {
                                    onPresetSelected("")
                                }
                            )
                        }
                        items(presets, key = { it.id }) { preset ->
                            PresetChip(
                                title = preset.title,
                                description = preset.description,
                                selected = uiState.selectedPreset?.id == preset.id,
                                onClick = { onPresetSelected(preset.id) }
                            )
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = uiState.maxTokensInput,
                onValueChange = onMaxTokensChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Max Tokens") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        item {
            OutlinedTextField(
                value = uiState.prompt,
                onValueChange = onPromptChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                label = { Text("Prompt") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRunBenchmark,
                    enabled = !uiState.isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (uiState.isRunning) "Running..." else "Run Benchmark")
                }
                TextButton(
                    onClick = onClear,
                    enabled = !uiState.isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Result", style = MaterialTheme.typography.titleMedium)
            Text("Engine: ${uiState.selectedEngineType.label}")
            Text("Max tokens: ${uiState.maxTokensInput}")
                    SelectedModelSummary(uiState = uiState)
                    Text("Preset: ${uiState.selectedPreset?.title ?: "Custom"}")
                    Text("Response: ${uiState.response.ifBlank { "-" }}")
                    Text("First token time: ${uiState.firstTokenTimeMs?.let { "$it ms" } ?: "-"}")
                    Text("Total time: ${uiState.totalTimeMs?.let { "$it ms" } ?: "-"}")
                    Text("Token count: ${uiState.tokenCount}")
                    Text(
                        "Tokens/sec: ${
                            uiState.tokensPerSecond?.let { String.format("%.2f", it) } ?: "-"
                        }"
                    )
                    val latestResult = uiState.completedResults.firstOrNull()
                    Text("RAM: ${latestResult?.totalRamMb?.let { String.format("%.2f MB", it) } ?: "-"}")
                    Text("Native RAM: ${latestResult?.nativeRamMb?.let { String.format("%.2f MB", it) } ?: "-"}")
                    Text("Java RAM: ${latestResult?.javaRamMb?.let { String.format("%.2f MB", it) } ?: "-"}")
                    if (!uiState.errorMessage.isNullOrBlank()) {
                        Text(
                            text = "Error: ${uiState.errorMessage}",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        uiState.completedResults.firstOrNull()?.let { latestResult ->
            item {
                QualityRatingSection(
                    result = latestResult,
                    selectedQuality = uiState.draftResponseQuality,
                    qualityNote = uiState.draftQualityNote,
                    onQualityChanged = onQualityChanged,
                    onQualityNoteChanged = onQualityNoteChanged,
                    onSaveQuality = onSaveQuality
                )
            }
        }

        item {
            Text("Completed Runs", style = MaterialTheme.typography.titleMedium)
        }

        if (uiState.completedResults.isEmpty()) {
            item { Text("No benchmark runs yet.") }
        } else {
            items(uiState.completedResults) { result ->
                CompletedResultItem(result = result)
            }
        }
    }
}

@Composable
private fun SelectedModelSummary(uiState: BenchmarkUiState) {
    val selectedCatalogModel = uiState.selectedCatalogModel
    val selectedModel = uiState.selectedModel
    val runtimeLabel = when {
        selectedCatalogModel != null -> selectedCatalogModel.runtimeType.displayName()
        uiState.selectedEngineType == EngineType.EXECUTORCH -> "ExecuTorch"
        uiState.selectedEngineType == EngineType.LITERT -> "LiteRT"
        uiState.selectedEngineType == EngineType.LLAMA_CPP -> "llama.cpp"
        else -> "Manual"
    }
    val engineLabel = selectedCatalogModel?.engineType?.name ?: uiState.selectedEngineType.label
    val promptTemplateLabel = selectedCatalogModel?.promptTemplateType?.name ?: "-"
    val mainModelFile = selectedCatalogModel?.modelPath ?: selectedModel?.modelFilePath ?: "-"
    val tokenizerFile = selectedCatalogModel?.tokenizerPath ?: selectedModel?.tokenizerFilePath ?: "-"
    val modelName = selectedCatalogModel?.displayName ?: selectedModel?.modelName ?: "No model selected"

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Model: $modelName")
        Text("Runtime: $runtimeLabel")
        Text("Engine: $engineLabel")
        Text("Prompt template: $promptTemplateLabel")
        Text("Main model file: $mainModelFile")
        Text("Tokenizer file: $tokenizerFile")
    }
}

private fun RuntimeType.displayName(): String {
    return when (this) {
        RuntimeType.EXECUTORCH -> "ExecuTorch"
        RuntimeType.LITERT -> "LiteRT"
        RuntimeType.LLAMA_CPP -> "llama.cpp"
    }
}

@Composable
private fun CompletedResultItem(result: BenchmarkResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Engine: ${result.engineName}")
            Text("Max tokens: ${result.maxTokens}")
            Text(result.modelName, style = MaterialTheme.typography.titleSmall)
            Text("Prompt: ${result.prompt}")
            Text("Total: ${result.totalTimeMs?.let { "$it ms" } ?: "-"}")
            Text("First token: ${result.firstTokenTimeMs?.let { "$it ms" } ?: "-"}")
            Text("Tokens: ${result.tokenCount}")
            Text("TPS: ${result.tokensPerSecond?.let { String.format("%.2f", it) } ?: "-"}")
            Text("RAM: ${result.totalRamMb?.let { String.format("%.2f MB", it) } ?: "-"}")
            Text("Native RAM: ${result.nativeRamMb?.let { String.format("%.2f MB", it) } ?: "-"}")
            Text("Java RAM: ${result.javaRamMb?.let { String.format("%.2f MB", it) } ?: "-"}")
            if (!result.errorMessage.isNullOrBlank()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Text(
                    text = "Error: ${result.errorMessage}",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BenchmarkScreenPreview() {
    LLMTheme {
        BenchmarkScreen(
            uiState = BenchmarkUiState(),
            onEngineSelected = {},
            onPresetSelected = {},
            onSelectModelFile = {},
            onSelectTokenizerFile = {},
            onMaxTokensChanged = {},
            onPromptChanged = {},
            onRunBenchmark = {},
            onClear = {},
            onQualityChanged = {},
            onQualityNoteChanged = {},
            onSaveQuality = {},
            onOpenCatalog = {},
            onOpenHistory = {},
            onOpenRag = {}
        )
    }
}

@Composable
private fun PresetChip(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick) {
            Column {
                Text(title)
                Text(description, style = MaterialTheme.typography.labelSmall)
            }
        }
    } else {
        TextButton(onClick = onClick) {
            Column {
                Text(title)
                Text(description, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun QualityRatingSection(
    result: BenchmarkResult,
    selectedQuality: ResponseQuality,
    qualityNote: String,
    onQualityChanged: (ResponseQuality) -> Unit,
    onQualityNoteChanged: (String) -> Unit,
    onSaveQuality: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Response Quality", style = MaterialTheme.typography.titleMedium)
            Text("Current: ${result.responseQuality.displayLabel()}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityChip("Good", selectedQuality == ResponseQuality.GOOD) { onQualityChanged(ResponseQuality.GOOD) }
                QualityChip("Partial", selectedQuality == ResponseQuality.PARTIAL) { onQualityChanged(ResponseQuality.PARTIAL) }
                QualityChip("Bad", selectedQuality == ResponseQuality.BAD) { onQualityChanged(ResponseQuality.BAD) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityChip("Repeated", selectedQuality == ResponseQuality.REPEATED) { onQualityChanged(ResponseQuality.REPEATED) }
                QualityChip("Empty", selectedQuality == ResponseQuality.EMPTY) { onQualityChanged(ResponseQuality.EMPTY) }
                QualityChip("Not rated", selectedQuality == ResponseQuality.UNKNOWN) { onQualityChanged(ResponseQuality.UNKNOWN) }
            }
            OutlinedTextField(
                value = qualityNote,
                onValueChange = onQualityNoteChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Quality note (optional)") }
            )
            TextButton(onClick = onSaveQuality) {
                Text("Save Quality")
            }
        }
    }
}

@Composable
private fun QualityChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick) {
            Text(label)
        }
    } else {
        TextButton(onClick = onClick) {
            Text(label)
        }
    }
}
