package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #747 regression proof — the in-session top-bar kebab (three-dots
 * "tools" menu) must stay FULLY ON SCREEN (containment, not just
 * "displayed") in the maintainer's reported port-forwarding-active state.
 *
 * Root cause class (research spike on #747): the trailing control cluster in
 * [ConsolidatedTopChrome] is a NON-SHRINKING `Row` (no `weight`, no
 * `widthIn`). When that cluster is wide — the worst case is forwarding-active
 * AND an agent present, which puts the wide "Terminal / Conversation" tab pill
 * next to the kebab, plus a non-live "Disconnected"/"Reconnecting" status pill,
 * plus the project-switcher crumb competing for width on the leading side —
 * the cluster overflows the 56dp row and Compose lays the LAST trailing child
 * (the 48dp kebab) past the right edge. The title's `weight(1f)` collapses to
 * zero but cannot rescue the kebab once the fixed-width cluster alone exceeds
 * the remaining row width. On-device the maintainer saw the `⇄` chip flush to
 * the edge with NO three-dots to its right: the kebab was shoved off-screen,
 * not occluded.
 *
 * The `⇄ N` chip itself was hard-cut earlier (#601); forwarding status now
 * lives inside the kebab. But the off-screen-kebab MECHANISM survives whenever
 * the non-shrinking trailing cluster is wide — so this test reproduces that
 * class directly against the production [ConsolidatedTopChrome], at a realistic
 * narrow phone width, in the reported worst-case state.
 *
 * F1/F2 (issue #657): asserts viewport CONTAINMENT via
 * [assertNodeFullyWithinRoot] (a bare `assertIsDisplayed()` is satisfied by an
 * off-the-right-edge node), composes the PRODUCTION chrome in the reported
 * state (agent tab pill + non-live status pill + project crumb), and does NOT
 * self-skip — it hard-fails when the kebab leaks off the edge.
 */
@RunWith(AndroidJUnit4::class)
class TmuxChromeKebabReachableTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The narrowest realistic phone content width after typical horizontal
     * system chrome — comfortably inside a Pixel-7 portrait viewport (~412dp)
     * yet representative of a real device. The chrome is constrained to this
     * width so the overflow is deterministic on any AVD density.
     */
    private val phoneContentWidth = 360.dp

    @Test
    fun kebabStaysOnScreenWithForwardingActiveAgentAndProjectCrumb() {
        // The maintainer's exact reported state: an agent session (so the wide
        // "Terminal / Conversation" tab pill is in the trailing cluster), port
        // forwarding active on a busy host, a project crumb competing for the
        // leading width, AND a non-live connection (so the "Disconnected" pill
        // adds to the cluster — the widest the trailing cluster ever gets).
        val switcher = HostTmuxSessionPickerViewModel.ProjectSwitcherState(
            currentSessionName = "3d-models",
            projectPath = "/home/alexey/projects/3d-models",
            siblings = listOf(
                HostTmuxSessionRow(name = "3d-models"),
                HostTmuxSessionRow(name = "3d-models-worker"),
            ),
        )
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(ROOT_TAG),
                ) {
                    // Constrain to a realistic phone content width so the
                    // overflow is deterministic regardless of AVD width.
                    Box(modifier = Modifier.width(phoneContentWidth)) {
                        ConsolidatedTopChrome(
                            sessionName = "3d-models",
                            agentName = "claude-3-5-sonnet",
                            onBack = {},
                            onMore = {},
                            tabLabels = listOf("Terminal", "Conversation"),
                            selectedTabIndex = 1,
                            projectLabel = "3d-models",
                            projectSwitcher = switcher,
                            connectionStatus = ConnectionStatus.Error,
                        )
                    }
                    TerminalBandFiller()
                }
            }
        }

        compose.onNodeWithTag(ROOT_TAG).assertExists()
        compose.waitForIdle()

        captureFullDevice(
            File(artifactDir(), "kebab-forwarding-active-agent-crumb.png"),
        )

        // A bare assertIsDisplayed() PASSES even when the kebab is shoved off
        // the right edge (it still participates in layout). Containment is the
        // load-bearing assertion: the kebab's rect must lie inside the root.
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(TMUX_FULL_CHROME_MORE_BUTTON_TAG)
    }

    @Test
    fun kebabStaysOnScreenWithForwardingActiveAndLongAgentName() {
        // Same overflow class, driven by a long agent/model name instead of the
        // crumb — proves the fix is general (the title yields, the kebab is
        // reserved), not specific to one widening source.
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(ROOT_TAG),
                ) {
                    Box(modifier = Modifier.width(phoneContentWidth)) {
                        ConsolidatedTopChrome(
                            sessionName = "3d-models",
                            agentName = "claude-3-5-sonnet-20241022-extended",
                            onBack = {},
                            onMore = {},
                            tabLabels = listOf("Terminal", "Conversation"),
                            selectedTabIndex = 0,
                            connectionStatus = ConnectionStatus.Connecting,
                        )
                    }
                    TerminalBandFiller()
                }
            }
        }

        compose.onNodeWithTag(ROOT_TAG).assertExists()
        compose.waitForIdle()

        captureFullDevice(
            File(artifactDir(), "kebab-forwarding-active-long-name.png"),
        )

        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(TMUX_FULL_CHROME_MORE_BUTTON_TAG)
        // The tab pill must remain reachable too — it must not be shoved off the
        // leading edge by the reservation of the kebab slot.
        compose.assertNodeFullyWithinRoot(TMUX_TABS_TAG)
    }

    @Test
    fun kebabIsTappableWhenMenuOpensInForwardingActiveState() {
        // Reachability, not just containment: open the menu via the kebab and
        // confirm the "Port forwarding" status row (the new home for the count)
        // is present — i.e. the user can actually get to the tools menu in the
        // forwarding-active state.
        val expanded = mutableStateOf(false)
        compose.setContent {
            PocketShellTheme {
                val isOpen = remember { expanded }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(ROOT_TAG),
                ) {
                    Box(modifier = Modifier.width(phoneContentWidth)) {
                        ConsolidatedTopChrome(
                            sessionName = "3d-models",
                            agentName = "claude-3-5-sonnet",
                            onBack = {},
                            onMore = { isOpen.value = true },
                            tabLabels = listOf("Terminal", "Conversation"),
                            selectedTabIndex = 1,
                            projectLabel = "3d-models",
                            connectionStatus = ConnectionStatus.Error,
                            moreMenu = {
                                TmuxMoreMenu(
                                    expanded = isOpen.value,
                                    onDismiss = { isOpen.value = false },
                                    forwardingState =
                                        com.pocketshell.app.portfwd.SessionForwardingIndicatorState(
                                            active = true,
                                            tunnelCount = 28,
                                        ),
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
                    }
                    TerminalBandFiller()
                }
            }
        }

        // Kebab must be on screen to be tappable at all.
        compose.assertNodeFullyWithinRoot(TMUX_FULL_CHROME_MORE_BUTTON_TAG)
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG).performClick()
        compose.waitForIdle()
        // The menu opened and the forwarding status row is reachable.
        compose.onNodeWithTag(TMUX_PORT_FORWARDING_BUTTON_TAG).assertExists()
        // #747 issue 2: the in-session port surface is the single legible count
        // row inside the kebab — NOT a per-port list that overlaps with many
        // ports. With 28 tunnels the row reads as one compact "active ports"
        // line (deep-link to the full scrollable PortForwardPanelScreen), so
        // there is nothing to overlap. Assert the count label is present, then
        // capture the open menu for visual proof.
        compose.onNodeWithText("28 active ports").assertExists()
        captureFullDevice(
            File(artifactDir(), "kebab-menu-open-28-ports.png"),
        )
    }

    @androidx.compose.runtime.Composable
    private fun TerminalBandFiller() {
        Box(
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
        val dir = File(mediaRoot, "additional_test_output/tmux-kebab-reachable")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create kebab-reachable screenshot dir: ${dir.absolutePath}"
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
                    "Could not write kebab-reachable screenshot: ${file.absolutePath}"
                }
            }
            println("TMUX_KEBAB_REACHABLE_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ROOT_TAG = "tmux:kebab-reachable-root"
    }
}
