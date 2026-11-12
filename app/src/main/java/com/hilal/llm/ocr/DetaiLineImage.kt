package com.hilal.llm.ocr

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/** Contrast-based margin trim for one line on a plain background; not text detection. */
object DetaiLineImage {
    data class Prepared(val bitmap: Bitmap, val inverted: Boolean)

    fun prepare(source: Bitmap, trim: Boolean): Prepared {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val inverted = background(pixels, w, h) < 128
        val normalized = if (inverted) {
            for (i in pixels.indices) {
                val value = 255 - gray(pixels[i])
                pixels[i] = Color.rgb(value, value, value)
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } else source
        try {
            val result = if (trim) trimMargins(normalized) else normalized
            if (result !== normalized && normalized !== source) normalized.recycle()
            return Prepared(result, inverted)
        } catch (error: Throwable) {
            if (normalized !== source) normalized.recycle()
            throw error
        }
    }

    private fun gray(pixel: Int): Int {
        val a = Color.alpha(pixel) / 255f
        return ((Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f) * a + 255 * (1 - a)).toInt()
    }

    private fun background(pixels: IntArray, w: Int, h: Int): Int {
        val border = ArrayList<Int>()
        for (x in 0 until w step maxOf(1, w / 100)) { border.add(gray(pixels[x])); border.add(gray(pixels[(h - 1) * w + x])) }
        for (y in 0 until h step maxOf(1, h / 100)) { border.add(gray(pixels[y * w])); border.add(gray(pixels[y * w + w - 1])) }
        border.sort()
        return border[border.size / 2]
    }

    fun trimMargins(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w < 3 || h < 3) return source
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val background = background(pixels, w, h)
        val maxContrast = pixels.maxOf { abs(gray(it) - background) }
        val threshold = maxOf(32, (maxContrast * 0.5).toInt())
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var ink = 0
        for (y in 0 until h) {
            var rowMin = w
            var rowMax = -1
            var count = 0
            for (x in 0 until w) {
                if (abs(gray(pixels[y * w + x]) - background) > threshold) {
                    rowMin = minOf(rowMin, x); rowMax = maxOf(rowMax, x); count++
                }
            }
            if (count >= 2) {
                minX = minOf(minX, rowMin); maxX = maxOf(maxX, rowMax)
                minY = minOf(minY, y); maxY = maxOf(maxY, y); ink += count
            }
        }
        if (ink < 8 || maxX < minX || maxY < minY) return source
        val padding = maxOf(3, ((maxY - minY + 1) * 0.18).toInt())
        minX = (minX - padding).coerceAtLeast(0); minY = (minY - padding).coerceAtLeast(0)
        maxX = (maxX + padding).coerceAtMost(w - 1); maxY = (maxY + padding).coerceAtMost(h - 1)
        return Bitmap.createBitmap(source, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
}
