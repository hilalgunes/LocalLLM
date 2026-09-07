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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.hilal.llm.benchmark.productionEngineTypes
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.RuntimeType
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.download.DownloadState
import com.hilal.llm.catalog.ui.ModelCatalogActivity
import com.hilal.llm.compare.CompareScreen
import com.hilal.llm.benchmark.history.BenchmarkHistoryRoute
import com.hilal.llm.benchmark.history.BenchmarkHistoryViewModel
import com.hilal.llm.navigation.MainBottomNavigation
import com.hilal.llm.navigation.MainDestination
import com.hilal.llm.rag.RagRoute
import com.hilal.llm.rag.RagViewModel
import com.hilal.llm.test.TestRoute
import com.hilal.llm.test.TestViewModel
import com.hilal.llm.ui.theme.LLMTheme
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {
    private val viewModel: BenchmarkViewModel by viewModels()
    private val ragViewModel: RagViewModel by viewModels()
    private val historyViewModel: BenchmarkHistoryViewModel by viewModels()
    private val testViewModel: TestViewModel by viewModels()

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
        PDFBoxResourceLoader.init(applicationContext)
        viewModel.initializeLlamaEngine(
            appContext = applicationContext,
            contentResolver = contentResolver
        )
        viewModel.initializeLiteRtEngine(
            appContext = applicationContext,
            contentResolver = contentResolver
        )
        viewModel.initializeExecuTorchEngine(
            appContext = applicationContext,
            contentResolver = contentResolver
        )
        viewModel.initializeBenchmarkHistoryRepository(applicationContext)
        viewModel.initializeModelCatalog(applicationContext)
        ragViewModel.initializeEngines(contentResolver)
        ragViewModel.loadModels()
        enableEdgeToEdge()
        setContent {
            LLMTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val navController = rememberNavController()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        MainBottomNavigation(navController = navController)
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = MainDestination.RUN.route,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable(MainDestination.RUN.route) {
                            RunScreen(
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
                                onShowModelPicker = viewModel::showModelPicker,
                                onHideModelPicker = viewModel::hideModelPicker,
                                onSelectCatalogModel = viewModel::selectCatalogModel,
                                onCancelModelDownload = viewModel::cancelModelDownload
                            )
                        }
                        composable(MainDestination.COMPARE.route) {
                            CompareScreen()
                        }
                        composable(MainDestination.RAG.route) {
                            RagRoute(viewModel = ragViewModel)
                        }
                        composable(MainDestination.TEST.route) {
                            TestRoute(
                                testViewModel = testViewModel,
                                benchmarkViewModel = viewModel
                            )
                        }
                        composable(MainDestination.HISTORY.route) {
                            BenchmarkHistoryRoute(viewModel = historyViewModel)
                        }
                    }
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
private fun RunScreen(
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
    onShowModelPicker: () -> Unit,
    onHideModelPicker: () -> Unit,
    onSelectCatalogModel: (String) -> Unit,
    onCancelModelDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val presetRepository = remember { BenchmarkPresetRepository() }
    val presets = remember { presetRepository.getAvailablePresets() }
    var showAdvancedModelDetails by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Local LLM Run",
                    style = MaterialTheme.typography.headlineSmall
                )
                TextButton(onClick = onOpenCatalog) {
                    Text("Models")
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
                    Text("Runtime Selection", style = MaterialTheme.typography.titleMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        productionEngineTypes.forEach { engineType ->
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
                    Text("Model", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onShowModelPicker, modifier = Modifier.fillMaxWidth()) {
                        Text("Choose Model")
                    }
                    RunModelSummary(
                        uiState = uiState,
                        showAdvanced = showAdvancedModelDetails,
                        onToggleAdvanced = { showAdvancedModelDetails = !showAdvancedModelDetails },
                        onSelectManualModelFile = onSelectModelFile,
                        onSelectManualTokenizerFile = onSelectTokenizerFile
                    )
                    if (!uiState.modelWarningMessage.isNullOrBlank()) {
                        Text(
                            text = "Warning: ${uiState.modelWarningMessage}",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (!uiState.errorMessage.isNullOrBlank()) {
                        Text(
                            text = uiState.errorMessage,
                            color = MaterialTheme.colorScheme.error
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

    if (uiState.isModelPickerVisible) {
        RuntimeModelPickerDialog(
            uiState = uiState,
            onDismiss = onHideModelPicker,
            onSelectModel = onSelectCatalogModel
        )
    }

    uiState.activeDownloadModelId?.let { modelId ->
        val model = uiState.availableModels.firstOrNull { it.id == modelId }
        if (model != null) {
            ModelDownloadDialog(
                model = model,
                state = uiState.modelDownloadStates[modelId] ?: DownloadState.Idle,
                onCancel = onCancelModelDownload
            )
        }
    }
}

@Composable
private fun RunModelSummary(
    uiState: BenchmarkUiState,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onSelectManualModelFile: () -> Unit,
    onSelectManualTokenizerFile: () -> Unit
) {
    val config = uiState.selectedModelConfig
    if (config == null) {
        Text("No model selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("Model: ${config.displayName}")
        Text("Runtime: ${config.runtimeType.displayName()}")
        Text("Quantization: ${config.quantization ?: "-"}")
        Text("Status: Ready")
        Text("Size: ${config.estimatedSize}")
    }

    TextButton(onClick = onToggleAdvanced) {
        Text(if (showAdvanced) "Hide advanced details" else "Advanced / manual files")
    }
    if (showAdvanced) {
        SelectedModelSummary(uiState = uiState)
        Text(
            "Development fallback",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSelectManualModelFile, modifier = Modifier.weight(1f)) {
                Text("Manual model file")
            }
            TextButton(onClick = onSelectManualTokenizerFile, modifier = Modifier.weight(1f)) {
                Text("Manual tokenizer")
            }
        }
    }
}

@Composable
private fun RuntimeModelPickerDialog(
    uiState: BenchmarkUiState,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    val runtime = when (uiState.selectedEngineType) {
        EngineType.LLAMA_CPP -> RuntimeType.LLAMA_CPP
        EngineType.LITERT -> RuntimeType.LITERT
        EngineType.EXECUTORCH -> RuntimeType.EXECUTORCH
        EngineType.FAKE -> null
    }
    val models = uiState.availableModels.filter { it.runtimeType == runtime }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose ${runtime?.displayName() ?: "Runtime"} Model") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (models.isEmpty()) {
                    item { Text("No compatible generation models are available.") }
                }
                items(models, key = { it.id }) { model ->
                    val state = uiState.modelDownloadStates[model.id] ?: DownloadState.Idle
                    val status = when {
                        !model.isEnabled -> "Unavailable"
                        !model.hasDownloadArtifacts -> "Download unavailable"
                        state is DownloadState.Success -> "Downloaded"
                        state is DownloadState.Downloading -> "Downloading ${state.progress}%"
                        state is DownloadState.Failed -> "Download failed"
                        else -> "Not downloaded"
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${model.family} • ${model.quantization ?: "-"} • ${model.estimatedSize}")
                            Text(
                                "${model.formatLabel} • $status",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { onSelectModel(model.id) },
                                enabled = model.isEnabled && model.hasDownloadArtifacts &&
                                    state !is DownloadState.Downloading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (state is DownloadState.Success) "Use Model" else "Download and Use")
                            }
                            if (!model.isEnabled && !model.disabledReason.isNullOrBlank()) {
                                Text(model.disabledReason, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ModelDownloadDialog(
    model: ModelConfig,
    state: DownloadState,
    onCancel: () -> Unit
) {
    val downloading = state as? DownloadState.Downloading
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Downloading ${model.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(downloading?.fileName ?: "Preparing download…")
                if (downloading != null) {
                    Text(
                        "${formatBytes(downloading.downloadedBytes)} / " +
                            (downloading.totalBytes?.let(::formatBytes) ?: "Unknown size")
                    )
                    Text("${downloading.progress}%")
                    if (downloading.totalBytes != null) {
                        LinearProgressIndicator(
                            progress = { downloading.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1_024.0 && unitIndex < units.lastIndex) {
        value /= 1_024.0
        unitIndex++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[unitIndex])
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
fun RunScreenPreview() {
    LLMTheme {
        RunScreen(
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
            onShowModelPicker = {},
            onHideModelPicker = {},
            onSelectCatalogModel = {},
            onCancelModelDownload = {}
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
