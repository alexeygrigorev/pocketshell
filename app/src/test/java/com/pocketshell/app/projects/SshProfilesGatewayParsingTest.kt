package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parsing tests for [SshProfilesGateway] — issue #718 (S2).
 *
 * The gateway runs the server-side `pocketshell profiles list --json` over a
 * lease, so the SSH round-trip is exercised in the connected E2E suite. These
 * tests pin the wire-format contract with the CLI: a `{"profiles":[{name,
 * engine, config_dir, default}, …]}` object where `config_dir` may be `null`
 * (the engine's built-in default).
 */
class SshProfilesGatewayParsingTest {

    @Test
    fun parsesProfilesListJsonIntoRows() {
        val json = """
            {
              "profiles": [
                {"name": "Claude", "engine": "claude", "config_dir": null, "default": true},
                {"name": "Claude (Z.AI)", "engine": "claude", "config_dir": "/home/alexey/.zlaude", "default": false},
                {"name": "Codex", "engine": "codex", "config_dir": null, "default": true}
              ]
            }
        """.trimIndent()

        val rows = SshProfilesGateway.parseProfiles(json)

        assertEquals(3, rows.size)
        assertEquals(RemoteProfile("Claude", "claude", null, default = true), rows[0])
        assertEquals(
            RemoteProfile("Claude (Z.AI)", "claude", "/home/alexey/.zlaude", default = false),
            rows[1],
        )
        assertEquals(RemoteProfile("Codex", "codex", null, default = true), rows[2])
    }

    @Test
    fun nullAndEmptyConfigDirBothBecomeNull() {
        val json = """
            {"profiles": [
              {"name": "A", "engine": "claude", "config_dir": null, "default": true},
              {"name": "B", "engine": "claude", "config_dir": "", "default": false}
            ]}
        """.trimIndent()

        val rows = SshProfilesGateway.parseProfiles(json)
        assertEquals(2, rows.size)
        assertNull(rows[0].configDir)
        assertNull(rows[1].configDir)
    }

    @Test
    fun toleratesEmptyAndMissingDocuments() {
        assertTrue(SshProfilesGateway.parseProfiles("").isEmpty())
        assertTrue(SshProfilesGateway.parseProfiles("   ").isEmpty())
        assertTrue(SshProfilesGateway.parseProfiles("not json").isEmpty())
        assertTrue(SshProfilesGateway.parseProfiles("{}").isEmpty())
        assertTrue(SshProfilesGateway.parseProfiles("""{"profiles": []}""").isEmpty())
    }

    @Test
    fun skipsRowsMissingNameOrEngine() {
        val json = """
            {"profiles": [
              {"engine": "claude", "config_dir": "/x"},
              {"name": "", "engine": "claude"},
              {"name": "noengine", "config_dir": "/y"},
              "garbage",
              {"name": "ok", "engine": "codex", "config_dir": "/z", "default": false}
            ]}
        """.trimIndent()

        val rows = SshProfilesGateway.parseProfiles(json)
        assertEquals(1, rows.size)
        assertEquals(RemoteProfile("ok", "codex", "/z", default = false), rows[0])
    }

    @Test
    fun defaultFlagDefaultsToFalseWhenAbsent() {
        val rows = SshProfilesGateway.parseProfiles(
            """{"profiles": [{"name": "X", "engine": "claude", "config_dir": "/x"}]}""",
        )
        assertEquals(1, rows.size)
        assertFalse(rows[0].default)
    }
}
