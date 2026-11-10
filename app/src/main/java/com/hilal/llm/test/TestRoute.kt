package com.hilal.llm.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hilal.llm.catalog.ui.GenerationModelUiState
import com.hilal.llm.benchmark.EngineType
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.inference.FeatureGenerationSession

@Composable
fun TestRoute(
    testViewModel: TestViewModel,
    modelState: GenerationModelUiState,
    onRuntimeSelected: (EngineType) -> Unit,
    onModelSelected: (String) -> Unit,
    onCancelDownload: () -> Unit,
    openSession: suspend (BenchmarkModelSelection) -> FeatureGenerationSession,
    modifier: Modifier = Modifier
) {
    val testState by testViewModel.uiState.collectAsStateWithLifecycle()

    TestScreen(
        testState = testState,
        modelState = modelState,
        onQuestionChanged = testViewModel::updateQuestion,
        onClassificationProviderSelected = testViewModel::selectClassificationProvider,
        onRuntimeSelected = onRuntimeSelected,
        onModelSelected = onModelSelected,
        onCancelDownload = onCancelDownload,
        onSubmit = { jevApiKey ->
            testViewModel.submit(
                selection = modelState.selectedCatalogModel,
                jevApiKey = jevApiKey,
                openSession = openSession
            )
        },
        modifier = modifier
    )
}
