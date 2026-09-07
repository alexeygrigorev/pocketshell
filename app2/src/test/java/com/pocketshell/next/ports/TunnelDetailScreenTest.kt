package com.pocketshell.next.ports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TunnelDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `manual tunnel detail exposes remove action`() {
        var stops = 0
        setContent(manual = true, onStop = { stops++ })

        composeRule.onNodeWithText("Manual tunnel").assertIsDisplayed()
        composeRule.onNodeWithTag(TUNNEL_STOP_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Remove tunnel").assertIsDisplayed()
        composeRule.onNodeWithTag(TUNNEL_STOP_TAG).performClick()

        assertEquals(1, stops)
    }

    @Test
    fun `auto discovered tunnel detail keeps stop action`() {
        setContent(manual = false)

        composeRule.onNodeWithTag(TUNNEL_STOP_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Stop tunnel").assertIsDisplayed()
        composeRule.onNodeWithText("Remove tunnel").assertDoesNotExist()
    }

    private fun setContent(
        manual: Boolean,
        onStop: () -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                TunnelDetailScreen(
                    hostName = "hetzner",
                    tunnel = TunnelInfo(
                        remotePort = 22,
                        localPort = 7_432,
                        process = "sshd",
                        status = TunnelInfo.Status.FORWARDING,
                    ),
                    manual = manual,
                    onBack = {},
                    onCopyAddress = {},
                    onStop = onStop,
                )
            }
        }
    }
}
