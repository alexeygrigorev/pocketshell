package com.pocketshell.app.hosts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshImportPayloadCodecTest {
    @Test
    fun decode_acceptsPrivateKeyPayload() {
        val decoded = SshImportPayloadCodec.decode(
            """
                {
                  "type": "pocketshell.ssh-import.v1",
                  "version": 1,
                  "name": "prod",
                  "host": "prod.example.com",
                  "port": 2222,
                  "username": "ubuntu",
                  "auth": {
                    "type": "privateKey",
                    "name": "prod-key",
                    "privateKeyPem": "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"
                  }
                }
            """.trimIndent(),
        ).getOrThrow()

        assertEquals("prod", decoded.name)
        assertEquals("prod.example.com", decoded.host)
        assertEquals(2222, decoded.port)
        assertEquals("ubuntu", decoded.username)
        val auth = decoded.auth as SshImportAuth.PrivateKey
        assertEquals("prod-key", auth.name)
        assertEquals(false, auth.passphraseRequired)
    }

    @Test
    fun decode_detectsEncryptedOpenSshKeyWhenPayloadSaysNoPassphrase() {
        val decoded = SshImportPayloadCodec.decode(
            """
                {
                  "type": "pocketshell.ssh-import.v1",
                  "version": 1,
                  "name": "prod",
                  "host": "prod.example.com",
                  "username": "ubuntu",
                  "auth": {
                    "type": "privateKey",
                    "name": "prod-key",
                    "privateKeyPem": ${JSONObject.quote(EncryptedOpenSshPrivateKey)},
                    "passphraseRequired": false
                  }
                }
            """.trimIndent(),
        ).getOrThrow()

        val auth = decoded.auth as SshImportAuth.PrivateKey
        assertEquals(true, auth.passphraseRequired)
    }

    @Test
    fun decode_acceptsKeyReferencePayload() {
        val decoded = SshImportPayloadCodec.decode(
            """
                {
                  "type": "pocketshell.ssh-import.v1",
                  "version": 1,
                  "name": "dev",
                  "host": "dev.example.com",
                  "username": "alexey",
                  "auth": {
                    "type": "keyRef",
                    "name": "existing-key"
                  }
                }
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(22, decoded.port)
        assertEquals(SshImportAuth.KeyReference("existing-key"), decoded.auth)
    }

    @Test
    fun decode_rejectsUnsupportedVersion() {
        val result = SshImportPayloadCodec.decode(
            """
                {
                  "type": "pocketshell.ssh-import.v1",
                  "version": 2,
                  "name": "prod",
                  "host": "prod.example.com",
                  "username": "ubuntu",
                  "auth": {"type": "keyRef", "name": "k"}
                }
            """.trimIndent(),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("version"))
    }

    @Test
    fun decode_rejectsUnsupportedTypeWithExpectedPocketShellHostPayload() {
        val result = SshImportPayloadCodec.decode(
            """
                {
                  "type": "pocketshell.settings.v1",
                  "version": 1
                }
            """.trimIndent(),
        )

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("PocketShell SSH host payload"))
        assertTrue(message.contains("pocketshell.ssh-import.v1"))
    }

    @Test
    fun decode_rejectsNonJsonWithExpectedPocketShellHostPayload() {
        val result = SshImportPayloadCodec.decode("not a JSON import file")

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("PocketShell SSH host payload"))
        assertTrue(message.contains("pocketshell.ssh-import.v1"))
    }

    @Test
    fun decode_rejectsInvalidPort() {
        val result = SshImportPayloadCodec.decode(
            """
                {
                  "type": "pocketshell.ssh-import.v1",
                  "version": 1,
                  "name": "prod",
                  "host": "prod.example.com",
                  "port": 70000,
                  "username": "ubuntu",
                  "auth": {"type": "keyRef", "name": "k"}
                }
            """.trimIndent(),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun decode_rejectsInvalidPrivateKey() {
        val result = SshImportPayloadCodec.decode(
            """
                {
                  "type": "pocketshell.ssh-import.v1",
                  "version": 1,
                  "name": "prod",
                  "host": "prod.example.com",
                  "username": "ubuntu",
                  "auth": {
                    "type": "privateKey",
                    "name": "prod-key",
                    "privateKeyPem": "not a key"
                  }
                }
            """.trimIndent(),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun encodeDecode_roundTripKeyReference() {
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "lab",
                host = "lab.example.com",
                port = 22,
                username = "root",
                auth = SshImportAuth.KeyReference("lab-key"),
            ),
        )

        assertEquals(
            SshImportConfig(
                name = "lab",
                host = "lab.example.com",
                port = 22,
                username = "root",
                auth = SshImportAuth.KeyReference("lab-key"),
            ),
            SshImportPayloadCodec.decode(payload).getOrThrow(),
        )
    }

    private companion object {
        val EncryptedOpenSshPrivateKey = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABDy65Wy4J
            GIiPmAlfzxEptmAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIFz+4rPsOPrrK7I/
            hz3T8H4UpgIdLal/ADv4OhvewZ+xAAAAkPodwX8olqflsAful+M/4T4BtLAaULs9Oc3GVb
            uy664Ebtmwo+/HhJmloTIoVs0STzeFeHAK4xkEq6Ut303sIER2av1O0qHUjyOMGPPZop1V
            4Sd/bBQJu/Q14nSsiSRJaHBN2SBpyFSul2/ZLU5xYhs7dzmCTbXH+BM1cP6ZE5byxA8jeW
            gH19i7Bcv1CK6+Pg==
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    }
}
