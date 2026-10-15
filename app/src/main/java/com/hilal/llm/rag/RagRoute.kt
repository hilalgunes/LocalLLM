package com.hilal.llm.rag

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RagRoute(
    viewModel: RagViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.loadModels() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val contentResolver = context.contentResolver
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var generationDropdownExpanded by remember { mutableStateOf(false) }
    var embeddingDropdownExpanded by remember { mutableStateOf(false) }
    var vectorStoreDropdownExpanded by remember { mutableStateOf(false) }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            viewModel.reportError("Dosya seçilmedi.")
            return@rememberLauncherForActivityResult
        }

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers do not support persisted permissions.
        }

        val displayName = readDisplayName(contentResolver, uri) ?: "document"
        coroutineScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    readDocumentText(contentResolver, uri, displayName)
                }
                viewModel.addDocument(displayName = displayName, text = text)
            } catch (throwable: Throwable) {
                viewModel.reportError(buildDocumentErrorMessage("Belge okunamadı", throwable, uri))
            }
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        RagScreen(
            uiState = uiState,
            generationDropdownExpanded = generationDropdownExpanded,
            embeddingDropdownExpanded = embeddingDropdownExpanded,
            vectorStoreDropdownExpanded = vectorStoreDropdownExpanded,
            onGenerationDropdownExpandedChange = { generationDropdownExpanded = it },
            onEmbeddingDropdownExpandedChange = { embeddingDropdownExpanded = it },
            onVectorStoreDropdownExpandedChange = { vectorStoreDropdownExpanded = it },
            onGenerationModelSelected = viewModel::selectGenerationModel,
            onEmbeddingModelSelected = viewModel::selectEmbeddingModel,
            onVectorStoreSelected = viewModel::selectVectorStoreBackend,
            onAddDocument = {
                documentPickerLauncher.launch(arrayOf("application/pdf", "text/plain", "text/*"))
            },
            onRemoveDocument = viewModel::removeDocument,
            onClearDocuments = viewModel::clearDocuments,
            onQuestionChanged = viewModel::updateQuestion,
            onAskQuestion = viewModel::askQuestion,
            onConfirmDownload = viewModel::confirmSelectedModelDownload,
            onDismissDownload = viewModel::dismissDownloadDialog,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

private fun readDisplayName(
    contentResolver: android.content.ContentResolver,
    uri: Uri
): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
    }
    return null
}

private fun buildDocumentErrorMessage(prefix: String, throwable: Throwable, uri: Uri): String {
    val errorType = throwable::class.simpleName ?: "Throwable"
    val message = throwable.message?.takeIf { it.isNotBlank() } ?: "neden bilinmiyor"
    return "$prefix ($errorType): $message [uri=$uri]"
}
