package com.hilal.llm.benchmark.history

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BenchmarkHistoryRoute(
    viewModel: BenchmarkHistoryViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.loadResults()
    }
    LaunchedEffect(viewModel) {
        viewModel.exportEvents.collect { event ->
            when (event) {
                is BenchmarkExportEvent.ShareFile -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        putExtra(Intent.EXTRA_SUBJECT, event.fileName)
                        clipData = ClipData.newRawUri(event.fileName, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(shareIntent, "Share benchmark export")
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(chooser)
                }
                is BenchmarkExportEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is BenchmarkExportEvent.Error -> snackbarHostState.showSnackbar(event.text)
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
        BenchmarkAnalyticsScreen(
            uiState = uiState,
            onRuntimeFilterSelected = viewModel::setRuntimeFilter,
            onSourceFilterSelected = viewModel::setSourceFilter,
            onToggleCompare = viewModel::toggleCompareSelection,
            onQualityChanged = viewModel::updateQuality,
            onExportCsv = viewModel::exportCsv,
            onExportJson = viewModel::exportJson,
            onClearCompare = viewModel::clearCompareSelection,
            modifier = Modifier.padding(innerPadding)
        )
    }
}
