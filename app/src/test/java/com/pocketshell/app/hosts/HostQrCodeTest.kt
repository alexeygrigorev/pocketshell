package com.pocketshell.app.hosts

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HostQrCodeTest {
    @Test
    fun qrEncodeDecode_roundTrip() {
        val payload = """{"type":"pocketshell.ssh-import.v1","name":"dev"}"""
        val bitmap = HostQrCode.encode(payload, sizePx = 320)

        assertEquals(payload, HostQrCode.decode(bitmap))
    }
}
