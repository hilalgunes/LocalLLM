package com.hilal.llm.benchmark.history

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hilal.llm.benchmark.BenchmarkResult
import com.hilal.llm.benchmark.ResponseQuality
import com.hilal.llm.benchmark.SelectionSource
import com.hilal.llm.catalog.RuntimeType
import com.hilal.llm.ui.theme.LLMTheme

class BenchmarkHistoryActivity : ComponentActivity() {
    private val viewModel: BenchmarkHistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LLMTheme {
                BenchmarkHistoryRoute(viewModel = viewModel)
            }
        }
    }
}

@Composable
internal fun BenchmarkAnalyticsScreen(
    uiState: BenchmarkHistoryUiState,
    onRuntimeFilterSelected: (RuntimeType?) -> Unit,
    onSourceFilterSelected: (SelectionSource?) -> Unit,
    onToggleCompare: (BenchmarkResult) -> Unit,
    onQualityChanged: (String, ResponseQuality, String?) -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onClearCompare: () -> Unit,
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onExportCsv,
                        enabled = !uiState.isExporting && uiState.filteredResults.isNotEmpty()
                    ) {
                        Text("Export CSV")
                    }
                    Button(
                        onClick = onExportJson,
                        enabled = !uiState.isExporting && uiState.filteredResults.isNotEmpty()
                    ) {
                        Text("Export JSON")
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Benchmark Analytics",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Persisted benchmark runs from benchmark_results.json",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF6B7280)
                    )
                }
            }

            item {
                QualitySummarySection(analytics = uiState.analytics)
            }

            item {
                SummarySection(analytics = uiState.analytics)
            }

            item {
                FilterSection(
                    selectedRuntimeFilter = uiState.selectedRuntimeFilter,
                    selectedSourceFilter = uiState.selectedSourceFilter,
                    onRuntimeFilterSelected = onRuntimeFilterSelected,
                    onSourceFilterSelected = onSourceFilterSelected
                )
            }

            if (uiState.compareLeft != null || uiState.compareRight != null) {
                item {
                    CompareSection(
                        left = uiState.compareLeft,
                        right = uiState.compareRight,
                        onClearCompare = onClearCompare
                    )
                }
            }

            item {
                Text(
                    text = "Results (${uiState.filteredResults.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (uiState.filteredResults.isEmpty()) {
                item {
                    Text(
                        text = "No saved benchmark results yet.",
                        color = Color(0xFF6B7280)
                    )
                }
            } else {
                items(uiState.filteredResults, key = { it.createdAt }) { result ->
                    BenchmarkHistoryCard(
                        result = result,
                        selectedForCompare = result.createdAt in uiState.compareSelectionKeys,
                        onToggleCompare = onToggleCompare,
                        onQualityChanged = onQualityChanged
                    )
                }
            }
        }
    }
}

@Composable
private fun SummarySection(analytics: BenchmarkAnalytics) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryCard(
                title = "Total Benchmarks",
                value = analytics.totalBenchmarks.toString(),
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "Unique Models",
                value = analytics.uniqueModels.toString(),
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "Fastest TPS",
                value = analytics.fastestTps?.let { String.format("%.2f", it) } ?: "-",
                subtitle = analytics.fastestModelName ?: "-",
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryCard(
                title = "Lowest RAM",
                value = analytics.lowestRam?.let { String.format("%.2f MB", it) } ?: "-",
                subtitle = analytics.lowestRamModelName ?: "-",
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "Average TPS",
                value = analytics.averageTps?.let { String.format("%.2f", it) } ?: "-",
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "Average First Token Time",
                value = analytics.averageFirstTokenTime?.let { String.format("%.0f ms", it) } ?: "-",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun QualitySummarySection(analytics: BenchmarkAnalytics) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryCard(
                title = "Good",
                value = analytics.goodCount.toString(),
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "Bad",
                value = analytics.badCount.toString(),
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryCard(
                title = "Repeated",
                value = analytics.repeatedCount.toString(),
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "Empty",
                value = analytics.emptyCount.toString(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Color(0xFF6B7280))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
            }
        }
    }
}

