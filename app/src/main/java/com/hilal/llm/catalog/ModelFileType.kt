package com.hilal.llm.catalog

enum class ModelFileType {
    EXECUTORCH_PTE,
    TOKENIZER_JSON,
    TOKENIZER_MODEL,
    LITERT_TASK,
    LITERT_TFLITE,
    LITERT_BUNDLE,
    LLAMA_GGUF,
    CONFIG_JSON,
    OTHER,
    ONNX_CONFIG
}
