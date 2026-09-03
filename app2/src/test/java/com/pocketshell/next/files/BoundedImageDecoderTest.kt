package com.pocketshell.next.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The image decoder's memory bound (task P-3b).
 *
 * The thing under test is NOT "does an image render" — it is "is the decode
 * bounded", and the honest way to prove that without inducing a
 * device-specific OOM is to observe the options the decoder chose. So the test
 * substitutes the codec (a constructor-style parameter, not a `*ForTest` seam)
 * and asserts on the two passes: a header-only pass, then a real decode whose
 * `inSampleSize` brings the pixel count under the cap.
 *
 * The old client proved the same property with three multi-megapixel image
 * files on device; this runs in milliseconds on the JVM and pins the arithmetic
 * exactly, which is what actually regresses.
 */
@RunWith(AndroidJUnit4::class)
class BoundedImageDecoderTest {

    @Test
    fun `a huge image is decoded subsampled, under the pixel cap`() {
        // 108 megapixels — bigger than any phone camera, the shape that OOMs an
        // unbounded decode.
        val codec = RecordingCodec(width = 12_000, height = 9_000)

        val bitmap = BoundedImageDecoder.decode(
            bytes = ByteArray(16),
            maxPixels = BoundedImageDecoder.DEFAULT_MAX_PIXELS,
            codec = codec,
        )

        assertNotNull("a decodable image must produce a bitmap", bitmap)
        assertEquals("expected exactly two passes", 2, codec.calls.size)

        val (bounds, real) = codec.calls
        assertTrue("the first pass must read the header only", bounds.inJustDecodeBounds)
        assertFalse("the second pass must actually decode", real.inJustDecodeBounds)

        assertTrue(
            "the decode must be subsampled, got inSampleSize=${real.inSampleSize}",
            real.inSampleSize > 1,
        )
        val decodedPixels = BoundedImageDecoder.sampledPixels(12_000, 9_000, real.inSampleSize)
        assertTrue(
            "decode would produce $decodedPixels pixels, over the cap",
            decodedPixels <= BoundedImageDecoder.DEFAULT_MAX_PIXELS,
        )
    }

    @Test
    fun `an image already under the cap is decoded at full resolution`() {
        val codec = RecordingCodec(width = 800, height = 600)

        BoundedImageDecoder.decode(ByteArray(16), codec = codec)

        assertEquals(1, codec.calls[1].inSampleSize)
    }

    @Test
    fun `sample size is the smallest power of two that fits the budget`() {
        assertEquals(1, BoundedImageDecoder.sampleSizeFor(1_000, 1_000, 2_000_000))
        assertEquals(2, BoundedImageDecoder.sampleSizeFor(2_000, 2_000, 2_000_000))
        assertEquals(4, BoundedImageDecoder.sampleSizeFor(4_000, 4_000, 2_000_000))
        assertEquals(8, BoundedImageDecoder.sampleSizeFor(12_000, 9_000, 2_000_000))
        // Every answer is a power of two: BitmapFactory rounds anything else
        // DOWN, so a "precise" ratio would silently decode bigger than asked.
        listOf(1_000 to 1_000, 2_000 to 2_000, 4_000 to 4_000, 12_000 to 9_000)
            .forEach { (width, height) ->
                val sampleSize = BoundedImageDecoder.sampleSizeFor(width, height, 2_000_000)
                assertEquals(
                    "inSampleSize $sampleSize is not a power of two",
                    1,
                    Integer.bitCount(sampleSize),
                )
            }
    }

    @Test
    fun `undecodable bytes produce null instead of throwing`() {
        val codec = RecordingCodec(width = 0, height = 0)

        assertNull(BoundedImageDecoder.decode(ByteArray(4), codec = codec))
        assertEquals("a failed header read must not attempt a real decode", 1, codec.calls.size)
    }

    @Test
    fun `a codec that throws on the real pass is reported as undecodable`() {
        val codec = object : BoundedImageDecoder.BitmapCodec {
            var pass = 0
            override fun decode(bytes: ByteArray, options: BitmapFactory.Options): Bitmap? {
                pass += 1
                if (pass == 1) {
                    options.outWidth = 100
                    options.outHeight = 100
                    return null
                }
                throw OutOfMemoryError("simulated decoder failure")
            }
        }

        assertNull(BoundedImageDecoder.decode(ByteArray(4), codec = codec))
    }

    /** Reports [width]×[height] on the header pass and a bitmap on the real one. */
    private class RecordingCodec(
        private val width: Int,
        private val height: Int,
    ) : BoundedImageDecoder.BitmapCodec {

        val calls = mutableListOf<BitmapFactory.Options>()

        override fun decode(bytes: ByteArray, options: BitmapFactory.Options): Bitmap? {
            calls += options
            if (options.inJustDecodeBounds) {
                options.outWidth = width
                options.outHeight = height
                return null
            }
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }
}
