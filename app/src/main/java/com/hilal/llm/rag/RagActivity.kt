package com.hilal.llm.rag

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.hilal.llm.catalog.ModelConfig
import com.hilal.llm.catalog.download.DownloadState
import com.hilal.llm.ui.theme.LLMTheme

class RagActivity : ComponentActivity() {
    private val viewModel: RagViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        viewModel.initializeEngines(contentResolver)
        viewModel.loadModels()
        enableEdgeToEdge()

        setContent {
            LLMTheme {
                RagRoute(viewModel = viewModel)
            }
        }
    }

}

@Composable
internal fun RagScreen(
    uiState: RagUiState,
    generationDropdownExpanded: Boolean,
    embeddingDropdownExpanded: Boolean,
    vectorStoreDropdownExpanded: Boolean,
    onGenerationDropdownExpandedChange: (Boolean) -> Unit,
    onEmbeddingDropdownExpandedChange: (Boolean) -> Unit,
    onVectorStoreDropdownExpandedChange: (Boolean) -> Unit,
    onGenerationModelSelected: (String) -> Unit,
    onEmbeddingModelSelected: (String) -> Unit,
    onVectorStoreSelected: (RagVectorStoreBackend) -> Unit,
    onAddDocument: () -> Unit,
    onRemoveDocument: (String) -> Unit,
    onClearDocuments: () -> Unit,
    onQuestionChanged: (String) -> Unit,
    onAskQuestion: () -> Unit,
    onConfirmDownload: () -> Unit,
    onDismissDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val generationModels = uiState.models.filter { it.id in ragGenerationModelIds && it.isEnabled }
    val embeddingModels = uiState.models.filter { it.id in ragEmbeddingModelIds && it.isEnabled }
    val selectedGenerationModel = uiState.models.firstOrNull { it.id == uiState.selectedGenerationModelId }
    val selectedEmbeddingModel = uiState.models.firstOrNull { it.id == uiState.selectedEmbeddingModelId }
    val selectedVectorStoreBackend = uiState.selectedVectorStoreBackend
    val pendingDownloadModel = uiState.models.firstOrNull { it.id == uiState.pendingDownloadModelId }
    val pendingDownloadState = pendingDownloadModel?.let { uiState.downloadStates[it.id] }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFF7F8FC),
                        Color(0xFFF2F4FA),
                        Color(0xFFEDEFF7)
                    )
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("RAG Test", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Üretici model, dokümanlar ve soru-cevap akışı tek ekranda.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF6B7280)
                    )
                }
            }

            item {
                ModelSelectionCard(
                    title = "Cevap Üretici Model",
                    subtitle = "Soruyu cevaplayacak modeli seç.",
                    selectedModel = selectedGenerationModel,
                    dropdownExpanded = generationDropdownExpanded,
                    onDropdownExpandedChange = onGenerationDropdownExpandedChange,
                    onModelSelected = onGenerationModelSelected,
                    models = generationModels,
                    downloadStates = uiState.downloadStates,
                    loadingHint = "Cevap üretimi için model indiriliyor...",
                    emptyHint = "Bir üretici model seç.",
                    accentColor = Color(0xFF3B82F6),
                    trailingTag = "Üretici"
                )
            }

            item {
                ModelSelectionCard(
                    title = "Embedding Model",
                    subtitle = "Doküman ve soru vektörlerini bu modelle üret.",
                    selectedModel = selectedEmbeddingModel,
                    dropdownExpanded = embeddingDropdownExpanded,
                    onDropdownExpandedChange = onEmbeddingDropdownExpandedChange,
                    onModelSelected = onEmbeddingModelSelected,
                    models = embeddingModels,
                    downloadStates = uiState.downloadStates,
                    loadingHint = "Embedding modeli indiriliyor...",
                    emptyHint = "Bir embedding model seç.",
                    accentColor = Color(0xFF10B981),
                    trailingTag = "Embedding"
                )
            }

            item {
                VectorStoreSelectionCard(
                    selectedBackend = selectedVectorStoreBackend,
                    dropdownExpanded = vectorStoreDropdownExpanded,
                    onDropdownExpandedChange = onVectorStoreDropdownExpandedChange,
                    onBackendSelected = onVectorStoreSelected
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F6FB))
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
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Doküman Kaynakları", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "PDF veya TXT yükle, sonra soruyu bu kaynaklara göre cevapla.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6B7280)
                                )
                            }
                            Button(
                                onClick = onAddDocument,
                                enabled = !uiState.isIndexingDocument
                            ) {
                                Text("Dosya Ekle")
                            }
                        }

                        if (uiState.isIndexingDocument) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = "${uiState.indexingDocumentName ?: "Belge"} okunuyor ve indexleniyor...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6B7280)
                                )
                            }
                        }

                        uiState.errorMessage?.let { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        if (uiState.documents.isEmpty()) {
                            Text(
                                text = "Henüz kaynak yok.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF6B7280)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                uiState.documents.forEach { document ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(18.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.White)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(document.displayName, fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    text = "${document.chunkCount} parça",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF6B7280)
                                                )
                                            }
                                            TextButton(onClick = { onRemoveDocument(document.id) }) {
                                                Text("Sil")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onClearDocuments, enabled = uiState.documents.isNotEmpty()) {
                                Text("Temizle")
                            }
                        }

                        Text(
                            text = "Seçili depo: ${selectedVectorStoreBackend.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6B7280)
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F6FB))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Soru", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = uiState.question,
                            onValueChange = onQuestionChanged,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp),
                            placeholder = { Text("Sorunu yaz...") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                        )
                        Button(
                            onClick = onAskQuestion,
                            enabled = !uiState.isGenerating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (uiState.isGenerating) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Ara")
                            }
                        }
                        Text(
                            text = when {
                                uiState.selectedGenerationModelId == null -> "Önce bir üretici model seç."
                                uiState.selectedEmbeddingModelId == null -> "Önce bir embedding model seç."
                                uiState.documents.isEmpty() -> "Önce bir doküman kaynağı seç."
                                else -> "Soru hazır."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6B7280)
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Cevap", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = uiState.answer.ifBlank { "Cevap burada görünecek." },
                            style = MaterialTheme.typography.bodyLarge
                        )

                        uiState.answerMetrics?.let { metrics ->
                            HorizontalDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Metrikler", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Üretici model: ${metrics.generationModelName ?: "-"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6B7280)
                                )
                                Text(
                                    text = "Embedding model: ${metrics.embeddingModelName ?: "-"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6B7280)
                                )
                                Text(
                                    text = "Depo: ${metrics.backendName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6B7280)
                                )
                                Text(
                                    text = "Mod: ${metrics.answerMode}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6B7280)
                                )
                                Text(
                                    text = "Kullanılan parça: ${metrics.sourceCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6B7280)
                                )
                                Text(
                                    text = "Parça bulma süresi: ${metrics.retrievalTimeMs} ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6B7280)
                                )
                                metrics.firstTokenLatencyMs?.let {
                                    Text(
                                        text = "İlk token süresi: $it ms",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF6B7280)
                                    )
                                }
                                metrics.totalTimeMs?.let {
                                    Text(
                                        text = "Toplam süre: $it ms",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF6B7280)
                                    )
                                }
                            }
                        }

                        if (uiState.highlightedSources.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Kullanılan Parçalar", style = MaterialTheme.typography.titleSmall)
                            uiState.highlightedSources.forEach { chunk ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "${chunk.documentName} / Parça ${chunk.chunkIndex + 1}",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = truncateForPreview(chunk.text),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF6B7280)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingDownloadModel != null) {
        downloadDialogContent(
            pendingDownloadModel = pendingDownloadModel,
            pendingDownloadState = pendingDownloadState,
            pendingDownloadRole = uiState.pendingDownloadRole,
            onConfirmDownload = onConfirmDownload,
            onDismissDownload = onDismissDownload
        )
    }

}

