package com.pocketshell.app.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI proof for the multi-profile session-type picker (audit #657 Gap-3,
 * issues #718 / #627 / #631, tracked by #723).
 *
 * The picker discovers Claude / Codex profiles host-side ([ProfilesGateway])
 * and renders a "Profile" [com.pocketshell.uikit.components.SegmentedToggle]
 * only when the engine has more than one profile. Picking a non-default
 * profile must thread its NAME into the launched `pocketshell agent`
 * command as `--profile '<name>'` so the agent runs against the right
 * `CLAUDE_CONFIG_DIR` / `CODEX_HOME` host-side. A silent break here launches
 * the agent against the WRONG config dir with green command-builder unit
 * tests (`SessionTypeChoiceCommandTest`), which never render the picker.
 *
 * This pins, on-device:
 *  - the Claude profile toggle is shown only when `claudeProfiles.size > 1`;
 *  - both discovered profiles are listed by name;
 *  - selecting a non-default Claude profile threads `--profile '<name>'`
 *    into the launched command, and the default profile emits no flag;
 *  - the same routing for Codex profiles;
 *  - a single-profile engine hides the toggle (no false picker).
 *
 * Drives [SessionTypePickerContent] directly (no SSH / sheet animation),
 * matching the sibling [SessionTypePickerSkipPermissionsUiTest].
 */
@RunWith(AndroidJUnit4::class)
class SessionTypeProfilePickerUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val claudeProfiles = listOf(
        ClaudeProfile(name = "default", default = true),
        ClaudeProfile(name = "work"),
        ClaudeProfile(name = "oss"),
    )
    private val codexProfiles = listOf(
        CodexProfile(name = "default", default = true),
        CodexProfile(name = "team"),
    )

    private fun picker(
        claudeProfiles: List<ClaudeProfile> = emptyList(),
        codexProfiles: List<CodexProfile> = emptyList(),
        onCreate: (SessionTypeChoice) -> Unit = {},
    ) {
        compose.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SessionTypePickerContent(
                        folderPath = "/srv/app",
                        folderLabel = "app",
                        onCancel = {},
                        onCreate = onCreate,
                        claudeProfiles = claudeProfiles,
                        codexProfiles = codexProfiles,
                    )
                }
            }
        }
    }

    @Test
    fun claudeProfileToggleListsDiscoveredProfilesAndRoutesSelection() {
        var choice: SessionTypeChoice? = null
        picker(claudeProfiles = claudeProfiles, codexProfiles = codexProfiles) { choice = it }

        // Agent + Claude are the defaults, so with >1 Claude profile the
        // "Profile" toggle is shown and lists all discovered profiles by name.
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CLAUDE_PROFILE_TAG).assertIsDisplayed()
        compose.onNodeWithText("default").assertIsDisplayed()
        compose.onNodeWithText("work").assertIsDisplayed()
        compose.onNodeWithText("oss").assertIsDisplayed()

        // Select the non-default "work" profile, then Create.
        compose.onNodeWithTag("$SESSION_TYPE_PICKER_CLAUDE_PROFILE_TAG:work").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()

        assertTrue("create should route a choice", choice != null)
        assertEquals(AgentCli.Claude, choice?.agent)
        assertEquals("work", choice?.claudeProfileName)
        assertNull("codex profile is irrelevant for a Claude session", choice?.codexProfileName)

        // The chosen profile threads into the launched command as --profile.
        val command = choice?.startCommand(
            claudeProfiles = claudeProfiles,
            codexProfiles = codexProfiles,
        )
        assertTrue(
            "non-default Claude profile must thread --profile 'work': $command",
            command?.contains("--profile 'work'") == true,
        )
    }

    @Test
    fun defaultClaudeProfileEmitsNoProfileFlag() {
        var choice: SessionTypeChoice? = null
        picker(claudeProfiles = claudeProfiles, codexProfiles = codexProfiles) { choice = it }

        // Pick the default profile explicitly, Create.
        compose.onNodeWithTag("$SESSION_TYPE_PICKER_CLAUDE_PROFILE_TAG:default").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()

        val command = choice?.startCommand(
            claudeProfiles = claudeProfiles,
            codexProfiles = codexProfiles,
        )
        assertEquals("default", choice?.claudeProfileName)
        // The default profile means "use the engine's built-in config dir" — no flag.
        assertTrue(
            "the default profile must emit no --profile flag: $command",
            command?.contains("--profile") == false,
        )
    }

    @Test
    fun codexProfileToggleListsDiscoveredProfilesAndRoutesSelection() {
        var choice: SessionTypeChoice? = null
        picker(claudeProfiles = claudeProfiles, codexProfiles = codexProfiles) { choice = it }

        // Switch to Codex; its >1-profile toggle then appears.
        compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_CODEX_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CODEX_PROFILE_TAG).assertIsDisplayed()
        compose.onNodeWithText("team").assertIsDisplayed()

        compose.onNodeWithTag("$SESSION_TYPE_PICKER_CODEX_PROFILE_TAG:team").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()

        assertEquals(AgentCli.Codex, choice?.agent)
        assertEquals("team", choice?.codexProfileName)
        val command = choice?.startCommand(
            claudeProfiles = claudeProfiles,
            codexProfiles = codexProfiles,
        )
        assertTrue(
            "codex command must carry the codex kind: $command",
            command?.startsWith("pocketshell agent codex ") == true,
        )
        assertTrue(
            "non-default Codex profile must thread --profile 'team': $command",
            command?.contains("--profile 'team'") == true,
        )
    }

    @Test
    fun singleProfileEngineHidesProfileToggle() {
        // Exactly one Claude profile (just the default) -> no profile picker.
        picker(
            claudeProfiles = listOf(ClaudeProfile(name = "default", default = true)),
            codexProfiles = codexProfiles,
        )

        compose.onNodeWithTag(SESSION_TYPE_PICKER_CLAUDE_PROFILE_TAG).assertDoesNotExist()
    }
}
