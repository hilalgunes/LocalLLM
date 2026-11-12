package com.hilal.llm.catalog.download

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ModelInventoryEntry(val ready: Boolean, val storedBytes: Long, val state: DownloadState)

/** All catalog entry points serialize mutations of the same storage root. */
internal class ModelFileOperations {
    private val mutex = Mutex()
    private val entries = MutableStateFlow<Map<String, ModelInventoryEntry>>(emptyMap())
    val inventory = entries.asStateFlow()
    fun publish(id: String, entry: ModelInventoryEntry) { entries.update { it + (id to entry) } }
    fun progress(id: String, state: DownloadState) {
        if (state == DownloadState.Idle || state == DownloadState.Success) return
        entries.update { current ->
            val old = current[id]
            current + (id to ModelInventoryEntry(old?.ready ?: false, old?.storedBytes ?: 0, state))
        }
    }
    suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock { block() }

    companion object {
        private val roots = ConcurrentHashMap<String, ModelFileOperations>()
        fun forRoot(path: String): ModelFileOperations = roots.getOrPut(path) { ModelFileOperations() }
    }
}
