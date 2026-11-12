package com.hilal.llm.catalog.download

import com.hilal.llm.catalog.ModelCatalogRepository
import com.hilal.llm.catalog.storage.ModelStorageResolver
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelInventoryTest {
    @Test fun separateScreensObserveTheSameDeletion(): Unit = runBlocking {
        val root = Files.createTempDirectory("model-inventory").toFile()
        try {
            val storage = ModelStorageResolver(root)
            val model = ModelCatalogRepository.getAvailableModels().first { it.files.any { file -> file.required } }
                .let { it.copy(files = listOf(it.files.first { file -> file.required }.copy(fileName = "weights.bin", minBytes = 4))) }
            val chat = ModelDownloadRepository(storage)
            val catalog = ModelDownloadRepository(ModelStorageResolver(root))
            File(storage.ensureModelDir(model), "weights.bin").writeText("data")
            chat.refreshInventory(listOf(model))
            assertTrue(catalog.inventory.value.getValue(model.id).ready)
            assertEquals(4L, catalog.inventory.value.getValue(model.id).storedBytes)
            assertTrue(catalog.deleteModel(model))
            val updated = chat.inventory.value.getValue(model.id)
            assertFalse(updated.ready)
            assertEquals(0L, updated.storedBytes)
            assertEquals(DownloadState.Idle, updated.state)
        } finally { root.deleteRecursively() }
    }

    @Test fun progressDoesNotEraseOtherModels() {
        val files = ModelFileOperations()
        files.publish("one", ModelInventoryEntry(true, 4, DownloadState.Success))
        files.publish("two", ModelInventoryEntry(false, 0, DownloadState.Idle))
        files.progress("two", DownloadState.Failed("Network"))
        assertTrue(files.inventory.value.getValue("one").ready)
        assertTrue(files.inventory.value.getValue("two").state is DownloadState.Failed)
    }
}
