package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #570 proof for image-preview memory behavior. We avoid a
 * device-specific OOM test and instead assert the production preview decoder
 * performs bounds-first, sampled bitmap decodes for multiple large images.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BoundedImageDecoderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun calculateInSampleSizeKeepsDecodedPixelsWithinCap() {
        assertEquals(1, BoundedImageDecoder.calculateInSampleSize(400, 300, 120_000))
        assertEquals(4, BoundedImageDecoder.calculateInSampleSize(1_600, 1_200, 120_000))
        assertEquals(8, BoundedImageDecoder.calculateInSampleSize(1_601, 1_201, 120_000))
        assertEquals(8, BoundedImageDecoder.calculateInSampleSize(4_000, 3_000, 250_000))
    }

    @Test
    fun decodesMultipleLargeImagesUnderThumbnailPixelBudget() {
        val maxPixels = 120_000
        val files = listOf(
            writePng("first.png", Color.RED),
            writePng("second.png", Color.GREEN),
            writePng("third.png", Color.BLUE),
        )

        files.forEach { file ->
            val decoded = BoundedImageDecoder.decodeFile(file, maxPixels)
            assertNotNull("expected ${file.name} to decode", decoded)
            decoded!!
            assertTrue(
                "decoded ${file.name} at ${decoded.width}x${decoded.height}, over $maxPixels px",
                decoded.width * decoded.height <= maxPixels,
            )
            decoded.recycle()
        }
    }

    @Test
    fun decodeStreamReopensInputAndKeepsDecodedPixelsWithinCap() {
        val maxPixels = 120_000
        val file = writePng("stream.png", Color.MAGENTA)
        var openCount = 0

        val decoded = BoundedImageDecoder.decodeStream(
            openInputStream = {
                openCount += 1
                file.inputStream()
            },
            maxPixels = maxPixels,
        )

        assertNotNull(decoded)
        decoded!!
        assertEquals(2, openCount)
        assertTrue(decoded.width * decoded.height <= maxPixels)
        decoded.recycle()
    }

    private fun writePng(name: String, color: Int): File {
        val file = tmp.newFile(name)
        val bitmap = Bitmap.createBitmap(1_600, 1_200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        file.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
        return file
    }
}
