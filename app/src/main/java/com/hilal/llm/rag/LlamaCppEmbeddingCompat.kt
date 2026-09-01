package com.hilal.llm.rag

import org.nehuatl.llamacpp.LlamaContext

internal object LlamaCppEmbeddingCompat {
    init {
        System.loadLibrary("rag_sqlitevec")
    }

    external fun embedding(
        llamaContext: LlamaContext,
        contextPtr: Long,
        text: String
    ): Any?
}
