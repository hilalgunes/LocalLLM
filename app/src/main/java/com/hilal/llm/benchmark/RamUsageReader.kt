package com.hilal.llm.benchmark

import android.os.Debug

object RamUsageReader {
    fun readSnapshot(): RamSnapshot? {
        return runCatching {
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            RamSnapshot(
                javaRamMb = memoryInfo.dalvikPss / 1024.0,
                nativeRamMb = memoryInfo.nativePss / 1024.0,
                totalRamMb = memoryInfo.totalPss / 1024.0
            )
        }.getOrNull()
    }
}
