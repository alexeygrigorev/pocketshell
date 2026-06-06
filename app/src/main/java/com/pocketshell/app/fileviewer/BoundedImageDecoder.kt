package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream

/**
 * Shared bounded bitmap decode for local image previews.
 *
 * Issue #570: image previews must never decode camera screenshots at original
 * resolution just to render a phone-sized preview. The decoder first reads
 * dimensions only, chooses a power-of-two sample size, then decodes a bounded
 * bitmap. This is deterministic proof for the memory path; tests cover three
 * large image files under a thumbnail-sized cap instead of trying to induce a
 * device-specific OOM.
 */
internal object BoundedImageDecoder {
    const val DEFAULT_MAX_PIXELS: Int = 2_000_000

    fun decodeFile(file: File, maxPixels: Int = DEFAULT_MAX_PIXELS): Bitmap? {
        require(maxPixels > 0) { "maxPixels must be positive" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val sampleSize = calculateInSampleSize(width, height, maxPixels)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    fun decodeStream(openInputStream: () -> InputStream?, maxPixels: Int = DEFAULT_MAX_PIXELS): Bitmap? {
        require(maxPixels > 0) { "maxPixels must be positive" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInputStream()?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val sampleSize = calculateInSampleSize(width, height, maxPixels)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openInputStream()?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    internal fun calculateInSampleSize(width: Int, height: Int, maxPixels: Int): Int {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(maxPixels > 0) { "maxPixels must be positive" }

        var sampleSize = 1
        while (decodedPixelCount(width, height, sampleSize) > maxPixels) {
            if (sampleSize > Int.MAX_VALUE / 2) return Int.MAX_VALUE
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun decodedPixelCount(width: Int, height: Int, sampleSize: Int): Long {
        val sampledWidth = ceilDiv(width, sampleSize)
        val sampledHeight = ceilDiv(height, sampleSize)
        return sampledWidth * sampledHeight
    }

    private fun ceilDiv(value: Int, divisor: Int): Long {
        return (value.toLong() + divisor.toLong() - 1L) / divisor.toLong()
    }
}
