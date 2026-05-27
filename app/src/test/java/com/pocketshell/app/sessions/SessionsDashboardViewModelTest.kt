package com.pocketshell.app.sessions

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.uikit.model.TagKind
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
        // Per issue #202 the labels are now mixed-case ("Codex" rather
        // than "codex") and the activity-state has its own slot
        // (TagKind.Attached / TagKind.Detached) instead of riding on
        // TagKind.Default. A first-time user can read "Codex" /
        // "Attached" without consulting the legend.
        val row = SessionSummary(
            hostId = 1L,
            hostName = "hetzner",
            sessionName = "codex",
            lastActivity = 100L,
            attached = true,
        ).dashboardRowUi()

        assertEquals("C", row.badge)
        assertEquals("codex conversation active", row.preview)
        assertEquals(listOf("Codex", "Attached"), row.tags.map { it.label })
        assertEquals(listOf(TagKind.Agent, TagKind.Attached), row.tags.map { it.kind })
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

        // Per issue #202: "Detached" replaces the ambiguous "idle"
        // word (consistent with #201 removing "idle" from host-card
        // vocabulary). The preview prose updates in lockstep so the
        // synthetic preview never contradicts the chip label.
        assertEquals("tmux session detached", deploy.preview)
        assertEquals(
            listOf(TagKind.Deploy, TagKind.Detached),
            deploy.tags.map { it.kind },
        )
        assertEquals(listOf("Deploy", "Detached"), deploy.tags.map { it.label })
        assertEquals(
            listOf(TagKind.Ml, TagKind.Detached),
            training.tags.map { it.kind },
        )
        assertEquals(listOf("ML", "Detached"), training.tags.map { it.label })
    }

    @Test
    fun dashboardRowUiAlwaysSurfacesActivityState() {
        // Issue #202 acceptance: a first-time user can interpret every
        // session-row indicator without external help. That requires
        // the activity-state to always be present so the user never
        // sees a row with no state context.
        val bare = SessionSummary(
            hostId = 1L,
            hostName = "h",
            sessionName = "scratch",
            lastActivity = 100L,
            attached = false,
        ).dashboardRowUi()
        assertEquals(listOf("Detached"), bare.tags.map { it.label })
        assertEquals(listOf(TagKind.Detached), bare.tags.map { it.kind })

        val bareAttached = SessionSummary(
            hostId = 1L,
            hostName = "h",
            sessionName = "scratch",
            lastActivity = 100L,
            attached = true,
        ).dashboardRowUi()
        assertEquals(listOf("Attached"), bareAttached.tags.map { it.label })
        assertEquals(listOf(TagKind.Attached), bareAttached.tags.map { it.kind })
    }

    @Test
    fun dashboardRowUiClaudeAndOpenCodeUseProperBrandLabels() {
        // Issue #202: labels match the docs/agent-awareness.md
        // canonical brand strings ("Claude" / "OpenCode") rather than
        // the previous lower-case "claude code" / "opencode" smush.
        val claude = SessionSummary(
            hostId = 1L,
            hostName = "h",
            sessionName = "claude-deploy",
            lastActivity = 100L,
            attached = false,
        ).dashboardRowUi()
        // "claude-deploy" matches both the agent heuristic AND the
        // deploy heuristic. We keep both classifier chips so the user
        // sees what the session is and what it's for; activity-state
        // trails. Order matters for legend consistency: classifier
        // chips first, activity-state last.
        assertEquals(
            listOf("Claude", "Deploy", "Detached"),
            claude.tags.map { it.label },
        )

        val open = SessionSummary(
            hostId = 1L,
            hostName = "h",
            sessionName = "opencode-1",
            lastActivity = 100L,
            attached = true,
        ).dashboardRowUi()
        assertEquals(
            listOf("OpenCode", "Attached"),
            open.tags.map { it.label },
        )
    }

    @Test
    fun dashboardRowUiAndLegendStayInSync() {
        // Issue #202: the legend (rendered in the Sessions section) is
        // the user-facing decoder for every emittable Tag kind. If
        // dashboardRowUi() can emit a kind that's missing from
        // SESSIONS_LEGEND_ENTRIES, the user sees an undecodable chip.
        val emittableKinds: Set<TagKind> = buildSet {
            // Walk the full set of names that the heuristic in
            // dashboardRowUi() recognises so the test fails loud if a
            // new classifier is added without a matching legend entry.
            listOf(
                "claude-main",
                "codex-1",
                "opencode-1",
                "agent-poc",
                "deploy-watch",
                "prod-shell",
                "training",
                "gpu-bench",
                "ml-eval",
                "scratch",
            ).forEach { name ->
                addAll(
                    SessionSummary(
                        hostId = 1L,
                        hostName = "h",
                        sessionName = name,
                        lastActivity = 100L,
                        attached = false,
                    ).dashboardRowUi().tags.map { it.kind },
                )
                addAll(
                    SessionSummary(
                        hostId = 1L,
                        hostName = "h",
                        sessionName = name,
                        lastActivity = 100L,
                        attached = true,
                    ).dashboardRowUi().tags.map { it.kind },
                )
            }
        }
        val documentedKinds = SESSIONS_LEGEND_ENTRIES.map { it.kind }.toSet()
        val undocumented = emittableKinds - documentedKinds
        assertTrue(
            "Every TagKind emittable by dashboardRowUi() must have a " +
                "matching legend entry. Missing: $undocumented",
            undocumented.isEmpty(),
        )
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
        val sentCommands: MutableList<String> get() = delegate.sentCommands
        override suspend fun connect() = delegate.connect()
        override suspend fun sendCommand(cmd: String): CommandResponse {
            delegate.sentCommands += cmd
            if (cmd.startsWith(throwOnCommand)) throw exception
            return CommandResponse(0L, emptyList(), false)
        }
        override fun outputFor(paneId: String) = delegate.outputFor(paneId)
        override fun close() = delegate.close()
        // Issue #215: detachCleanly added to the interface; the
        // throwing fake delegates to FakeTmuxClient so the killSession
        // tests don't have to reason about the new method's
        // semantics — they only care that sendCommand throws on the
        // configured command name.
        override suspend fun detachCleanly(timeoutMs: Long) =
            delegate.detachCleanly(timeoutMs)
        override suspend fun resizeWindow(sessionId: String, cols: Int, rows: Int) =
            delegate.resizeWindow(sessionId, cols, rows)
    }
}
