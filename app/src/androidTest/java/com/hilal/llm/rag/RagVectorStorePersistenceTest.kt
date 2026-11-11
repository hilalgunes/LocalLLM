package com.hilal.llm.rag

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RagVectorStorePersistenceTest {
    @Test fun restartPreservesVectorsAndFailedWriteKeepsCommittedDocuments() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "rag-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(directory, name)
            override fun getFilesDir(): File = directory
        }
        var store: RagVectorStoreRepository? = null
        try {
            val first = RagDocument("first", "note.txt", "First", 1)
            val second = RagDocument("second", "note.txt", "Second", 1)
            val a = RagChunk("first", "note.txt", 0, "First")
            val b = RagChunk("second", "note.txt", 0, "Second")
            store = RagVectorStoreRepository(context)
            store.upsertDocument(first, listOf(a), listOf(floatArrayOf(1f, 0f)))
            store.upsertDocument(second, listOf(b), listOf(floatArrayOf(0f, 1f)))
            store.close()
            store = RagVectorStoreRepository(context)
            val ranked = store.rank(RagVectorStoreBackend.IN_MEMORY, "", floatArrayOf(0f, 1f), 1)
            assertEquals("second", ranked.single().documentId)
            val failure = runCatching { store.reindexAll(emptyMap()) }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(2, store.snapshot().documents.size)
            store.removeDocument("first")
            store.close()
            store = RagVectorStoreRepository(context)
            assertEquals(listOf("second"), store.snapshot().documents.map { it.id })
            assertEquals("second", store.rank(RagVectorStoreBackend.IN_MEMORY, "", floatArrayOf(0f, 1f), 1).single().documentId)
        } finally {
            store?.close()
            directory.deleteRecursively()
        }
    }
}
