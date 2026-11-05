package com.hilal.llm.inference

import com.hilal.llm.catalog.BenchmarkModelSelection

fun validateCatalogSelectionForRun(selection: BenchmarkModelSelection): String? {
    return when (selection.engineType) {
        com.hilal.llm.catalog.EngineType.EXECUTORCH_GENERATE -> {
            val hasPte = selection.modelPath.trim().endsWith(".pte", ignoreCase = true)
            val hasTokenizer = !selection.tokenizerPath.isNullOrBlank()
            when {
                !hasPte -> "Selected ExecuTorch model must be a .pte file."
                !hasTokenizer -> "Selected ExecuTorch model requires a tokenizer file."
                else -> null
            }
        }
        com.hilal.llm.catalog.EngineType.EXECUTORCH_FORWARD ->
            "This ExecuTorch model is forward-only and requires a custom forward runner."

        com.hilal.llm.catalog.EngineType.LITERT_LM -> {
            val normalized = selection.modelPath.trim().lowercase()
            val supported = normalized.endsWith(".litertlm") ||
                normalized.endsWith(".task") ||
                normalized.endsWith(".tflite") ||
                normalized.endsWith(".zip") ||
                normalized.endsWith(".bundle")
            if (supported) null else "Selected LiteRT model format is not supported by current LiteRT runner."
        }

        com.hilal.llm.catalog.EngineType.ONNX_GENAI -> {
            if (selection.modelPath.endsWith("genai_config.json")) null
            else "ONNX requires a complete GenAI model package from the catalog."
        }
        com.hilal.llm.catalog.EngineType.LLAMA_CPP -> {
            if (selection.modelPath.trim().endsWith(".gguf", ignoreCase = true)) {
                null
            } else {
                "Selected llama.cpp model must be a .gguf file."
            }
        }
    }
}
