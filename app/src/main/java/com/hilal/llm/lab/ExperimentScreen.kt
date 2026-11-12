package com.hilal.llm.lab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hilal.llm.catalog.ui.GenerationModelViewModel
import com.hilal.llm.inference.ModelSessionService

@Composable
fun ExperimentScreen(owner: ExperimentViewModel, models: GenerationModelViewModel, sessions: ModelSessionService) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by owner.state.collectAsStateWithLifecycle()
    val catalog by models.uiState.collectAsStateWithLifecycle()
    var showModels by remember { mutableStateOf(false) }
    val blocked = state.running || state.loading || catalog.activeDownloadModelId != null || catalog.deletingModelId != null
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Tekrarlanabilir model deneyi", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { showModels = true }, enabled = !blocked) { Text(catalog.selectedCatalogModel?.displayName ?: "Model seç") }
            if (catalog.activeDownloadModelId != null) TextButton(onClick = models::cancelDownload) { Text("İndirmeyi iptal et") }
            OutlinedTextField(state.prompt, owner::prompt, label = { Text("Soru") }, enabled = !blocked, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.maxTokens, owner::tokens, label = { Text("Maksimum token") }, enabled = !blocked)
            Row {
                FilterChip(state.mode == "cold", { owner.mode("cold") }, { Text("Soğuk başlangıç") }, enabled = !blocked)
                Spacer(Modifier.width(8.dp))
                FilterChip(state.mode == "warm", { owner.mode("warm") }, { Text("Isıtılmış model") }, enabled = !blocked)
            }
            Text("Isıtılmış ölçüm önce kayda alınmayan bir hazırlık yanıtı üretir. Android’de bu mod LiteRT modelleri içindir. Hazırlık süresi oturum bekleme süresini de içerir.", style = MaterialTheme.typography.bodySmall)
            if (state.running) Button(owner::stop) { Text("Durdur") }
            else Button({ catalog.selectedCatalogModel?.let { owner.run(it, sessions) } }, enabled = !blocked && catalog.selectedCatalogModel != null) { Text("Çalıştır ve kaydet") }
            (state.error ?: catalog.errorMessage)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.output.isNotEmpty()) Text(state.output)
        }
        items(state.records, key = { it.id }) { record ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${record.definition.modelId} · ${record.definition.mode} · ${record.status}")
                    Text(record.definition.prompt)
                    Text("Hazırlık: ${record.metrics.preparationMs.toLong()} ms · Üretim: ${record.metrics.generationMs.toLong()} ms")
                    TextButton(onClick = {
                        val json = com.google.gson.GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(record)
                        context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("application/json").putExtra(android.content.Intent.EXTRA_TEXT, json), "Deneyi paylaş"))
                    }) { Text("JSON paylaş") }
                    TextButton(onClick = {
                        owner.restore(record)
                        models.selectModel(record.definition.modelId) { owner.run(it, sessions) }
                    }, enabled = !blocked) { Text("Aynı ayarlarla tekrar çalıştır") }
                }
            }
        }
    }
    if (showModels) AlertDialog(onDismissRequest = { showModels = false }, title = { Text("Deney modeli") }, text = {
        LazyColumn { items(catalog.availableModels, key = { it.id }) { model ->
            TextButton(onClick = { showModels = false; models.selectModel(model.id) }, enabled = model.isEnabled) { Text(model.displayName) }
        } }
    }, confirmButton = { TextButton({ showModels = false }) { Text("Kapat") } })
}
