package com.pocketshell.app.hosts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_SHEET_TAG
import com.pocketshell.app.bootstrap.HostBootstrapSheetState
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Issue #1243: after the guided first-host SSH test succeeds, the primary
 * action must enter the existing host bootstrap/setup flow instead of skipping
 * straight to the folder list.
 */
class FirstHostTestConnectScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun successActionStartsSetup_andSetupStepIsActive() {
        val host = HostEntity(
            id = 42L,
            name = "Lab",
            hostname = "127.0.0.1",
            username = "test",
            keyId = 7L,
        )
        val key = SshKeyEntity(id = 7L, name = "lab", privateKeyPath = "/tmp/lab")
        var setupHost: HostEntity? = null
        var setupKeyPath: String? = null

        compose.setContent {
            PocketShellTheme {
                FirstHostTestConnectContent(
                    state = FirstHostTestConnectState(
                        host = host,
                        key = key,
                        status = FirstHostTestStatus.Success,
                    ),
                    hostId = host.id,
                    onBack = {},
                    onEditHost = {},
                    onRetry = {},
                    onStartSetup = { h, path ->
                        setupHost = h
                        setupKeyPath = path
                    },
                    bootstrapState = null,
                    bootstrapHostName = "",
                    onInstall = {},
                    onInstallTool = {},
                    onEnableNotifications = {},
                    onDismissSetup = {},
                )
            }
        }

        compose.onNodeWithText("4. Setup", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Finish setup", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(FIRST_HOST_TEST_CONNECT_OPEN_TAG, useUnmergedTree = true)
            .performClick()

        compose.runOnIdle {
            assertEquals(host, setupHost)
            assertEquals("/tmp/lab", setupKeyPath)
        }
    }

    @Test
    fun setupStateRendersExistingBootstrapSheet() {
        compose.setContent {
            PocketShellTheme {
                FirstHostTestConnectContent(
                    state = FirstHostTestConnectState(status = FirstHostTestStatus.Success),
                    hostId = 42L,
                    onBack = {},
                    onEditHost = {},
                    onRetry = {},
                    onStartSetup = { _, _ -> },
                    bootstrapState = HostBootstrapSheetState.Prompt(needsTmux = true),
                    bootstrapHostName = "Lab",
                    onInstall = {},
                    onInstallTool = {},
                    onEnableNotifications = {},
                    onDismissSetup = {},
                )
            }
        }

        compose.onNodeWithTag(HOST_BOOTSTRAP_SHEET_TAG).assertIsDisplayed()
    }
}
