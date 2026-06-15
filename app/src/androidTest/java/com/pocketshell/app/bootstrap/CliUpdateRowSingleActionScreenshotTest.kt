package com.pocketshell.app.bootstrap

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #779 — the host-setup "pocketshell CLI update" row must present ONE
 * clear action. The bug was a red `Badge("Update")` rendered immediately next
 * to a `PocketShellButton("Upgrade")`, which read as two competing buttons
 * (the maintainer's "shows BOTH Update and Upgrade" report).
 *
 * This drives the REAL [HostBootstrapSheet] in its [HostBootstrapSheetState.Prompt]
 * state with a pocketshell [ToolStatus.VersionMismatch] (the outdated-CLI row),
 * on the emulator, and proves:
 *
 *  - The status word is "Outdated" (a state, not a verb) — NOT "Update".
 *  - The single action button is "Update".
 *  - The old synonym verb "Upgrade" appears NOWHERE.
 *
 * It is a direct-Compose screenshot (no `ActivityScenario.launch`) so it does
 * not depend on the full host-connect journey; the live Docker upgrade journey
 * is exercised separately by [HostBootstrapScenarioSuiteTest]'s uv-upgrade
 * scenarios. A PNG is written for the status comment.
 */
class CliUpdateRowSingleActionScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val outdatedReport = HostBootstrapReport(
        tools = mapOf(
            BootstrapTool.Pocketshell to ToolStatus.VersionMismatch(
                path = "/home/alexey/.local/bin/pocketshell",
                currentVersion = "0.3.33",
                expectedVersion = "0.4.1",
            ),
        ),
        installer = PythonToolInstaller.Uv,
        installerPath = "/home/alexey/.local/bin/uv",
        daemon = PocketshellDaemonStatus.Running(enabled = true),
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun outdatedCliRowShowsOneUpdateAction_notUpdateBadgePlusUpgradeButton() {
        compose.setContent {
            PocketShellTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                ) {
                    HostBootstrapSheet(
                        state = HostBootstrapSheetState.Prompt(
                            needsTmux = false,
                            report = outdatedReport,
                        ),
                        hostName = "hetzner",
                        onInstall = {},
                        onInstallTool = {},
                        onSkip = {},
                        onDismiss = {},
                    )
                }
            }
        }

        compose.waitForIdle()
        SystemClock.sleep(250)

        // The outdated-CLI row is present.
        compose.onNodeWithText("pocketshell CLI update needed").assertIsDisplayed()

        // Issue #779: ONE clear action — "Update" button + "Outdated" status,
        // and the synonym verb "Upgrade" must NOT appear as a control.
        compose.onNodeWithText("Outdated").assertIsDisplayed()
        compose.onNodeWithText("Update").assertIsDisplayed()
        compose.onAllNodesWithText("Upgrade").assertCountEquals(0)

        capture("issue-779-cli-update-single-action.png")
    }

    private fun capture(name: String) {
        val dir = ensureArtifactDir()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(dir, name)
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write screenshot: ${file.absolutePath}"
                }
            }
            println("CLI_UPDATE_ROW_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/cli-update-row")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create cli-update-row screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }
}
