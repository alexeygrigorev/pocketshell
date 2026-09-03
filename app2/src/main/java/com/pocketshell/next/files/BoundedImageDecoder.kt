package com.pocketshell.next.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes an image to a bounded pixel budget (rewrite task P-3b).
 *
 * A phone must never decode a 48-megapixel screenshot at full resolution just
 * to paint a phone-sized preview: at ARGB_8888 that is ~190 MB for one bitmap,
 * far past a typical per-app heap. So the decode is two-pass — read the header
 * for dimensions only, pick a power-of-two `inSampleSize` that brings the pixel
 * count under [DEFAULT_MAX_PIXELS], then decode.
 *
 * [BitmapCodec] is a constructor-style parameter, not a `*ForTest` seam: it is
 * how a test observes WHICH options the decoder chose, which is the only way to
 * prove the bound was applied without inducing a device-specific OOM. The
 * default is the real `BitmapFactory`.
 */
object BoundedImageDecoder {

    /**
     * ~2 megapixels — comfortably above any phone viewport (a 1440×3120 display
     * is 4.5 MP but an image is drawn into a fraction of it) and ~8 MB at
     * ARGB_8888.
     */
    const val DEFAULT_MAX_PIXELS: Int = 2_000_000

    /**
     * Decodes [bytes], or returns null when they are not a decodable image.
     *
     * Never throws for undecodable input: the viewer's caller falls back to the
     * binary/hex renderer, which is a better answer than a crash.
     */
    fun decode(
        bytes: ByteArray,
        maxPixels: Int = DEFAULT_MAX_PIXELS,
        codec: BitmapCodec = AndroidBitmapCodec,
    ): Bitmap? {
        require(maxPixels > 0) { "maxPixels must be positive, was $maxPixels" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        codec.decode(bytes, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPixels)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { codec.decode(bytes, options) }.getOrNull()
    }

    /**
     * The smallest power-of-two subsample that brings `width × height` under
     * [maxPixels].
     *
     * Power of two because that is the only thing `BitmapFactory` honours
     * exactly — any other value is rounded down to one, so computing a "precise"
     * ratio would silently decode bigger than asked.
     */
    fun sampleSizeFor(width: Int, height: Int, maxPixels: Int): Int {
        require(width > 0 && height > 0) { "image must have positive dimensions" }
        require(maxPixels > 0) { "maxPixels must be positive, was $maxPixels" }
        var sampleSize = 1
        while (sampledPixels(width, height, sampleSize) > maxPixels) {
            if (sampleSize > Int.MAX_VALUE / 2) return Int.MAX_VALUE
            sampleSize *= 2
        }
        return sampleSize
    }

    /** Pixel count a decode at [sampleSize] produces, matching AOSP's rounding. */
    fun sampledPixels(width: Int, height: Int, sampleSize: Int): Long =
        ceilDiv(width, sampleSize) * ceilDiv(height, sampleSize)

    private fun ceilDiv(value: Int, divisor: Int): Long =
        (value.toLong() + divisor.toLong() - 1L) / divisor.toLong()

    /** The one call the decoder makes into the platform. */
    fun interface BitmapCodec {
        fun decode(bytes: ByteArray, options: BitmapFactory.Options): Bitmap?
    }

    private object AndroidBitmapCodec : BitmapCodec {
        override fun decode(bytes: ByteArray, options: BitmapFactory.Options): Bitmap? =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
