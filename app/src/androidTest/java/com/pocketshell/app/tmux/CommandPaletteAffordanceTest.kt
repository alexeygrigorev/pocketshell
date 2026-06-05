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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.agentcommands.AGENT_COMMAND_SHEET_TAG
import com.pocketshell.app.agentcommands.AgentCommand
import com.pocketshell.app.agentcommands.AgentCommandSheet
import com.pocketshell.app.agentcommands.agentCommandSendChipTag
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
 * must be reachable in ≤1 obvious tap from an agent terminal screen — a clear
 * dedicated button in the session chrome, NOT buried among the bottom chip row.
 *
 * The full [TmuxSessionScreen] needs Hilt + a live tmux client, so these tests
 * exercise the exact composables the screen renders: the dedicated "/"
 * [CommandPaletteButton] inside [ConsolidatedTopChrome], and the existing #436
 * [AgentCommandSheet] it opens. We verify:
 *
 *  1. The "/" affordance is visible in the agent-session header.
 *  2. One tap opens the palette (the #436 sheet), and a command row's Send chip
 *     routes the literal `/command` back to the caller (reused #436 behaviour).
 *  3. The affordance is absent for a plain (non-agent) shell header.
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
    fun commandPaletteButtonOpensSheetAndSendsCommandInOneTap() {
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
                        showCommandPalette = true,
                        onCommandPalette = {
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

        // 1. The dedicated "/" affordance is visible in the header.
        compose.onNodeWithTag(TMUX_COMMAND_PALETTE_BUTTON_TAG).assertIsDisplayed()

        // 2. One obvious tap opens the existing #436 palette.
        compose.onNodeWithTag(TMUX_COMMAND_PALETTE_BUTTON_TAG).performClick()
        compose.waitForIdle()
        assertTrue("Tapping the palette button should open the sheet", sheetOpen)
        compose.onNodeWithTag(AGENT_COMMAND_SHEET_TAG).assertIsDisplayed()

        captureFullDevice(File(artifactDir(), "command-palette-open-viewport.png"))

        // 3. Selecting a command routes the literal `/command` (reused #436).
        compose.onNodeWithTag(agentCommandSendChipTag("/goal")).performClick()
        compose.waitForIdle()
        assertEquals("/goal", sent?.command)
    }

    @Test
    fun commandPaletteButtonAbsentForPlainShellHeader() {
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
                        showCommandPalette = false,
                    )
                }
            }
        }
        compose.onNodeWithTag(TMUX_COMMAND_PALETTE_BUTTON_TAG).assertDoesNotExist()
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
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
