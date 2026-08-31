package com.pocketshell.app.projects

import com.pocketshell.uikit.model.SessionAgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the app-side half of issue #2275.
 *
 * The custom row is deliberate: replacing the registry projection with a
 * closed enum or a built-in-only branch must make this test fail. The test
 * also exercises the actual picker choice/launch model, not a source-text
 * assertion that merely mentions the desired names.
 */
class RegistryDrivenSessionTypePickerTest {

    private val customCodex = RemoteEngine(
        id = "godex-custom",
        familyId = "codex",
        family = SessionAgentKind.Codex,
        label = "GoDex Custom",
        launch = RemoteEngineLaunch(
            skipPermissionsArgv = listOf("--custom-yolo"),
            supportsSkipPermissions = true,
        ),
    )

    @Test
    fun pickerRowsUseOpenRegistryIdsAndHideDisabledOrUnavailableEngines() {
        val rows = availableEnginesForCreate(
            listOf(
                RemoteEngine(
                    id = "claude",
                    familyId = "claude",
                    family = SessionAgentKind.Claude,
                    label = "Claude",
                ),
                customCodex,
                customCodex.copy(id = "disabled", enabled = false),
                customCodex.copy(id = "missing", available = false),
                customCodex.copy(id = "not-creatable", availableForCreate = false),
            ),
        )

        assertEquals(listOf("claude", "godex-custom"), rows.map { it.id })
        assertFalse(rows.any { it.id == "disabled" })
        assertFalse(rows.any { it.id == "missing" })
        assertFalse(rows.any { it.id == "not-creatable" })
    }

    @Test
    fun hostAvailabilityAndDisabledReasonReachTheCreateProjection() {
        val rows = SshEnginesGateway.parseEnginesPayload(
            """
            {"engines": [
              {"id":"available","family":"codex","label":"Available",
               "enabled":true,"available":true,"available_for_create":true},
              {"id":"missing","family":"codex","label":"Missing",
               "enabled":true,"available":false,"available_for_create":false,
               "unavailable_reason":"`codex` is not installed on this host (not on PATH)."},
              {"id":"disabled","family":"codex","label":"Disabled",
               "enabled":false,"available":true,"available_for_create":false,
               "unavailable_reason":"disabled in the host registry"}
            ]}
            """.trimIndent(),
        )

        assertEquals(
            listOf("available"),
            availableEnginesForCreate(rows).map { it.id },
        )
        assertEquals(
            "`codex` is not installed on this host (not on PATH).",
            rows.first { it.id == "missing" }.unavailableReason,
        )
        assertEquals(
            "disabled in the host registry",
            rows.first { it.id == "disabled" }.unavailableReason,
        )
    }

    @Test
    fun customRawIdLaunchesThroughWrapperAndKeepsClosedFamilyForTree() {
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            engine = customCodex,
            startDirectory = "/srv/project",
        )

        assertEquals("godex-custom", choice.engineId)
        assertEquals(SessionAgentKind.Codex, choice.sessionAgentKind)
        assertEquals(
            "pocketshell agent godex-custom --dir '/srv/project'",
            choice.startCommand(),
        )
        assertTrue("the raw registry id must reach the wrapper", choice.startCommand()!!.contains("godex-custom"))
    }
}
