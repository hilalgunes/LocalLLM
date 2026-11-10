package com.hilal.llm.catalog.storage

import com.hilal.llm.catalog.*
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ModelStorageResolverTest {
    private fun withStorage(check: (ModelStorageResolver, ModelConfig) -> Unit) {
        val root = Files.createTempDirectory("model-inventory").toFile()
        val config = ModelCatalogRepository.getAvailableModels().first { it.files.any { file -> file.required } }
            .let { it.copy(files = listOf(it.files.first { file -> file.required }.copy(fileName = "weights.bin", minBytes = 4))) }
        try { check(ModelStorageResolver(root), config) } finally { root.deleteRecursively() }
    }

    @Test fun partialBytesCountWithoutMarkingModelReady() = withStorage { storage, model ->
        File(storage.ensureModelDir(model), "weights.bin.tmp").writeText("abc")
        assertEquals(3L, storage.storedBytes(model))
        assertFalse(storage.isModelDownloaded(model))
    }

    @Test fun requiredFileMustMeetMinimumSize() = withStorage { storage, model ->
        val file = File(storage.ensureModelDir(model), "weights.bin")
        file.writeText("abc")
        assertFalse(storage.isModelDownloaded(model))
        file.appendText("d")
        assertTrue(storage.isModelDownloaded(model))
    }

    @Test fun emptyManifestIsNotDownloadableOrReady() = withStorage { storage, model ->
        val empty = model.copy(files = emptyList())
        assertFalse(empty.hasDownloadArtifacts)
        assertFalse(storage.isModelDownloaded(empty))
    }

    @Test fun deleteRemovesCompleteAndPartialFilesOnlyForChosenModel() = withStorage { storage, model ->
        File(storage.ensureModelDir(model), "weights.bin").writeText("data")
        File(storage.ensureModelDir(model), "weights.bin.tmp").writeText("part")
        val other = model.copy(id = model.id + "-other")
        File(storage.ensureModelDir(other), "weights.bin").writeText("data")
        assertTrue(storage.deleteModel(model))
        assertEquals(0L, storage.storedBytes(model))
        assertFalse(storage.isModelDownloaded(model))
        assertTrue(storage.isModelDownloaded(other))
    }
}