@Composable
private fun FilterSection(
    selectedRuntimeFilter: RuntimeType?,
    selectedSourceFilter: SelectionSource?,
    onRuntimeFilterSelected: (RuntimeType?) -> Unit,
    onSourceFilterSelected: (SelectionSource?) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Filters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FilterChipRow(
                label = "Runtime",
                selectedIndex = when (selectedRuntimeFilter) {
                    null -> 0
                    RuntimeType.EXECUTORCH -> 1
                    RuntimeType.LITERT -> 2
                    RuntimeType.ONNX -> 4
                    RuntimeType.LLAMA_CPP -> 3
                },
                chips = listOf("All", "ExecuTorch", "LiteRT", "llama.cpp", "ONNX"),
                onSelected = { index ->
                    onRuntimeFilterSelected(
                        when (index) {
                            1 -> RuntimeType.EXECUTORCH
                            2 -> RuntimeType.LITERT
                            4 -> RuntimeType.ONNX
                            3 -> RuntimeType.LLAMA_CPP
                            else -> null
                        }
                    )
                }
            )
            FilterChipRow(
                label = "Source",
                selectedIndex = when (selectedSourceFilter) {
                    null -> 0
                    SelectionSource.MANUAL -> 1
                    SelectionSource.CATALOG -> 2
                },
                chips = listOf("All", "Manual", "Catalog"),
                onSelected = { index ->
                    onSourceFilterSelected(
                        when (index) {
                            1 -> SelectionSource.MANUAL
                            2 -> SelectionSource.CATALOG
                            else -> null
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun FilterChipRow(
    label: String,
    chips: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF6B7280))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            chips.forEachIndexed { index, chipLabel ->
                val selected = selectedIndex == index
                if (selected) {
                    Button(
                        onClick = { onSelected(index) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(chipLabel)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelected(index) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(chipLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareSection(
    left: BenchmarkResult?,
    right: BenchmarkResult?,
    onClearCompare: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Compare Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = onClearCompare) {
                    Text("Clear")
                }
            }

            if (left == null || right == null) {
                Text(
                    text = "Select two results to compare them.",
                    color = Color(0xFF6B7280)
                )
            } else {
                Text("${left.modelName} vs ${right.modelName}", fontWeight = FontWeight.SemiBold)
                CompareMetricRow("TPS", left.tokensPerSecond, right.tokensPerSecond, suffix = "")
                CompareMetricRow("First Token Time", left.firstTokenTimeMs?.toDouble(), right.firstTokenTimeMs?.toDouble(), suffix = " ms")
                CompareMetricRow("Total RAM", left.totalRamMb, right.totalRamMb, suffix = " MB")
                CompareMetricRow("Native RAM", left.nativeRamMb, right.nativeRamMb, suffix = " MB")
                CompareMetricRow("Java RAM", left.javaRamMb, right.javaRamMb, suffix = " MB")
                CompareMetricRow("Token Count", left.tokenCount.toDouble(), right.tokenCount.toDouble(), suffix = "")
            }
        }
    }
}

@Composable
private fun CompareMetricRow(
    label: String,
    leftValue: Double?,
    rightValue: Double?,
    suffix: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF6B7280))
        Text("A: ${formatMetric(leftValue, suffix)}  |  B: ${formatMetric(rightValue, suffix)}")
    }
}

@Composable
private fun BenchmarkHistoryCard(
    result: BenchmarkResult,
    selectedForCompare: Boolean,
    onToggleCompare: (BenchmarkResult) -> Unit,
    onQualityChanged: (String, ResponseQuality, String?) -> Unit
) {
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(result.modelName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RuntimeBadge(result = result)
                        SourceBadge(source = result.selectionSource)
                        QualityBadge(quality = result.responseQuality)
                    }
                }
                OutlinedButton(onClick = { onToggleCompare(result) }) {
                    Text(if (selectedForCompare) "Selected" else "Compare")
                }
            }

            Text("Engine: ${result.engineName}", color = Color(0xFF6B7280))
            if (!result.presetTitle.isNullOrBlank()) {
                Text("Preset: ${result.presetTitle}", color = Color(0xFF6B7280))
            }
            Text("Prompt: ${result.prompt}")
            Text("Response: ${result.response.ifBlank { "-" }}")
            Text("Total: ${result.totalTimeMs?.let { "$it ms" } ?: "-"}")
            Text("First token: ${result.firstTokenTimeMs?.let { "$it ms" } ?: "-"}")
            Text("Tokens: ${result.tokenCount}")
            Text("TPS: ${result.tokensPerSecond?.let { String.format("%.2f", it) } ?: "-"}")
            Text("RAM: ${result.totalRamMb?.let { String.format("%.2f MB", it) } ?: "-"}")
            if (!result.qualityNote.isNullOrBlank()) {
                Text(
                    text = "Note: ${result.qualityNote}",
                    color = Color(0xFF6B7280)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QualityChangeButton("Good", result, ResponseQuality.GOOD, onQualityChanged)
                QualityChangeButton("Partial", result, ResponseQuality.PARTIAL, onQualityChanged)
                QualityChangeButton("Bad", result, ResponseQuality.BAD, onQualityChanged)
                QualityChangeButton("Rep", result, ResponseQuality.REPEATED, onQualityChanged)
                QualityChangeButton("Empty", result, ResponseQuality.EMPTY, onQualityChanged)
            }
            if (!result.errorMessage.isNullOrBlank()) {
                Text(text = "Error: ${result.errorMessage}", color = Color(0xFFB91C1C))
            }
        }
    }
}

