package com.hilal.llm.catalog

import com.hilal.llm.catalog.download.DownloadState
import com.hilal.llm.catalog.download.ModelDownloadRepository
import com.hilal.llm.catalog.runtime.ModelRunFileMapper
import com.hilal.llm.catalog.storage.ModelStorageResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared validation, download and file resolution for chat, benchmark and RAG. */
class ModelCatalogService(storage: ModelStorageResolver) {
    private val downloads = ModelDownloadRepository(storage)
    private val mapper = ModelRunFileMapper(storage)

    suspend fun prepare(model: ModelConfig, purpose: ModelPurpose, onState: (DownloadState) -> Unit): BenchmarkModelSelection {
        require(model.purpose == purpose) { "${model.displayName} seçilen işlem için kullanılamaz." }
        require(model.isEnabled && model.hasDownloadArtifacts) { model.disabledReason ?: "Model kullanılamıyor." }
        downloads.downloadModel(model, onState)
        return withContext(Dispatchers.IO) { resolve(model) }
    }

    fun resolve(model: ModelConfig): BenchmarkModelSelection {
        check(model.isEnabled) { model.disabledReason ?: "Model kullanılamıyor." }
        check(downloads.isModelDownloaded(model)) { "${model.displayName} henüz indirilmedi." }
        val files = mapper.toRunFiles(model)
        return BenchmarkModelSelection(model.id, model.displayName, model.runtimeType, model.engineType,
            model.promptTemplateType, files.modelPath, files.tokenizerPath)
    }
}
