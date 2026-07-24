package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issues #189 / #192 screenshot evidence — captures the tmux session
 * top chrome ([ConsolidatedTopChrome] / [CompactBreadcrumb]) plus the
 * inline Terminal/Conversation toggle so the reviewer and maintainer can
 * eyeball the navigation chrome without driving a live tmux session.
 *
 * Issue #782: the per-window navigation strip (WindowStrip) was removed —
 * PocketShell no longer manages tmux windows, so there is no in-session
 * window-tab row to screenshot. The only in-session tab dimension is
 * Terminal/Conversation.
 *
 * Combined with [TmuxSessionScreenImeChromeTest] (which asserts the
 * 56dp height limit and the IME-down/up tag visibility contract), this
 * is the visual gate for the chrome IA.
 */
@RunWith(AndroidJUnit4::class)
class TmuxConsolidatedChromeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureSingleWindowNoAgentChrome() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        // Pad below the system status bar so the
                        // chrome doesn't overlap the system clock /
                        // notification icons in the screenshot. The
                        // real screen pulls this padding from its
                        // root scaffold; here we hard-code a Pixel-7
                        // status-bar height (24dp) so the test stays
                        // independent of the device's window insets
                        // (which createAndroidComposeRule does not
                        // always populate).
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    // Single window, no agent: just the header row.
                    // No strip (nothing to switch to), no toggle.
                    ConsolidatedTopChrome(
                        sessionName = "scratch",
                        onBack = {},
                        onMore = {},
                    )
                    PaneProxy()
                }
            }
        }
        compose.onNodeWithTag(SCREENSHOT_ROOT_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice(File(artifactDir(), "consolidated-chrome-single-window-no-agent.png"))
    }

    /**
     * Issue #1487 (reverses #601): the maintainer now wants the active
     * port-forwarding status as an always-visible, glanceable pill in the
     * session top chrome (Google-Maps-chip intent), NOT buried in the kebab
     * menu. Hard-cut per D22 — the old #601 "the header NEVER renders a
     * forwarding chip" rule is deleted, not gated. This asserts that when
     * forwarding is active the header DOES render the `⇄` pill (with the
     * per-host content description), while an INACTIVE host still renders no
     * pill (covered by [ForwardingPillPresenceTest]). The kebab is still
     * present as the tap-through to the port-forward panel.
     */
    @Test
    fun activeForwardingRendersHeaderPill() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    ConsolidatedTopChrome(
                        sessionName = "scratch",
                        onBack = {},
                        onMore = {},
                        forwardingState =
                            com.pocketshell.app.portfwd.SessionForwardingIndicatorState(
                                active = true,
                                tunnelCount = 2,
                            ),
                    )
                    PaneProxy()
                }
            }
        }

        compose.onNodeWithTag(SCREENSHOT_ROOT_TAG).assertExists()
        // The kebab (tap-through to the port-forward panel) is still present.
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG).assertIsDisplayed()
        // Issue #1487: the header now DOES carry the forwarding pill when active.
        compose.onNodeWithTag(TMUX_PORT_FORWARD_PILL_TAG).assertIsDisplayed()
        assertTrue(
            "active port-forwarding must render its per-host pill in the header (#1487)",
            compose.onAllNodesWithContentDescription(
                "2 ports forwarding active for this host",
            ).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice(File(artifactDir(), "consolidated-chrome-active-forwarding-header-pill.png"))
    }

    @Test
    fun captureAgentChrome() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    // Agent session: header row with the Terminal/Conversation
                    // toggle. Issue #782: no per-window strip — windows are no
                    // longer surfaced in the in-session chrome.
                    ConsolidatedTopChrome(
                        sessionName = "claude-main",
                        tabLabels = listOf("Terminal", "Conversation"),
                        selectedTabIndex = 1,
                        onBack = {},
                        onMore = {},
                    )
                    PaneProxy()
                }
            }
        }
        compose.onNodeWithTag(SCREENSHOT_ROOT_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice(File(artifactDir(), "consolidated-chrome-with-agent.png"))
    }

    @Test
    fun captureLongNameBreadcrumbChrome() {
        // Issue #637: a long host/session name must ellipsise inside the title
        // slot and leave the kebab at its stable right-edge position — it must
        // not push the kebab off screen or overlap it.
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    ConsolidatedTopChrome(
                        sessionName = "really-long-session-name-that-overflows-the-header",
                        onBack = {},
                        onMore = {},
                    )
                    PaneProxy()
                }
            }
        }
        compose.onNodeWithTag(SCREENSHOT_ROOT_TAG).assertExists()
        // The kebab must still be on screen (not pushed off the right edge).
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG).assertIsDisplayed()
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice(File(artifactDir(), "consolidated-chrome-long-name-breadcrumb.png"))
    }

    @Test
    fun captureLongNameWithAgentToggleChrome() {
        // Issue #637: with the agent Terminal/Conversation toggle present, a
        // long name must ellipsise and yield width to the toggle + kebab — the
        // toggle must stay uncramped and the kebab must stay on screen.
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    ConsolidatedTopChrome(
                        sessionName = "really-long-session-name-that-overflows-the-header",
                        tabLabels = listOf("Terminal", "Conversation"),
                        selectedTabIndex = 0,
                        onBack = {},
                        onMore = {},
                    )
                    PaneProxy()
                }
            }
        }
        compose.onNodeWithTag(SCREENSHOT_ROOT_TAG).assertExists()
        // Toggle and kebab both stay fully on screen even with a long name.
        compose.onNodeWithTag(TMUX_TABS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG).assertIsDisplayed()
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice(File(artifactDir(), "consolidated-chrome-long-name-with-agent.png"))
    }

    @Test
    fun captureImeUpCompactChrome() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        // Pad below the system status bar so the
                        // chrome doesn't overlap the system clock /
                        // notification icons in the screenshot. The
                        // real screen pulls this padding from its
                        // root scaffold; here we hard-code a Pixel-7
                        // status-bar height (24dp) so the test stays
                        // independent of the device's window insets
                        // (which createAndroidComposeRule does not
                        // always populate).
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    // IME up: chrome collapses to the compact breadcrumb;
                    // the strip + toggle are suppressed by the screen (so
                    // they are simply not rendered here).
                    CompactBreadcrumb(
                        sessionName = "claude-main",
                        onBack = {},
                        onMore = {},
                    )
                    PaneProxy()
                }
            }
        }
        compose.onNodeWithTag(SCREENSHOT_ROOT_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice(File(artifactDir(), "consolidated-chrome-ime-up-compact.png"))
    }

    @Test
    fun captureRightAnchoredMoreMenu() {
        compose.setContent {
            PocketShellTheme {
                val expanded = remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    ConsolidatedTopChrome(
                        sessionName = "claude-main",
                        onBack = {},
                        onMore = { expanded.value = true },
                        moreMenu = {
                            TmuxMoreMenu(
                                expanded = expanded.value,
                                onDismiss = { expanded.value = false },
                                onCreateSession = {},
                                onRenameSession = {},
                                onKillSession = {},
                                onSwitchSession = {},
                                onOpenJobs = {},
                                onOpenUsage = {},
                                onDetach = {},
                            )
                        },
                    )
                    PaneProxy()
                }
            }
        }
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
            .performTouchInput {
                val tap = Offset(right - 1f, centerY)
                down(tap)
                up()
            }
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice(File(artifactDir(), "right-anchored-more-menu.png"))
    }

    @androidx.compose.runtime.Composable
    private fun PaneProxy() {
        // A muted band below the chrome so the screenshots show the
        // boundary between the toolbar and the terminal viewport. The
        // actual terminal renderer is too heavy to mount here; the
        // proxy is enough to make the chrome reduction visible.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(PocketShellColors.TermBg)
                .padding(12.dp),
        )
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/tmux-consolidated-chrome")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create consolidated-chrome screenshot dir: ${dir.absolutePath}"
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
                    "Could not write consolidated-chrome screenshot: ${file.absolutePath}"
                }
            }
            println("TMUX_CONSOLIDATED_CHROME_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val SCREENSHOT_ROOT_TAG = "tmux:consolidated-chrome-screenshot"
    }
}
