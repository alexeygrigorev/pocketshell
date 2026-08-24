package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-contract tests for the host `pocketshell engines list --json` payload. */
class SshEnginesGatewayParsingTest {

    @Test
    fun parsesBuiltInAndCustomEnginesWithoutLosingRawIdsOrLaunchMetadata() {
        val rows = SshEnginesGateway.parseEnginesPayload(
            """
            {
              "engines": [
                {
                  "id": "godex",
                  "family": "codex",
                  "harness": "codex",
                  "label": "GoDex",
                  "provider_mark": "acme",
                  "usage_provider": "openai",
                  "enabled": true,
                  "available": true,
                  "available_for_create": true,
                  "launch": {
                    "argv": ["pocketshell", "agent", "codex", "--profile", "{profile}"],
                    "skip_permissions_argv": ["--yolo"],
                    "supports_skip_permissions": true,
                    "env": {"set": {"CODEX_HOME": "/srv/codex"}, "unset": ["OLD_CODEX"]},
                    "profile_env": "CODEX_HOME",
                    "profile": {
                      "env_var": "CODEX_HOME",
                      "default_dirname": ".codex",
                      "markers": ["config.toml"],
                      "name_hints": ["codex"],
                      "default_label": "Codex"
                    }
                  }
                },
                {
                  "id": "custom-agent",
                  "family": "made-up-family",
                  "label": "Custom Agent",
                  "enabled": false,
                  "available": false,
                  "available_for_create": false,
                  "unavailable_reason": "not installed"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, rows.size)

        val godex = rows[0]
        assertEquals("godex", godex.id)
        assertEquals("godex", godex.rawId)
        assertEquals("codex", godex.familyId)
        assertEquals(SessionAgentKind.Codex, godex.family)
        assertEquals("GoDex", godex.label)
        assertEquals("acme", godex.providerMark)
        assertEquals("openai", godex.usageProvider)
        assertTrue(godex.enabled)
        assertTrue(godex.available)
        assertTrue(godex.availableForCreate)
        assertEquals(listOf("pocketshell", "agent", "codex", "--profile", "{profile}"), godex.launch.argv)
        assertEquals(listOf("--yolo"), godex.launch.skipPermissionsArgv)
        assertTrue(godex.launch.supportsSkipPermissions)
        assertEquals(mapOf("CODEX_HOME" to "/srv/codex"), godex.launch.environmentSet)
        assertEquals(listOf("OLD_CODEX"), godex.launch.environmentUnset)
        assertEquals("CODEX_HOME", godex.launch.profileEnvironment)
        assertEquals("CODEX_HOME", godex.launch.profile?.environmentVariable)
        assertEquals(".codex", godex.launch.profile?.defaultDirectoryName)
        assertEquals(listOf("config.toml"), godex.launch.profile?.markers)
        assertEquals(listOf("codex"), godex.launch.profile?.nameHints)
        assertEquals("Codex", godex.launch.profile?.defaultLabel)

        val custom = rows[1]
        assertEquals("custom-agent", custom.rawId)
        assertEquals("made-up-family", custom.familyId)
        assertEquals(SessionAgentKind.Unknown, custom.family)
        assertFalse(custom.enabled)
        assertFalse(custom.available)
        assertFalse(custom.availableForCreate)
        assertEquals("not installed", custom.unavailableReason)
    }

    @Test
    fun availableForCreateCannotContradictEnabledOrAvailableFlags() {
        val rows = SshEnginesGateway.parseEnginesPayload(
            """
            {"engines": [
              {"id":"disabled-but-claimed-createable","family":"codex",
               "enabled":false,"available":true,"available_for_create":true},
              {"id":"unavailable-but-claimed-createable","family":"claude",
               "enabled":true,"available":false,"available_for_create":true},
              {"id":"both-unsafe-but-claimed-createable","family":"shell",
               "enabled":false,"available":false,"available_for_create":true},
              {"id":"explicitly-not-createable","family":"codex",
               "enabled":true,"available":true,"available_for_create":false},
              {"id":"implicitly-createable","family":"codex",
               "enabled":true,"available":true}
            ]}
            """.trimIndent(),
        ).associateBy { it.rawId }

        assertFalse(rows.getValue("disabled-but-claimed-createable").availableForCreate)
        assertFalse(rows.getValue("unavailable-but-claimed-createable").availableForCreate)
        assertFalse(rows.getValue("both-unsafe-but-claimed-createable").availableForCreate)
        assertFalse(rows.getValue("explicitly-not-createable").availableForCreate)
        assertTrue(rows.getValue("implicitly-createable").availableForCreate)
    }

    @Test
    fun malformedDocumentsAndRowsFailSafe() {
        assertTrue(SshEnginesGateway.parseEngines("").isEmpty())
        assertTrue(SshEnginesGateway.parseEngines("not json").isEmpty())
        assertTrue(SshEnginesGateway.parseEngines("{}").isEmpty())

        val rows = SshEnginesGateway.parseEngines(
            """
            {"engines": [
              null,
              {"family": "codex"},
              {"id": "", "family": "claude"},
              {"id": "ok", "family": "claude", "launch": "wrong-shape"}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, rows.size)
        assertEquals("ok", rows.single().rawId)
        assertEquals(SessionAgentKind.Claude, rows.single().family)
        assertTrue(rows.single().launch.argv.isEmpty())
    }

    @Test
    fun validEmptyRegistryIsDifferentFromMalformedDocument() {
        assertTrue(SshEnginesGateway.parseEngines("{\"engines\": []}").isEmpty())
        assertFalse(SshEnginesGateway.parseEnginesDocument("{\"engines\": []}") == null)
        assertTrue(SshEnginesGateway.parseEnginesDocument("not json") == null)
    }
}
