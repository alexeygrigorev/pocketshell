package com.pocketshell.next.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rendered QR itself: a payload painted to a bitmap and read back out.
 *
 * This is the one hop the codec tests cannot cover — everything else asserts on
 * strings, and a QR that encodes a string the decoder cannot recover is still a
 * broken feature.
 *
 * LEGACY graphics, deliberately: this test checks the DATA path — payload to
 * matrix to pixel array to zxing — and LEGACY's shadow bitmaps store the int
 * array verbatim, so the round-trip is byte-identical on every host. NATIVE
 * routes the bitmap through real Skia, whose premultiply round-trip and CPU-
 * dispatched code paths differ per machine and intermittently handed zxing a
 * binarization it could not read (NotFoundException on CI runners only; runs
 * 34382673852, 34412450162). Real rendering is proven by the J17 journey on
 * the emulator, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class HostQrCodeTest {

    @Test
    fun `a host payload survives being rendered to a QR and read back`() {
        val payload = QrChunkCodec.encode(
            SshImportPayloadCodec.encode(
                SshImportConfig(
                    name = "hetzner",
                    host = "135.181.114.209",
                    port = 2222,
                    username = "alexey",
                    auth = SshImportAuth.KeyReference("hetzner-key"),
                ),
            ),
        ).single()

        val bitmap = HostQrCode.encode(payload)
        assertEquals(720, bitmap.width)
        assertEquals(720, bitmap.height)

        assertEquals(payload, HostQrCode.decode(bitmap))
    }

    @Test
    fun `a payload big enough to need several QRs renders every part readably`() {
        val parts = QrChunkCodec.encode("z".repeat(QrChunkCodec.CHUNK_SIZE * 2 + 10))
        assertTrue("fixture must be multi-part", parts.size > 2)

        assertEquals(parts, parts.map { HostQrCode.decode(HostQrCode.encode(it)) })
    }
}
