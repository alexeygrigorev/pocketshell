package com.pocketshell.app.hosts

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.components.HOST_CONNECTING_ROW_TAG
import com.pocketshell.uikit.components.HOST_CONNECTING_SPINNER_TAG
import com.pocketshell.uikit.components.HostCard
import com.pocketshell.uikit.model.HostSetupState
import com.pocketshell.uikit.model.HostStatus
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostCardConnectingIndicatorTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connectingLabelRendersInlineWithSpinner() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    HostCard(
                        name = "hetzner",
                        subtitle = "root@host.example:22",
                        status = HostStatus.NoActiveSessions,
                        onClick = {},
                        modifier = Modifier.testTag("host-card"),
                        setupState = HostSetupState.Ready,
                        connectingLabel = "Checking setup",
                    )
                }
            }
        }

        compose.onNodeWithTag(HOST_CONNECTING_ROW_TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(HOST_CONNECTING_SPINNER_TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Checking setup").assertIsDisplayed()
    }
}
