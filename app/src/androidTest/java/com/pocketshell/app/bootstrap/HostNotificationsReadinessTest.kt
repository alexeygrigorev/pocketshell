package com.pocketshell.app.bootstrap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Issue #1236 (D26) — per-host notification readiness + the "enable
 * notifications" upgrade affordance on the [HostBootstrapSheet].
 *
 * Under D21 the app never background-polls, so a server-side stop/idle hook →
 * FCM is the ONLY path to an "agent needs input" push. A host with the CLI but
 * no hooks is a SILENT host — and before #1236 that was invisible. This drives
 * the REAL sheet on the emulator and proves:
 *
 *  - A ready host whose hooks are OFF ([HooksStatus.NotInstalled]) shows an
 *    actionable "Agent notifications · Off · Enable" row, and tapping Enable
 *    fires the enable-notifications path (which folds in the non-destructive
 *    `pocketshell hooks install`).
 *  - The success sheet surfaces the readiness line: "Notifications: off" when
 *    hooks are not installed, "Notifications: on" when they are.
 *
 * Direct-Compose (no `ActivityScenario.launch`) so it does not depend on the
 * full host-connect journey; the live Docker bootstrap journey (bootstrap a
 * fixture host → non-destructive hook merge) is exercised separately by
 * [HostBootstrapScenarioSuiteTest.notifications].
 */
class HostNotificationsReadinessTest {

    @get:Rule
    val compose = createComposeRule()

    private fun readyButNotificationsOffReport(): HostBootstrapReport = HostBootstrapReport(
        tools = mapOf(
            BootstrapTool.Pocketshell to ToolStatus.Installed("/usr/local/bin/pocketshell"),
        ),
        installer = PythonToolInstaller.Uv,
        installerPath = "/usr/local/bin/uv",
        daemon = PocketshellDaemonStatus.Running(enabled = true),
        hooks = HooksStatus.NotInstalled,
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun promptSheet_showsEnableNotifications_forSilentHost_andTapFiresEnable() {
        val report = readyButNotificationsOffReport()
        // A compatible CLI with hooks definitively off is the actionable state.
        assertEquals(true, report.notificationsActionable)

        var enableClicks = 0
        var installAllClicks = 0
        compose.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    HostBootstrapSheet(
                        state = HostBootstrapSheetState.Prompt(needsTmux = false, report = report),
                        hostName = "hetzner",
                        onInstall = { installAllClicks++ },
                        onInstallTool = {},
                        onEnableNotifications = { enableClicks++ },
                        onSkip = {},
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        // The silent host is VISIBLE: an actionable notifications row + Enable.
        compose.onNodeWithTag(HOST_BOOTSTRAP_ROW_TAG_PREFIX + HOST_BOOTSTRAP_NOTIFICATIONS_ROW_TITLE)
            .assertExists()
        compose.onNodeWithTag(HOST_BOOTSTRAP_ENABLE_NOTIFICATIONS_TAG).assertExists()

        // Tapping Enable runs the enable-notifications path (NOT install-all).
        compose.onNodeWithTag(HOST_BOOTSTRAP_ENABLE_NOTIFICATIONS_TAG).performClick()
        compose.waitForIdle()
        assertEquals("Enable must fire onEnableNotifications", 1, enableClicks)
        assertEquals("Enable must not be the install-all path", 0, installAllClicks)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun successSheet_showsNotificationsOff_whenHooksNotInstalled() {
        compose.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    HostBootstrapSheet(
                        state = HostBootstrapSheetState.Success(notificationsReady = false),
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

        compose.onNodeWithTag(HOST_BOOTSTRAP_NOTIFICATIONS_STATUS_TAG).assertExists()
        compose.onNodeWithText("Notifications: off", substring = true).assertExists()
    }
}
