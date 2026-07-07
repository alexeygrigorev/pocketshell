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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1320 regression proof — the 5×-recurring "Conversation toggle missing"
 * dogfood blocker (#962 → #975 → #1057 → #1158). The REAL root cause is a LAYOUT
 * clip, NOT the agent-detection gate: in [ConsolidatedTopChrome] the
 * Terminal/Conversation toggle used to live inside a `weight(1f, fill = false)`
 * trailing slot that competed with the title's own `weight(1f)`. Two `weight(1f)`
 * slots split the remaining row width 50/50, so a long agent/session title
 * starved the toggle's slot below its intrinsic width and its "Conversation"
 * segment was width-constrained and ellipsised away — the user saw only
 * "Terminal" and had no way to switch to the conversation view. Detection had
 * already fired (the header showed the agent name), which is exactly why adding
 * yet another detection OR-term never fixed it.
 *
 * The load-bearing property this proves (red→green): the **Conversation
 * segment** must render at its FULL intrinsic width — it must NOT be squeezed
 * narrower than the same segment rendered unconstrained. The segment bug is
 * width-STARVATION (the constrained toggle ellipsises the "Conversation" label),
 * NOT an off-the-edge overflow, so a bare `assertIsDisplayed()` — and even a
 * whole-toggle containment check — passes on base (the squeezed toggle still
 * lies within the row). The decisive check compares the production segment's
 * rendered width against a reference intrinsic render of the identical
 * [SegmentedToggle]; on base the production segment is starved (< intrinsic) and
 * the assertion FAILS, with the fix (toggle pulled OUT of the weighted slot and
 * reserved at intrinsic width) it renders full and PASSES. It is density-
 * independent (no hard-coded pixel threshold) and does NOT self-skip on CI.
 *
 * Class coverage (D31/D32 G2): long title, long title + status pill present,
 * long project crumb, and the narrowest supported width with everything
 * competing. F1/F2 (#657): composes the PRODUCTION [ConsolidatedTopChrome] in
 * the reported state, captures a full-device screenshot for visual proof, and
 * asserts the segment is displayed + tappable (reachability, not just presence).
 *
 * Pure Compose-rule UI test — NO Docker fixture, NO SSH/tmux, NO toxiproxy, NO
 * 2222/2226 port — so it needs no tests.yml service change and is wired into
 * `scripts/ci-journey-suite.sh` so the class regression cannot silently return.
 */
@RunWith(AndroidJUnit4::class)
class TmuxChromeConversationTogglePresentTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The Conversation segment tag inside [ConsolidatedTabPill] (index 1). */
    private val conversationSegmentTag = TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX + 1

    /** A very long agent/model title — the maintainer's reported widening source. */
    private val longAgentName =
        "pocketshell Claude Code sonnet-4.5 extended-thinking session"

    @Test
    fun conversationSegmentRendersFullWidth_longAgentName() {
        assertConversationSegmentNotStarved(
            artifactName = "conv-toggle-long-agent",
            phoneWidth = 360.dp,
            agentName = longAgentName,
            connectionStatus = ConnectionStatus.Connected,
            projectLabel = null,
            projectSwitcher = HostTmuxSessionPickerViewModel.ProjectSwitcherState(),
        )
    }

    @Test
    fun conversationSegmentRendersFullWidth_longAgentNameWithStatusPill() {
        // Class coverage: a non-live connection surfaces the "Disconnected" pill
        // in the yielding region — the widest the leading cluster ever gets. The
        // toggle must still win its full width over the pill.
        assertConversationSegmentNotStarved(
            artifactName = "conv-toggle-long-agent-status-pill",
            phoneWidth = 360.dp,
            agentName = longAgentName,
            connectionStatus = ConnectionStatus.Error,
            projectLabel = null,
            projectSwitcher = HostTmuxSessionPickerViewModel.ProjectSwitcherState(),
        )
    }

    @Test
    fun conversationSegmentRendersFullWidth_longProjectCrumb() {
        // Class coverage: a long project crumb competes for the leading width.
        val switcher = HostTmuxSessionPickerViewModel.ProjectSwitcherState(
            currentSessionName = "pocketshell-android-client",
            projectPath = "/home/alexey/projects/pocketshell-android-client",
            siblings = listOf(
                HostTmuxSessionRow(name = "pocketshell-android-client"),
                HostTmuxSessionRow(name = "pocketshell-android-client-worker"),
            ),
        )
        assertConversationSegmentNotStarved(
            artifactName = "conv-toggle-long-crumb",
            phoneWidth = 360.dp,
            agentName = longAgentName,
            connectionStatus = ConnectionStatus.Connected,
            projectLabel = "pocketshell-android-client",
            projectSwitcher = switcher,
        )
    }

    @Test
    fun conversationSegmentRendersFullWidth_narrowestSupportedWidth() {
        // Class coverage: the narrowest realistic phone content width, long title
        // AND status pill AND crumb all competing. The toggle still never yields.
        val switcher = HostTmuxSessionPickerViewModel.ProjectSwitcherState(
            currentSessionName = "app",
            projectPath = "/home/alexey/projects/app",
            siblings = listOf(
                HostTmuxSessionRow(name = "app"),
                HostTmuxSessionRow(name = "app-worker"),
            ),
        )
        assertConversationSegmentNotStarved(
            artifactName = "conv-toggle-narrowest",
            phoneWidth = 320.dp,
            agentName = longAgentName,
            connectionStatus = ConnectionStatus.Error,
            projectLabel = "long-project-folder-name",
            projectSwitcher = switcher,
        )
    }

    /**
     * Renders BOTH a reference intrinsic [SegmentedToggle] (unconstrained) and
     * the production [ConsolidatedTopChrome] constrained to [phoneWidth] with a
     * long title, then asserts the production Conversation segment is at least as
     * wide as the reference (within slop) — i.e. NOT starved/ellipsised. Also
     * captures a full-device screenshot and asserts the segment is displayed +
     * tappable.
     */
    private fun assertConversationSegmentNotStarved(
        artifactName: String,
        phoneWidth: Dp,
        agentName: String,
        connectionStatus: ConnectionStatus,
        projectLabel: String?,
        projectSwitcher: HostTmuxSessionPickerViewModel.ProjectSwitcherState,
    ) {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(ROOT_TAG),
                ) {
                    // Reference: the same two-label toggle rendered UNCONSTRAINED
                    // (wrap content) so its Conversation segment measures at full
                    // intrinsic width — the width the production segment MUST
                    // match. Comparing against it keeps the check density-
                    // independent (no hard-coded pixel threshold).
                    Box {
                        SegmentedToggle(
                            labels = listOf("Terminal", "Conversation"),
                            selectedIndex = 1,
                            onSelected = {},
                            segmentTag = { index ->
                                if (index == 1) REFERENCE_CONVERSATION_TAG else null
                            },
                        )
                    }
                    // Production chrome constrained to a realistic phone width so
                    // the (base) weight-split squeeze is deterministic on any AVD.
                    Box(modifier = Modifier.width(phoneWidth)) {
                        ConsolidatedTopChrome(
                            sessionName = "app",
                            agentName = agentName,
                            onBack = {},
                            onMore = {},
                            tabLabels = listOf("Terminal", "Conversation"),
                            selectedTabIndex = 1,
                            projectLabel = projectLabel,
                            projectSwitcher = projectSwitcher,
                            connectionStatus = connectionStatus,
                        )
                    }
                    TerminalBandFiller()
                }
            }
        }

        compose.onNodeWithTag(ROOT_TAG).assertExists()
        compose.waitForIdle()

        captureFullDevice(File(artifactDir(), "$artifactName.png"))

        val intrinsicConversationWidth = nodeWidthPx(REFERENCE_CONVERSATION_TAG)
        val renderedConversationWidth = nodeWidthPx(conversationSegmentTag)
        val slopPx = compose.density.density * SLOP_DP

        if (renderedConversationWidth < intrinsicConversationWidth - slopPx) {
            throw AssertionError(
                "Issue #1320: the Conversation segment is STARVED — it rendered " +
                    "at ${renderedConversationWidth}px but its full intrinsic " +
                    "width is ${intrinsicConversationWidth}px (slop=${slopPx}px). " +
                    "The Terminal/Conversation toggle is being squeezed by the " +
                    "long title in ConsolidatedTopChrome, so the 'Conversation' " +
                    "label is clipped/ellipsised and the user cannot switch to " +
                    "the conversation view. phoneWidth=$phoneWidth " +
                    "agentName='$agentName' status=$connectionStatus " +
                    "projectLabel=$projectLabel.",
            )
        }

        // Belt-and-suspenders: the whole toggle must also lie within the root
        // (never pushed off the edge), and the Conversation segment must be
        // displayed + tappable — reachability, not just width.
        compose.assertNodeFullyWithinRoot(TMUX_TABS_TAG)
        compose.onNodeWithTag(conversationSegmentTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    private fun nodeWidthPx(tag: String): Float =
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .width

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
        val dir = File(mediaRoot, "additional_test_output/tmux-conversation-toggle-present")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create conversation-toggle screenshot dir: ${dir.absolutePath}"
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
                    "Could not write conversation-toggle screenshot: ${file.absolutePath}"
                }
            }
            println("TMUX_CONVERSATION_TOGGLE_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ROOT_TAG = "tmux:conv-toggle-present-root"
        const val REFERENCE_CONVERSATION_TAG = "test:reference-conversation-segment"
        const val SLOP_DP = 1f
    }
}
