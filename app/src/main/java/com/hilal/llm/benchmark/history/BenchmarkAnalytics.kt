package com.hilal.llm.benchmark.history

data class BenchmarkAnalytics(
    val totalBenchmarks: Int,
    val uniqueModels: Int,
    val fastestModelName: String?,
    val fastestTps: Double?,
    val lowestRamModelName: String?,
    val lowestRam: Double?,
    val averageTps: Double?,
    val averageFirstTokenTime: Double?,
    val goodCount: Int,
    val badCount: Int,
    val repeatedCount: Int,
    val emptyCount: Int
)
