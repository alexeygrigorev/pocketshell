package com.pocketshell.next.ports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rendered port-forward screen on the host JVM (Robolectric), the same way
 * `:shared:ui-kit` tests its primitives.
 *
 * Every assertion is on the RENDERED tree: which message appears for which state
 * (off vs unreachable vs scanning vs "all rows are hidden" are four DIFFERENT
 * situations that must not paint the same blank), that a row's action carries its
 * own remote port, and that a duplicate remote port cannot crash the list.
 */
@RunWith(AndroidJUnit4::class)
class PortForwardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `forwarding rows render remote, local, process, status and traffic`() {
        setContent(
            state(
                enabled = true,
                connection = ConnectionState.Connected,
                rows = listOf(
                    forwarding(remotePort = 3_000, localPort = 3_001, process = "vite", bytes = 2_048),
                ),
            ),
        )

        composeRule.onNodeWithTag(portRowTag(3_000)).assertIsDisplayed()
        composeRule.onNodeWithText("3000").assertIsDisplayed()
        composeRule.onNodeWithText("3001").assertIsDisplayed()
        composeRule.onNodeWithText("vite").assertIsDisplayed()
        composeRule.onNodeWithText("Forwarding").assertIsDisplayed()
        composeRule.onNodeWithText("2.0 KB").assertIsDisplayed()
        composeRule.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun `a discovered but unforwarded row offers Start and hides its local port`() {
        setContent(
            state(
                enabled = true,
                connection = ConnectionState.Connected,
                rows = listOf(available(remotePort = 8_080)),
            ),
        )

        composeRule.onNodeWithText("Available").assertIsDisplayed()
        composeRule.onNodeWithText("Start").assertIsDisplayed()
        composeRule.onNodeWithText("8080").assertIsDisplayed()
        // Exactly two dashes: the local port (no socket is bound yet, so printing
        // one would be a lie) and the traffic cell (no bytes have flowed). Read on
        // the UNMERGED tree — the clickable row merges its children into one
        // semantics node, so the merged tree would report a single hit for both.
        assertEquals(
            2,
            composeRule.onAllNodesWithText("-", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `tapping a row's action reports that row's remote port`() {
        val toggled = mutableListOf<Int>()
        setContent(
            state(
                enabled = true,
                connection = ConnectionState.Connected,
                rows = listOf(available(8_080), available(9_000)),
            ),
            onTogglePort = { toggled += it },
        )

        composeRule.onNodeWithTag(portRowTag(9_000)).performClick()

        assertEquals(listOf(9_000), toggled)
    }

    @Test
    fun `the On segment turns forwarding on`() {
        val requested = mutableListOf<Boolean>()
        setContent(state(enabled = false), onSetEnabled = { requested += it })

        composeRule.onNodeWithTag("${FORWARDING_TOGGLE_TAG}_on").performClick()

        assertEquals(listOf(true), requested)
    }

    @Test
    fun `forwarding off says so instead of rendering an empty table`() {
        setContent(state(enabled = false))

        composeRule
            .onNodeWithText("Forwarding is off. Turn it on to discover listening ports.")
            .assertIsDisplayed()
    }

    @Test
    fun `an unreachable host is distinguishable from an empty one`() {
        setContent(state(enabled = true, connection = ConnectionState.Lost))

        composeRule
            .onNodeWithText("Forwarding paused. This host could not be reached.")
            .assertIsDisplayed()
    }

    @Test
    fun `a host that stopped retrying reads as needing attention, not reconnecting`() {
        // #2491: the terminal state has to be visibly distinct from
        // "Reconnecting" AND must not claim a retry is coming — nothing will
        // change until the user confirms the key.
        setContent(
            state(
                enabled = true,
                connection = ConnectionState.Lost,
                attention = ForwardingController.NEEDS_TRUST_ATTENTION,
            ),
        )

        composeRule
            .onNodeWithText(
                "Forwarding paused. ${ForwardingController.NEEDS_TRUST_ATTENTION}",
            )
            .assertIsDisplayed()
        // The reconnecting rendering (a spinner that says work is in flight)
        // must be absent — that is the state this one is distinguished from.
        composeRule.onNodeWithText("Scanning ports…").assertDoesNotExist()
        composeRule.onNodeWithText("Retrying…", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a reconnecting host still renders as work in flight`() {
        // The other half of the distinction: a transient failure keeps the
        // spinner and never shows the terminal message, so the two states can
        // never read the same.
        setContent(state(enabled = true, connection = ConnectionState.Reconnecting))

        composeRule.onNodeWithText("Scanning ports…").assertIsDisplayed()
        composeRule.onNodeWithText("Forwarding paused.", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the header state label separates a parked host from a reconnecting one`() {
        // The header falls back to the connection label when the host has no
        // subtitle. "Needs attention" is terminal wording; "Reconnecting" is not.
        setContent(
            state(enabled = true, connection = ConnectionState.Lost, hostSubtitle = ""),
        )
        composeRule.onNodeWithText("Needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Reconnecting").assertDoesNotExist()
    }

    @Test
    fun `the paused message falls back to a plain reason when none is known`() {
        assertEquals(
            "Forwarding paused. This host could not be reached.",
            pausedMessage(null),
        )
        assertEquals("Forwarding paused. Fix the key.", pausedMessage("Fix the key."))
    }

    @Test
    fun `a table emptied purely by the filter says the rows are hidden`() {
        setContent(
            state(enabled = true, connection = ConnectionState.Connected, rows = emptyList(), hiddenCount = 3),
        )

        composeRule.onNodeWithText("3 noisy ports hidden.").assertIsDisplayed()
        composeRule.onNodeWithTag(SHOW_ALL_PORTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Show hidden/noisy ports (3 hidden)").assertIsDisplayed()
    }

    @Test
    fun `the show-all checkbox reports the user's choice`() {
        val requested = mutableListOf<Boolean>()
        setContent(state(enabled = true, hiddenCount = 2), onSetShowAllPorts = { requested += it })

        composeRule.onNodeWithTag(SHOW_ALL_PORTS_TAG).performClick()

        assertEquals(listOf(true), requested)
    }

    @Test
    fun `two rows sharing a remote port render without a duplicate-key crash`() {
        // The old client crashed here (`Key "22" already used`): the same remote
        // port can legitimately appear twice — discovered on two interfaces, or a
        // forwarded row beside its still-AVAILABLE twin.
        val duplicated = listOf(
            forwarding(remotePort = 3_000, localPort = 3_000, process = "vite", bytes = 0),
            available(remotePort = 3_000),
        )
        assertEquals(
            "keys must be unique for LazyColumn",
            2,
            tunnelRowKeys(duplicated).toSet().size,
        )

        setContent(state(enabled = true, connection = ConnectionState.Connected, rows = duplicated))

        composeRule.onNodeWithTag(PORT_TABLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Forwarding").assertIsDisplayed()
        composeRule.onNodeWithText("Available").assertIsDisplayed()
    }

    /**
     * Issue #2532: Ports used the status dot as its only leading chrome, so
     * there was no on-screen way back to the tree. Back must exist, be the
     * word `Back` (not `‹`), and fire `onBack`.
     */
    @Test
    fun `tapping Back in the header fires onBack`() {
        var backs = 0
        setContent(state(enabled = false), onBack = { backs += 1 })

        composeRule.onNodeWithTag(PORT_FORWARD_BACK_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("‹").assertDoesNotExist()
        composeRule.onNodeWithTag(PORT_FORWARD_BACK_TAG).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `byte formatting steps through the units`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1_024))
        assertEquals("1.0 MB", formatBytes(1_024L * 1_024))
        assertEquals("1.0 GB", formatBytes(1_024L * 1_024 * 1_024))
    }

    @Test
    fun `the hidden-count label is only shown while rows are actually hidden`() {
        assertEquals("Show hidden/noisy ports", showAllPortsLabel(checked = true, hiddenCount = 4))
        assertEquals("Show hidden/noisy ports", showAllPortsLabel(checked = false, hiddenCount = 0))
        assertEquals(
            "Show hidden/noisy ports (4 hidden)",
            showAllPortsLabel(checked = false, hiddenCount = 4),
        )
        assertEquals("1 noisy port hidden.", hiddenPortsMessage(1))
        assertEquals("2 noisy ports hidden.", hiddenPortsMessage(2))
    }

    // ------------------------------------------------------------------ helpers

    private fun setContent(
        state: PortForwardUiState,
        onSetEnabled: (Boolean) -> Unit = {},
        onTogglePort: (Int) -> Unit = {},
        onSetShowAllPorts: (Boolean) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            PocketShellTheme {
                PortForwardScreen(
                    state = state,
                    onSetEnabled = onSetEnabled,
                    onTogglePort = onTogglePort,
                    onSetShowAllPorts = onSetShowAllPorts,
                    onBack = onBack,
                )
            }
        }
    }

    private fun state(
        enabled: Boolean = false,
        connection: ConnectionState = ConnectionState.Idle,
        attention: String? = null,
        rows: List<TunnelInfo> = emptyList(),
        hiddenCount: Int = 0,
        showAllPorts: Boolean = false,
        hostSubtitle: String = "alexey@rmthz:22",
    ) = PortForwardUiState(
        hostId = 1L,
        hostName = "rmthz",
        hostSubtitle = hostSubtitle,
        enabled = enabled,
        connection = connection,
        attention = attention,
        rows = rows,
        showAllPorts = showAllPorts,
        hiddenCount = hiddenCount,
        loading = false,
    )

    private fun forwarding(remotePort: Int, localPort: Int, process: String, bytes: Long) = TunnelInfo(
        remotePort = remotePort,
        localPort = localPort,
        process = process,
        status = TunnelInfo.Status.FORWARDING,
        bytesIn = bytes,
        bytesOut = 0,
        speedBps = 0,
    )

    private fun available(remotePort: Int) = TunnelInfo(
        remotePort = remotePort,
        localPort = remotePort,
        process = "sshd",
        status = TunnelInfo.Status.AVAILABLE,
    )
}
