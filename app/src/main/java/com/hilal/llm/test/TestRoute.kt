package com.hilal.llm.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hilal.llm.benchmark.BenchmarkViewModel

@Composable
fun TestRoute(
    testViewModel: TestViewModel,
    benchmarkViewModel: BenchmarkViewModel,
    modifier: Modifier = Modifier
) {
    val testState by testViewModel.uiState.collectAsStateWithLifecycle()
    val benchmarkState by benchmarkViewModel.uiState.collectAsStateWithLifecycle()

    TestScreen(
        testState = testState,
        benchmarkState = benchmarkState,
        onQuestionChanged = testViewModel::updateQuestion,
        onRuntimeSelected = benchmarkViewModel::setEngine,
        onModelSelected = benchmarkViewModel::selectCatalogModel,
        onCancelDownload = benchmarkViewModel::cancelModelDownload,
        onSubmit = {
            testViewModel.submit(
                selection = benchmarkState.selectedCatalogModel,
                openSession = benchmarkViewModel::openFeatureGenerationSession
            )
        },
        modifier = modifier
    )
}
