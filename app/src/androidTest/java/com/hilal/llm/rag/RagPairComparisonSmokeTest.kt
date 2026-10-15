package com.hilal.llm.rag

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Downloads actual public bundles and compares real vectors, retrieval and generated answers. */
class RagPairComparisonSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<RagComparisonActivity>()
    @Test fun comparesRealGemmaModelsAndFinalAnswers() {
        lateinit var vm: RagComparisonViewModel
        compose.runOnIdle {
            vm = ViewModelProvider(compose.activity)[RagComparisonViewModel::class.java]
            vm.edit { it.copy(includeAnswers = true, generationId = "gemma-litert-example", repeats = 1, topK = 1, dimension = 128,
                passages = defaultComparisonPassages.lines().take(3).joinToString("\n"),
                queries = "İzin talebimi ne kadar önceden bildirmeliyim? | 1 | En az yedi gün önceden.") }
            vm.downloadSelected()
        }
        compose.waitUntil(600_000) { !vm.state.value.busy }
        assertNull("Download failed", vm.state.value.error)
        compose.runOnIdle { vm.edit { it.copy(includeAnswers = true, generationId = "gemma-litert-example") }; vm.run() }
        compose.waitUntil(600_000) { !vm.state.value.busy }
        assertNull("Comparison failed", vm.state.value.error)
        val report = requireNotNull(vm.state.value.report)
        assertEquals(2, report.results.size)
        assertEquals(2, report.artifactSha256.size)
        assertTrue(report.artifactSha256.values.all { it.length == 64 })
        for (result in report.results) {
            assertNull(result.error)
            assertEquals(128, result.dimension)
            assertTrue(result.indexingMs.single() > 0)
            assertTrue(result.sampledPeakPssMb > 0)
            val case = result.cases.single()
            assertEquals(report.dataset.passages.size, case.fullRanking.size)
            val source = case.sources.single()
            assertTrue(source.passage in 1..report.dataset.passages.size)
            assertEquals(source.passage in case.query.relevant, source.relevant)
            assertEquals(if (source.relevant) 1.0 else 0.0, case.retrieval.recall, 0.0)
            val answer = requireNotNull(result.cases.single().answer)
            assertNull(answer.error)
            assertTrue(answer.text.isNotBlank())
            assertTrue(requireNotNull(answer.firstTextMs) > 0)
            assertTrue(answer.totalMs >= answer.firstTextMs)
        }
        compose.runOnIdle { vm.rate(report.results[0].modelId, 0, "Doğru") }
        assertTrue(vm.exportJson().contains("Doğru"))
        compose.runOnIdle { vm.rate(report.results[0].modelId, 0, "") }
    }
}
