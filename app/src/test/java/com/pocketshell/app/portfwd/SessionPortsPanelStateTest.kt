package com.pocketshell.app.portfwd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #2176: what the per-session Ports panel renders, and what each row's tap
 * will do.
 *
 * The forwarding half is driven through the REAL [ForwardingController] — the
 * same singleton the in-session forwarding pill and the host-wide panel read —
 * because acceptance criterion 7 is precisely that the panel and the indicator
 * cannot disagree. A hand-rolled fake snapshot would prove nothing about that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionPortsPanelStateTest {

    /**
     * [SessionPortsPanelViewModel.stateFor] hands its `stateIn` to
     * `viewModelScope`, i.e. `Dispatchers.Main`. Pin Main to the test scheduler
     * so the projection's owned background hop resolves under the test's clock
     * rather than racing a real dispatcher — the #708/#882/#1048 flake class.
     */
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun mention(port: Int, at: Long = port.toLong(), process: String = "node") =
        SessionPortMention(
            port = port,
            firstSeenAtEpochMs = at,
            process = process,
            matchedText = "Local: http://localhost:$port/",
        )

    /**
     * Acceptance criterion 5: a forwarded row knows the loopback port and the
     * URL to open, so the tap can go straight to the browser.
     */
    @Test
    fun `a forwarded row carries its loopback URL`() {
        val state = sessionPortsPanelState(
            mentions = listOf(mention(5173)),
            snapshot = ForwardingHostSnapshot(
                active = true,
                tunnelCount = 1,
                forwardedPortMap = mapOf(5173 to 18173),
            ),
        )

        val row = state.rows.single()
        assertTrue(row.forwarded)
        assertEquals(18173, row.localPort)
        assertEquals("18173", row.localLabel)
        assertEquals("Forwarding", row.statusLabel)
        assertEquals("http://127.0.0.1:18173", row.localUrl)
    }

    /**
     * Acceptance criterion 6's precondition: a not-forwarded row has NO URL, so
     * the UI cannot accidentally open a dead `localhost` page — it must go
     * through the confirm-then-forward path instead.
     */
    @Test
    fun `a not-forwarded row has no URL to open`() {
        val state = sessionPortsPanelState(
            mentions = listOf(mention(5173)),
            snapshot = ForwardingHostSnapshot(active = true, tunnelCount = 0),
        )

        val row = state.rows.single()
        assertFalse(row.forwarded)
        assertNull(row.localUrl)
        assertEquals("-", row.localLabel)
        assertEquals("Not forwarded", row.statusLabel)
    }

    /** A host with forwarding switched off entirely reads as not forwarded. */
    @Test
    fun `an inactive host snapshot forwards nothing`() {
        val state = sessionPortsPanelState(
            mentions = listOf(mention(5173)),
            snapshot = ForwardingHostSnapshot(
                active = false,
                tunnelCount = 1,
                forwardedPortMap = mapOf(5173 to 18173),
            ),
        )

        assertFalse("a stale map on an inactive host must not read as live", state.rows.single().forwarded)
    }

    @Test
    fun `a host with no snapshot at all forwards nothing`() {
        val state = sessionPortsPanelState(mentions = listOf(mention(5173)), snapshot = null)

        assertFalse(state.rows.single().forwarded)
    }

    /**
     * Acceptance criterion 8 (empty): an honest explanation, not a blank
     * surface. [SessionPortsPanelState.isEmpty] is what the panel branches on.
     */
    @Test
    fun `zero ports is an explicit empty state`() {
        val state = sessionPortsPanelState(mentions = emptyList(), snapshot = null)

        assertTrue(state.isEmpty)
        assertEquals(emptyList<SessionPortRow>(), state.rows)
    }

    /**
     * Acceptance criterion 8 (many) AND the explicit non-goal: a session's own
     * ports are shown REGARDLESS of [InterestingPortFilter]'s `3000..10000`
     * range. That filter exists to denoise a host-wide scan; attribution has
     * already solved that here, so hiding a port the user's own session printed
     * would be the feature failing at its one job.
     */
    @Test
    fun `shows every mentioned port including ones outside the interesting range`() {
        val ports = listOf(22, 80, 443, 3000, 5173, 8000, 9999, 18080, 54321)
        assertFalse(
            "the fixture must actually contain out-of-range ports or this proves nothing",
            ports.all { InterestingPortFilter.isVisibleByDefault(it) },
        )

        val state = sessionPortsPanelState(
            mentions = ports.map { mention(it) },
            snapshot = null,
        )

        assertEquals(ports, state.rows.map { it.port })
    }

    /** Rows read chronologically — the order the session announced them. */
    @Test
    fun `rows preserve the mention order`() {
        val state = sessionPortsPanelState(
            mentions = listOf(mention(8000, at = 10L), mention(5173, at = 20L)),
            snapshot = null,
        )

        assertEquals(listOf(8000, 5173), state.rows.map { it.port })
    }

    /**
     * Acceptance criterion 2, at the view-model seam: the panel for session B
     * renders session A's ports nowhere. Driven through the real store + view
     * model rather than the pure function, so a future "helpfully" merged
     * lookup would fail here.
     */
    @Test
    fun `the panel for session B never shows session A's ports`() = runTest {
        val store = SessionPortsStore()
        val controller = ForwardingController(context)
        val vm = SessionPortsPanelViewModel(store, controller)
        store.recordAndPersist(SessionPortsKey(1L, "alpha"), mention(5173))
        store.recordAndPersist(SessionPortsKey(1L, "beta"), mention(8000))

        val alpha = vm.stateFor(hostId = 1L, sessionName = "alpha").first()
        val beta = vm.stateFor(hostId = 1L, sessionName = "beta").first()

        assertEquals(listOf(5173), alpha.rows.map { it.port })
        assertEquals(listOf(8000), beta.rows.map { it.port })
    }

    /** Same session name on a different host is a different session. */
    @Test
    fun `two hosts with the same session name do not share ports`() = runTest {
        val store = SessionPortsStore()
        val vm = SessionPortsPanelViewModel(store, ForwardingController(context))
        store.recordAndPersist(SessionPortsKey(1L, "main"), mention(5173))

        val other = vm.stateFor(hostId = 2L, sessionName = "main").first()

        assertTrue(other.isEmpty)
    }

    /**
     * Acceptance criterion 7: forwarding started ELSEWHERE (the host-wide panel,
     * the notification, a terminal URL tap — all of which land on the same
     * controller) moves this panel. The row flips without the panel being
     * reopened, because it reads the controller's flow rather than a copy.
     */
    @Test
    fun `a forward started elsewhere flips the row live`() = runTest {
        val store = SessionPortsStore()
        val controller = ForwardingController(context)
        val vm = SessionPortsPanelViewModel(store, controller)
        store.recordAndPersist(SessionPortsKey(4L, "work"), mention(5173))
        val flow = vm.stateFor(hostId = 4L, sessionName = "work")

        assertFalse(flow.first().rows.single().forwarded)

        controller.registerActiveHost(hostId = 4L, hostName = "hetzner")
        controller.updateActiveTunnels(4L, mapOf(5173 to 18173))

        val forwarded = flow.first { it.rows.singleOrNull()?.forwarded == true }
        assertEquals(18173, forwarded.rows.single().localPort)
        assertEquals("http://127.0.0.1:18173", forwarded.rows.single().localUrl)
    }

    /** ...and stopping it elsewhere flips the row back. */
    @Test
    fun `a forward stopped elsewhere flips the row back`() = runTest {
        val store = SessionPortsStore()
        val controller = ForwardingController(context)
        val vm = SessionPortsPanelViewModel(store, controller)
        store.recordAndPersist(SessionPortsKey(5L, "work"), mention(5173))
        controller.registerActiveHost(hostId = 5L, hostName = "hetzner")
        controller.updateActiveTunnels(5L, mapOf(5173 to 18173))
        val flow = vm.stateFor(hostId = 5L, sessionName = "work")
        assertTrue(flow.first { it.rows.singleOrNull()?.forwarded == true }.rows.single().forwarded)

        controller.unregisterActiveHost(5L)

        val stopped = flow.first { it.rows.singleOrNull()?.forwarded == false }
        assertNull(stopped.rows.single().localUrl)
    }

    /**
     * Acceptance criterion 5: tapping an already-forwarded row opens
     * `http://localhost:<localPort>` directly — no confirm, no detour.
     */
    @Test
    fun `tapping a forwarded row opens its loopback URL directly`() {
        val state = sessionPortsPanelState(
            mentions = listOf(mention(5173)),
            snapshot = ForwardingHostSnapshot(
                active = true,
                tunnelCount = 1,
                forwardedPortMap = mapOf(5173 to 18173),
            ),
        )

        val action = sessionPortTapAction(state.rows.single())

        assertEquals(SessionPortTapAction.OpenLocalUrl("http://127.0.0.1:18173"), action)
    }

    /**
     * Acceptance criterion 6: tapping a NOT-forwarded row asks first. It must
     * NOT resolve to an open — opening a loopback URL with no tunnel behind it
     * just shows a connection error, and forwarding without asking opens a
     * network path the user never agreed to.
     */
    @Test
    fun `tapping a not-forwarded row asks before forwarding`() {
        val state = sessionPortsPanelState(
            mentions = listOf(mention(5173)),
            snapshot = null,
        )

        val action = sessionPortTapAction(state.rows.single())

        assertEquals(SessionPortTapAction.ConfirmForward(5173), action)
        assertFalse(
            "a not-forwarded tap must never resolve to an open",
            action is SessionPortTapAction.OpenLocalUrl,
        )
    }

    /**
     * ...and the moment the tunnel comes up, the SAME row's tap becomes a direct
     * open. This is the confirm → forward → open sequence's final step, and it
     * follows from the live forwarding state rather than from any latch the
     * panel keeps.
     */
    @Test
    fun `the same row switches from ask to open once its tunnel is up`() = runTest {
        val store = SessionPortsStore()
        val controller = ForwardingController(context)
        val vm = SessionPortsPanelViewModel(store, controller)
        store.recordAndPersist(SessionPortsKey(6L, "work"), mention(5173))
        val flow = vm.stateFor(hostId = 6L, sessionName = "work")
        assertEquals(
            SessionPortTapAction.ConfirmForward(5173),
            sessionPortTapAction(flow.first().rows.single()),
        )

        controller.registerActiveHost(hostId = 6L, hostName = "hetzner")
        controller.updateActiveTunnels(6L, mapOf(5173 to 18173))

        val forwarded = flow.first { it.rows.singleOrNull()?.forwarded == true }
        assertEquals(
            SessionPortTapAction.OpenLocalUrl("http://127.0.0.1:18173"),
            sessionPortTapAction(forwarded.rows.single()),
        )
    }

    // ------------------------------------------------------------------
    // Addendum (#2176): the forwarding pill is now the single chrome entry
    // point into ports, so the panel it opens has to carry the route onward
    // to the host-wide list the maintainer asked to browse manually.
    // ------------------------------------------------------------------

    /**
     * The footer names the same host-wide tunnel count the pill shows, so the
     * route to the manual hunt is obvious rather than hidden behind a generic
     * label.
     */
    @Test
    fun `the host-ports route names the same count the pill shows`() {
        val state = sessionPortsPanelState(
            mentions = listOf(mention(5173)),
            snapshot = ForwardingHostSnapshot(active = true, tunnelCount = 58),
        )

        assertEquals(58, state.hostTunnelCount)
        assertEquals("All host ports (58 forwarding)", state.hostPortsLabel)
    }

    @Test
    fun `the host-ports route reads naturally for one tunnel and for none`() {
        assertEquals(
            "All host ports (1 forwarding)",
            sessionPortsPanelState(
                mentions = emptyList(),
                snapshot = ForwardingHostSnapshot(active = true, tunnelCount = 1),
            ).hostPortsLabel,
        )
        assertEquals(
            "All host ports",
            sessionPortsPanelState(mentions = emptyList(), snapshot = null).hostPortsLabel,
        )
    }

    /**
     * Addendum criterion 3: while the host is RESTORING, the tunnel count is
     * transient. Quoting it would tell the user something that is about to be
     * false, so the label says what is actually happening — and the route stays
     * open either way, because that is the one thing the user might want most
     * during a reconnect.
     */
    @Test
    fun `a restoring host does not quote a transient tunnel count`() {
        val state = sessionPortsPanelState(
            mentions = listOf(mention(5173)),
            snapshot = ForwardingHostSnapshot(
                active = true,
                tunnelCount = 58,
                restoring = true,
            ),
        )

        assertTrue(state.hostRestoring)
        assertEquals("All host ports — reconnecting…", state.hostPortsLabel)
        assertFalse(
            "a restoring label must not quote the count: ${state.hostPortsLabel}",
            state.hostPortsLabel.contains("58"),
        )
    }

    /**
     * Addendum criterion 3, the other half: a restoring host must not empty the
     * session's rows. The mentions are durable and survive a transient reconnect,
     * so the panel opened from an amber pill still shows real content — it just
     * reports every row as not-forwarded, which is the truth mid-reconnect.
     */
    @Test
    fun `a restoring host keeps the session's rows and reports them not forwarded`() {
        val state = sessionPortsPanelState(
            mentions = listOf(mention(5173), mention(8000)),
            snapshot = ForwardingHostSnapshot(
                active = true,
                tunnelCount = 2,
                forwardedPortMap = emptyMap(),
                restoring = true,
            ),
        )

        assertFalse("the list must not go empty during a reconnect", state.isEmpty)
        assertEquals(listOf(5173, 8000), state.rows.map { it.port })
        assertTrue(state.rows.none { it.forwarded })
    }

    /**
     * Addendum criterion 2: the pill's description must now also convey that it
     * is actionable, while keeping the #1487 status wording it already had.
     */
    @Test
    fun `the forwarding pill is described as actionable without losing its status`() {
        val state = SessionForwardingIndicatorState(active = true, tunnelCount = 58)

        assertEquals(
            "58 ports forwarding active for this host",
            state.contentDescription,
        )
        assertEquals(
            "58 ports forwarding active for this host. Opens session ports",
            state.actionableContentDescription,
        )
    }

    @Test
    fun `the actionable description covers the restoring and single-tunnel wording too`() {
        assertEquals(
            "Port forwarding restoring for this host. Opens session ports",
            SessionForwardingIndicatorState(active = true, tunnelCount = 3, restoring = true)
                .actionableContentDescription,
        )
        assertEquals(
            "1 port forwarding active for this host. Opens session ports",
            SessionForwardingIndicatorState(active = true, tunnelCount = 1)
                .actionableContentDescription,
        )
    }

    /** The panel subtitle identifies which session's ports these are. */
    @Test
    fun `subtitle names the host and session`() {
        assertEquals("hetzner · work", sessionSubtitle("hetzner", "work"))
        assertEquals("work", sessionSubtitle("", "work"))
        assertEquals("hetzner", sessionSubtitle("hetzner", ""))
    }
}
