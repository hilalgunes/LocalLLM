package com.hilal.llm.benchmark

class BenchmarkPresetRepository {
    private val presets = listOf(
        BenchmarkPreset(
            id = "basic-math-short",
            title = "Math Short",
            description = "Basic arithmetic sanity check.",
            prompt = "What is 2+2? Answer only the number.",
            maxTokens = 16,
            category = BenchmarkPresetCategory.BASIC
        ),
        BenchmarkPreset(
            id = "basic-one-word",
            title = "One Word",
            description = "Single-word completion check.",
            prompt = "Reply with exactly one word: hello",
            maxTokens = 16,
            category = BenchmarkPresetCategory.BASIC
        ),
        BenchmarkPreset(
            id = "turkish-short",
            title = "Turkish Short",
            description = "Short Turkish answer test.",
            prompt = "Android nedir? Türkçe ve tek kısa cümleyle açıkla.",
            maxTokens = 64,
            category = BenchmarkPresetCategory.TURKISH
        ),
        BenchmarkPreset(
            id = "instruction-summarization",
            title = "Summarization",
            description = "Instruction following and condensation.",
            prompt = "Summarize this in one sentence: Android is an operating system for mobile devices developed by Google.",
            maxTokens = 64,
            category = BenchmarkPresetCategory.INSTRUCTION
        ),
        BenchmarkPreset(
            id = "reasoning-simple",
            title = "Simple Reasoning",
            description = "Light reasoning and arithmetic.",
            prompt = "If a car travels 60 km in 1 hour, how far does it travel in 2 hours? Answer briefly.",
            maxTokens = 64,
            category = BenchmarkPresetCategory.REASONING
        ),
        BenchmarkPreset(
            id = "long-output-paragraph",
            title = "Paragraph",
            description = "Longer response generation check.",
            prompt = "Explain what on-device AI means in one short paragraph.",
            maxTokens = 128,
            category = BenchmarkPresetCategory.LONG_OUTPUT
        )
    )

    fun getAvailablePresets(): List<BenchmarkPreset> = presets

    fun getPresetById(id: String): BenchmarkPreset? = presets.firstOrNull { it.id == id }
}
