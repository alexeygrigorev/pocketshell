package com.pocketshell.next.hosts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.InputStream

/**
 * QR bitmap rendering and still-image decoding (rewrite task P-6).
 *
 * The only Android-specific part of the QR stack; the payload format
 * ([SshImportPayloadCodec]) and the chunk envelope ([QrChunkCodec]) are pure
 * Kotlin and tested as such.
 *
 * Error correction is M rather than the default L because the reader is a phone
 * camera pointed at a phone screen, at an angle, under glare —
 * [QrChunkCodec.CHUNK_SIZE] is sized for exactly this level.
 */
object HostQrCode {

    /** Render [payload] as a square black-on-white QR bitmap. */
    fun encode(payload: String, sizePx: Int = DEFAULT_SIZE_PX): Bitmap {
        val matrix = MultiFormatWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            ),
        )
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val row = y * sizePx
            for (x in 0 until sizePx) {
                pixels[row + x] = if (matrix[x, y]) BLACK else WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    /**
     * Decode the QR in an image [stream] — the fallback for a device whose
     * camera is unavailable or whose permission the user has permanently
     * declined.
     */
    fun decode(stream: InputStream): Result<String> = runCatching {
        val bitmap = BitmapFactory.decodeStream(stream)
            ?: throw IllegalArgumentException("That file is not an image")
        decode(bitmap)
    }

    /** Decode the QR in an already-loaded [bitmap]. */
    fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)),
        ).text
    }

    private const val DEFAULT_SIZE_PX = 720
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
