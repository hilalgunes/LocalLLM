package com.hilal.llm.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hilal.llm.catalog.ui.GenerationModelViewModel
import com.hilal.llm.catalog.download.DownloadState

@Composable
fun ChatRoute(owner: ChatViewModel, models: GenerationModelViewModel) {
    val chat = owner.chat
    val state by chat.uiState.collectAsStateWithLifecycle()
    val modelState by models.uiState.collectAsStateWithLifecycle()
    val thread = state.activeThread
    var showModels by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<ChatThread?>(null) }
    var renameText by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<ChatThread?>(null) }
    val messages = thread?.messages.orEmpty()
    val list = rememberLazyListState()
    LaunchedEffect(thread?.id) { if (thread != null) models.restoreModel(thread.modelId) }
    LaunchedEffect(thread?.id, messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }
    val modelReady = modelState.selectedCatalogModel?.takeIf { it.modelId == thread?.modelId }
    val selecting = modelState.activeDownloadModelId != null

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sohbet", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { showHistory = true }, enabled = !state.running && !state.loading) { Text("Geçmiş") }
            TextButton(onClick = chat::newThread, enabled = !state.running && !state.loading) { Text("Yeni") }
        }
        Text(thread?.title ?: "Sohbetler yükleniyor…", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { showModels = true }, enabled = !state.running && !state.loading, modifier = Modifier.fillMaxWidth()) {
            Text(modelState.availableModels.firstOrNull { it.id == thread?.modelId }?.displayName ?: "Model seç")
        }
        modelState.activeDownloadModelId?.let { id ->
            val progress = modelState.modelDownloadStates[id] as? DownloadState.Downloading
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(progress?.progressLabel ?: "Model hazırlanıyor…", Modifier.weight(1f))
                TextButton(onClick = models::cancelDownload) { Text("İptal") }
            }
        }
        (state.storageError ?: state.error ?: modelState.errorMessage)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ne hakkında konuşalım?", style = MaterialTheme.typography.titleLarge)
                    Text("Bir model seçip ilk mesajını yaz. Konuşmaların bu cihazda saklanır.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("chat-messages"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(messages, key = { it.id }) { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = if (message.role == ChatRole.USER) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (message.role == ChatRole.USER) "Sen" else "Asistan", style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(message.text.ifBlank { if (message.status == ReplyStatus.GENERATING) "Yanıt hazırlanıyor…" else "Yanıt tamamlanmadı." }) }
                            when (message.status) {
                                ReplyStatus.INTERRUPTED -> Text("Durduruldu · Kısmi yanıt saklandı", style = MaterialTheme.typography.labelSmall)
                                ReplyStatus.FAILED -> Text("Yanıt tamamlanamadı", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
        if (!state.running && messages.any { it.role == ChatRole.USER }) {
            TextButton(onClick = { chat.retry(modelReady) }, enabled = modelReady != null && !selecting) {
                Text(if (messages.lastOrNull()?.status == ReplyStatus.COMPLETE) "Yanıtı yeniden oluştur" else "Tekrar dene")
            }
        }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = state.draft, onValueChange = chat::updateDraft, placeholder = { Text("Bir mesaj yaz…") }, maxLines = 5, enabled = !state.loading, modifier = Modifier.weight(1f).testTag("chat-input"))
            if (state.running) {
                Button(onClick = chat::stop, enabled = !state.stopping, modifier = Modifier.testTag("chat-stop")) { Text(if (state.stopping) "Duruyor…" else "Durdur") }
            } else {
                Button(onClick = { chat.send(modelReady) }, enabled = state.draft.isNotBlank() && modelReady != null && !selecting && !state.loading, modifier = Modifier.testTag("chat-send")) { Text("Gönder") }
            }
        }
    }
    if (showModels) AlertDialog(
        onDismissRequest = { showModels = false }, title = { Text("Sohbet modeli") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(modelState.availableModels, key = { it.id }) { model ->
                    TextButton(enabled = model.isEnabled && model.hasDownloadArtifacts, onClick = {
                        val selectedThread = thread?.id
                        showModels = false
                        models.selectModel(model.id) { selection ->
                            if (chat.uiState.value.activeThread?.id == selectedThread) chat.selectModel(selection.modelId)
                        }
                    }) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(model.displayName)
                            Text("${model.runtimeType} · ${model.estimatedSize}", style = MaterialTheme.typography.bodySmall)
                            Text(if (!model.isEnabled) model.disabledReason ?: "Kullanılamıyor" else if (modelState.modelDownloadStates[model.id] is DownloadState.Success) "Kullan" else "İndir ve kullan", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showModels = false }) { Text("Kapat") } }
    )
    if (showHistory) AlertDialog(
        onDismissRequest = { showHistory = false }, title = { Text("Sohbet geçmişi") },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(state.archive.threads.sortedByDescending { it.updatedAt }, key = { it.id }) { item ->
                Column {
                    TextButton(onClick = { chat.selectThread(item.id); showHistory = false }) { Text(item.title) }
                    Row {
                        TextButton(onClick = { rename = item; renameText = item.title; showHistory = false }) { Text("Adlandır") }
                        TextButton(modifier = Modifier.testTag("delete-thread-${item.id}"), onClick = { delete = item; showHistory = false }) { Text("Sil") }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Kapat") } }
    )
    rename?.let { item -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("Sohbet adı") }, text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { chat.renameThread(item.id, renameText); rename = null }, enabled = renameText.isNotBlank()) { Text("Kaydet") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("Vazgeç") } }) }
    delete?.let { item -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Sohbet silinsin mi?") }, text = { Text(item.title) }, confirmButton = { TextButton(onClick = { chat.deleteThread(item.id); delete = null }) { Text("Sil") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Vazgeç") } }) }
}