@Composable
private fun ModelSelectionCard(
    title: String,
    subtitle: String,
    selectedModel: ModelConfig?,
    dropdownExpanded: Boolean,
    onDropdownExpandedChange: (Boolean) -> Unit,
    onModelSelected: (String) -> Unit,
    models: List<ModelConfig>,
    downloadStates: Map<String, DownloadState>,
    loadingHint: String,
    emptyHint: String,
    accentColor: Color,
    trailingTag: String?
) {
    val selectedState = selectedModel?.let { downloadStates[it.id] } ?: DownloadState.Idle
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F6FB))
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
                }
                if (!trailingTag.isNullOrBlank()) {
                    Text(
                        text = trailingTag,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onDropdownExpandedChange(true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedModel?.displayName ?: emptyHint, maxLines = 1)
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { onDropdownExpandedChange(false) }
                ) {
                    models.forEach { model ->
                        val downloadState = downloadStates[model.id] ?: DownloadState.Idle
                        val isSelected = selectedModel?.id == model.id
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        if (isSelected) "${model.displayName} (seçili)" else model.displayName
                                    )
                                    Text(
                                        text = modelMenuSubtitle(model, downloadState, isSelected),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF6B7280)
                                    )
                                }
                            },
                            onClick = {
                                onDropdownExpandedChange(false)
                                onModelSelected(model.id)
                            },
                            enabled = model.isEnabled
                        )
                    }
                }
            }

            Text(
                text = when {
                    selectedModel == null -> emptyHint
                    selectedState is DownloadState.Success -> "Seçili: ${selectedModel.displayName} - hazır."
                    selectedState is DownloadState.Downloading -> loadingHint
                    else -> "Seçili: ${selectedModel.displayName}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B7280)
            )

            if (selectedState is DownloadState.Downloading) {
                LinearProgressIndicator(
                    progress = { selectedState.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun VectorStoreSelectionCard(
    selectedBackend: RagVectorStoreBackend,
    dropdownExpanded: Boolean,
    onDropdownExpandedChange: (Boolean) -> Unit,
    onBackendSelected: (RagVectorStoreBackend) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F6FB))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Retrieval Modu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Karşılaştırma için kaynak bulma yöntemini seç. Graph RAG mevcut akışı bozmadan ayrı çalışır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7280)
                )
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onDropdownExpandedChange(true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedBackend.displayName, maxLines = 1)
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { onDropdownExpandedChange(false) }
                ) {
                    RagVectorStoreBackend.values().forEach { backend ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(backend.displayName)
                                    Text(
                                        text = backend.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF6B7280)
                                    )
                                }
                            },
                            onClick = {
                                onDropdownExpandedChange(false)
                                onBackendSelected(backend)
                            }
                        )
                    }
                }
            }

            Text(
                text = selectedBackend.description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B7280)
            )
        }
    }
}

