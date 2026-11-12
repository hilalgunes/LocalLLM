package com.hilal.llm.catalog

import com.hilal.llm.catalog.storage.ModelStorageResolver
import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogServiceTest {
    @Test fun missingRequiredFilesCannotBecomeSelection() {
        val root = Files.createTempDirectory("catalog-service").toFile()
        try {
            val model = ModelCatalogRepository.getAvailableModels().first { it.isEnabled && it.runtimeType == RuntimeType.LLAMA_CPP }
            val service = ModelCatalogService(ModelStorageResolver(root))
            assertTrue(runCatching { service.resolve(model) }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun wrongPurposeFailsBeforeDownload(): Unit = runBlocking {
        val root = Files.createTempDirectory("catalog-service").toFile()
        try {
            val model = ModelCatalogRepository.getAvailableModels().first { it.purpose == ModelPurpose.GENERATION }
            var progressCalled = false
            val result = runCatching { ModelCatalogService(ModelStorageResolver(root)).prepare(model, ModelPurpose.EMBEDDING) { progressCalled = true } }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertFalse(progressCalled)
            assertEquals(0, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
}
