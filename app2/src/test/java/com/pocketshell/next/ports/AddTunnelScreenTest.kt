package com.pocketshell.next.ports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddTunnelScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `invalid port cannot submit`() {
        setContent(remote = "70000", local = "8080", valid = false)

        composeRule.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun `valid loopback mapping submits both ports`() {
        var submits = 0
        setContent(
            name = "Fixture HTTP",
            remote = "5173",
            local = "35173",
            valid = true,
            onSubmit = { submits++ },
        )

        composeRule.onNodeWithTag(ADD_TUNNEL_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG)
            .performScrollTo()
            .performClick()

        assertEquals(1, submits)
    }

    @Test
    fun `large text can scroll to the submit action`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.8f)) {
                PocketShellTheme {
                    AddTunnelScreen(
                        name = "Fixture HTTP",
                        remotePort = "5173",
                        localPort = "35173",
                        valid = true,
                        onNameChange = {},
                        onRemotePortChange = {},
                        onLocalPortChange = {},
                        onSubmit = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ADD_TUNNEL_FORM_SCROLL_TAG)
            .performScrollToNode(hasTestTag(ADD_TUNNEL_SUBMIT_TAG))
        composeRule.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG).assertIsDisplayed()
    }

    @Test
    fun `a local port collision is visible and cannot submit`() {
        setContent(
            name = "Second tunnel",
            remote = "5174",
            local = "35173",
            valid = false,
            localPortCollision = "Local port 35173 is already used by Fixture HTTP",
        )

        composeRule.onNodeWithTag(ADD_TUNNEL_SUBMIT_TAG).assertIsNotEnabled()
        composeRule.onNodeWithText("Local port 35173 is already used by Fixture HTTP")
            .assertIsDisplayed()
    }

    @Test
    fun `more options discloses the safe remote address and exposure warning`() {
        setContent(remote = "5173", local = "35173", valid = true)

        composeRule.onNodeWithTag(ADD_TUNNEL_REMOTE_ADDRESS_CONTAINER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(ADD_TUNNEL_FORM_SCROLL_TAG)
            .performScrollToNode(hasTestTag(ADD_TUNNEL_OPTIONS_TAG))
        composeRule.onNodeWithTag(
            ADD_TUNNEL_OPTIONS_TAG,
            useUnmergedTree = true,
        ).performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ADD_TUNNEL_REMOTE_ADDRESS_CONTAINER_TAG).performScrollTo()
        composeRule.onNodeWithTag(ADD_TUNNEL_REMOTE_ADDRESS_CONTAINER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Remote address").assertIsDisplayed()
        composeRule.onNodeWithText("127.0.0.1").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Changing the local bind address can expose this service to other devices. " +
                "PocketShell keeps this tunnel on 127.0.0.1.",
        ).assertIsDisplayed()
    }

    private fun setContent(
        name: String = "Fixture HTTP",
        remote: String,
        local: String,
        valid: Boolean,
        localPortCollision: String? = null,
        onSubmit: () -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                AddTunnelScreen(
                    name = name,
                    remotePort = remote,
                    localPort = local,
                    valid = valid,
                    localPortCollision = localPortCollision,
                    onNameChange = {},
                    onRemotePortChange = {},
                    onLocalPortChange = {},
                    onSubmit = onSubmit,
                    onBack = {},
                )
            }
        }
    }
}
