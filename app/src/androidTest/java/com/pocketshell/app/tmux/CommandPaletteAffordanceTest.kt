package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.agentcommands.AGENT_COMMAND_SHEET_TAG
import com.pocketshell.app.agentcommands.AgentCommand
import com.pocketshell.app.agentcommands.AgentCommandSheet
import com.pocketshell.app.agentcommands.agentCommandArgumentFieldTag
import com.pocketshell.app.agentcommands.agentCommandRowTag
import com.pocketshell.app.agentcommands.agentCommandSendChipTag
import com.pocketshell.app.voice.SESSION_AGENT_COMMANDS_CHIP_TAG
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #462: the agent command palette ('/goal', '/compact', '/clear', …)
 * must be reachable in ≤1 obvious tap from an agent terminal screen without
 * adding another action to the top chrome.
 *
 * The full [TmuxSessionScreen] needs Hilt + a live tmux client, so these tests
 * exercise the exact composables the screen renders: [ConsolidatedTopChrome],
 * [CompactBreadcrumb], [TmuxTerminalBottomControls], and the existing #436
 * [AgentCommandSheet]. We verify:
 *
 *  1. The old top-chrome "/" affordance is absent in full and compact chrome.
 *  2. One bottom-control tap opens the palette and routes command text back.
 *  3. The IME-open tmux keybar exposes the same one-tap palette access.
 *  4. Plain shell wiring does not render the agent affordance.
 *
 * A screenshot of the header + open palette is written for reviewer/maintainer
 * eyeball evidence.
 */
@RunWith(AndroidJUnit4::class)
class CommandPaletteAffordanceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test
    fun agentBottomCommandControlOpensSheetAndSendsParameterizedCommand() {
        var sheetOpen = false
        var sent: AgentCommand? = null
        compose.setContent {
            PocketShellTheme {
                var open by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(ROOT_TAG),
                ) {
                    ConsolidatedTopChrome(
                        sessionName = "agent-main",
                        agentName = AgentKind.ClaudeCode.displayName,
                        tabLabels = listOf("Terminal", "Conversation"),
                        selectedTabIndex = 1,
                        onBack = {},
                        onMore = {},
                    )
                    CompactBreadcrumb(
                        sessionName = "agent-main",
                        onBack = {},
                        onMore = {},
                    )
                    TmuxTerminalBottomControls(
                        isImeVisible = false,
                        showConversation = false,
                        sessionLive = true,
                        isAgentPane = true,
                        keyBarExpanded = false,
                        onKeyBarExpandedChange = {},
                        onKey = {},
                        onChipTap = {},
                        onDictateTap = {},
                        onEnterTap = {},
                        onShowKeyboardTap = {},
                        onAddSnippetTap = null,
                        onAgentCommandsTap = {
                            sheetOpen = true
                            open = true
                        },
                    )
                }
                if (open) {
                    AgentCommandSheet(
                        agent = AgentKind.ClaudeCode,
                        onDismiss = { open = false },
                        onCommandSend = { sent = it },
                    )
                }
            }
        }

        // 1. The old dedicated "/" affordance is absent from both top chromes.
        compose.onNodeWithTag(TMUX_COMMAND_PALETTE_BUTTON_TAG).assertDoesNotExist()

        // 2. One obvious bottom-control tap opens the existing #436 palette.
        compose.onNodeWithTag(SESSION_AGENT_COMMANDS_CHIP_TAG).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertTrue("Tapping the bottom palette control should open the sheet", sheetOpen)
        compose.onNodeWithTag(AGENT_COMMAND_SHEET_TAG).assertIsDisplayed()

        captureFullDevice(File(artifactDir(), "command-palette-open-viewport.png"))

        // 3. Parameterized commands expand first, then route the composed text.
        compose.onNodeWithTag(agentCommandRowTag("/goal")).performClick()
        compose.onNodeWithTag(agentCommandArgumentFieldTag("/goal"))
            .performTextInput("ship command templates")
        compose.onNodeWithTag(agentCommandSendChipTag("/goal")).performClick()
        compose.waitForIdle()
        assertEquals("/goal ship command templates", sent?.command)
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test
    fun agentImeKeybarCommandControlOpensSheet() {
        var sheetOpen = false
        compose.setContent {
            PocketShellTheme {
                var open by remember { mutableStateOf(false) }
                TmuxTerminalBottomControls(
                    isImeVisible = true,
                    showConversation = false,
                    sessionLive = true,
                    isAgentPane = true,
                    keyBarExpanded = false,
                    onKeyBarExpandedChange = {},
                    onKey = {},
                    onChipTap = {},
                    onDictateTap = {},
                    onEnterTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = null,
                    onAgentCommandsTap = {
                        sheetOpen = true
                        open = true
                    },
                )
                if (open) {
                    AgentCommandSheet(
                        agent = AgentKind.ClaudeCode,
                        onDismiss = { open = false },
                        onCommandSend = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(TMUX_KEY_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithText(TmuxAgentCommandsKeyLabel).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertTrue("Tapping the IME keybar palette control should open the sheet", sheetOpen)
        compose.onNodeWithTag(AGENT_COMMAND_SHEET_TAG).assertIsDisplayed()
    }

    @Test
    fun commandPaletteAffordanceAbsentForPlainShellControls() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(ROOT_TAG),
                ) {
                    ConsolidatedTopChrome(
                        sessionName = "scratch",
                        onBack = {},
                        onMore = {},
                    )
                    TmuxTerminalBottomControls(
                        isImeVisible = false,
                        showConversation = false,
                        sessionLive = true,
                        isAgentPane = false,
                        keyBarExpanded = false,
                        onKeyBarExpandedChange = {},
                        onKey = {},
                        onChipTap = {},
                        onDictateTap = {},
                        onEnterTap = {},
                        onShowKeyboardTap = {},
                        onAddSnippetTap = {},
                    )
                }
            }
        }
        compose.onNodeWithTag(TMUX_COMMAND_PALETTE_BUTTON_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SESSION_AGENT_COMMANDS_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithText(AgentCommandsChip).assertDoesNotExist()
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/command-palette-affordance")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create command-palette screenshot dir: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write command-palette screenshot: ${file.absolutePath}"
                }
            }
            println("COMMAND_PALETTE_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ROOT_TAG = "command-palette-affordance-root"
    }
}
