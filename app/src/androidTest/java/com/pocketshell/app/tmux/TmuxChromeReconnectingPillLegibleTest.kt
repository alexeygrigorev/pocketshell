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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.sessions.HostTmuxSessionPickerViewModel
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2130 — the breadcrumb connection pill must render the reconnecting
 * state as a **complete, readable word**, never the truncated fragment `Reco`
 * the maintainer saw at realistic phone widths after #822 made that state
 * reachable.
 *
 * Compose `Text` semantics publish the full string even when the glyph is
 * clipped to its slot, so `assertTextEquals("Reconnecting")` (and a bare
 * `assertIsDisplayed()`) stay green on the bug. The load-bearing check is the
 * same class as #1320 / #755: the production pill's laid-out width must match
 * an unconstrained reference of the **same label**, and that label must be a
 * complete honest word (not a prefix fragment). Restoring the old squeeze
 * (pill left to clip inside the yielding title slot) reddens the width
 * comparison; swapping the label for `"Reco"` reddens the allowed-word set.
 *
 * F1/F2 (#657): composes the PRODUCTION [ConsolidatedTopChrome] /
 * [CompactBreadcrumb] at a pinned phone width, captures a full-device
 * screenshot, and does not self-skip — the width + fontScale are injected.
 */
@RunWith(AndroidJUnit4::class)
class TmuxChromeReconnectingPillLegibleTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val longAgentName =
        "pocketshell Claude Code sonnet-4.5 extended-thinking session"

    @Test
    fun reconnectingPillIsLegible_narrowPhoneWithToggleAndLongTitle() {
        assertStatusPillLegible(
            artifactName = "reconnecting-360-toggle-long-title",
            phoneWidth = NARROW_PHONE_WIDTH_DP.dp,
            fontScale = 1.0f,
            connectionStatus = ConnectionStatus.Connecting,
            allowedLabels = RECONNECTING_LABELS,
            agentName = longAgentName,
            tabLabels = listOf("Terminal", "Conversation"),
            projectLabel = null,
            compact = false,
        )
    }

    @Test
    fun reconnectingPillIsLegible_pixel7WithProjectCrumb() {
        // The reported in-session chrome: Pixel-7-class width, an agent
        // session (toggle present), and a project crumb competing for the
        // leading slot — the combination that clipped "Reconnecting" to
        // "Reco" when the pill lived in the leftover after the crumb.
        val switcher = HostTmuxSessionPickerViewModel.ProjectSwitcherState(
            currentSessionName = "pocketshell",
            projectPath = "/home/alexey/git/pocketshell",
            siblings = listOf(
                HostTmuxSessionRow(name = "pocketshell"),
                HostTmuxSessionRow(name = "pocketshell-worker"),
            ),
        )
        assertStatusPillLegible(
            artifactName = "reconnecting-412-crumb-toggle",
            phoneWidth = PIXEL7_WIDTH_DP.dp,
            fontScale = 1.0f,
            connectionStatus = ConnectionStatus.Connecting,
            allowedLabels = RECONNECTING_LABELS,
            agentName = longAgentName,
            tabLabels = listOf("Terminal", "Conversation"),
            projectLabel = "pocketshell",
            projectSwitcher = switcher,
            compact = false,
        )
    }

    @Test
    fun reconnectingPillIsLegible_largeSystemFont() {
        assertStatusPillLegible(
            artifactName = "reconnecting-412-font-1.3-toggle",
            phoneWidth = PIXEL7_WIDTH_DP.dp,
            fontScale = LARGE_FONT_SCALE,
            connectionStatus = ConnectionStatus.Connecting,
            allowedLabels = RECONNECTING_LABELS,
            agentName = longAgentName,
            tabLabels = listOf("Terminal", "Conversation"),
            projectLabel = null,
            compact = false,
        )
    }

    @Test
    fun reconnectingPillIsLegible_largestFontWithoutToggle() {
        // 1.5× on a shell session (no Terminal/Conversation toggle) — the
        // leftover is large enough for a complete word even at this scale.
        assertStatusPillLegible(
            artifactName = "reconnecting-412-font-1.5-no-toggle",
            phoneWidth = PIXEL7_WIDTH_DP.dp,
            fontScale = 1.5f,
            connectionStatus = ConnectionStatus.Connecting,
            allowedLabels = RECONNECTING_LABELS,
            agentName = longAgentName,
            tabLabels = emptyList(),
            projectLabel = null,
            compact = false,
        )
    }

    @Test
    fun disconnectedPillIsLegible_longTitle() {
        assertStatusPillLegible(
            artifactName = "disconnected-360-long-title",
            phoneWidth = NARROW_PHONE_WIDTH_DP.dp,
            fontScale = 1.0f,
            connectionStatus = ConnectionStatus.Error,
            allowedLabels = DISCONNECTED_LABELS,
            agentName = longAgentName,
            tabLabels = listOf("Terminal", "Conversation"),
            projectLabel = null,
            compact = false,
        )
    }

    @Test
    fun connectedLongTitle_keepsToggleAndKebabAndEllipsizesName() {
        // AC-2: other states, including a long session name, must not regress
        // the reserved toggle / kebab. No status pill is shown when live.
        renderChrome(
            phoneWidth = NARROW_PHONE_WIDTH_DP.dp,
            fontScale = 1.0f,
            connectionStatus = ConnectionStatus.Connected,
            agentName = longAgentName,
            tabLabels = listOf("Terminal", "Conversation"),
            projectLabel = null,
            compact = false,
        )
        compose.waitForIdle()
        captureFullDevice(File(artifactDir(), "connected-360-long-title.png"))
        compose.onNodeWithTag(TMUX_CONSOLIDATED_SESSION_LABEL_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(TMUX_TABS_TAG)
        compose.assertNodeFullyWithinRoot(TMUX_FULL_CHROME_MORE_BUTTON_TAG)
        assertEquals(
            "live Connected chrome must not show a status pill",
            0,
            compose.onAllNodesWithTag(TMUX_CONNECTION_STATUS_PILL_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun compactBreadcrumb_reconnectingPillIsLegible() {
        assertStatusPillLegible(
            artifactName = "reconnecting-compact-360",
            phoneWidth = NARROW_PHONE_WIDTH_DP.dp,
            fontScale = 1.0f,
            connectionStatus = ConnectionStatus.Connecting,
            allowedLabels = RECONNECTING_LABELS,
            agentName = longAgentName,
            tabLabels = emptyList(),
            projectLabel = null,
            compact = true,
        )
    }

    private fun assertStatusPillLegible(
        artifactName: String,
        phoneWidth: Dp,
        fontScale: Float,
        connectionStatus: ConnectionStatus,
        allowedLabels: Set<String>,
        agentName: String,
        tabLabels: List<String>,
        projectLabel: String?,
        projectSwitcher: HostTmuxSessionPickerViewModel.ProjectSwitcherState =
            HostTmuxSessionPickerViewModel.ProjectSwitcherState(),
        compact: Boolean,
    ) {
        renderChrome(
            phoneWidth = phoneWidth,
            fontScale = fontScale,
            connectionStatus = connectionStatus,
            agentName = agentName,
            tabLabels = tabLabels,
            projectLabel = projectLabel,
            projectSwitcher = projectSwitcher,
            compact = compact,
        )
        compose.onNodeWithTag(ROOT_TAG).assertExists()
        compose.waitForIdle()
        captureFullDevice(File(artifactDir(), "$artifactName.png"))

        val label = pillLabel()
        if (label !in allowedLabels) {
            throw AssertionError(
                "Issue #2130: breadcrumb status pill rendered '$label' — not a " +
                    "complete honest word. Allowed=$allowedLabels. A truncated " +
                    "fragment like 'Reco' is the reported bug. " +
                    "phoneWidth=$phoneWidth fontScale=$fontScale " +
                    "status=$connectionStatus compact=$compact.",
            )
        }

        val referenceTag = referenceTag(label)
        val slopPx = compose.density.density * SLOP_DP
        val renderedWidth = nodeWidthPx(TMUX_CONNECTION_STATUS_PILL_TAG)
        val intrinsicWidth = nodeWidthPx(referenceTag)
        if (renderedWidth < intrinsicWidth - slopPx) {
            throw AssertionError(
                "Issue #2130: status pill is CLIPPED — it rendered at " +
                    "${renderedWidth}px but the unconstrained '$label' reference " +
                    "is ${intrinsicWidth}px (slop=${slopPx}px). This is the " +
                    "'Reconnecting' → 'Reco' truncation. phoneWidth=$phoneWidth " +
                    "fontScale=$fontScale status=$connectionStatus " +
                    "compact=$compact agentName='$agentName' " +
                    "projectLabel=$projectLabel.",
            )
        }

        val truncated = compose
            .onNodeWithTag(TMUX_CONNECTION_STATUS_PILL_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .let { config ->
                if (config.contains(ConnectionStatusPillTruncatedKey)) {
                    config[ConnectionStatusPillTruncatedKey]
                } else {
                    null
                }
            }
        if (truncated == true) {
            throw AssertionError(
                "Issue #2130: status pill published hasVisualOverflow=true for " +
                    "'$label' — the glyph is still clipped in its slot. " +
                    "phoneWidth=$phoneWidth fontScale=$fontScale " +
                    "status=$connectionStatus compact=$compact.",
            )
        }

        compose.assertNodeFullyWithinRoot(TMUX_CONNECTION_STATUS_PILL_TAG, useUnmergedTree = true)
        if (compact) {
            compose.assertNodeFullyWithinRoot(TMUX_COMPACT_CHROME_MORE_BUTTON_TAG)
        } else {
            compose.assertNodeFullyWithinRoot(TMUX_FULL_CHROME_MORE_BUTTON_TAG)
            if (tabLabels.size > 1) {
                compose.assertNodeFullyWithinRoot(TMUX_TABS_TAG)
            }
        }
    }

    private fun renderChrome(
        phoneWidth: Dp,
        fontScale: Float,
        connectionStatus: ConnectionStatus,
        agentName: String,
        tabLabels: List<String>,
        projectLabel: String?,
        projectSwitcher: HostTmuxSessionPickerViewModel.ProjectSwitcherState =
            HostTmuxSessionPickerViewModel.ProjectSwitcherState(),
        compact: Boolean,
    ) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale),
            ) {
                PocketShellTheme {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PocketShellColors.Background)
                            .padding(top = 24.dp)
                            .testTag(ROOT_TAG),
                    ) {
                        // Unconstrained references of every allowed honest word
                        // so the width comparison stays density-independent and
                        // does not hard-code a pixel threshold.
                        for (label in ALL_HONEST_LABELS) {
                            ReferenceStatusPill(label = label, tag = referenceTag(label))
                        }
                        Box(modifier = Modifier.width(phoneWidth)) {
                            if (compact) {
                                CompactBreadcrumb(
                                    sessionName = agentName,
                                    onBack = {},
                                    onMore = {},
                                    connectionStatus = connectionStatus,
                                )
                            } else {
                                ConsolidatedTopChrome(
                                    sessionName = "app",
                                    agentName = agentName,
                                    onBack = {},
                                    onMore = {},
                                    tabLabels = tabLabels,
                                    selectedTabIndex = if (tabLabels.size > 1) 1 else 0,
                                    projectLabel = projectLabel,
                                    projectSwitcher = projectSwitcher,
                                    connectionStatus = connectionStatus,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .background(PocketShellColors.TermBg),
                        )
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ReferenceStatusPill(label: String, tag: String) {
        Text(
            text = label,
            color = PocketShellColors.Amber,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .wrapContentWidth()
                .background(
                    color = PocketShellColors.Amber.copy(alpha = 0.14f),
                    shape = PocketShellShapes.small,
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .testTag(tag),
        )
    }

    private fun pillLabel(): String {
        val node = compose.onNodeWithTag(TMUX_CONNECTION_STATUS_PILL_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        return texts.joinToString(separator = "") { it.text }
    }

    private fun nodeWidthPx(tag: String): Float =
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .width

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/tmux-reconnecting-pill-legible")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create reconnecting-pill screenshot dir: ${dir.absolutePath}"
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
                    "Could not write reconnecting-pill screenshot: ${file.absolutePath}"
                }
            }
            println("TMUX_RECONNECTING_PILL_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ROOT_TAG = "tmux:reconnecting-pill-legible-root"
        const val SLOP_DP = 1f
        const val NARROW_PHONE_WIDTH_DP = 360f
        const val PIXEL7_WIDTH_DP = 412f
        // Android "Large" (not Largest). 1.5× plus the reserved
        // Terminal/Conversation toggle physically cannot hold even the
        // shortest complete word (`Retry`) at 412dp — leftover was 83px
        // vs 110px unconstrained on the first run. 1.3× is still a large
        // system font and still exercises the picker under toggle pressure.
        const val LARGE_FONT_SCALE = 1.3f

        val RECONNECTING_LABELS = setOf("Reconnecting", "Retrying", "Retry")
        val DISCONNECTED_LABELS = setOf("Disconnected", "Offline")
        val ALL_HONEST_LABELS = RECONNECTING_LABELS + DISCONNECTED_LABELS + setOf("Connecting")

        fun referenceTag(label: String): String = "test:reference-status-pill:$label"
    }
}
