package com.hilal.llm.benchmark.history

import com.hilal.llm.benchmark.BenchmarkResult
import com.hilal.llm.benchmark.SelectionSource
import com.hilal.llm.catalog.RuntimeType

data class BenchmarkHistoryUiState(
    val results: List<BenchmarkResult> = emptyList(),
    val filteredResults: List<BenchmarkResult> = emptyList(),
    val analytics: BenchmarkAnalytics = BenchmarkAnalytics(
        totalBenchmarks = 0,
        uniqueModels = 0,
        fastestModelName = null,
        fastestTps = null,
        lowestRamModelName = null,
        lowestRam = null,
        averageTps = null,
        averageFirstTokenTime = null,
        goodCount = 0,
        badCount = 0,
        repeatedCount = 0,
        emptyCount = 0
    ),
    val selectedRuntimeFilter: RuntimeType? = null,
    val selectedSourceFilter: SelectionSource? = null,
    val compareSelectionKeys: List<Long> = emptyList(),
    val compareLeft: BenchmarkResult? = null,
    val compareRight: BenchmarkResult? = null,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val errorMessage: String? = null
)
