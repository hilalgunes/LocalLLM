package com.hilal.llm.benchmark

enum class EngineType(val label: String) {
    FAKE("Fake Engine"),
    LLAMA_CPP("Llama.cpp Engine"),
    LITERT("LiteRT Engine"),
    EXECUTORCH("ExecuTorch Engine")
}

val productionEngineTypes = listOf(
    EngineType.LLAMA_CPP,
    EngineType.LITERT,
    EngineType.EXECUTORCH
)
