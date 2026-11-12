package com.hilal.llm.catalog.download

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All catalog entry points serialize mutations of the same storage root. */
internal class ModelFileOperations {
    private val mutex = Mutex()
    suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock { block() }

    companion object {
        private val roots = ConcurrentHashMap<String, ModelFileOperations>()
        fun forRoot(path: String): ModelFileOperations = roots.getOrPut(path) { ModelFileOperations() }
    }
}
