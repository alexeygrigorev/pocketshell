package com.pocketshell.app.sessions

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.uikit.model.TagKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        "$name::$activitySec::${if (attached) 1 else 0}"

    private fun register(
        registry: ActiveTmuxClients,
        host: HostEntity,
        keyPath: String,
        client: FakeTmuxClient,
    ) {
        registry.register(
            hostId = host.id,
            hostName = host.name,
            hostname = host.hostname,
            port = host.port,
            username = host.username,
            keyPath = keyPath,
            client = client,
        )
    }

    @Test
    fun sessionsStartEmpty() = runTest {
        val vm = newVm()
        assertTrue(vm.sessions.value.isEmpty())
    }

    @Test
    fun parseListSessionsRowExtractsAllFields() {
        val vm = newVm()
        val parsed = vm.parseListSessionsRow(
            line = "agent-main::1716300000::1",
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
    fun parseListSessionsRowAttachedFalseWhenZero() {
        val vm = newVm()
        val parsed = vm.parseListSessionsRow(
            line = "idle::100::0",
            hostId = 1L,
            hostName = "h",
        )
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
        assertNull(vm.parseListSessionsRow("name::notanumber::0", 1L, "h"))
        // Empty name.
        assertNull(vm.parseListSessionsRow("::100::0", 1L, "h"))
    }

    @Test
    fun parseListSessionsRowAttachedDefaultsFalseWhenFieldUnparseable() {
        val vm = newVm()
        val parsed = vm.parseListSessionsRow("s::100::garbage", 1L, "h")
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
        register(registry, host(1L, "h"), "/k", client)
        runCurrent()
        assertEquals(1, vm.sessions.value.size)
        val callsWhileRegistered = client.sentCommands.size

        registry.unregister(1L)
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
    fun listSessionsErrorLeavesPreviousSnapshotIntact() = runTest {
        val registry = newRegistry()
        val vm = newVm(registry = registry, pollMs = 50L)

        val client = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf(row("s1", activitySec = 100L)),
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
        // Error reply currently treated as "no sessions on this host" —
        // contract: per-host snapshot is overwritten only on a
        // successful (non-error) poll, so the previous snapshot
        // survives.
        assertEquals(
            "previous snapshot survives transient list-sessions error",
            1,
            vm.sessions.value.size,
        )
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
    fun formatRelativeTimeMatchesMockupCadence() {
        // <60s → "now"
        assertEquals("now", formatRelativeTime(nowSec = 100L, thenSec = 95L))
        // 2m
        assertEquals("2m", formatRelativeTime(nowSec = 200L, thenSec = 80L))
        // 14m
        assertEquals("14m", formatRelativeTime(nowSec = 14 * 60L + 5, thenSec = 5L))
        // 1h
        assertEquals("1h", formatRelativeTime(nowSec = 3_700L, thenSec = 0L))
        // 23h still hours
        assertEquals("23h", formatRelativeTime(nowSec = 23L * 3_600L, thenSec = 0L))
        // 25h → 1d
        assertEquals("1d", formatRelativeTime(nowSec = 25L * 3_600L, thenSec = 0L))
        // Clamped at zero — clock skew between the host and the device
        // should never produce a negative delta.
        assertEquals("now", formatRelativeTime(nowSec = 0L, thenSec = 100L))
    }

    @Test
    fun dashboardRowUiSurfacesAgentAndAttachedState() {
        val row = SessionSummary(
            hostId = 1L,
            hostName = "hetzner",
            sessionName = "codex",
            lastActivity = 100L,
            attached = true,
        ).dashboardRowUi()

        assertEquals("C", row.badge)
        assertEquals("codex conversation active", row.preview)
        assertEquals(listOf("codex", "attached"), row.tags.map { it.label })
        assertEquals(listOf(TagKind.Agent, TagKind.Default), row.tags.map { it.kind })
    }

    @Test
    fun dashboardRowUiSurfacesDomainHintsWithoutAgent() {
        val deploy = SessionSummary(
            hostId = 1L,
            hostName = "prod",
            sessionName = "deploy-watch",
            lastActivity = 100L,
            attached = false,
        ).dashboardRowUi()
        val training = SessionSummary(
            hostId = 2L,
            hostName = "gpu-box",
            sessionName = "training",
            lastActivity = 100L,
            attached = false,
        ).dashboardRowUi()

        assertEquals("tmux session idle", deploy.preview)
        assertEquals(listOf(TagKind.Deploy), deploy.tags.map { it.kind })
        assertEquals(listOf(TagKind.Ml), training.tags.map { it.kind })
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
            repeat(3) {
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
        runCurrent()

        assertTrue(client.sentCommands.contains("new-session -d -s 'next'"))
        assertTrue(client.sentCommands.contains("rename-session -t 'next' 'renamed'"))
        assertTrue(client.sentCommands.contains("kill-session -t 'renamed'"))
    }
}
