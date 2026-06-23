package com.hilal.llm.catalog.storage

import com.hilal.llm.catalog.ModelFileType
import java.io.File

data class ModelLocalFile(
    val type: ModelFileType,
    val fileName: String,
    val localFile: File,
    val exists: Boolean,
    val required: Boolean
)
