package com.hilal.llm.catalog

object ModelCatalogRepository {
    private val availableModels: List<ModelConfig> = listOf(
        ModelConfig(
            id = "qwen3-0.6b-executorch-xnnpack",
            displayName = "Qwen3 0.6B INT8/INT4 ExecuTorch XNNPACK",
            description = "ExecuTorch generate model with a Qwen prompt template on XNNPACK.",
            runtimeType = RuntimeType.EXECUTORCH,
            engineType = EngineType.EXECUTORCH_GENERATE,
            promptTemplateType = PromptTemplateType.QWEN,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.EXECUTORCH_PTE,
                    fileName = "model.pte",
                    url = "https://huggingface.co/larryliu0820/Qwen3-0.6B-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/model.pte",
                    required = true,
                    description = "Primary ExecuTorch model file."
                ),
                ModelFileConfig(
                    type = ModelFileType.TOKENIZER_JSON,
                    fileName = "tokenizer.json",
                    url = "https://huggingface.co/larryliu0820/Qwen3-0.6B-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/tokenizer.json",
                    required = true,
                    description = "Required tokenizer for the Qwen export."
                ),
                ModelFileConfig(
                    type = ModelFileType.OTHER,
                    fileName = "vocab.json",
                    url = "https://huggingface.co/larryliu0820/Qwen3-0.6B-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/vocab.json",
                    required = false,
                    description = "Additional tokenizer vocabulary artifact published with the export."
                ),
                ModelFileConfig(
                    type = ModelFileType.OTHER,
                    fileName = "merges.txt",
                    url = "https://huggingface.co/larryliu0820/Qwen3-0.6B-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/merges.txt",
                    required = false,
                    description = "Additional tokenizer merge rules published with the export."
                )
            ),
            estimatedSize = "~0.6 GB",
            quantization = "8da4w",
            isEnabled = true,
            disabledReason = null
        ),
        ModelConfig(
            id = "gemma3-4b-executorch-hqq-int8-int4",
            displayName = "Gemma 3 4B HQQ INT8/INT4 ExecuTorch",
            description = "ExecuTorch generate model with Gemma prompt formatting.",
            runtimeType = RuntimeType.EXECUTORCH,
            engineType = EngineType.EXECUTORCH_GENERATE,
            promptTemplateType = PromptTemplateType.GEMMA,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.EXECUTORCH_PTE,
                    fileName = "model.pte",
                    url = "https://huggingface.co/pytorch/gemma-3-4b-it-HQQ-INT8-INT4/resolve/main/model.pte",
                    required = true,
                    description = "Primary ExecuTorch model file."
                ),
                ModelFileConfig(
                    type = ModelFileType.TOKENIZER_JSON,
                    fileName = "tokenizer.json",
                    url = "https://huggingface.co/pytorch/gemma-3-4b-it-HQQ-INT8-INT4/resolve/main/tokenizer.json",
                    required = true,
                    description = "Required tokenizer format for Gemma exports."
                ),
                ModelFileConfig(
                    type = ModelFileType.TOKENIZER_MODEL,
                    fileName = "tokenizer.model",
                    url = "https://huggingface.co/pytorch/gemma-3-4b-it-HQQ-INT8-INT4/resolve/main/tokenizer.model",
                    required = false,
                    description = "Alternative tokenizer artifact published with the export."
                ),
                ModelFileConfig(
                    type = ModelFileType.CONFIG_JSON,
                    fileName = "config.json",
                    url = "https://huggingface.co/pytorch/gemma-3-4b-it-HQQ-INT8-INT4/resolve/main/config.json",
                    required = false,
                    description = "Upstream model configuration metadata."
                ),
                ModelFileConfig(
                    type = ModelFileType.CONFIG_JSON,
                    fileName = "generation_config.json",
                    url = "https://huggingface.co/pytorch/gemma-3-4b-it-HQQ-INT8-INT4/resolve/main/generation_config.json",
                    required = false,
                    description = "Generation defaults published with the export."
                ),
                ModelFileConfig(
                    type = ModelFileType.OTHER,
                    fileName = "tokenizer_config.json",
                    url = "https://huggingface.co/pytorch/gemma-3-4b-it-HQQ-INT8-INT4/resolve/main/tokenizer_config.json",
                    required = false,
                    description = "Tokenizer configuration used by the export."
                ),
                ModelFileConfig(
                    type = ModelFileType.OTHER,
                    fileName = "special_tokens_map.json",
                    url = "https://huggingface.co/pytorch/gemma-3-4b-it-HQQ-INT8-INT4/resolve/main/special_tokens_map.json",
                    required = false,
                    description = "Special token mapping for the tokenizer."
                ),
                ModelFileConfig(
                    type = ModelFileType.OTHER,
                    fileName = "chat_template.jinja",
                    url = "https://huggingface.co/pytorch/gemma-3-4b-it-HQQ-INT8-INT4/resolve/main/chat_template.jinja",
                    required = false,
                    description = "Chat template published with the export."
                )
            ),
            estimatedSize = "~3.0 GB",
            quantization = "HQQ INT8/INT4",
            isEnabled = false,
            disabledReason = "This Gemma 3 4B HQQ export is multimodal and is not compatible with the current text-only LlmModule.generate runner."
        ),
        ModelConfig(
            id = "gemma3-1b-executorch-xnnpack",
            displayName = "Gemma 3 1B IT INT8/INT4 ExecuTorch XNNPACK",
            description = "Text-only ExecuTorch generate model with Gemma prompt formatting.",
            runtimeType = RuntimeType.EXECUTORCH,
            engineType = EngineType.EXECUTORCH_GENERATE,
            promptTemplateType = PromptTemplateType.GEMMA,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.EXECUTORCH_PTE,
                    fileName = "model.pte",
                    url = "https://huggingface.co/larryliu0820/Gemma3-1B-IT-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/model.pte",
                    required = true,
                    description = "Primary ExecuTorch model file."
                ),
                ModelFileConfig(
                    type = ModelFileType.TOKENIZER_JSON,
                    fileName = "tokenizer.json",
                    url = "https://huggingface.co/larryliu0820/Gemma3-1B-IT-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/tokenizer.json",
                    required = true,
                    description = "Required tokenizer for the Gemma 1B export."
                ),
                ModelFileConfig(
                    type = ModelFileType.TOKENIZER_MODEL,
                    fileName = "tokenizer.model",
                    url = "https://huggingface.co/larryliu0820/Gemma3-1B-IT-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/tokenizer.model",
                    required = false,
                    description = "Alternative tokenizer artifact published with the export."
                ),
                ModelFileConfig(
                    type = ModelFileType.CONFIG_JSON,
                    fileName = "config.json",
                    url = "https://huggingface.co/larryliu0820/Gemma3-1B-IT-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/config.json",
                    required = false,
                    description = "Upstream model configuration metadata."
                ),
                ModelFileConfig(
                    type = ModelFileType.CONFIG_JSON,
                    fileName = "generation_config.json",
                    url = "https://huggingface.co/larryliu0820/Gemma3-1B-IT-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/generation_config.json",
                    required = false,
                    description = "Generation defaults published with the export."
                ),
                ModelFileConfig(
                    type = ModelFileType.OTHER,
                    fileName = "tokenizer_config.json",
                    url = "https://huggingface.co/larryliu0820/Gemma3-1B-IT-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/tokenizer_config.json",
                    required = false,
                    description = "Tokenizer configuration used by the export."
                ),
                ModelFileConfig(
                    type = ModelFileType.OTHER,
                    fileName = "special_tokens_map.json",
                    url = "https://huggingface.co/larryliu0820/Gemma3-1B-IT-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/special_tokens_map.json",
                    required = false,
                    description = "Special token mapping for the tokenizer."
                ),
                ModelFileConfig(
                    type = ModelFileType.OTHER,
                    fileName = "chat_template.jinja",
                    url = "https://huggingface.co/larryliu0820/Gemma3-1B-IT-INT8-INT4-ExecuTorch-XNNPACK/resolve/main/chat_template.jinja",
                    required = false,
                    description = "Chat template published with the export."
                )
            ),
            estimatedSize = "~1.0 GB",
            quantization = "INT8/INT4",
            isEnabled = true,
            disabledReason = null
        ),
        ModelConfig(
            id = "phi4-mini-parq-executorch-forward",
            displayName = "Phi-4-mini PARQ ExecuTorch",
            description = "Forward-only ExecuTorch example model for custom runners.",
            runtimeType = RuntimeType.EXECUTORCH,
            engineType = EngineType.EXECUTORCH_FORWARD,
            promptTemplateType = PromptTemplateType.PHI,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.EXECUTORCH_PTE,
                    fileName = "phi4_model_2bit.pte",
                    url = "https://example.com/models/phi4-mini-parq/phi4_model_2bit.pte",
                    required = true,
                    description = "Forward-only ExecuTorch export."
                ),
                ModelFileConfig(
                    type = ModelFileType.TOKENIZER_JSON,
                    fileName = "tokenizer.json",
                    url = "https://example.com/models/phi4-mini-parq/tokenizer.json",
                    required = true,
                    description = "Tokenizer required by the forward runner."
                )
            ),
            estimatedSize = "~1.8 GB",
            quantization = "PARQ 2-bit",
            isEnabled = false,
            disabledReason = "This model exposes only forward method and requires a custom forward runner."
        ),
        ModelConfig(
            id = "gemma-litert-example",
            displayName = "Gemma 4 E2B IT LiteRT-LM",
            description = "LiteRT-LM Gemma export packaged as a runtime-specific bundle.",
            runtimeType = RuntimeType.LITERT,
            engineType = EngineType.LITERT_LM,
            promptTemplateType = PromptTemplateType.GEMMA,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.LITERT_TASK,
                    fileName = "gemma-4-E2B-it.litertlm",
                    url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                    required = true,
                    description = "LiteRT task package."
                ),
                ModelFileConfig(
                    type = ModelFileType.LITERT_BUNDLE,
                    fileName = "litert-bundle.zip",
                    url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/litert-bundle.zip",
                    required = false,
                    description = "Alternative packaged bundle if the model is distributed as a bundle instead of a task file."
                )
            ),
            estimatedSize = "~2.6 GB",
            quantization = "LiteRT",
            isEnabled = true,
            disabledReason = null
        ),
        ModelConfig(
            id = "qwen3-0.6b-litert",
            displayName = "Qwen3 0.6B LiteRT-LM",
            description = "LiteRT-LM Qwen3 export with mixed INT4 weights and float KV cache.",
            runtimeType = RuntimeType.LITERT,
            engineType = EngineType.LITERT_LM,
            promptTemplateType = PromptTemplateType.QWEN,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.LITERT_TASK,
                    fileName = "qwen3_0_6b_mixed_int4.litertlm",
                    url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm",
                    required = true,
                    description = "Primary LiteRT-LM model bundle."
                )
            ),
            estimatedSize = "~475 MB",
            quantization = "mixed INT4",
            isEnabled = true,
            disabledReason = null
        ),
        ModelConfig(
            id = "qwen3-5-2b-litert-lm",
            displayName = "Qwen 3.5 2B LiteRT-LM",
            description = "LiteRT-LM text-only bundle of Qwen 3.5 2B for on-device inference.",
            runtimeType = RuntimeType.LITERT,
            engineType = EngineType.LITERT_LM,
            promptTemplateType = PromptTemplateType.QWEN,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.LITERT_TASK,
                    fileName = "qwen35_2b.litertlm",
                    url = "https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM/resolve/main/qwen35_2b.litertlm",
                    required = true,
                    description = "Primary LiteRT-LM text-only bundle file."
                )
            ),
            estimatedSize = "~1.9 GB",
            quantization = "int8 dynamic",
            isEnabled = true,
            disabledReason = null
        ),
        ModelConfig(
            id = "qwen3-5-2b-litert",
            displayName = "Qwen 3.5 2B LiteRT",
            description = "LiteRT conversion of Qwen 3.5 2B for on-device inference.",
            runtimeType = RuntimeType.LITERT,
            engineType = EngineType.LITERT_LM,
            promptTemplateType = PromptTemplateType.QWEN,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.LITERT_TASK,
                    fileName = "model_multimodal.litertlm",
                    url = "https://huggingface.co/litert-community/Qwen3.5-2B-LiteRT/resolve/main/model_multimodal.litertlm",
                    required = true,
                    description = "Primary LiteRT bundle file."
                )
            ),
            estimatedSize = "~2.6 GB",
            quantization = "int8 dynamic",
            isEnabled = false,
            disabledReason = "This multimodal LiteRT bundle is not compatible with the current text-only LiteRT-LM runtime. Use Qwen 3.5 2B LiteRT-LM instead."
        ),
        ModelConfig(
            id = "llama-cpp-example",
            displayName = "llama.cpp Example",
            description = "GGUF-based llama.cpp model with embedded tokenizer data.",
            runtimeType = RuntimeType.LLAMA_CPP,
            engineType = EngineType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.LLAMA,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.LLAMA_GGUF,
                    fileName = "model.gguf",
                    url = "https://example.com/models/llama-cpp/model.gguf",
                    required = true,
                    description = "Primary GGUF model file."
                )
            ),
            estimatedSize = "~4.0 GB",
            quantization = "Q4_K_M",
            isEnabled = true,
            disabledReason = null
        ),
        ModelConfig(
            id = "qwen3-5-2b-gguf",
            displayName = "Qwen 3.5 2B GGUF",
            description = "llama.cpp-compatible GGUF build of Qwen 3.5 2B.",
            runtimeType = RuntimeType.LLAMA_CPP,
            engineType = EngineType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.QWEN,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.LLAMA_GGUF,
                    fileName = "Qwen3.5-2B-Q4_K_M.gguf",
                    url = "https://huggingface.co/lmstudio-community/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
                    required = true,
                    description = "Primary llama.cpp GGUF model file."
                )
            ),
            estimatedSize = "~1.27 GB",
            quantization = "Q4_K_M",
            isEnabled = true,
            disabledReason = null
        ),
        ModelConfig(
            id = "qwen3-embedding-0.6b-gguf",
            displayName = "Qwen3 Embedding 0.6B GGUF",
            description = "Text embedding model for semantic retrieval and ranking.",
            runtimeType = RuntimeType.LLAMA_CPP,
            engineType = EngineType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.LLAMA,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.LLAMA_GGUF,
                    fileName = "Qwen3-Embedding-0.6B-Q8_0.gguf",
                    url = "https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/main/Qwen3-Embedding-0.6B-Q8_0.gguf",
                    required = true,
                    description = "Primary Qwen3 embedding model file.",
                    minBytes = 600_000_000L
                )
            ),
            estimatedSize = "~639 MB",
            quantization = "Q8_0",
            isEnabled = true,
            disabledReason = null
        ),
        ModelConfig(
            id = "gecko-110m-en-256-litert",
            displayName = "Gecko 110M EN 256 LiteRT",
            description = "LiteRT/TFLite local text embedding model for semantic retrieval.",
            runtimeType = RuntimeType.LITERT,
            engineType = EngineType.LITERT_LM,
            promptTemplateType = PromptTemplateType.LLAMA,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.LITERT_TFLITE,
                    fileName = "Gecko_256_quant.tflite",
                    url = "https://huggingface.co/litert-community/Gecko-110m-en/resolve/main/Gecko_256_quant.tflite",
                    required = true,
                    description = "Primary Gecko LiteRT embedding model file.",
                    minBytes = 100_000_000L
                ),
                ModelFileConfig(
                    type = ModelFileType.OTHER,
                    fileName = "sentencepiece.model",
                    url = "https://huggingface.co/litert-community/Gecko-110m-en/resolve/main/sentencepiece.model",
                    required = true,
                    description = "SentencePiece tokenizer for Gecko embedding."
                )
            ),
            estimatedSize = "~115 MB",
            quantization = "dynamic INT8",
            isEnabled = true,
            disabledReason = null
        ),
        ModelConfig(
            id = "multilingual-e5-small-gguf",
            displayName = "multilingual-e5-small GGUF",
            description = "Compact multilingual embedding model for retrieval.",
            runtimeType = RuntimeType.LLAMA_CPP,
            engineType = EngineType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.LLAMA,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.LLAMA_GGUF,
                    fileName = "multilingual-e5-small-q8_0.gguf",
                    url = "https://huggingface.co/cstr/multilingual-e5-small-GGUF/resolve/main/multilingual-e5-small-q8_0.gguf",
                    required = true,
                    description = "Primary multilingual embedding model file.",
                    minBytes = 120_000_000L
                )
            ),
            estimatedSize = "~132 MB",
            quantization = "Q8_0",
            isEnabled = true,
            disabledReason = null
        ),
        ModelConfig(
            id = "all-minilm-l6-v2-gguf",
            displayName = "all-MiniLM-L6-v2 GGUF",
            description = "Compact English embedding model for retrieval.",
            runtimeType = RuntimeType.LLAMA_CPP,
            engineType = EngineType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.LLAMA,
            files = listOf(
                ModelFileConfig(
                    type = ModelFileType.LLAMA_GGUF,
                    fileName = "all-MiniLM-L6-v2-q8_0.gguf",
                    url = "https://huggingface.co/cstr/all-MiniLM-L6-v2-GGUF/resolve/main/all-MiniLM-L6-v2-q8_0.gguf",
                    required = true,
                    description = "Primary MiniLM embedding model file.",
                    minBytes = 20_000_000L
                )
            ),
            estimatedSize = "~24.7 MB",
            quantization = "Q8_0",
            isEnabled = true,
            disabledReason = null
        )
    )

    fun getAvailableModels(): List<ModelConfig> = availableModels

    fun getModelById(id: String): ModelConfig? = availableModels.firstOrNull { it.id == id }
}
