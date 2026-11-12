package com.hilal.llm.ocr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetaiLineImageTest {
    @Test fun trimsMarginsWithoutChangingInput() {
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        for (y in 15..24) for (x in 30..49) source.setPixel(x, y, Color.BLACK)
        val result = DetaiLineImage.prepare(source, trim = true)
        try {
            assertFalse(result.inverted)
            assertEquals(26, result.bitmap.width)
            assertEquals(16, result.bitmap.height)
            assertEquals(Color.BLACK, result.bitmap.getPixel(3, 3))
            assertEquals(80, source.width)
            assertEquals(Color.WHITE, source.getPixel(0, 0))
            assertFalse(source.isRecycled)
        } finally {
            if (result.bitmap !== source) result.bitmap.recycle()
            source.recycle()
        }
    }

    @Test fun normalizesDarkBackgroundWithoutRecyclingSource() {
        val source = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        source.setPixel(10, 10, Color.WHITE)
        val result = DetaiLineImage.prepare(source, trim = false)
        try {
            assertTrue(result.inverted)
            assertEquals(Color.WHITE, result.bitmap.getPixel(0, 0))
            assertEquals(Color.BLACK, result.bitmap.getPixel(10, 10))
            assertEquals(Color.BLACK, source.getPixel(0, 0))
            assertFalse(source.isRecycled)
        } finally { result.bitmap.recycle(); source.recycle() }
    }

    @Test fun transparentBlankAndTinyImagesStayIntact() {
        for (size in listOf(1, 20)) {
            val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.TRANSPARENT)
            try {
                val result = DetaiLineImage.prepare(source, trim = true)
                assertFalse(result.inverted)
                assertSame(source, result.bitmap)
            } finally { source.recycle() }
        }
    }
}
