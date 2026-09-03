package com.pocketshell.next.hosts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `pocketshell.ssh-import.v1` payload format
 * (`docs/ssh-qr-import.md`), which app2 shares with the shipping client and
 * with `pocketshell qr-share`. Robolectric supplies the real `org.json`.
 *
 * The rejection cases matter as much as the round-trip: this parser's input
 * comes from a camera pointed at an arbitrary QR, so "not our payload" is a
 * routine input, not an exceptional one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SshImportPayloadCodecTest {

    @Test
    fun `a key-reference payload round-trips`() {
        val config = SshImportConfig(
            name = "hetzner",
            host = "135.181.114.209",
            port = 22,
            username = "alexey",
            auth = SshImportAuth.KeyReference(name = "hetzner-key"),
        )

        val decoded = SshImportPayloadCodec.decode(SshImportPayloadCodec.encode(config)).getOrThrow()

        assertEquals(config, decoded)
    }

    @Test
    fun `a private-key payload round-trips`() {
        val config = SshImportConfig(
            name = "builder",
            host = "10.0.0.7",
            port = 2222,
            username = "root",
            auth = SshImportAuth.PrivateKey(name = "builder-key", privateKeyPem = TEST_PEM),
        )

        val decoded = SshImportPayloadCodec.decode(SshImportPayloadCodec.encode(config)).getOrThrow()

        assertEquals(config, decoded)
    }

    @Test
    fun `the encoded payload carries the versioned type discriminator`() {
        val json = JSONObject(
            SshImportPayloadCodec.encode(
                SshImportConfig("h", "h.example.com", 22, "u", SshImportAuth.KeyReference("k")),
            ),
        )

        assertEquals("pocketshell.ssh-import.v1", json.getString("type"))
        assertEquals(1, json.getInt("version"))
    }

    /**
     * The documented tolerances a third-party emitter is allowed to rely on:
     * `hostname` as an alias for `host`, an absent `name` defaulting to the
     * host, and an absent `port` defaulting to 22.
     */
    @Test
    fun `optional fields fall back the way the format documents`() {
        val decoded = SshImportPayloadCodec.decode(
            """
            {"type":"pocketshell.ssh-import.v1","version":1,
             "hostname":"box.example.com","username":"alexey",
             "auth":{"type":"keyRef","name":"k"}}
            """.trimIndent(),
        ).getOrThrow()

        assertEquals("box.example.com", decoded.name)
        assertEquals("box.example.com", decoded.host)
        assertEquals(22, decoded.port)
    }

    @Test
    fun `a foreign QR payload is rejected with a message, not a crash`() {
        val foreign = listOf(
            "https://example.com/some-page",
            "",
            "not json at all",
            """{"type":"some.other.format","version":1}""",
            """{"type":"pocketshell.ssh-import.v1","version":99,"host":"h","username":"u",
               "auth":{"type":"keyRef","name":"k"}}""",
        )

        foreign.forEach { payload ->
            val error = SshImportPayloadCodec.decode(payload).exceptionOrNull()
            assertNotNull("expected '$payload' to be rejected", error)
            assertTrue(
                "expected a user-readable message for '$payload', got ${error!!.message}",
                !error.message.isNullOrBlank(),
            )
        }
    }

    @Test
    fun `structurally valid but nonsensical payloads are rejected`() {
        val bad = mapOf(
            "missing username" to
                """{"type":"pocketshell.ssh-import.v1","version":1,"host":"h",
                   "auth":{"type":"keyRef","name":"k"}}""",
            "blank host" to
                """{"type":"pocketshell.ssh-import.v1","version":1,"host":"  ","username":"u",
                   "auth":{"type":"keyRef","name":"k"}}""",
            "port out of range" to
                """{"type":"pocketshell.ssh-import.v1","version":1,"host":"h","port":70000,
                   "username":"u","auth":{"type":"keyRef","name":"k"}}""",
            "non-integer port" to
                """{"type":"pocketshell.ssh-import.v1","version":1,"host":"h","port":"ssh",
                   "username":"u","auth":{"type":"keyRef","name":"k"}}""",
            "host with whitespace" to
                """{"type":"pocketshell.ssh-import.v1","version":1,"host":"a b","username":"u",
                   "auth":{"type":"keyRef","name":"k"}}""",
            "unknown auth type" to
                """{"type":"pocketshell.ssh-import.v1","version":1,"host":"h","username":"u",
                   "auth":{"type":"password","name":"k"}}""",
            "private key that is not a key" to
                """{"type":"pocketshell.ssh-import.v1","version":1,"host":"h","username":"u",
                   "auth":{"type":"privateKey","name":"k","privateKeyPem":"hello"}}""",
        )

        bad.forEach { (label, payload) ->
            assertTrue("expected '$label' to be rejected", SshImportPayloadCodec.decode(payload).isFailure)
        }
    }

    @Test
    fun `an oversized payload is refused before it is parsed`() {
        val huge = "x".repeat(SshImportPayloadCodec.MAX_PAYLOAD_BYTES + 1)

        val error = SshImportPayloadCodec.decode(huge).exceptionOrNull()

        assertTrue(error!!.message!!.contains("too large"))
    }

    private companion object {
        /** Shape-valid PEM; the codec only checks the block markers. */
        val TEST_PEM = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtz
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    }
}
