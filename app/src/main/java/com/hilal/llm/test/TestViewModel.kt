package com.hilal.llm.test

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hilal.llm.benchmark.FeatureGenerationSession
import com.hilal.llm.catalog.BenchmarkModelSelection
import com.hilal.llm.catalog.RuntimeType
import com.hilal.llm.test.network.RetrievalException
import com.hilal.llm.test.network.RetrievalRepository
import com.hilal.llm.test.network.RetrievalResult
import com.hilal.llm.test.network.RetrievedChunk
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class TestUiState(
    val question: String = "",
    val isRunning: Boolean = false,
    val classificationResult: String? = null,
    val retrievalStatus: RetrievalStatus = RetrievalStatus.Idle,
    val retrievalTimeMs: Long? = null,
    val retrievedChunks: List<RetrievedChunk> = emptyList(),
    val answer: String = "",
    val classificationTimeMs: Long? = null,
    val finalGenerationTimeMs: Long? = null,
    val totalTimeMs: Long? = null,
    val errorMessage: String? = null
)

sealed interface RetrievalStatus {
    data object Idle : RetrievalStatus
    data object Running : RetrievalStatus
    data object Success : RetrievalStatus
    data object Ignored : RetrievalStatus
    data class Failed(val message: String) : RetrievalStatus
}

