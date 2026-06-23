package com.hilal.llm.benchmark

data class RamSnapshot(
    val javaRamMb: Double,
    val nativeRamMb: Double,
    val totalRamMb: Double
)
