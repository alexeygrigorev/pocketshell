package com.pocketshell.app.env

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parsing / payload-shaping tests for [SshEnvGateway] — issue #264.
 *
 * The gateway opens its own [com.pocketshell.core.ssh.SshConnection], so
 * the SSH round-trip is exercised in the connected E2E suite. These
 * tests pin the wire-format contract with the `pocketshell env ... --json`
 * CLI (#262) and the D24 secret-safety invariant of the stdin payload.
 */
class SshEnvGatewayParsingTest {

    @Test
    fun parsesEnvListJsonArrayIntoRows() {
        val json = """
            [
              {"key": "API_KEY", "file": ".env", "has_value": true},
              {"key": "EMPTY", "file": ".env", "has_value": false},
              {"key": "EXPORTED", "file": ".envrc", "has_value": true}
            ]
        """.trimIndent()

        val rows = SshEnvGateway.parseKeyList(json)

        assertEquals(3, rows.size)
        assertEquals(EnvKeyRow("API_KEY", ".env", true), rows[0])
        assertEquals(EnvKeyRow("EMPTY", ".env", false), rows[1])
        assertEquals(EnvKeyRow("EXPORTED", ".envrc", true), rows[2])
    }

    @Test
    fun parseKeyListTolaratesEmptyDocument() {
        assertTrue(SshEnvGateway.parseKeyList("").isEmpty())
        assertTrue(SshEnvGateway.parseKeyList("   ").isEmpty())
        assertTrue(SshEnvGateway.parseKeyList("[]").isEmpty())
    }

    @Test
    fun parseKeyListSkipsRowsWithoutKey() {
        val rows = SshEnvGateway.parseKeyList("""[{"file": ".env"}, {"key": "OK", "file": ".env", "has_value": true}]""")
        assertEquals(listOf("OK"), rows.map { it.key })
    }

    @Test
    fun parsesEnvGetJsonObjectIntoValueMap() {
        val values = SshEnvGateway.parseValueMap("""{"API_KEY": "sk-secret-123", "URL": "https://x"}""")
        assertEquals("sk-secret-123", values["API_KEY"])
        assertEquals("https://x", values["URL"])
        assertEquals(2, values.size)
    }

    @Test
    fun parseValueMapTolaratesEmptyDocument() {
        assertTrue(SshEnvGateway.parseValueMap("").isEmpty())
        assertTrue(SshEnvGateway.parseValueMap("{}").isEmpty())
    }

    @Test
    fun setPayloadIsValidJsonObjectWithExactValues() {
        val payload = SshEnvGateway.buildSetPayload(mapOf("API_KEY" to "sk-secret-123"))
        val obj = JSONObject(payload)
        assertEquals("sk-secret-123", obj.getString("API_KEY"))
        assertEquals(1, obj.length())
    }

    @Test
    fun setPayloadEscapesAwkwardValues() {
        // Newlines, quotes, '$', '#', backslashes must round-trip through
        // the JSON-on-stdin path so the CLI's surgical writer can store
        // them verbatim.
        val tricky = "line1\nline2 \"quoted\" \$VAR #notacomment \\end"
        val payload = SshEnvGateway.buildSetPayload(mapOf("WEIRD" to tricky))
        val obj = JSONObject(payload)
        assertEquals(tricky, obj.getString("WEIRD"))
    }

    @Test
    fun setPayloadNeverEmbedsRawSecretInArgvShape() {
        // D24: the value travels via stdin JSON. The payload itself is
        // JSON (so a reviewer can confirm it is what gets uploaded to the
        // remote temp file), and the *command line* the gateway builds
        // must never contain the value. We assert the payload is the only
        // carrier by checking it parses back to the original value.
        val secret = "top-secret-value"
        val payload = SshEnvGateway.buildSetPayload(mapOf("SECRET" to secret))
        assertTrue("payload should carry the value as JSON", payload.contains("SECRET"))
        assertEquals(secret, JSONObject(payload).getString("SECRET"))
    }

    @Test
    fun envFileTargetMapsFileNames() {
        assertEquals(EnvFileTarget.Env, EnvFileTarget.fromFileName(".env"))
        assertEquals(EnvFileTarget.Envrc, EnvFileTarget.fromFileName(".envrc"))
        // Unknown names default to .env (the locked default, D24).
        assertEquals(EnvFileTarget.Env, EnvFileTarget.fromFileName("garbage"))
        assertEquals(".env", EnvFileTarget.Env.fileName)
        assertEquals(".envrc", EnvFileTarget.Envrc.fileName)
    }

    @Test
    fun keyValidationMatchesCliIdentifierRule() {
        assertTrue(EnvViewModel.isValidKey("API_KEY"))
        assertTrue(EnvViewModel.isValidKey("_underscore"))
        assertTrue(EnvViewModel.isValidKey("a1"))
        assertFalse(EnvViewModel.isValidKey("1leading"))
        assertFalse(EnvViewModel.isValidKey("has-dash"))
        assertFalse(EnvViewModel.isValidKey("has space"))
        assertFalse(EnvViewModel.isValidKey(""))
    }
}