class TestViewModel(
    private val retrievalRepository: RetrievalRepository = RetrievalRepository()
) : ViewModel() {
    companion object {
        private const val TAG = "TEST_FLOW"
        private const val CLASSIFICATION_MAX_TOKENS = 8
        private const val ANSWER_MAX_TOKENS = 512
    }

    private val _uiState = MutableStateFlow(TestUiState())
    val uiState: StateFlow<TestUiState> = _uiState.asStateFlow()
    private val requestSequence = AtomicLong(0)
    private var activeJob: Job? = null

    fun updateQuestion(question: String) {
        _uiState.update { it.copy(question = question, errorMessage = null) }
    }

    fun submit(
        selection: BenchmarkModelSelection?,
        openSession: suspend (BenchmarkModelSelection) -> FeatureGenerationSession
    ) {
        val question = _uiState.value.question.trim()
        if (question.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Lütfen bir soru yazın.") }
            return
        }
        if (selection == null) {
            _uiState.update { it.copy(errorMessage = "Önce bir local generation modeli seçin.") }
            return
        }

        activeJob?.cancel()
        val requestId = requestSequence.incrementAndGet()
        activeJob = viewModelScope.launch {
            val totalStartedAt = SystemClock.elapsedRealtime()
            val isLiteRt = selection.runtimeType == RuntimeType.LITERT
            var flowSession: FeatureGenerationSession? = null
            updateIfCurrent(requestId) {
                it.copy(
                    isRunning = true,
                    classificationResult = null,
                    retrievalStatus = RetrievalStatus.Running,
                    retrievalTimeMs = null,
                    retrievedChunks = emptyList(),
                    answer = "",
                    classificationTimeMs = null,
                    finalGenerationTimeMs = null,
                    totalTimeMs = null,
                    errorMessage = null
                )
            }
            logFlowStart()
            Log.d(TAG, "TEST_FLOW | Soru gönderildi")
            Log.d(TAG, "TEST_FLOW | Soru = \"${question.forFlowLog()}\"")

            try {
                supervisorScope {
                    val retrievalDeferred = async {
                        val startedAt = SystemClock.elapsedRealtime()
                        Log.d(TAG, "TEST_FLOW | [RETRIEVAL] İstek gönderildi")
                        try {
                            val result = retrievalRepository.query(question)
                            val elapsed = SystemClock.elapsedRealtime() - startedAt
                            Log.d(TAG, "TEST_FLOW | [RETRIEVAL] Cevap geldi")
                            Log.d(TAG, "TEST_FLOW | [RETRIEVAL] Chunk sayısı = ${result.chunks.size}")
                            Log.d(TAG, "TEST_FLOW | [RETRIEVAL] Süre = ${elapsed.asSeconds()} saniye")
                            TimedRetrieval(result, elapsed)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            val elapsed = SystemClock.elapsedRealtime() - startedAt
                            Log.e(TAG, "TEST_FLOW | Retrieval failed | ${error.message}", error)
                            throw TimedRetrievalFailure(error, elapsed)
                        }
                    }
                    val classificationDeferred = async {
                        val startedAt = SystemClock.elapsedRealtime()
                        Log.d(TAG, "TEST_FLOW | [1. AŞAMA] İK sınıflandırması başladı")
                        if (isLiteRt) Log.d(PERF_TAG, "LITERT_PERF | Classification started")
                        try {
                            val classificationPrompt = buildClassificationPrompt(question)
                            Log.d(TAG, "TEST_FLOW | [LLM #1] Prompt gönderildi | Classification")
                            logFlowText("[LLM #1] Prompt", classificationPrompt)
                            val session = openSession(selection).also { flowSession = it }
                            val raw = session.generate(classificationPrompt, CLASSIFICATION_MAX_TOKENS)
                            val normalized = normalizeClassificationOutput(raw)
                            val elapsed = SystemClock.elapsedRealtime() - startedAt
                            Log.d(TAG, "TEST_FLOW | [LLM #1] Classification cevabı geldi")
                            Log.d(TAG, "TEST_FLOW | [LLM #1] Cevap = $normalized")
                            Log.d(TAG, "TEST_FLOW | [LLM #1] Süre = ${elapsed.asSeconds()} saniye")
                            if (isLiteRt) {
                                Log.d(
                                    PERF_TAG,
                                    "LITERT_PERF | Classification completed | ${elapsed.asSeconds()} saniye"
                                )
                            }
                            TimedClassification(normalized, elapsed)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            throw LocalGenerationFailure("LLM classification başarısız oldu.", error)
                        }
                    }

                    val classification = try {
                        classificationDeferred.await()
                    } catch (error: Throwable) {
                        retrievalDeferred.cancel()
                        throw error
                    }
                    updateIfCurrent(requestId) {
                        it.copy(
                            classificationResult = classification.result,
                            classificationTimeMs = classification.elapsedMs
                        )
                    }
                    Log.d(TAG, "TEST_FLOW | Classification sonucu = ${classification.result}")

                    val finalPrompt: String
                    val finalMode: String
                    var finalChunkCount = 0
                    if (classification.result.equals("EVET", ignoreCase = true)) {
                        Log.d(TAG, "TEST_FLOW | RAG akışına geçiliyor")
                        Log.d(TAG, "TEST_FLOW | Retrieval sonucu kullanılacak")
                        val retrieval = try {
                            retrievalDeferred.await()
                        } catch (failure: TimedRetrievalFailure) {
                            updateIfCurrent(requestId) {
                                it.copy(
                                    retrievalStatus = RetrievalStatus.Failed(readableRetrievalError(failure.cause)),
                                    retrievalTimeMs = failure.elapsedMs
                                )
                            }
                            throw failure.cause
                        }
                        if (retrieval.result.chunks.isEmpty()) {
                            updateIfCurrent(requestId) {
                                it.copy(
                                    retrievalStatus = RetrievalStatus.Failed("Retrieval sonucu boş döndü."),
                                    retrievalTimeMs = retrieval.elapsedMs
                                )
                            }
                            throw EmptyRetrievalFailure()
                        }
                        finalChunkCount = retrieval.result.chunks.take(3).size
                        Log.d(TAG, "TEST_FLOW | Kullanılacak chunk sayısı = $finalChunkCount")
                        updateIfCurrent(requestId) {
                            it.copy(
                                retrievalStatus = RetrievalStatus.Success,
                                retrievalTimeMs = retrieval.elapsedMs,
                                retrievedChunks = retrieval.result.chunks.take(3)
                            )
                        }
                        finalPrompt = buildRagAnswerPrompt(question, retrieval.result.chunks.take(3))
                        finalMode = "RAG"
                        Log.d(TAG, "TEST_FLOW | [2. AŞAMA] RAG final cevap üretimi başladı")
                    } else {
                        retrievalDeferred.cancel()
                        Log.d(TAG, "TEST_FLOW | Normal LLM akışına geçiliyor")
                        Log.d(TAG, "TEST_FLOW | Retrieval sonucu kullanılmayacak")
                        updateIfCurrent(requestId) {
                            it.copy(
                                retrievalStatus = RetrievalStatus.Ignored,
                                retrievalTimeMs = null,
                                retrievedChunks = emptyList()
                            )
                        }
                        finalPrompt = buildNormalAnswerPrompt(question)
                        finalMode = "NORMAL"
                        Log.d(TAG, "TEST_FLOW | [2. AŞAMA] Final cevap üretimi başladı")
                    }

                    val generationStartedAt = SystemClock.elapsedRealtime()
                    val chunkSuffix = if (finalMode == "RAG") " | chunks=$finalChunkCount" else ""
                    Log.d(TAG, "TEST_FLOW | [LLM #2] Prompt gönderildi | Mode=$finalMode$chunkSuffix")
                    logFlowText("[LLM #2] Prompt", finalPrompt)
                    if (isLiteRt) Log.d(PERF_TAG, "LITERT_PERF | Final generation started")
                    val answer = try {
                        checkNotNull(flowSession) { "Feature generation session is not initialized." }
                            .generate(finalPrompt, ANSWER_MAX_TOKENS)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        throw LocalGenerationFailure("Final local LLM üretimi başarısız oldu.", error)
                    }
                    val generationElapsed = SystemClock.elapsedRealtime() - generationStartedAt
                    val totalElapsed = SystemClock.elapsedRealtime() - totalStartedAt
                    Log.d(TAG, "TEST_FLOW | [LLM #2] Final cevap geldi")
                    Log.d(TAG, "TEST_FLOW | [LLM #2] Süre = ${generationElapsed.asSeconds()} saniye")
                    if (isLiteRt) {
                        Log.d(
                            PERF_TAG,
                            "LITERT_PERF | Final generation completed | ${generationElapsed.asSeconds()} saniye"
                        )
                    }
                    Log.d(TAG, "TEST_FLOW | Final cevap = \"${answer.forFlowLog()}\"")
                    Log.d(TAG, "TEST_FLOW | TOPLAM SÜRE = ${totalElapsed.asSeconds()} saniye")
                    logFlowEnd()
                    updateIfCurrent(requestId) {
                        it.copy(
                            isRunning = false,
                            answer = answer,
                            finalGenerationTimeMs = generationElapsed,
                            totalTimeMs = totalElapsed
                        )
                    }
                }
            } catch (_: CancellationException) {
                Log.d(TAG, "TEST_FLOW | Akış iptal edildi")
                logFlowEnd()
                // A newer submission owns the visible state.
            } catch (error: Throwable) {
                val message = when (error) {
                    is EmptyRetrievalFailure -> "Retrieval sonucu boş döndü. Final cevap üretilmedi."
                    is RetrievalException -> error.message ?: "Retrieval isteği başarısız oldu."
                    is LocalGenerationFailure -> error.message ?: "Local LLM çalıştırılamadı."
                    else -> error.message ?: "Test akışı tamamlanamadı."
                }
                updateIfCurrent(requestId) {
                    it.copy(
                        isRunning = false,
                        totalTimeMs = SystemClock.elapsedRealtime() - totalStartedAt,
                        errorMessage = message
                    )
                }
                val totalElapsed = SystemClock.elapsedRealtime() - totalStartedAt
                Log.e(TAG, "TEST_FLOW | Akış hata ile sonlandı | Hata = $message")
                Log.d(TAG, "TEST_FLOW | TOPLAM SÜRE = ${totalElapsed.asSeconds()} saniye")
                logFlowEnd()
            } finally {
                try {
                    flowSession?.close()
                } catch (error: Throwable) {
                    Log.e(PERF_TAG, "LITERT_PERF | Session close failed", error)
                }
                if (isLiteRt) {
                    val elapsed = SystemClock.elapsedRealtime() - totalStartedAt
                    Log.d(PERF_TAG, "LITERT_PERF | Full Test Flow = ${elapsed.asSeconds()} saniye")
                }
            }
        }
    }

    private fun updateIfCurrent(requestId: Long, transform: (TestUiState) -> TestUiState) {
        if (requestSequence.get() == requestId) _uiState.update(transform)
    }

    private fun readableRetrievalError(error: Throwable): String {
        return (error as? RetrievalException)?.message ?: "Retrieval isteği başarısız oldu."
    }

    private fun buildClassificationPrompt(question: String): String = """
        Kullanıcı sorusu:
        $question

        Bu soru İnsan Kaynakları (İK) ile ilgili mi?

        Yalnızca "Evet" veya "Hayır" cevabı ver.
        Başka hiçbir açıklama veya metin üretme.
    """.trimIndent()

    private fun buildRagAnswerPrompt(question: String, chunks: List<RetrievedChunk>): String {
        val context = chunks.mapIndexed { index, chunk ->
            "[Chunk ${index + 1}]\n${chunk.text}"
        }.joinToString("\n\n")
        return """
            Kullanıcının sorusu:
            $question

            Aşağıda soruyla ilgili doküman parçaları verilmiştir:

            $context

            Kullanıcının sorusunu verilen doküman parçalarındaki bilgileri temel alarak cevapla.

            Dokümanlarda yeterli bilgi bulunmuyorsa bunu açıkça belirt.
        """.trimIndent()
    }

    private fun buildNormalAnswerPrompt(question: String): String = """
        Kullanıcının sorusu:
        $question

        Kullanıcının sorusuna uygun, açık ve doğru bir cevap ver.
    """.trimIndent()

    private data class TimedClassification(val result: String, val elapsedMs: Long)
    private data class TimedRetrieval(val result: RetrievalResult, val elapsedMs: Long)
    private class TimedRetrievalFailure(override val cause: Throwable, val elapsedMs: Long) : Exception(cause)
    private class EmptyRetrievalFailure : Exception()
    private class LocalGenerationFailure(message: String, cause: Throwable) : Exception(message, cause)
}

