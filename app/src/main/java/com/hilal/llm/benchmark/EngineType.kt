package com.hilal.llm.benchmark

enum class EngineType(val label: String) {
    FAKE("Fake Engine"),
    LLAMA_CPP("Llama.cpp Engine"),
    LITERT("LiteRT Engine"),
    MEDIAPIPE("MediaPipe Engine"),
    EXECUTORCH("ExecuTorch Engine")
}
