package com.hilal.llm.benchmark

import android.content.ContentResolver
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BenchmarkViewModel : ViewModel() {
    companion object {
        private const val DEFAULT_MAX_TOKENS = 512
        private const val MIN_MAX_TOKENS = 1
        private const val MAX_MAX_TOKENS = 2048
    }

    private val fakeEngine: LocalLlmEngine = FakeLlmEngine()
    private var llamaEngine: LocalLlmEngine? = null
    private var liteRtEngine: LocalLlmEngine? = null
    private var mediaPipeEngine: LocalLlmEngine? = null
    private var execuTorchEngine: LocalLlmEngine? = null
    private var selectedEngine: LocalLlmEngine = fakeEngine
    private val _uiState = MutableStateFlow(BenchmarkUiState())
    val uiState: StateFlow<BenchmarkUiState> = _uiState.asStateFlow()

    fun initializeLlamaEngine(
        appContext: Context,
        contentResolver: ContentResolver
    ) {
        llamaEngine = LlamaCppLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
    }

    fun initializeLiteRtEngine(
        appContext: Context,
        contentResolver: ContentResolver
    ) {
        liteRtEngine = LiteRtLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
    }

    fun initializeMediaPipeEngine(
        appContext: Context,
        contentResolver: ContentResolver
    ) {
        mediaPipeEngine = MediaPipeLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
    }

    fun initializeExecuTorchEngine(
        appContext: Context,
        contentResolver: ContentResolver
    ) {
        execuTorchEngine = ExecuTorchLlmEngine(
            appContext = appContext,
            contentResolver = contentResolver
        )
    }

    fun setEngine(engineType: EngineType) {
        selectedEngine = when (engineType) {
            EngineType.FAKE -> fakeEngine
            EngineType.LLAMA_CPP -> llamaEngine ?: fakeEngine
            EngineType.LITERT -> liteRtEngine ?: fakeEngine
            EngineType.MEDIAPIPE -> mediaPipeEngine ?: fakeEngine
            EngineType.EXECUTORCH -> execuTorchEngine ?: fakeEngine
        }
        _uiState.update { it.copy(selectedEngineType = engineType) }
    }

    fun onModelFileSelected(displayName: String, uri: String) {
        val warning = when (_uiState.value.selectedEngineType) {
            EngineType.LITERT ->
                if (displayName.endsWith(".litertlm", ignoreCase = true)) null
                else "Selected file is not .litertlm. It is allowed, but .litertlm is preferred for LiteRT."
            EngineType.MEDIAPIPE ->
                if (displayName.endsWith(".task", ignoreCase = true)) null
                else "Selected file is not .task. It is allowed, but .task is preferred for MediaPipe."
            EngineType.EXECUTORCH ->
                if (displayName.endsWith(".pte", ignoreCase = true)) null
                else "Selected file is not .pte. It is allowed, but .pte is preferred for ExecuTorch."
            else ->
                if (displayName.endsWith(".gguf", ignoreCase = true)) null
                else "Selected file is not .gguf. It is allowed for now, but .gguf is preferred."
        }

        _uiState.update {
            it.copy(
                selectedModel = LlmModelInfo(
                    modelName = displayName,
                    modelFilePath = uri,
                    tokenizerName = it.selectedModel?.tokenizerName,
                    tokenizerFilePath = it.selectedModel?.tokenizerFilePath
                ),
                errorMessage = null,
                modelWarningMessage = warning
            )
        }
    }

    fun onTokenizerFileSelected(displayName: String, uri: String) {
        val warning = if (
            displayName.endsWith(".json", ignoreCase = true) ||
            displayName.endsWith(".model", ignoreCase = true) ||
            displayName.endsWith(".bin", ignoreCase = true)
        ) {
            null
        } else {
            "Selected tokenizer file extension is unusual. tokenizer.json / tokenizer.model / tokenizer.bin is preferred."
        }

        _uiState.update { current ->
            val currentModel = current.selectedModel
            if (currentModel == null) {
                current.copy(
                    errorMessage = "Select model file first, then tokenizer.",
                    modelWarningMessage = warning
                )
            } else {
                current.copy(
                    selectedModel = currentModel.copy(
                        tokenizerName = displayName,
                        tokenizerFilePath = uri
                    ),
                    errorMessage = null,
                    modelWarningMessage = warning
                )
            }
        }
    }

    fun updatePrompt(prompt: String) {
        _uiState.update { it.copy(prompt = prompt) }
    }

    fun updateMaxTokensInput(value: String) {
        _uiState.update { it.copy(maxTokensInput = value.filter { ch -> ch.isDigit() }) }
    }

    fun clear() {
        _uiState.update {
            it.copy(
                maxTokensInput = DEFAULT_MAX_TOKENS.toString(),
                prompt = "",
                response = "",
                firstTokenTimeMs = null,
                totalTimeMs = null,
                tokenCount = 0,
                tokensPerSecond = null,
                errorMessage = null,
                modelWarningMessage = null,
                isRunning = false
            )
        }
    }

    fun runBenchmark() {
        val state = _uiState.value
        val model = state.selectedModel
        val prompt = state.prompt.trim()

        if (model == null) {
            _uiState.update { it.copy(errorMessage = "Please select a model file.") }
            return
        }
        if (prompt.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please enter a prompt.") }
            return
        }
        val parsedMaxTokens = state.maxTokensInput.toIntOrNull()
        if (parsedMaxTokens == null) {
            _uiState.update {
                it.copy(errorMessage = "Please enter a valid max tokens value.")
            }
            return
        }
        val maxTokens = parsedMaxTokens.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)
        if (maxTokens != parsedMaxTokens) {
            _uiState.update { it.copy(maxTokensInput = maxTokens.toString()) }
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRunning = true,
                    errorMessage = null,
                    response = "",
                    firstTokenTimeMs = null,
                    totalTimeMs = null,
                    tokenCount = 0,
                    tokensPerSecond = null
                )
            }

            val request = BenchmarkPrompt(
                modelName = model.modelName,
                modelFilePath = model.modelFilePath,
                tokenizerFilePath = model.tokenizerFilePath,
                prompt = prompt,
                maxTokens = maxTokens
            )
            val benchmarkRunner = BenchmarkRunner(selectedEngine)

            benchmarkRunner.run(
                request = request,
                onToken = { partialResponse ->
                    _uiState.update {
                        it.copy(
                            response = partialResponse,
                            tokenCount = estimateTokenCount(partialResponse)
                        )
                    }
                },
                onComplete = { result ->
                    val finalized = result.copy(
                        engineName = _uiState.value.selectedEngineType.label,
                        maxTokens = maxTokens
                    )
                    _uiState.update {
                        it.copy(
                            response = finalized.response,
                            firstTokenTimeMs = finalized.firstTokenTimeMs,
                            totalTimeMs = finalized.totalTimeMs,
                            tokenCount = finalized.tokenCount,
                            tokensPerSecond = finalized.tokensPerSecond,
                            errorMessage = finalized.errorMessage,
                            isRunning = false,
                            completedResults = listOf(finalized) + it.completedResults
                        )
                    }
                }
            )
        }
    }
}