@Composable
private fun QualityChangeButton(
    label: String,
    result: BenchmarkResult,
    quality: ResponseQuality,
    onQualityChanged: (String, ResponseQuality, String?) -> Unit
) {
    TextButton(
        onClick = { onQualityChanged(result.resultId, quality, result.qualityNote) }
    ) {
        Text(label)
    }
}

@Composable
private fun RuntimeBadge(result: BenchmarkResult) {
    val runtimeType = inferRuntimeType(result)
    val label = when {
        result.selectionSource == SelectionSource.MANUAL -> "Manual"
        runtimeType == RuntimeType.EXECUTORCH -> "ExecuTorch"
        runtimeType == RuntimeType.LITERT -> "LiteRT"
        runtimeType == RuntimeType.ONNX -> "ONNX"
        runtimeType == RuntimeType.LLAMA_CPP -> "llama.cpp"
        else -> "Unknown"
    }
    val background = when {
        result.selectionSource == SelectionSource.MANUAL -> Color(0xFFF3F4F6)
        runtimeType == RuntimeType.EXECUTORCH -> Color(0xFFE0F2FE)
        runtimeType == RuntimeType.LITERT -> Color(0xFFECFDF5)
        runtimeType == RuntimeType.LLAMA_CPP -> Color(0xFFF3E8FF)
        else -> Color(0xFFE5E7EB)
    }
    val textColor = when {
        result.selectionSource == SelectionSource.MANUAL -> Color(0xFF374151)
        runtimeType == RuntimeType.EXECUTORCH -> Color(0xFF0369A1)
        runtimeType == RuntimeType.LITERT -> Color(0xFF047857)
        runtimeType == RuntimeType.LLAMA_CPP -> Color(0xFF7C3AED)
        else -> Color(0xFF374151)
    }
    Badge(label = label, background = background, textColor = textColor)
}

@Composable
private fun SourceBadge(source: SelectionSource) {
    val (label, background, textColor) = when (source) {
        SelectionSource.MANUAL -> Triple("Manual", Color(0xFFF3F4F6), Color(0xFF374151))
        SelectionSource.CATALOG -> Triple("Catalog", Color(0xFFFFF7ED), Color(0xFF9A3412))
    }
    Badge(label = label, background = background, textColor = textColor)
}

@Composable
private fun QualityBadge(quality: ResponseQuality) {
    val (label, background, textColor) = when (quality) {
        ResponseQuality.UNKNOWN -> Triple("Not rated", Color(0xFFF3F4F6), Color(0xFF374151))
        ResponseQuality.GOOD -> Triple("Good", Color(0xFFDCFCE7), Color(0xFF166534))
        ResponseQuality.PARTIAL -> Triple("Partial", Color(0xFFFEF3C7), Color(0xFF92400E))
        ResponseQuality.BAD -> Triple("Bad", Color(0xFFFEE2E2), Color(0xFFB91C1C))
        ResponseQuality.REPEATED -> Triple("Repeated", Color(0xFFEDE9FE), Color(0xFF6D28D9))
        ResponseQuality.EMPTY -> Triple("Empty", Color(0xFFE5E7EB), Color(0xFF374151))
    }
    Badge(label = label, background = background, textColor = textColor)
}

@Composable
private fun Badge(label: String, background: Color, textColor: Color) {
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.labelSmall)
    }
}

private fun inferRuntimeType(result: BenchmarkResult): RuntimeType? {
    result.runtimeType?.let { return it }
    val key = "${result.engineName} ${result.modelFilePath}".lowercase()
    return when {
        "executorch" in key -> RuntimeType.EXECUTORCH
        "litert" in key -> RuntimeType.LITERT
        "llama" in key -> RuntimeType.LLAMA_CPP
        else -> null
    }
}

private fun formatMetric(value: Double?, suffix: String): String {
    return if (value == null) "-" else String.format("%.2f%s", value, suffix)
}
