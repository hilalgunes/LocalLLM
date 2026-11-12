package com.hilal.llm.rag

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hilal.llm.catalog.ModelCatalogRepository
import com.hilal.llm.catalog.storage.ModelStorageResolver
import com.hilal.llm.inference.ModelSessionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RagIndexLifecycleTest {
    @Test fun changingEmbeddingReindexesAndRestartKeepsIdentity() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val key = "rag-lifecycle-${UUID.randomUUID()}"
        val root = File(base.cacheDir, key).apply { mkdirs() }
        val app = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = root
            override fun getDatabasePath(name: String): File = File(root, name)
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$key-$name", mode)
        }
        val models = listOf("gecko-110m-en-256-litert", "qwen3-embedding-0.6b-gguf").map { requireNotNull(ModelCatalogRepository.getModelById(it)) }
        val storage = ModelStorageResolver(app)
        for (model in models) for (file in model.files.filter { it.required }) {
            val target = File(storage.ensureModelDir(model), file.fileName)
            target.parentFile?.mkdirs()
            RandomAccessFile(target, "rw").use { it.setLength(maxOf(1, file.minBytes ?: 1)) }
        }
        val embeddedModels = mutableListOf<String>()
        val sessions = ModelSessionService { error("Generation is not used in this test") }
        val owners = ViewModelStore()
        fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        fun create(): RagViewModel {
            lateinit var vm: RagViewModel
            onMain {
                vm = RagViewModel(app) { id, _ -> object : RagEmbeddingProvider {
                    override suspend fun embed(text: String, embeddingModelPath: String, tokenizerPath: String?, purpose: RagEmbeddingPurpose): FloatArray {
                        embeddedModels += id
                        return if (id == models[0].id) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
                    }
                    override suspend fun canInitialize(embeddingModelPath: String, tokenizerPath: String?) = true
                    override fun close() = Unit
                } }
                owners.put("rag", vm)
                vm.initialize(app.contentResolver, sessions)
                vm.loadModels()
            }
            return vm
        }
        try {
            val vm = create()
            onMain { vm.selectEmbeddingModel(models[0].id) }
            withTimeout(8000) { vm.uiState.first { !it.isIndexingDocument } }
            onMain { vm.addDocument("note.txt", "Bir belge metni.") }
            withTimeout(8000) { vm.uiState.first { !it.isIndexingDocument && it.documents.size == 1 } }
            onMain { vm.selectEmbeddingModel(models[1].id) }
            withTimeout(8000) { vm.uiState.first { !it.isIndexingDocument } }
            assertTrue(embeddedModels.containsAll(models.map { it.id }))
            val count = embeddedModels.size
            onMain { owners.clear() }
            val restored = create()
            withTimeout(8000) { restored.uiState.first { it.documents.size == 1 } }
            onMain { restored.selectEmbeddingModel(models[1].id) }
            withTimeout(8000) { restored.uiState.first { !it.isIndexingDocument } }
            assertEquals(count, embeddedModels.size)
            onMain { restored.clearDocuments() }
            withTimeout(8000) { restored.uiState.first { !it.isIndexingDocument && it.documents.isEmpty() } }
            assertEquals("", restored.uiState.value.answer)
            assertTrue(restored.uiState.value.highlightedSources.isEmpty())
        } finally {
            onMain { owners.clear() }
            sessions.close()
            app.getSharedPreferences("rag-index", 0).edit().clear().commit()
            root.deleteRecursively()
        }
    }
}
