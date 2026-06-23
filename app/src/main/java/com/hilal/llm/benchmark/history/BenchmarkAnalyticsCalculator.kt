package com.hilal.llm.benchmark.history

import com.hilal.llm.benchmark.BenchmarkResult
import com.hilal.llm.benchmark.ResponseQuality

object BenchmarkAnalyticsCalculator {
    fun calculate(results: List<BenchmarkResult>): BenchmarkAnalytics {
        val successfulResults = results.filter { it.isSuccess }
        val tpsResults = successfulResults.filter { it.tokensPerSecond != null }
        val ramResults = successfulResults.filter { it.totalRamMb != null }
        val firstTokenResults = successfulResults.filter { it.firstTokenTimeMs != null }

        val fastest = tpsResults.maxByOrNull { it.tokensPerSecond ?: Double.MIN_VALUE }
        val lowestRam = ramResults.minByOrNull { it.totalRamMb ?: Double.MAX_VALUE }

        return BenchmarkAnalytics(
            totalBenchmarks = results.size,
            uniqueModels = results.map { it.modelName }.distinct().size,
            fastestModelName = fastest?.modelName,
            fastestTps = fastest?.tokensPerSecond,
            lowestRamModelName = lowestRam?.modelName,
            lowestRam = lowestRam?.totalRamMb,
            averageTps = tpsResults.takeIf { it.isNotEmpty() }?.mapNotNull { it.tokensPerSecond }?.average(),
            averageFirstTokenTime = firstTokenResults.takeIf { it.isNotEmpty() }
                ?.mapNotNull { it.firstTokenTimeMs?.toDouble() }
                ?.average(),
            goodCount = results.count { it.responseQuality == ResponseQuality.GOOD },
            badCount = results.count { it.responseQuality == ResponseQuality.BAD },
            repeatedCount = results.count { it.responseQuality == ResponseQuality.REPEATED },
            emptyCount = results.count { it.responseQuality == ResponseQuality.EMPTY }
        )
    }
}