internal fun normalizeClassificationOutput(raw: String): String {
    return raw.trim()
        .split(Regex("\\s+"))
        .firstOrNull()
        ?.trim { !it.isLetter() }
        ?.uppercase(Locale.ROOT)
        .orEmpty()
}

private fun Long.asSeconds(): String = String.format(Locale.ROOT, "%.3f", this / 1000.0)

private fun String.forFlowLog(): String = replace("\r", " ").replace("\n", " ")

private fun logFlowText(label: String, value: String) {
    val normalized = value.forFlowLog()
    val parts = normalized.chunked(3_000)
    if (parts.size <= 1) {
        Log.d("TEST_FLOW", "TEST_FLOW | $label = \"$normalized\"")
        return
    }
    parts.forEachIndexed { index, part ->
        Log.d(
            "TEST_FLOW",
            "TEST_FLOW | $label [${index + 1}/${parts.size}] = \"$part\""
        )
    }
}

private fun logFlowStart() {
    Log.d("TEST_FLOW", "==================================================")
    Log.d("TEST_FLOW", "TEST FLOW START")
    Log.d("TEST_FLOW", "==================================================")
}

private fun logFlowEnd() {
    Log.d("TEST_FLOW", "==================================================")
    Log.d("TEST_FLOW", "TEST FLOW END")
    Log.d("TEST_FLOW", "==================================================")
}

private const val PERF_TAG = "LITERT_PERF"
