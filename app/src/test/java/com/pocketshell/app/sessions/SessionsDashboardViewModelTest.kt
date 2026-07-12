package com.pocketshell.app.sessions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.uikit.model.SessionAgentState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [SessionsDashboardViewModel] — issue #46.
 *
 * Drives a real [ActiveTmuxClients] singleton with caller-supplied
 * [FakeTmuxClient] instances whose `list-sessions` responses are queued
 * verbatim. The view model is created with a tight `pollIntervalMs` so
 * the `runTest` virtual clock can advance through several polls in
 * milliseconds.
 *
 * Robolectric is intentionally NOT needed here — the dashboard view
 * model does not touch Android framework types (no
 * `TerminalSurfaceState`, no `Handler`/`Looper`). A plain
 * `MainDispatcherRule` + `runTest` is enough.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsDashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels: MutableList<SessionsDashboardViewModel> = mutableListOf()

    @After
    fun tearDown() {
        createdViewModels.forEach { it.stopForTest() }
        createdViewModels.clear()
    }

    private fun newRegistry(): ActiveTmuxClients = ActiveTmuxClients()

    private fun newVm(
        registry: ActiveTmuxClients = newRegistry(),
        pollMs: Long = 50L,
    ): SessionsDashboardViewModel {
        val vm = SessionsDashboardViewModel(activeClients = registry)
        vm.pollIntervalMs = pollMs
        createdViewModels += vm
        return vm
    }

    /** Convenience constructor for a host row with sensible defaults. */
    private fun host(id: Long, name: String): HostEntity =
        HostEntity(
            id = id,
            name = name,
            hostname = "$name.example",
            port = 22,
            username = "alex",
            keyId = 1L,
        )

    /** Build a tmux `list-sessions` response line in the wire format. */
    private fun row(name: String, activitySec: Long, attached: Boolean = false): String =
        "$name::1::$activitySec::${if (attached) 1 else 0}"

    private fun register(
        registry: ActiveTmuxClients,
        host: HostEntity,
        keyPath: String,
        client: FakeTmuxClient,
        startDirectoryExists: (suspend (String) -> Boolean)? = null,
    ): ActiveTmuxClients.Registration =
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = keyPath,
            client = client,
            startDirectoryExists = startDirectoryExists,
        )

    @Test
    fun sessionsStartEmpty() = runTest {
        val vm = newVm()
        assertTrue(vm.sessions.value.isEmpty())
    }

    @Test
    fun parseListSessionsRowExtractsAllFields() {
        val vm = newVm()
        val parsed = vm.parseListSessionsRow(
            line = "agent-main::1716290000::1716300000::1",
            hostId = 7L,
            hostName = "hetzner",
        )
        assertEquals(7L, parsed?.hostId)
        assertEquals("hetzner", parsed?.hostName)
        assertEquals("agent-main", parsed?.sessionName)
        assertEquals(1716300000L, parsed?.lastActivity)
        assertEquals(true, parsed?.attached)
    }

    @Test
    fun parseListSessionsRowResolvesFreshAgentStateFromDashboardShape() {
        // Issue #1237: the dashboard shape carries @ps_agent_state (+ updated_at)
        // as the 5th/6th columns. A fresh idle/waiting resolves to its chip
        // state; activity is at-or-before the state write, so it is not stale.
        val vm = newVm()
        val idle = vm.parseListSessionsRow(
            line = "agent-idle::1::1000::1::idle::1000",
            hostId = 3L,
            hostName = "hetzner",
        )
        assertEquals(SessionAgentState.Idle, idle?.agentState)
        val waiting = vm.parseListSessionsRow(
            line = "agent-wait::1::990::1::waiting_for_input::1000",
            hostId = 3L,
            hostName = "hetzner",
        )
        assertEquals(SessionAgentState.WaitingForInput, waiting?.agentState)
    }

    @Test
    fun parseListSessionsRowDropsStaleAgentStateAndUnknownIsAbsent() {
        val vm = newVm()
        // Activity newer than the recorded idle → stale → Unknown (no chip).
        val stale = vm.parseListSessionsRow(
            line = "agent-idle::1::5000::1::idle::1000",
            hostId = 3L,
            hostName = "hetzner",
        )
        assertEquals(SessionAgentState.Unknown, stale?.agentState)
        // Absent state option (4-field legacy shape) → Unknown.
        val absent = vm.parseListSessionsRow(
            line = "agent-legacy::1::1000::1",
            hostId = 3L,
            hostName = "hetzner",
        )
        assertEquals(SessionAgentState.Unknown, absent?.agentState)
    }

    @Test
    fun parseListSessionsRowAttachedFalseWhenZero() {
        val vm = newVm()
        val parsed = vm.parseListSessionsRow(
            line = "idle::1::100::0",
            hostId = 1L,
            hostName = "h",
        )
        assertFalse(parsed!!.attached)
    }

    @Test
    fun parseListSessionsRowKeepsEscapedTabNameFromDoubleColonOutput() {
        val vm = newVm()
        val parsed = vm.parseListSessionsRow(
            line = """a\tb\tc\td::1780253919::1780253920::0""",
            hostId = 1L,
            hostName = "h",
        )

        assertEquals("""a\tb\tc\td""", parsed?.sessionName)
        assertEquals(1780253920L, parsed?.lastActivity)
        assertFalse(parsed!!.attached)
    }

    @Test
    fun parseListSessionsRowReturnsNullOnMalformedInput() {
        val vm = newVm()
        // Blank line.
        assertNull(vm.parseListSessionsRow("", 1L, "h"))
        // Missing fields.
        assertNull(vm.parseListSessionsRow("only-name", 1L, "h"))
        assertNull(vm.parseListSessionsRow("a::b", 1L, "h"))
        // Non-numeric activity.
        assertNull(vm.parseListSessionsRow("name::1::notanumber::0", 1L, "h"))
        // Empty name.
        assertNull(vm.parseListSessionsRow("::1::100::0", 1L, "h"))
    }

    @Test
    fun parseListSessionsRowAttachedDefaultsFalseWhenFieldUnparseable() {
        val vm = newVm()
        val parsed = vm.parseListSessionsRow("s::1::100::garbage", 1L, "h")
        // attached parses to 0 → false; the row stays parseable so
        // we don't lose visibility because of a tmux quirk.
        assertFalse(parsed!!.attached)
    }

    @Test
    fun aggregatesSessionsAcrossMultipleHosts() = runTest {
        val registry = newRegistry()
        val vm = newVm(registry = registry)

        val hostA = host(1L, "hetzner")
        val hostB = host(2L, "gpu-box")
        val clientA = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(
                        row("agent-main", activitySec = 2_000L, attached = true),
                        row("deploy-watch", activitySec = 1_500L),
                    ),
                    isError = false,
                ),
            )
        }
        val clientB = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(row("training", activitySec = 1_800L)),
                    isError = false,
                ),
            )
        }

        register(registry, hostA, "/keys/a", clientA)
        register(registry, hostB, "/keys/b", clientB)
        runCurrent()

        val sessions = vm.sessions.value
        assertEquals(3, sessions.size)
        // Sorted by lastActivity desc: 2000 > 1800 > 1500.
        assertEquals(listOf("agent-main", "training", "deploy-watch"), sessions.map { it.sessionName })
        // Host names are carried through verbatim for the UI suffix.
        assertEquals("hetzner", sessions[0].hostName)
        assertEquals("gpu-box", sessions[1].hostName)
        assertEquals("hetzner", sessions[2].hostName)
        // Attached flag survives the aggregation.
        assertEquals(true, sessions[0].attached)
        assertEquals(false, sessions[1].attached)
        vm.stopForTest()
    }

    @Test
    fun sortsByLastActivityDescending() = runTest {
        val vm = newVm()
        vm.applyHostSnapshotForTest(
            hostId = 1L,
            summaries = listOf(
                SessionSummary(1L, "h", "old", lastActivity = 100L, attached = false),
                SessionSummary(1L, "h", "new", lastActivity = 1000L, attached = false),
                SessionSummary(1L, "h", "mid", lastActivity = 500L, attached = false),
            ),
        )
        val names = vm.sessions.value.map { it.sessionName }
        assertEquals(listOf("new", "mid", "old"), names)
    }

    @Test
    fun tieBreakIsDeterministicByHostThenSessionName() = runTest {
        val vm = newVm()
        vm.applyHostSnapshotForTest(
            hostId = 1L,
            summaries = listOf(
                SessionSummary(1L, "bravo", "z-second", lastActivity = 500L, attached = false),
                SessionSummary(1L, "alpha", "a-first", lastActivity = 500L, attached = false),
                SessionSummary(1L, "alpha", "z-second", lastActivity = 500L, attached = false),
            ),
        )
        val rows = vm.sessions.value
        // Same activity → host name asc, then session name asc.
        assertEquals(listOf("alpha", "alpha", "bravo"), rows.map { it.hostName })
        assertEquals(listOf("a-first", "z-second", "z-second"), rows.map { it.sessionName })
    }

    @Test
    fun registeringHostStartsPolling() = runTest {
        val registry = newRegistry()
        val vm = newVm(registry = registry, pollMs = 30L)

        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(row("s1", activitySec = 100L)),
                    isError = false,
                ),
            )
        }
        register(registry, host(1L, "h"), "/k", client)
        runCurrent()

        assertTrue(
            "expected a list-sessions command on register, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("list-sessions") },
        )
        assertEquals(1, vm.sessions.value.size)
        vm.stopForTest()
    }

    @Test
    fun manualRefreshUpdatesExistingHostSnapshot() = runTest {
        val registry = newRegistry()
        val vm = newVm(registry = registry)

        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(row("s1", activitySec = 100L)),
                    isError = false,
                ),
            )
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf(row("s1", activitySec = 200L)),
                    isError = false,
                ),
            )
        }
        val h = host(1L, "h")
        register(registry, h, "/k", client)
        runCurrent()
        val entry = vm.entryFor(h.id)!!
        vm.cancelPollersForTest()

        vm.refreshEntryForTest(entry)
        assertEquals(200L, vm.sessions.value.single().lastActivity)
        vm.stopForTest()
    }

    @Test
    fun unregisteringHostCancelsPollerAndDropsItsSessions() = runTest {
        val registry = newRegistry()
        val vm = newVm(registry = registry, pollMs = 50L)

        val client = FakeTmuxClient().apply {
            // Keep handing back the same response on every poll.
            repeat(8) {
                responses.addLast(
                    CommandResponse(
                        number = 0L,
                        output = listOf(row("s1", activitySec = 100L)),
                        isError = false,
                    ),
                )
            }
        }
        val registration = register(registry, host(1L, "h"), "/k", client)
        runCurrent()
        assertEquals(1, vm.sessions.value.size)
        val callsWhileRegistered = client.sentCommands.size

        registry.unregister(registration)
        runCurrent()
        // Snapshot empty → aggregate is empty.
        assertTrue(vm.sessions.value.isEmpty())

        assertEquals(
            "no further list-sessions after unregister",
            callsWhileRegistered,
            client.sentCommands.size,
        )
        vm.stopForTest()
    }

    @Test
    fun listSessionsErrorMarksPreviousSnapshotStale() = runTest {
        val registry = newRegistry()
        val vm = newVm(registry = registry, pollMs = 50L)

        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(row("s1", activitySec = 100L, attached = true)),
                    isError = false,
                ),
            )
            // Subsequent polls return error.
            repeat(4) {
                responses.addLast(
                    CommandResponse(
                        number = 0L,
                        output = listOf("server shutting down"),
                        isError = true,
                    ),
                )
            }
        }
        register(registry, host(1L, "h"), "/k", client)
        runCurrent()
        // Snapshot intact after the first ok poll.
        assertEquals(1, vm.sessions.value.size)

        val entry = vm.entryFor(1L)!!
        vm.cancelPollersForTest()
        vm.refreshEntryForTest(entry)
        val stale = vm.sessions.value.single()
        assertEquals("s1", stale.sessionName)
        assertTrue("previous snapshot survives transient list-sessions error as stale", stale.stale)
        assertFalse("stale snapshot must not retain live attached hint", stale.attached)
        vm.stopForTest()
    }

    @Test
    fun entryForReturnsRegisteredEntry() = runTest {
        val registry = newRegistry()
        val vm = newVm(registry = registry)

        val h = host(42L, "kappa")
        val c = FakeTmuxClient()
        register(registry, h, "/k", c)
        runCurrent()

        val entry = vm.entryFor(42L)
        assertEquals(h.id, entry?.hostId)
        assertEquals(h.name, entry?.hostName)
        assertEquals(h.hostname, entry?.hostname)
        assertEquals(h.port, entry?.port)
        assertEquals(h.username, entry?.username)
        assertEquals("/k", entry?.keyPath)
        assertSame(c, entry?.client)
        vm.stopForTest()
    }

    @Test
    fun entryForReturnsNullForUnknownHost() = runTest {
        val vm = newVm()
        assertNull(vm.entryFor(999L))
    }

    @Test
    fun fetchSessionsForTestReturnsParsedRows() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(
                        row("a", activitySec = 10L),
                        row("b", activitySec = 20L, attached = true),
                    ),
                    isError = false,
                ),
            )
        }
        val h = host(5L, "five")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )
        val rows = vm.fetchSessionsForTest(entry)
        assertEquals(2, rows.size)
        assertEquals("a", rows[0].sessionName)
        assertEquals("b", rows[1].sessionName)
        assertEquals(true, rows[1].attached)
        assertEquals(5L, rows[0].hostId)
        assertEquals("five", rows[0].hostName)
    }

    @Test
    fun lifecycleActionsIssueTmuxCommandsAndRefresh() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            repeat(4) {
                responses.addLast(
                    CommandResponse(
                        number = it.toLong(),
                        output = emptyList(),
                        isError = false,
                    ),
                )
            }
        }
        val h = host(9L, "nine")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )

        vm.createSession(entry, "next")
        vm.renameSession(entry, "next", "renamed")
        vm.killSession(entry, "renamed")
        // Issue #168: killSession now waits up to 2s for tmux's
        // %sessions-changed event before refreshing. Emit it
        // synchronously so the kill flow completes without burning the
        // full 2s fallback timeout.
        runCurrent()
        client.emittedEvents.emit(ControlEvent.SessionsChanged)
        advanceUntilIdle()

        assertTrue(client.sentCommands.contains("new-session -d -s 'next' -c '~'"))
        assertTrue(client.sentCommands.contains("rename-session -t 'next' 'renamed'"))
        assertTrue(client.sentCommands.contains("kill-session -t 'renamed'"))
        assertNull(
            "successful kill should not produce a killError banner",
            vm.killError.value,
        )
    }

    /**
     * Issue #1496: every dashboard session-management round-trip (Create,
     * Rename, Kill, and the live `list-sessions` poll) must ride the DEDICATED
     * exec lane ([TmuxClient.sendLifecycleViaExec]) — NOT the shared `-CC`
     * [TmuxClient.sendCommand] a live Codex `%output` burst can
     * head-of-line-block for 30-40s. Proven by asserting each command lands in
     * [FakeTmuxClient.sendLifecycleViaExecCalls] (the exec lane), and that the
     * raw `-CC` send-lane (bare [FakeTmuxClient.sendCommand]) was never used for
     * any of them.
     */
    @Test
    fun dashboardLifecycleAndPollRideTheExecLaneNotTheCcSendCommand() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            // new-session, rename-session, kill-session, then the refresh
            // list-sessions each pop one empty success.
            repeat(6) {
                responses.addLast(
                    CommandResponse(number = it.toLong(), output = emptyList(), isError = false),
                )
            }
        }
        val h = host(31L, "thirtyone")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )

        vm.createSession(entry, "next")
        vm.renameSession(entry, "next", "renamed")
        vm.killSession(entry, "renamed")
        runCurrent()
        client.emittedEvents.emit(ControlEvent.SessionsChanged)
        advanceUntilIdle()
        // The live poll seam runs the same fetch the poll loop uses.
        vm.fetchSessionsForTest(entry)

        assertTrue(
            "Create must ride the exec lane; calls=${client.sendLifecycleViaExecCalls}",
            client.sendLifecycleViaExecCalls.any { it == "new-session -d -s 'next' -c '~'" },
        )
        assertTrue(
            "Rename must ride the exec lane; calls=${client.sendLifecycleViaExecCalls}",
            client.sendLifecycleViaExecCalls.any { it == "rename-session -t 'next' 'renamed'" },
        )
        assertTrue(
            "Kill must ride the exec lane; calls=${client.sendLifecycleViaExecCalls}",
            client.sendLifecycleViaExecCalls.any { it == "kill-session -t 'renamed'" },
        )
        assertTrue(
            "the list-sessions poll must ride the exec lane; calls=${client.sendLifecycleViaExecCalls}",
            client.sendLifecycleViaExecCalls.any { it.startsWith("list-sessions") },
        )
    }

    /**
     * Issue #168 — silent kill failures (failure mode 1 from the issue
     * body) must now surface as a [killError] banner AND must not run
     * the refresh, because a refresh after a failed kill would show the
     * still-alive row and reproduce the exact symptom the issue
     * reports.
     */
    @Test
    fun killSessionTransportFailureSurfacesErrorAndSkipsRefresh() = runTest {
        val vm = newVm()
        val client = ThrowingTmuxClient(
            throwOnCommand = "kill-session",
            exception = IllegalStateException("ssh channel closed"),
        )
        val h = host(11L, "elem")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )

        vm.killSession(entry, "doomed")
        advanceUntilIdle()

        assertTrue(
            "expected the kill command to be attempted, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("kill-session") },
        )
        // The refresh would issue a list-sessions if we hadn't skipped
        // it; we deliberately did NOT queue a list-sessions response
        // because the kill failure path must not call it.
        assertFalse(
            "transport failure must skip the refresh — refresh would issue list-sessions",
            client.sentCommands.any { it.startsWith("list-sessions") },
        )
        val msg = vm.killError.value
        assertNotNull("expected user-facing killError after transport failure", msg)
        assertTrue(
            "killError should mention the session name; got '$msg'",
            msg!!.contains("doomed"),
        )
    }

    /**
     * Issue #168 — a tmux `%error` response (e.g. `can't find session`)
     * is also surfaced rather than silently swallowed. Mirrors the
     * transport-failure path: error visible, refresh skipped.
     */
    @Test
    fun killSessionTmuxErrorResponseSurfacesErrorAndSkipsRefresh() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf("can't find session: ghost"),
                    isError = true,
                ),
            )
        }
        val h = host(12L, "twelve")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )

        vm.killSession(entry, "ghost")
        advanceUntilIdle()

        assertFalse(
            "tmux %error must skip the refresh",
            client.sentCommands.any { it.startsWith("list-sessions") },
        )
        val msg = vm.killError.value
        assertNotNull("expected killError on tmux %error response", msg)
        assertTrue(
            "killError should include the tmux error detail; got '$msg'",
            msg!!.contains("ghost"),
        )
    }

    /**
     * Issue #168 — happy path: after the kill is acknowledged on the
     * wire, the dashboard must wait for tmux's `%sessions-changed`
     * notification (not blindly issue list-sessions before tmux has
     * finished tearing the session down) and then refresh.
     */
    @Test
    fun killSessionSuccessWaitsForSessionsChangedEventBeforeRefresh() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            // Response for the kill itself.
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = emptyList(),
                    isError = false,
                ),
            )
            // Response for the post-kill refresh — empty list (the
            // session is gone, no rows left).
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = emptyList(),
                    isError = false,
                ),
            )
        }
        val h = host(13L, "thirteen")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )
        // Seed a snapshot so we can prove it's wiped on refresh.
        vm.applyHostSnapshotForTest(
            hostId = h.id,
            summaries = listOf(
                SessionSummary(h.id, h.name, "stale", lastActivity = 100L, attached = false),
            ),
        )
        assertEquals(1, vm.sessions.value.size)

        vm.killSession(entry, "stale")
        runCurrent()
        // Before the event arrives the refresh has NOT happened yet:
        // only the kill command should be on the wire.
        assertEquals(
            "only the kill command should be in flight before %sessions-changed",
            listOf("kill-session -t 'stale'"),
            client.sentCommands,
        )
        // tmux finishes the teardown and emits the deterministic event.
        client.emittedEvents.emit(ControlEvent.SessionsChanged)
        advanceUntilIdle()

        // Now the refresh has fired and the empty list-sessions reply
        // has wiped the per-host snapshot.
        assertTrue(
            "refresh should have issued list-sessions after %sessions-changed",
            client.sentCommands.any { it.startsWith("list-sessions") },
        )
        assertTrue(
            "expected the killed session to disappear from the aggregate list",
            vm.sessions.value.isEmpty(),
        )
        assertNull("happy path should not surface a killError", vm.killError.value)
    }

    /**
     * Issue #168 — if tmux never emits `%sessions-changed` (degenerate
     * server, dropped event after reconnect, etc.) the dashboard must
     * still refresh after the 2s fallback so the row eventually
     * disappears. Keeps the worst-case latency bounded.
     */
    @Test
    fun killSessionFallsBackToRefreshAfterTimeoutWithoutEvent() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = emptyList(),
                    isError = false,
                ),
            )
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = emptyList(),
                    isError = false,
                ),
            )
        }
        val h = host(14L, "fourteen")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )

        vm.killSession(entry, "lonely")
        runCurrent()
        // Half-way through the timeout: refresh must still be pending,
        // we deliberately never emit %sessions-changed.
        advanceTimeBy(1_500)
        runCurrent()
        assertFalse(
            "refresh must not fire while the event timeout is still pending",
            client.sentCommands.any { it.startsWith("list-sessions") },
        )
        // Past the 2s fallback the refresh kicks regardless, so the
        // dashboard stays consistent in the pathological case.
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(
            "refresh must fire after the event-wait fallback expires",
            client.sentCommands.any { it.startsWith("list-sessions") },
        )
    }

    @Test
    fun clearKillErrorResetsTheStateFlow() = runTest {
        val vm = newVm()
        val client = ThrowingTmuxClient(
            throwOnCommand = "kill-session",
            exception = RuntimeException("boom"),
        )
        val h = host(15L, "fifteen")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )
        vm.killSession(entry, "x")
        advanceUntilIdle()
        assertNotNull(vm.killError.value)
        vm.clearKillError()
        assertNull(vm.killError.value)
    }

    /**
     * Issue #204 — happy path for `+ New session`. The view model
     * issues `new-session -d -s <name> -c <cwd>`, then on success
     * refreshes the per-host snapshot so the new row appears within the
     * 2s acceptance budget (much faster than the 10s poll cadence
     * elsewhere in this file would imply).
     */
    @Test
    fun createSessionSendsDetachedNewSessionAndRefreshes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            // Response for the new-session command itself.
            responses.addLast(
                CommandResponse(number = 0L, output = emptyList(), isError = false),
            )
            // Response for the post-create refresh — the new row plus a
            // stable peer so we can assert the snapshot update.
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf(
                        row("fresh", activitySec = 200L),
                        row("existing", activitySec = 100L),
                    ),
                    isError = false,
                ),
            )
        }
        val h = host(20L, "twenty")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )
        vm.applyHostSnapshotForTest(
            hostId = h.id,
            summaries = listOf(
                SessionSummary(h.id, h.name, "existing", lastActivity = 100L, attached = false),
            ),
        )

        vm.createSession(entry, "fresh", startDirectory = "/srv/app")
        advanceUntilIdle()

        assertTrue(
            "expected the detached new-session command, got ${client.sentCommands}",
            client.sentCommands.contains("new-session -d -s 'fresh' -c '/srv/app'"),
        )
        // The refresh must have fired: list-sessions came after the
        // new-session command.
        assertTrue(
            "expected list-sessions to follow new-session",
            client.sentCommands.any { it.startsWith("list-sessions") },
        )
        // The post-refresh aggregate now includes the freshly created
        // row, sorted by activity desc.
        assertEquals(
            listOf("fresh", "existing"),
            vm.sessions.value.map { it.sessionName },
        )
        // Happy path stays silent — no banner.
        assertNull(
            "successful create should not produce a createError banner",
            vm.createError.value,
        )
    }

    /**
     * Issue #296 — the dashboard must reject a missing start folder
     * before sending `new-session -c <dir>`. tmux can otherwise create
     * the session in `$HOME`, making the requested folder silently lie.
     */
    @Test
    fun createSessionRejectsMissingStartDirectoryBeforeNewSession() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        val h = host(24L, "twentyfour")
        val checkedDirectories = mutableListOf<String>()
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
            startDirectoryExists = { directory ->
                checkedDirectories += directory
                false
            },
        )

        vm.createSession(entry, "", startDirectory = "/srv/missing")
        advanceUntilIdle()

        assertEquals(listOf("/srv/missing"), checkedDirectories)
        assertFalse(
            "missing start folder must not reach tmux new-session; got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("new-session") },
        )
        assertFalse(
            "missing start folder must skip refresh; got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("list-sessions") },
        )
        val msg = vm.createError.value
        assertNotNull("expected createError for missing start folder", msg)
        assertTrue(
            "createError should name the requested folder; got '$msg'",
            msg!!.contains("/srv/missing"),
        )
        assertTrue(
            "blank name should derive the session name from the requested folder; got '$msg'",
            msg.contains("missing"),
        )
    }

    /**
     * Issue #204 — a duplicate-name (or any other) tmux `%error`
     * response surfaces a [createError] banner and SKIPS the refresh.
     * Skipping the refresh matches the kill-error path's rationale: an
     * unconditional refresh after a failed create would mask the
     * failure mode (e.g. "the duplicate-name row still exists" would
     * make it look like the create silently succeeded).
     */
    @Test
    fun createSessionTmuxErrorSurfacesErrorAndSkipsRefresh() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf("duplicate session: fresh"),
                    isError = true,
                ),
            )
        }
        val h = host(21L, "twentyone")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )

        vm.createSession(entry, "fresh")
        advanceUntilIdle()

        assertTrue(
            "expected the new-session command to be attempted, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("new-session") },
        )
        assertFalse(
            "tmux %error must skip the refresh",
            client.sentCommands.any { it.startsWith("list-sessions") },
        )
        val msg = vm.createError.value
        assertNotNull("expected createError on tmux %error response", msg)
        assertTrue(
            "createError should include the resolved session name; got '$msg'",
            msg!!.contains("fresh"),
        )
        assertTrue(
            "createError should surface the tmux detail; got '$msg'",
            msg.contains("duplicate"),
        )
    }

    /**
     * Issue #204 — transport-level failures (SSH channel dropped) also
     * surface a banner and skip the refresh.
     */
    @Test
    fun createSessionTransportFailureSurfacesErrorAndSkipsRefresh() = runTest {
        val vm = newVm()
        val client = ThrowingTmuxClient(
            throwOnCommand = "new-session",
            exception = IllegalStateException("ssh channel closed"),
        )
        val h = host(22L, "twentytwo")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )

        vm.createSession(entry, "doomed")
        advanceUntilIdle()

        assertTrue(
            "expected the new-session command to be attempted",
            client.sentCommands.any { it.startsWith("new-session") },
        )
        assertFalse(
            "transport failure must skip the refresh",
            client.sentCommands.any { it.startsWith("list-sessions") },
        )
        val msg = vm.createError.value
        assertNotNull("expected createError after transport failure", msg)
        assertTrue(
            "createError should mention the session name; got '$msg'",
            msg!!.contains("doomed"),
        )
    }

    /**
     * Issue #204 — the banner is one-shot; the user dismisses it and
     * the StateFlow flips back to null so a subsequent failure can
     * surface a fresh banner.
     */
    @Test
    fun clearCreateErrorResetsTheStateFlow() = runTest {
        val vm = newVm()
        val client = ThrowingTmuxClient(
            throwOnCommand = "new-session",
            exception = RuntimeException("boom"),
        )
        val h = host(23L, "twentythree")
        val entry = ActiveTmuxClients.Entry(
            hostId = h.id,
            hostName = h.name,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            keyPath = "/k",
            client = client,
        )
        vm.createSession(entry, "x")
        advanceUntilIdle()
        assertNotNull(vm.createError.value)
        vm.clearCreateError()
        assertNull(vm.createError.value)
    }

    /**
     * Issue #1164 (battery/heat) — load-bearing battery property, driven
     * through the REAL [SessionsDashboardViewModel.observeProcessLifecycle]
     * path: while the whole process is backgrounded (`ON_STOP`) the
     * `list-sessions` poll MUST stop firing SSH round-trips, and it MUST
     * resume promptly on `ON_START`.
     *
     * Reproduces the pre-fix gap: without the foreground gate, the poller
     * keeps issuing `list-sessions` on cadence forever while the app is
     * backgrounded / screen off — the audit's "clearest small win". The
     * red case (fix reverted) fails the "no polls while backgrounded"
     * assertEquals below; the fix parks the loop so it holds.
     */
    @Test
    fun pollParksWhenBackgroundedAndResumesPromptlyOnForeground() = runTest {
        val registry = newRegistry()
        val vm = newVm(registry = registry, pollMs = 50L)

        // FakeTmuxClient with no queued responses returns an empty success
        // per poll, so every poll still records a `list-sessions` command.
        val client = FakeTmuxClient()
        val owner = ManualLifecycleOwner().also { it.moveTo(Lifecycle.State.STARTED) }
        vm.observeProcessLifecycle(owner)
        runCurrent()

        register(registry, host(1L, "h"), "/k", client)
        runCurrent()

        fun listSessionsPolls() = client.sentCommands.count { it.startsWith("list-sessions") }

        // Foreground: the immediate first poll fired and the cadence keeps ticking.
        val afterRegister = listSessionsPolls()
        assertTrue("foreground poll must fire on register", afterRegister >= 1)
        advanceTimeBy(50L * 3)
        runCurrent()
        val foregroundPolls = listSessionsPolls()
        assertTrue(
            "foreground poll must keep ticking on cadence (was $afterRegister, now $foregroundPolls)",
            foregroundPolls > afterRegister,
        )

        // Background (ON_STOP): the poller parks.
        owner.moveTo(Lifecycle.State.CREATED) // dispatches ON_STOP
        runCurrent()
        // Flush any in-flight cadence delay so the loop reaches its park point.
        advanceTimeBy(50L)
        runCurrent()
        val atBackground = listSessionsPolls()

        // Advance well past many cadences while backgrounded — the
        // load-bearing battery assertion: ZERO further polls.
        advanceTimeBy(50L * 20)
        runCurrent()
        assertEquals(
            "backgrounded dashboard must issue NO further list-sessions polls (battery #1164)",
            atBackground,
            listSessionsPolls(),
        )

        // Foreground again (ON_START): poll resumes with a prompt refresh.
        owner.moveTo(Lifecycle.State.STARTED) // dispatches ON_START
        runCurrent()
        assertTrue(
            "returning to foreground must promptly resume the poll (was $atBackground)",
            listSessionsPolls() > atBackground,
        )
        vm.stopForTest()
    }

    /**
     * Issue #1164 — focused proof of the poll gate itself, isolated from
     * the lifecycle plumbing via [SessionsDashboardViewModel.setProcessStartedForTest].
     * A backgrounded process (flag false) must never issue a
     * `list-sessions`, even across many cadences; flipping to foreground
     * fires the parked poll immediately.
     */
    @Test
    fun pollIsSuppressedWhileProcessBackgroundedAndFiresOnForeground() = runTest {
        val registry = newRegistry()
        val vm = newVm(registry = registry, pollMs = 50L)
        // Enter the backgrounded state BEFORE the host registers.
        vm.setProcessStartedForTest(false)
        val client = FakeTmuxClient()
        register(registry, host(1L, "h"), "/k", client)
        runCurrent()
        advanceTimeBy(50L * 10)
        runCurrent()
        assertEquals(
            "no list-sessions may fire while the process is backgrounded",
            0,
            client.sentCommands.count { it.startsWith("list-sessions") },
        )

        // Flip to foreground — the parked poller fires promptly.
        vm.setProcessStartedForTest(true)
        runCurrent()
        assertTrue(
            "the poll must fire promptly once the process is foreground",
            client.sentCommands.any { it.startsWith("list-sessions") },
        )
        vm.stopForTest()
    }

    /**
     * Manual [LifecycleOwner] backed by [LifecycleRegistry.createUnsafe] so
     * the registry does NOT enforce the main thread — this test class runs
     * without Robolectric (no real main looper), and `createUnsafe` lets
     * `addObserver` / `currentState =` dispatch synchronously on the test
     * thread. Tests drive transitions via [moveTo]; the dashboard's
     * lifecycle observer then fires deterministically.
     */
    private class ManualLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.INITIALIZED
        }
        override val lifecycle: Lifecycle = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    /**
     * Test double that throws on a chosen command — exercises the
     * "sendCommand throws" branch of [SessionsDashboardViewModel.killSession]
     * without touching SSH.
     */
    private class ThrowingTmuxClient(
        private val throwOnCommand: String,
        private val exception: Throwable,
    ) : com.pocketshell.core.tmux.TmuxClient {
        private val delegate = FakeTmuxClient()
        override val events = delegate.events
        // Issue #173 (gap fix to keep unit tests compiling under
        // #145 round-3): `TmuxClient` gained a `disconnected:
        // StateFlow<Boolean>` member that this hand-rolled test fake
        // never started overriding. Delegating to the underlying
        // [FakeTmuxClient] keeps the throwing semantics intact while
        // satisfying the interface contract. Tracked separately from
        // #145 — this fake is unrelated to the disconnect monitor work.
        override val disconnected = delegate.disconnected
        override val disconnectEvent = delegate.disconnectEvent
        override val outputBacklogOverflows = delegate.outputBacklogOverflows
        val sentCommands: MutableList<String> get() = delegate.sentCommands
        override suspend fun connect() = delegate.connect()
        override suspend fun sendCommand(cmd: String): CommandResponse {
            delegate.sentCommands += cmd
            if (cmd.startsWith(throwOnCommand)) throw exception
            return CommandResponse(0L, emptyList(), false)
        }
        override fun outputFor(paneId: String) = delegate.outputFor(paneId)
        override fun drainPaneOutputBacklog(paneId: String) = delegate.drainPaneOutputBacklog(paneId)
        override fun close() = delegate.close()
        // Issue #215: detachCleanly added to the interface; the
        // throwing fake delegates to FakeTmuxClient so the killSession
        // tests don't have to reason about the new method's
        // semantics — they only care that sendCommand throws on the
        // configured command name.
        override suspend fun detachCleanly(timeoutMs: Long) =
            delegate.detachCleanly(timeoutMs)
        override suspend fun setWindowSizeLatest(sessionId: String) =
            delegate.setWindowSizeLatest(sessionId)
        override suspend fun refreshClientSize(cols: Int, rows: Int) =
            delegate.refreshClientSize(cols, rows)
    }
}
