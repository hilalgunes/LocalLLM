package com.hilal.llm.rag

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic two-colour fixtures validate native image support, not photo retrieval accuracy. */
class ImageEmbeddingSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<RagComparisonActivity>()
    @Test fun computesRealImageAndCaptionEmbeddings() {
        val context = compose.activity.applicationContext
        val images = listOf(Color.BLUE, Color.GREEN).mapIndexed { index, colour ->
            val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(colour)
            val file = File(context.cacheDir, "embedding-image-test-$index.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            Uri.fromFile(file)
        }
        lateinit var vm: ImageComparisonViewModel
        compose.runOnIdle {
            vm = ViewModelProvider(compose.activity)[ImageComparisonViewModel::class.java]
            vm.add(images)
            vm.caption(0, "Mavi renkli görsel")
            vm.caption(1, "Yeşil renkli görsel")
            vm.queries("Mavi renkli görseli bul | 1 |")
            vm.run(128)
        }
        compose.waitUntil(180_000) { !vm.state.value.busy }
        assertNull(vm.state.value.error)
        val report = requireNotNull(vm.state.value.report)
        assertEquals(2, report.results.size)
        for (side in report.results) {
            assertTrue(side.indexMs > 0)
            assertEquals(2, side.itemTimesMs.size)
            assertEquals(2, side.queries.single().rankings.size)
            assertTrue(side.queries.single().rankings.all { it.score.isFinite() })
        }
        assertTrue(report.results[1].approach.contains("Doğrudan görsel"))
        assertTrue(vm.exportJson().contains("Caption creation cost is NOT measured"))
    }
}
