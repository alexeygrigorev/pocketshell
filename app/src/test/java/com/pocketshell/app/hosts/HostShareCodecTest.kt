package com.pocketshell.app.hosts

import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostShareCodecTest {
    @Test
    fun encodeDecode_roundTrip_excludesPrivateKeyMaterial() {
        val host = HostEntity(
            name = "prod",
            hostname = "prod.example.com",
            port = 2200,
            username = "alexey",
            keyId = 42,
        )
        val key = SshKeyEntity(
            id = 42,
            name = "work-key",
            privateKeyPath = "/data/user/0/com.pocketshell.app/files/ssh-keys/work-key",
            hasPassphrase = true,
        )

        val payload = HostShareCodec.encode(host, key)
        assertTrue(!payload.contains("privateKeyPath"))
        assertTrue(!payload.contains(key.privateKeyPath))

        val decoded = HostShareCodec.decode(payload).getOrThrow()
        assertEquals("prod", decoded.name)
        assertEquals("prod.example.com", decoded.hostname)
        assertEquals(2200, decoded.port)
        assertEquals("alexey", decoded.username)
        assertEquals("work-key", decoded.keyName)
    }

    @Test
    fun decode_rejectsUnknownPayloadType() {
        val result = HostShareCodec.decode("""{"type":"other"}""")
        assertTrue(result.isFailure)
    }

    @Test
    fun decode_rejectsMalformedPort() {
        val result = HostShareCodec.decode(
            """
                {
                  "type":"pocketshell.host.v1",
                  "name":"prod",
                  "hostname":"prod.example.com",
                  "port":"not-a-number",
                  "username":"alexey",
                  "keyName":"work-key"
                }
            """.trimIndent(),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun decode_defaultsMissingPortToSshPort() {
        val decoded = HostShareCodec.decode(
            """
                {
                  "type":"pocketshell.host.v1",
                  "name":"prod",
                  "hostname":"prod.example.com",
                  "username":"alexey",
                  "keyName":"work-key"
                }
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(22, decoded.port)
    }
}