@Composable
private fun downloadDialogContent(
    pendingDownloadModel: ModelConfig,
    pendingDownloadState: DownloadState?,
    pendingDownloadRole: RagModelRole?,
    onConfirmDownload: () -> Unit,
    onDismissDownload: () -> Unit
) {
    val isDownloading = pendingDownloadState is DownloadState.Downloading
    val roleLabel = when (pendingDownloadRole) {
        RagModelRole.GENERATION -> "Cevap üretici model"
        RagModelRole.EMBEDDING -> "Embedding modeli"
        null -> "Model"
    }

    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) {
                onDismissDownload()
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmDownload,
                enabled = !isDownloading
            ) {
                Text(if (isDownloading) "İndiriliyor" else "İndir")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismissDownload,
                enabled = !isDownloading
            ) {
                Text("Vazgeç")
            }
        },
        title = { Text("$roleLabel indirilsin mi?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${pendingDownloadModel.displayName} indirilmedi. İstersen şimdi indirelim.")
                Text(
                    text = pendingDownloadModel.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7280)
                )
                if (pendingDownloadState is DownloadState.Downloading) {
                    LinearProgressIndicator(
                        progress = { pendingDownloadState.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${pendingDownloadState.progressLabel}\n${pendingDownloadState.fileName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6B7280)
                    )
                }
            }
        }
    )
}

private fun modelMenuSubtitle(
    model: ModelConfig,
    downloadState: DownloadState,
    isSelected: Boolean
): String {
    return when {
        !model.isEnabled -> model.disabledReason ?: "Disabled"
        downloadState is DownloadState.Success && isSelected -> "Downloaded - selected"
        downloadState is DownloadState.Success -> "Downloaded"
        downloadState is DownloadState.Downloading -> "${downloadState.progressLabel} • ${downloadState.fileName}"
        isSelected -> "Selected"
        else -> "Not downloaded"
    }
}
