package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.SessionForwardingIndicatorState
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #487 / #601: chrome-level coverage for the in-session "port
 * forwarding active for this host" indicator ([SessionForwardingChip]). Mounts the
 * composable directly (no Hilt / live tmux) so the affordance is verified at
 * the UI layer:
 *
 *  - The chip renders its tunnel count + talkback description when the host is
 *    actively forwarding.
 *  - A "restoring" state reads as restoring, not removed.
 *  - The whole chip is a single tap target into the port-forward panel.
 *
 * The screen-level "show only while THIS host forwards / hide otherwise"
 * gating is driven by [SessionForwardingIndicatorState.visible] and covered by
 * the JVM unit tests; this test verifies the rendered indicator itself.
 */
@RunWith(AndroidJUnit4::class)
class SessionForwardingChipUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chipRendersCountAndIsTappable() {
        var tapped = false
        compose.setContent {
            PocketShellTheme {
                Column {
                    SessionForwardingChip(
                        state = SessionForwardingIndicatorState(active = true, tunnelCount = 2),
                        onClick = { tapped = true },
                    )
                }
            }
        }

        compose.onNodeWithTag(TMUX_SESSION_FORWARDING_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithText("Ports 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("2 ports forwarding active for this host")
            .assertIsDisplayed()
            .assertHasClickAction()
        captureChip("session-forwarding-chip")
        compose.onNodeWithContentDescription("2 ports forwarding active for this host")
            .performClick()
        assertTrue("tapping the chip must route to the port-forward panel", tapped)
    }

    private fun captureChip(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("could not capture the chip screenshot", bitmap)
        val ctx = instrumentation.targetContext
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(ctx)
        val dir = File(mediaRoot, "additional_test_output/session-forwarding-chip")
        dir.mkdirs()
        val shot = File(dir, "$name-viewport.png")
        FileOutputStream(shot).use {
            bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap!!.recycle()
        println("SESSION_FORWARDING_CHIP_SCREENSHOT ${shot.absolutePath}")
    }

    @Test
    fun restoringChipReadsAsRestoring() {
        compose.setContent {
            PocketShellTheme {
                Column {
                    SessionForwardingChip(
                        state = SessionForwardingIndicatorState(
                            active = true,
                            tunnelCount = 0,
                            restoring = true,
                        ),
                        onClick = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Port forwarding restoring for this host")
            .assertIsDisplayed()
        compose.onNodeWithText("Ports").assertIsDisplayed()
    }

    @Test
    fun fullChromeSurfacesForwardingIndicatorAndTapAction() {
        var tapped = false
        compose.setContent {
            PocketShellTheme {
                ConsolidatedTopChrome(
                    sessionName = "main",
                    onBack = {},
                    onMore = {},
                    forwardingState = SessionForwardingIndicatorState(
                        active = true,
                        tunnelCount = 3,
                    ),
                    onOpenPortForwarding = { tapped = true },
                )
            }
        }

        compose.onNodeWithTag(TMUX_SESSION_FORWARDING_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithText("Ports 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("3 ports forwarding active for this host")
            .assertHasClickAction()
            .performClick()
        assertTrue("full chrome indicator must route to the port-forward panel", tapped)
    }

    @Test
    fun compactChromeKeepsForwardingIndicatorVisible() {
        compose.setContent {
            PocketShellTheme {
                CompactBreadcrumb(
                    sessionName = "main",
                    onBack = {},
                    onMore = {},
                    forwardingState = SessionForwardingIndicatorState(
                        active = true,
                        tunnelCount = 1,
                    ),
                )
            }
        }

        compose.onNodeWithTag(TMUX_SESSION_FORWARDING_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithText("Ports 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("1 port forwarding active for this host")
            .assertIsDisplayed()
    }
}
