package com.pocketshell.app.tmux

import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.app.session.SessionTab
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionAgentDetectionStateTest : TmuxSessionViewModelTestBase() {
    private fun newClaudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun newCodexDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.Codex,
        sourcePath = "/home/u/.codex/sessions/xyz.jsonl",
        sessionId = "xyz",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun newOpenCodeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.OpenCode,
        sourcePath = "/home/u/.local/share/opencode/opencode.db#ses_123",
        sessionId = "ses_123",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    /**
     * Connects the VM to a stable host/session and applies a single pane in
     * window @0, so reconnect simulations can re-apply panes under the same
     * window with a rotated pane id.
     */
    private fun TmuxSessionViewModel.connectWithPaneForTest(
        paneId: String,
        windowId: String = "@0",
        sessionName: String = "work",
    ) {
        replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
            client = FakeTmuxClient(),
        )
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId,
                    windowId,
                    "$0",
                    "shell",
                    paneIndex = 0,
                    sessionName = sessionName,
                ),
            ),
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // Issue #819 (Slice A2): the #495 reattach memory-seed must RE-ANCHOR the
    // remembered conversation source against live re-detection — it must NOT
    // render the remembered `detection.sourcePath` BLIND as Live.
    //
    // The bug (maintainer dogfood): the Conversation tab showed a DIFFERENT
    // Codex transcript than the route-true Terminal — the `ai-shipping-labs`
    // header over a `git-pocketshell-desktop-codex` transcript — because two
    // same-cwd same-kind (Codex) sessions (a sub-agent / second window / second
    // worktree) share a cwd and the source is picked by mtime, so a remembered
    // detection captured during a prior mis-pick carried the SIBLING's rollout.
    // The seed restored that stale source and rendered it Live before the live
    // `/proc/<pid>/fd` round-trip (Slice A1) re-bound the route-true source.
    //
    // RED on base: the seed carries `remembered.detection.sourcePath` (the stale
    // sibling source) as a Live row — so `restored.detection!!.sourcePath`
    // equals the sibling source the Terminal is NOT attached to.
    // GREEN with A2: the seed restores a detection-LESS resolving placeholder;
    // live re-detection (the route's OWN fd-pinned source) binds the route-true
    // source, so Conversation == Terminal route.
    //
    // G2 class coverage: sub-agent/nested, two-windows, two-worktrees all reduce
    // to "remembered source != route-true source"; plus the missing-data
    // (remembered window whose agent has since EXITED) case, and the #554 no-flap
    // hold (a transient post-reattach null must NOT tear the pane to a raw shell).
    // ════════════════════════════════════════════════════════════════════

    private fun codexDetection(sourcePath: String, sessionId: String): AgentDetection =
        AgentDetection(
            agent = AgentKind.Codex,
            sourcePath = sourcePath,
            sessionId = sessionId,
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

    @Test
    fun cachedRecordedKindWithRawSourceRereadsAfterGenerationAppearsAndRebinds() =
        runTest(scheduler) {
            val now = System.currentTimeMillis() / 1000
            val rawSource = "/home/u/.claude/projects/-workspace-proj/raw-before-generation.jsonl"
            val generationSource = "/home/u/.claude/projects/-workspace-proj/generation-current.jsonl"
            val siblingSource = "/home/u/.claude/projects/-workspace-proj/sibling.jsonl"
            val session = FakeSshSession(
                recordedKindOutput = "claude\n",
                recordedSourceOutput = "$rawSource\n",
                detectionOutput = """
                    claude|${now - 120}|/workspace/proj|$rawSource
                    claude|${now - 60}|/workspace/proj|$generationSource
                    claude|$now|/workspace/proj|$siblingSource
                """.trimIndent(),
            )
            val vm = newVm()
            vm.replaceClientForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = FakeTmuxClient(),
                session = session,
            )
            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        paneId = "%0",
                        windowId = "@0",
                        sessionId = "$0",
                        title = "claude",
                        paneIndex = 0,
                        cwd = "/workspace/proj",
                        currentCommand = "claude",
                        paneTty = "/dev/pts/7",
                        sessionName = "work",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "precondition: the first open cached a raw legacy @ps_agent_source",
                rawSource,
                vm.agentConversations.value["%0"]?.detection?.sourcePath,
            )

            session.setRecordedSourceGenerationOutput("launch-2\n")
            session.setRecordedSourceOutput("launch-2\t$generationSource\n")
            vm.startAgentDetectionForPaneForTest("%0")
            advanceUntilIdle()

            assertEquals(
                "when @ps_agent_source_generation appears, the cache-hit path must " +
                    "not return the previously cached raw source before validating " +
                    "the generation-scoped source option",
                generationSource,
                vm.agentConversations.value["%0"]?.detection?.sourcePath,
            )
            assertTrue(
                "the raw cached source must be overridden via a standalone " +
                    "generation-aware source read; commands=${session.execCommands}",
                session.execCommands.any {
                    it.contains("@@PS_RECORDED_SOURCE_GENERATION@@") &&
                        it.contains("@ps_agent_source_generation") &&
                        it.contains("@ps_agent_source") &&
                        !it.contains("@@PS_RECORDED_KIND@@")
                },
            )
        }

    @Test
    fun codexReattachSeedReanchorsAcrossSubAgentTwoWindowAndTwoWorktreeSiblings() =
        runTest(scheduler) {
            // G2 class coverage: the three same-cwd same-kind collision shapes the
            // maintainer can hit (a sub-agent/orchestrator, a second tmux window,
            // a second git worktree) all reduce to "the remembered source is a
            // sibling rollout, the route-true source is the pane's own". For each,
            // the seed must refuse the remembered source and the live re-bind must
            // restore the route-true one.
            data class Case(
                val name: String,
                val siblingSource: String,
                val routeTrueSource: String,
            )
            val cases = listOf(
                Case(
                    "sub-agent/orchestrator out-flushing the pane's own rollout",
                    "/home/u/.codex/sessions/orchestrator-subagent.jsonl",
                    "/home/u/.codex/sessions/pane-own-session.jsonl",
                ),
                Case(
                    "two tmux windows in one project dir",
                    "/home/u/.codex/sessions/window-b.jsonl",
                    "/home/u/.codex/sessions/window-a.jsonl",
                ),
                Case(
                    "two git worktrees of one repo sharing the codex sessions dir",
                    "/home/u/.codex/sessions/worktree-feature.jsonl",
                    "/home/u/.codex/sessions/worktree-main.jsonl",
                ),
            )

            for ((index, case) in cases.withIndex()) {
                val windowId = "@$index"
                val oldPane = "%${index * 2}"
                val newPane = "%${index * 2 + 1}"
                val vm = newVm()
                vm.connectWithPaneForTest(paneId = oldPane, windowId = windowId)
                vm.startAgentConversationForTest(
                    oldPane,
                    codexDetection(case.siblingSource, "sibling-$index"),
                )
                vm.selectSessionTab(oldPane, SessionTab.Conversation)
                runCurrent()

                vm.applyParsedPanesForTest(
                    listOf(
                        TmuxSessionViewModel.ParsedPane(
                            newPane, windowId, "$0", "shell", paneIndex = 0,
                            cwd = "/home/u/git/pocketshell", paneTty = "/dev/pts/$index",
                            sessionName = "work",
                        ),
                    ),
                )
                runCurrent()

                val seeded = vm.agentConversations.value[newPane]!!
                assertNull(
                    "#819 (A2) [${case.name}]: seed must not carry the sibling source",
                    seeded.detection,
                )
                assertNotEquals(
                    "#819 (A2) [${case.name}]: sibling source must not be active",
                    case.siblingSource,
                    seeded.detection?.sourcePath,
                )

                vm.markAgentTailLiveForTest(
                    newPane,
                    codexDetection(case.routeTrueSource, "route-true-$index"),
                )
                runCurrent()

                val bound = vm.agentConversations.value[newPane]!!
                assertEquals(
                    "#819 (A2) [${case.name}]: live re-bind anchors the route-true source",
                    case.routeTrueSource,
                    bound.detection!!.sourcePath,
                )
            }
        }

    @Test
    fun codexReattachSeedHoldsResolvingPlaceholderThroughTransientNullThenTearsDownOnConfirmedExit() =
        runTest(scheduler) {
            // #554 no-flap guarantee AND the missing-data (agent-exited) case.
            // The A2 seed is detection-less, so the #554 hold can no longer key
            // off `detection != null`. A transient post-reattach null must be
            // HELD + re-confirmed (the window was a KNOWN agent), and only a
            // CONFIRMED exit (AGENT_EXIT_CONFIRMATIONS consecutive nulls) tears
            // the placeholder down — never a flap to a raw shell on the first null.
            val vm = newVm()
            vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
            vm.startAgentConversationForTest(
                "%0",
                codexDetection("/home/u/.codex/sessions/remembered.jsonl", "remembered"),
            )
            vm.selectSessionTab("%0", SessionTab.Conversation)
            runCurrent()

            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        "%7", "@0", "$0", "shell", paneIndex = 0,
                        cwd = "/home/u/git/pocketshell", paneTty = "/dev/pts/9",
                        sessionName = "work",
                    ),
                ),
            )
            runCurrent()

            assertTrue(
                "precondition: the reattach seed is a remembered-agent resolving placeholder",
                vm.agentConversations.value["%7"]!!.rememberedAgentPlaceholder,
            )

            // First live re-detection comes back NULL (the agent's log/process is
            // not yet observable on the fresh attach). The #554 hold must keep the
            // placeholder — NOT drop the pane to a raw shell.
            val droppedOnFirstNull = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            assertFalse(
                "#554/#819 (A2): a transient first null must NOT tear down the " +
                    "remembered resolving placeholder",
                droppedOnFirstNull,
            )
            assertNotNull(
                "#819 (A2): the resolving placeholder is retained across the first null",
                vm.agentConversations.value["%7"],
            )

            // After AGENT_EXIT_CONFIRMATIONS consecutive nulls the exit is
            // confirmed; the placeholder is torn down (the agent genuinely exited
            // — the missing-data case), never left stranded on "Loading…".
            var dropped = false
            repeat(8) {
                if (!dropped) {
                    dropped = vm.handleNullAgentDetectionForTest("%7")
                    runCurrent()
                }
            }
            assertTrue(
                "#819 (A2): a CONFIRMED exit tears down the remembered placeholder",
                dropped,
            )
            assertNull(
                "#819 (A2): the placeholder is dropped on confirmed exit — never " +
                    "stranded on 'Loading…'",
                vm.agentConversations.value["%7"],
            )
        }

    @Test
    fun codexReattachSeedSeam_seedsResolvingPlaceholderNotRememberedSource() =
        runTest(scheduler) {
            // Direct seam coverage of seedAgentConversationFromMemory itself (no
            // live-detection round-trip in the way), proving the seed in isolation
            // restores a resolving placeholder rather than the remembered source.
            val memory = AgentSessionMemory()
            val vm = newVm(agentSessionMemory = memory)
            vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
            runCurrent()
            // Arm memory for window @0 with a Codex sibling source.
            memory.remember(
                hostId = 42L,
                sessionName = "work",
                windowId = "@0",
                detection = codexDetection(
                    "/home/u/.codex/sessions/sibling.jsonl",
                    "sibling",
                ),
                wasOnConversation = true,
            )
            // Drop the existing row so the seed's "no existing row" gate passes,
            // then drive the seed seam directly.
            vm.clearAgentDetectionForPaneForTest("%0")
            // clearAgentDetectionForPane forgets memory; re-arm it after the clear.
            memory.remember(
                hostId = 42L,
                sessionName = "work",
                windowId = "@0",
                detection = codexDetection(
                    "/home/u/.codex/sessions/sibling.jsonl",
                    "sibling",
                ),
                wasOnConversation = true,
            )
            runCurrent()
            vm.seedAgentConversationFromMemoryForTest("%0")
            runCurrent()

            val seeded = vm.agentConversations.value["%0"]!!
            assertNull(
                "#819 (A2): the seed seam restores a resolving placeholder, not " +
                    "the remembered source",
                seeded.detection,
            )
            assertTrue(seeded.rememberedAgentPlaceholder)
            assertEquals(ConversationLoadState.Loading, seeded.loadState)
            assertEquals(SessionTab.Conversation, seeded.selectedTab)
        }

    // ─── Issue #818: configurable open-time default tab + the #815 line ───
    //
    // #818 makes the tab a freshly-OPENED agent session lands on configurable
    // (default Conversation — the black-screen cure). The #815 invariant is
    // preserved but reframed: the open-time default ONLY governs the fresh-row
    // open; a detection/refresh on an ALREADY-open session must never yank the
    // user's tab in either direction. A remembered/explicit per-session choice
    // still wins (seed-from-memory runs first).

    @Test
    fun freshlyOpenedAgentLandsOnConfiguredTerminalDefault() = runTest(scheduler) {
        // Issue #818: with the open-time default set to Terminal (the user
        // opted out of the Conversation default), a POSITIVE agent detection
        // that first lands on a pane with NO existing row (no remembered tab,
        // no explicit choice) opens on the raw Terminal view.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        assertNull("precondition: no conversation row before detection", vm.agentConversations.value["%0"])

        // Live detection lands (the production markAgentTailLive path, current == null).
        vm.markAgentTailLiveForTest("%0", newClaudeDetection())
        runCurrent()

        val state = vm.agentConversations.value["%0"]!!
        assertEquals(
            "open-time default Terminal: a freshly-opened agent session lands on Terminal",
            SessionTab.Terminal,
            state.selectedTab,
        )
        assertEquals(AgentKind.ClaudeCode, state.detection?.agent)
    }

    @Test
    fun freshlyOpenedAgentLandsOnConversationByDefault() = runTest(scheduler) {
        // Issue #818: the DEFAULT open-time view is Conversation — the
        // black-screen cure. With no override (= production default) a fresh
        // agent detection opens directly on the readable Conversation view.
        //
        // Issue #878: with the default = Conversation, the pre-detection
        // placeholder row is now seeded at pane-add (so the user sees the
        // detecting placeholder, NOT the black Terminal, during the detection
        // window). The detection-less seed already lands on Conversation; the
        // detection then fills in the real agent while PRESERVING that tab.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        val seeded = vm.agentConversations.value["%0"]!!
        assertEquals(
            "precondition (#878): the pre-detection placeholder lands on Conversation",
            SessionTab.Conversation,
            seeded.selectedTab,
        )
        assertNull("precondition (#878): the seed has no detection yet", seeded.detection)

        vm.markAgentTailLiveForTest("%0", newClaudeDetection())
        runCurrent()

        val state = vm.agentConversations.value["%0"]!!
        assertEquals(
            "the default open-time view is Conversation (#818 black-screen cure)",
            SessionTab.Conversation,
            state.selectedTab,
        )
        assertEquals(AgentKind.ClaudeCode, state.detection?.agent)
        assertFalse(
            "the autoSeededPlaceholder flag clears once a real detection lands",
            state.autoSeededPlaceholder,
        )
    }

    @Test
    fun freshlyOpenedAgentViaFullSshPathHonoursTerminalDefault() = runTest(scheduler) {
        // Issue #818: the open-time default through the REAL end-to-end
        // production conversation-start path (startAgentConversationForPane →
        // markAgentTailLive), not only the markAgentTailLive seam. Pinned to
        // Terminal to prove the opt-out reaches the open path.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val session = FakeSshSession(
            wcOutput = "2\n",
            initialEventsOutput =
                """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"hi"}}""",
        )

        vm.startAgentConversationForPaneForTest("%0", session, detection)
        advanceUntilIdle()

        assertEquals(
            "the end-to-end detect+load path honours the Terminal open-time default",
            SessionTab.Terminal,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )
    }

    // ─── Issue #878: pre-detection placeholder seeded at pane-add ─────────
    //
    // The #818 Conversation-default was applied only AFTER the detection SSH
    // round-trip (markAgentTailLive's current == null branch). Meanwhile the raw
    // TmuxTerminalPager is always mounted, so a fresh presumed-agent pane with
    // NO remembered status showed the BLACK Terminal for the whole detection
    // window (~0.3s cache-hit, ~0.95s+ cold/foreign). #878 closes that gap by
    // seeding the detection-less Conversation placeholder row at PANE-ADD so the
    // screen paints ConversationDetectingPlaceholder (the "Loading…" state),
    // NOT the Terminal void, during detection.
    //
    // G10 reproduce-first: on base (no fix) `connectWithPaneForTest` leaves NO
    // conversation row, so `agentConversations.value[pane]` is null and the
    // screen's `showConversationPlaceholder` (requires a Conversation-tab,
    // detection-less row) is false → the black Terminal shows. These tests
    // assert the row EXISTS with `selectedTab == Conversation`, detection ==
    // null, loadState == Loading — i.e. the placeholder is up. RED on base,
    // GREEN with the seed.

    @Test
    fun freshPresumedAgentPaneSeedsConversationPlaceholderBeforeDetection() = runTest(scheduler) {
        // AC1 (class: the generic fresh-open path; Claude detection lands later).
        // The DEFAULT open-time view is Conversation, so a freshly-added pane
        // shows the detecting placeholder for the WHOLE detection window.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        val placeholder = vm.agentConversations.value["%0"]
        assertNotNull(
            "#878: a fresh presumed-agent pane gets a pre-detection placeholder row",
            placeholder,
        )
        assertEquals(
            "#878: the placeholder is on the Conversation tab (so the screen paints" +
                " the detecting placeholder, not the black Terminal)",
            SessionTab.Conversation,
            placeholder!!.selectedTab,
        )
        assertNull("#878: the placeholder has no detection yet", placeholder.detection)
        assertEquals(
            "#878: the placeholder is in the Loading state (the detecting placeholder)",
            ConversationLoadState.Loading,
            placeholder.loadState,
        )
        assertTrue(
            "#878: the row is flagged as an auto-seed so a shell can drop it",
            placeholder.autoSeededPlaceholder,
        )

        // Detection lands (Claude): the placeholder becomes the real agent row,
        // STILL on Conversation, and the auto-seed flag clears.
        vm.markAgentTailLiveForTest("%0", newClaudeDetection())
        runCurrent()
        val live = vm.agentConversations.value["%0"]!!
        assertEquals(AgentKind.ClaudeCode, live.detection?.agent)
        assertEquals(
            "#878: the user stays on Conversation once detection lands",
            SessionTab.Conversation,
            live.selectedTab,
        )
        assertFalse(
            "#878: a real detection clears the auto-seed flag",
            live.autoSeededPlaceholder,
        )
    }

    @Test
    fun freshPaneWherePlaceholderResolvesToCodexStaysOnConversation() = runTest(scheduler) {
        // AC1 (class: Codex). The pre-detection placeholder is the same for any
        // agent kind; here a Codex detection resolves it. The user is on the
        // Conversation surface for the whole window and after Codex lands.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        assertEquals(
            "#878: placeholder up (Conversation) before Codex is detected",
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )
        assertNull(vm.agentConversations.value["%0"]!!.detection)

        vm.markAgentTailLiveForTest("%0", newCodexDetection())
        runCurrent()
        val live = vm.agentConversations.value["%0"]!!
        assertEquals(AgentKind.Codex, live.detection?.agent)
        assertEquals(
            "#878: Codex pane stays on Conversation once detection lands",
            SessionTab.Conversation,
            live.selectedTab,
        )
    }

    // ─── Issue #894 (epic #821 "Slice C"): a freshly-opened CONFIRMED SHELL ──
    // pane (recorded `@ps_agent_kind=shell`) must NOT flash the #878 "Loading
    // conversation…" placeholder when the open-time default is Conversation,
    // while a presumed-agent / foreign pane STILL gets it (no #878 regression).
    //
    // G10 reproduce-first: on base (no Slice C gate) `seedPresumedAgentPlaceholder`
    // seeds EVERY pane (it has no confirmed-shell gate) and `confirmedShell` is
    // hard-wired false — so a confirmed-shell pane gets the auto-seeded
    // Conversation placeholder (the wrong-surface flash). These tests assert the
    // confirmed-shell pane has NO auto-seeded placeholder and is published in
    // [confirmedShellPaneIds]; RED on base (the placeholder is present, the set
    // is empty), GREEN with the verdict-driven gate.
    //
    // G2 class coverage: shell-vs-agent × default=Conversation-vs-Terminal.

    // ─── Issue #874 (residual black-screen): the reconcile/cache-restore void ──
    //
    // #975 fixed the verdict-clearing and #989 fixed the terminal-buffer reseed,
    // leaving one residual void: a presumed-agent pane that is RECONCILED rather
    // than freshly added — a beyond-grace reattach (#959) or a switch-back to a
    // REBUILT cached runtime — whose conversation row was DROPPED (the R3-B
    // 2-null collapse on a wedged channel) has NO row on restore.
    // `restoreCachedRuntime` only restarts rows that carried a live `detection`
    // (`restartAgentConversationsForRestoredRuntime`'s `state.detection ?: return`),
    // and that path never reconciles — so a presumed-agent pane with a dropped
    // row falls through to the always-mounted raw `TmuxTerminalPager` → the #807
    // black void.
    //
    // The fix re-seeds the #878 Conversation placeholder for the session's
    // presumed-agent panes when the recorded-kind verdict resolves the session as
    // NOT a confirmed shell (`applyRecordedShellVerdict(isShell = false)`, the
    // single verdict-application point reached after a restore via
    // `refreshCurrentSessionRecordedKind`). It runs AFTER the verdict so #894's
    // no-flash-on-shell invariant holds.
    //
    // G10 reproduce-first: on base the verdict-application re-seed does not exist,
    // so a dropped-row presumed-agent pane stays rowless → raw Terminal void. RED
    // on base (row null after the verdict), GREEN with the fix (Conversation
    // placeholder re-seeded). G2 class coverage: reconciled-presumed-agent
    // (re-seeds), reconciled-confirmed-shell (NO placeholder, #894 no-flash),
    // freshly-added (unchanged).

    @Test
    fun reconciledPresumedAgentWithDroppedRowReseedsConversationPlaceholder() = runTest(scheduler) {
        // The residual #874 void: a presumed-agent pane whose row was dropped and
        // is then restored/reconciled (no live detection to restart) gets its
        // Conversation placeholder re-seeded when the verdict resolves NOT-shell.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        // Drop the auto-seeded row to null — model the R3-B 2-null collapse on a
        // wedged channel that wiped the conversation row before the runtime was
        // parked (so the restored runtime carries NO row for this pane).
        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()
        assertNull("precondition: the presumed-agent pane has no conversation row", vm.agentConversations.value["%0"])

        // The recorded-kind verdict resolves the session as NOT a confirmed shell
        // (foreign / agent / re-classified) — the single verdict-application point
        // reached on a restore via refreshCurrentSessionRecordedKind. On base no
        // re-seed runs → the pane stays rowless → the raw Terminal void (#807).
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = false)
        runCurrent()

        val row = vm.agentConversations.value["%0"]
        assertNotNull(
            "#874: a not-shell verdict re-seeds the dropped presumed-agent pane's" +
                " Conversation placeholder (no residual black Terminal void)",
            row,
        )
        assertEquals("#874: the re-seed lands on the Conversation surface", SessionTab.Conversation, row!!.selectedTab)
        assertNull("#874: the re-seed is detection-less (the detecting placeholder)", row.detection)
        assertEquals("#874: the re-seed is in the Loading state", ConversationLoadState.Loading, row.loadState)
        assertTrue("#874: the re-seed is flagged as an auto-seed", row.autoSeededPlaceholder)
    }

    @Test
    fun confirmedShellVerdictNeverReseedsPlaceholderNoFlash() = runTest(scheduler) {
        // #894 no-flash invariant (class coverage): a CONFIRMED-shell verdict must
        // NOT re-seed the Conversation placeholder for a rowless pane. The #874
        // re-seed pass runs ONLY on the not-shell branch; the shell branch still
        // drops/keeps placeholders exactly as before (no wrong-surface flash on a
        // genuine shell).
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()
        assertNull("precondition: rowless pane", vm.agentConversations.value["%0"])

        // A recorded SHELL verdict must NOT re-seed — a confirmed shell stays on
        // the raw Terminal (correct), never flashing the Conversation placeholder.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertNull(
            "#894: a confirmed-shell verdict never re-seeds the Conversation placeholder",
            vm.agentConversations.value["%0"],
        )
        assertTrue(
            "#894: the confirmed-shell verdict is published per-pane",
            "%0" in vm.confirmedShellPaneIds.value,
        )
    }

    // ─── Issue #1158 (REOPENED chain #962→#975→#1057→#1158) — the STICKY ────────
    // alt-buffer agent latch, driven from SERVER TRUTH. The maintainer launches
    // `claude`/`codex`/glm DIRECTLY inside a shell-recorded session, so
    // `@ps_agent_kind` stays `shell`, the confirmed-shell verdict is never cleared,
    // and live detection never binds for the node-wrapped-Claude / Codex-`/proc` /
    // Z.AI fleet. The detection-INDEPENDENT alt-buffer signal restores the
    // Conversation tab and, once shown, must STAY for the session's life.
    //
    // The signal source is the tmux SERVER's `#{alternate_on}` flag read on every
    // `list-panes` reconcile ([ParsedPane.alternateOn] →
    // [TmuxSessionViewModel.latchAltBufferAgentsFromParsed]) — NOT the CLIENT
    // emulator (which is inert on the real `-CC` path: the capture-pane seed
    // replays screen TEXT onto the client's MAIN buffer and an idle agent emits no
    // fresh `?1049h`, so `isAlternateBufferActive` stays false forever — the exact
    // synthetic-masks-reality cheat that hid #1158 for five fixes). Driving the
    // latch through `applyParsedPanesForTest(alternateOn = true)` exercises the
    // REAL production reconcile path.
    //
    // RED→GREEN (base): remove the `latchAltBufferAgentsFromParsed(sorted)` call in
    // `applyParsedPanes` (the fix) and every case below FAILS — the server-truth
    // `alternate_on` is read but never latched, so the tab stays hidden, exactly
    // the maintainer's #1158 symptom. ───────────────────────────────────────────

    /** Server-truth reconcile: one pane in session `$0` reporting `#{alternate_on}`. */
    private fun TmuxSessionViewModel.reconcilePaneAltBuffer(
        paneId: String,
        alternateOn: Boolean,
        sessionName: String = "work",
    ) {
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId,
                    "@0",
                    "$0",
                    "shell",
                    paneIndex = 0,
                    alternateOn = alternateOn,
                    sessionName = sessionName,
                ),
            ),
        )
    }

    @Test
    fun altBufferAgentLatchesShellRecordedSessionAndIsStickyAcrossBufferFlip() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        // The maintainer's exact state: the session is a CONFIRMED shell
        // (`@ps_agent_kind=shell`) — nothing else will ever show the tab.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertTrue(
            "precondition: the session is a confirmed shell",
            "%0" in vm.confirmedShellPaneIds.value,
        )
        assertTrue(
            "precondition: no alt-buffer sighting yet → tab still hidden (RED on base)",
            "%0" !in vm.altBufferAgentPaneIds.value,
        )

        // The agent (claude/codex/glm — kind-agnostic) goes full-screen: the tmux
        // SERVER reports `#{alternate_on}=1` on the next reconcile. This is the real
        // path — no client-emulator injection — and it LATCHES the session.
        vm.reconcilePaneAltBuffer(paneId = "%0", alternateOn = true)
        runCurrent()
        assertTrue(
            "#1158: the server-truth alt-buffer read latches the session → Conversation tab shown",
            "%0" in vm.altBufferAgentPaneIds.value,
        )

        // STICKY: the agent leaves the alt-buffer (exits a full-screen view / drops
        // detection) — a later reconcile reports `#{alternate_on}=0`. The tab MUST
        // stay for the rest of the session.
        vm.reconcilePaneAltBuffer(paneId = "%0", alternateOn = false)
        runCurrent()
        assertTrue(
            "#1158 sticky: a later buffer flip / detection drop must NOT collapse the tab",
            "%0" in vm.altBufferAgentPaneIds.value,
        )
    }

    @Test
    fun altBufferAgentSurvivesReattachPaneIdRotation() = runTest(scheduler) {
        // Sticky across the real reattach path: tmux re-attach assigns a NEW pane
        // id under the SAME session ($0). The latch is per-SESSION, so the new pane
        // id inherits the Conversation tab without needing a fresh alt-buffer
        // sighting — the maintainer's long-lived sessions keep the tab across
        // reconnects.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        vm.reconcilePaneAltBuffer(paneId = "%0", alternateOn = true)
        runCurrent()
        assertTrue("precondition: latched on the original pane id", "%0" in vm.altBufferAgentPaneIds.value)

        // Reattach: same session $0, new pane id %7. The reconcile reports
        // `#{alternate_on}=0` for the new pane (the agent may be idle-on-main by now),
        // but the per-SESSION latch keeps the tab.
        vm.reconcilePaneAltBuffer(paneId = "%7", alternateOn = false)
        runCurrent()
        assertTrue(
            "#1158 sticky: the new pane id under the latched session keeps the tab",
            "%7" in vm.altBufferAgentPaneIds.value,
        )
        assertTrue("old pane id is gone", "%0" !in vm.altBufferAgentPaneIds.value)
    }

    @Test
    fun plainShellOnMainBufferNeverLatchesNoFlap() = runTest(scheduler) {
        // #894/#815 no-flap adjacency: a plain interactive shell sitting at a prompt
        // is on the MAIN buffer, so the tmux SERVER reports `#{alternate_on}=0` on
        // every reconcile → the latch never fires → NO Conversation tab. The
        // alt-buffer signal is POSITIVE-only.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        vm.reconcilePaneAltBuffer(paneId = "%0", alternateOn = false)
        runCurrent()
        assertTrue(
            "#894 no-flap: a main-buffer shell never latches → tab stays hidden",
            "%0" !in vm.altBufferAgentPaneIds.value,
        )
    }

    @Test
    fun notShellVerdictDoesNotClobberLiveRowNoYank() = runTest(scheduler) {
        // #815 no-yank invariant (class coverage): the #874 re-seed must NOT
        // clobber a pane that ALREADY has a row (a live agent or a user-tapped
        // choice). seedPresumedAgentPlaceholder self-gates on `containsKey`, so a
        // not-shell verdict over a live row is a no-op.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        // A live Codex binds; user is on the Terminal tab (an explicit choice the
        // re-seed must not yank back to Conversation).
        vm.markAgentTailLiveForTest("%0", newCodexDetection())
        vm.selectSessionTab("%0", SessionTab.Terminal)
        runCurrent()
        val before = vm.agentConversations.value["%0"]!!
        assertEquals("precondition: live Codex row on Terminal", SessionTab.Terminal, before.selectedTab)
        assertEquals(AgentKind.Codex, before.detection?.agent)

        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = false)
        runCurrent()
        val after = vm.agentConversations.value["%0"]!!
        assertEquals(
            "#815: the not-shell verdict re-seed does NOT yank a live row's tab",
            SessionTab.Terminal,
            after.selectedTab,
        )
        assertEquals("#815: the live detection is preserved", AgentKind.Codex, after.detection?.agent)
    }

    // ─── Issue #1083: the cache-restore re-seed must fire from the RESTORE ──────
    // OPERATION ITSELF, not only when the SCREEN re-calls
    // refreshCurrentSessionRecordedKind. The #874/#1004 fix re-seeds the dropped
    // Conversation row inside applyRecordedShellVerdict(isShell=false), but that
    // verdict only resolved when the screen happened to re-read the recorded kind
    // after a switch. A cache-restore that did NOT trigger that screen
    // recomposition left the presumed-agent pane rowless → the #807 raw-Terminal
    // black void ("switch stays black, very hard to force a redraw"). The fix
    // drives refreshCurrentSessionRecordedKind() from restoreCachedRuntime, so the
    // void close is coupled to the restore. These two tests are the G10/G2 gate:
    // (1) restore alone re-seeds (RED on base — no re-seed without a screen call),
    // (2) restore + a later screen read does NOT double-seed (the containsKey gate).

    private fun TmuxSessionViewModel.connectPresumedAgentPaneWithDroppedRowForTest(
        session: FakeSshSession,
    ) {
        // Default open-time tab = Conversation (the #878 black-screen cure) so a
        // presumed-agent pane seeds the "Loading conversation…" placeholder.
        setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "shell",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
    }

    @Test
    fun cacheRestoreReseedsDroppedRowWithoutScreenRefresh() = runTest(scheduler) {
        // G10 reproduce-first: a presumed-agent pane whose Conversation row was
        // dropped (the R3-B 2-null collapse), then PARKED and RESTORED on a warm
        // switch-back, must have its placeholder re-seeded by the restore itself —
        // WITHOUT the test (or screen) calling refreshCurrentSessionRecordedKind.
        // The restored session's `@ps_agent_kind` reads empty (foreign / not a
        // confirmed shell), so the verdict resolves NOT-shell and the re-seed
        // fires. RED on base (restore never read the recorded kind → row stays
        // null → raw Terminal void); GREEN with the fix.
        val session = FakeSshSession(recordedKindOutput = "")
        val vm = newVm()
        vm.connectPresumedAgentPaneWithDroppedRowForTest(session)
        runCurrent()
        // The R3-B collapse drops the auto-seeded row → the runtime parks with NO
        // conversation row for this pane.
        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()
        assertNull(
            "precondition: the presumed-agent pane has no conversation row before the switch",
            vm.agentConversations.value["%0"],
        )

        // The warm switch-away/switch-back round trip. NO screen
        // refreshCurrentSessionRecordedKind is called by the test.
        vm.parkAndRestoreActiveRuntimeForTest()
        awaitCondition { vm.agentConversations.value["%0"] != null }

        val row = vm.agentConversations.value["%0"]
        assertNotNull(
            "#1083: a cache-restore re-seeds the dropped presumed-agent pane's " +
                "Conversation placeholder from the restore operation itself — no " +
                "residual black Terminal void, even without a screen refresh",
            row,
        )
        assertEquals(
            "#1083: the re-seed lands on the Conversation surface",
            SessionTab.Conversation,
            row!!.selectedTab,
        )
        assertNull("#1083: the re-seed is detection-less (the detecting placeholder)", row.detection)
        assertEquals("#1083: the re-seed is in the Loading state", ConversationLoadState.Loading, row.loadState)
        assertTrue("#1083: the re-seed is flagged as an auto-seed", row.autoSeededPlaceholder)
    }

    @Test
    fun cacheRestoreThenScreenRefreshDoesNotDoubleSeed() = runTest(scheduler) {
        // G2 class coverage: BOTH the restore-driven path AND the screen-driven
        // path re-seed, and the two together must NOT double-seed — the
        // restore's re-seed must survive a subsequent screen
        // refreshCurrentSessionRecordedKind unchanged (the seedPresumedAgentPlaceholder
        // `containsKey` gate). A double-seed would clobber the row or re-create it.
        val session = FakeSshSession(recordedKindOutput = "")
        val vm = newVm()
        vm.connectPresumedAgentPaneWithDroppedRowForTest(session)
        runCurrent()
        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()

        // Restore-driven re-seed fires first.
        vm.parkAndRestoreActiveRuntimeForTest()
        awaitCondition { vm.agentConversations.value["%0"] != null }
        val afterRestore = vm.agentConversations.value["%0"]!!
        assertTrue(
            "#1083: the restore-driven re-seed produced the auto-seeded placeholder",
            afterRestore.autoSeededPlaceholder,
        )

        // The screen now ALSO re-reads the recorded kind (the still-live
        // screen-driven path). It must be a no-op over the already-seeded row.
        val kindReadsBefore = session.execCommands.count {
            it.contains("show-options -v") && it.contains("@ps_agent_kind")
        }
        vm.refreshCurrentSessionRecordedKind()
        awaitCondition {
            session.execCommands.count {
                it.contains("show-options -v") && it.contains("@ps_agent_kind")
            } > kindReadsBefore
        }
        runCurrent()

        val afterScreen = vm.agentConversations.value["%0"]!!
        assertSame(
            "#1083: the screen-driven re-seed does NOT double-seed — the row " +
                "re-seeded by the restore is preserved identically (containsKey gate)",
            afterRestore,
            afterScreen,
        )
    }


    @Test
    fun confirmedShellPaneSeedGateSuppressesConversationPlaceholder() = runTest(scheduler) {
        // AC1 + AC4 (shell branch, default = Conversation). A pane whose session
        // the durable tree recorded as `@ps_agent_kind=shell` must NOT carry the
        // auto-seeded "Loading conversation…" placeholder.
        val vm = newVm()
        // Default open-time tab is Conversation (the black-screen cure) — the
        // exact state where the wrong-surface shell flash happens.
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        // On open, before the recorded-kind read lands, the pane got the #878
        // auto-seed (the same optimistic cure an agent gets — we can't yet tell
        // them apart). This is the pre-verdict state.
        assertNotNull(
            "#878: a fresh pane gets the optimistic placeholder before the recorded-kind read",
            vm.agentConversations.value["%0"],
        )

        // The recorded `@ps_agent_kind` reads back SHELL (the durable tree
        // verdict). Slice C drops the auto-seeded placeholder IMMEDIATELY (the
        // confirmed shell never lingers on the wrong surface — the first-open
        // flash is killed) and publishes the pane as confirmed-shell.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertNull(
            "#894 (Slice C): a confirmed-shell pane has its auto-seeded" +
                " Conversation placeholder dropped — no wrong-surface flash",
            vm.agentConversations.value["%0"],
        )
        assertTrue(
            "#894 (Slice C): the confirmed-shell verdict is published per-pane",
            "%0" in vm.confirmedShellPaneIds.value,
        )

        // The load-bearing GATE: a fresh seed attempt for a CONFIRMED shell must
        // be a no-op (on base, with no gate, this re-creates the placeholder —
        // the RED state).
        vm.seedPresumedAgentPlaceholderForTest("%0")
        runCurrent()
        assertNull(
            "#894 (Slice C): the seed gate skips a confirmed shell — it never" +
                " re-creates the Conversation placeholder",
            vm.agentConversations.value["%0"],
        )
    }

    @Test
    fun presumedAgentPaneStillSeedsConversationPlaceholder() = runTest(scheduler) {
        // AC2 + AC4 (agent / no-shell-verdict branch, default = Conversation).
        // The #878 black-screen cure is UNCHANGED: a pane with NO confirmed-shell
        // verdict (a presumed-agent / foreign / not-yet-classified pane) STILL
        // gets the auto-seeded placeholder so it never shows the black Terminal.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        val placeholder = vm.agentConversations.value["%0"]
        assertNotNull(
            "#894: a presumed-agent pane (no shell verdict) STILL gets the #878 cure",
            placeholder,
        )
        assertEquals(SessionTab.Conversation, placeholder!!.selectedTab)
        assertNull(placeholder.detection)
        assertTrue(placeholder.autoSeededPlaceholder)
        assertFalse(
            "#894: a presumed-agent pane is NOT published as confirmed-shell",
            "%0" in vm.confirmedShellPaneIds.value,
        )

        // A re-seed (e.g. a later reconcile) keeps the placeholder for a
        // presumed agent — the cure is intact.
        vm.seedPresumedAgentPlaceholderForTest("%0")
        runCurrent()
        assertNotNull(
            "#894: re-seeding a presumed-agent pane keeps the placeholder",
            vm.agentConversations.value["%0"],
        )
    }

    @Test
    fun confirmedShellPaneIdsReflectsRecordedVerdictNotAHardWiredConstant() = runTest(scheduler) {
        // AC3: `confirmedShell` reflects the real per-pane shell-vs-agent truth
        // from the recorded-kind record, not a hard-wired constant. Marking the
        // session shell publishes the pane; re-classifying it (shell -> agent)
        // un-publishes it so the agent surface returns.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        assertFalse(
            "#894: a pane with no recorded verdict is NOT confirmed-shell",
            "%0" in vm.confirmedShellPaneIds.value,
        )

        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertTrue(
            "#894: a recorded SHELL verdict publishes the pane as confirmed-shell",
            "%0" in vm.confirmedShellPaneIds.value,
        )

        // Re-classified to an agent (the recorded kind is no longer shell): the
        // confirmed-shell flag clears, so the presumed-agent surface returns.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = false)
        runCurrent()
        assertFalse(
            "#894: a non-shell (agent) verdict un-publishes the pane — not sticky",
            "%0" in vm.confirmedShellPaneIds.value,
        )
    }

    // Issue #962 — a live agent started INSIDE a session recorded
    // `@ps_agent_kind=shell` must regain its agent surface (and the Conversation
    // toggle). The recorded-shell verdict (#894) publishes the pane as
    // confirmed-shell, which collapses `presumedAgent` and hides the toggle for
    // the life of the session — the maintainer's exact dogfood report. The fix:
    // the AUTHORITATIVE live-detection event re-classifies the session OUT of
    // confirmed-shell. This deterministic reproduction injects the exact state
    // machine the on-device journey exercises (the Docker fixture cannot make the
    // host daemon classify a non-cgroup-scoped process, so per D33 the failing
    // state is injected synthetically and hard-asserted, never self-skipped).
    //
    // RED on base: confirmedShell stays set after the live detection binds (the
    // override absent). GREEN: confirmedShell is cleared, so the toggle returns.
    // Class coverage (G2): claude / codex / opencode + the no-flap control.

    @Test
    fun liveAgentDetectionClearsConfirmedShellSoConversationToggleReturns() = runTest(scheduler) {
        assertConfirmedShellClearedByLiveAgent(::newClaudeDetection)
    }

    @Test
    fun liveCodexDetectionClearsConfirmedShellInRecordedShellSession() = runTest(scheduler) {
        assertConfirmedShellClearedByLiveAgent(::newCodexDetection)
    }

    @Test
    fun liveOpenCodeDetectionClearsConfirmedShellInRecordedShellSession() = runTest(scheduler) {
        assertConfirmedShellClearedByLiveAgent(::newOpenCodeDetection)
    }

    private fun TestScope.assertConfirmedShellClearedByLiveAgent(
        detectionFactory: () -> AgentDetection,
    ) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        // The session is recorded `@ps_agent_kind=shell` (a plain shell the
        // user/kind-picker classified as shell). The pane is published
        // confirmed-shell → the Conversation toggle is hidden (presumedAgent
        // collapses). This is the durable state the maintainer hit.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertTrue(
            "#962 precondition: a recorded-shell pane is published confirmed-shell " +
                "(the state that hides the Conversation toggle)",
            "%0" in vm.confirmedShellPaneIds.value,
        )

        // A live agent runtime is detected INSIDE the shell-recorded pane (the
        // user started claude/codex/opencode). On base (no fix) confirmedShell
        // stays set and the toggle stays hidden; the fix re-classifies the
        // session out of confirmed-shell on this authoritative detection event.
        vm.markAgentTailLiveForTest("%0", detectionFactory())
        runCurrent()

        assertFalse(
            "#962: a live agent detection in a recorded-shell session must clear the " +
                "confirmed-shell verdict so presumedAgent returns and the Conversation " +
                "toggle reappears (RED on base — confirmedShell stays set)",
            "%0" in vm.confirmedShellPaneIds.value,
        )
        // The pane now carries the live agent detection (the parsed conversation
        // surface), proving the toggle reaches a real source, not an empty tab.
        assertEquals(
            "#962: the live agent detection is bound to the pane",
            detectionFactory().agent,
            vm.agentConversations.value["%0"]?.detection?.agent,
        )
    }

    @Test
    fun genuineRecordedShellWithNoAgentKeepsConfirmedShellNoFlap() = runTest(scheduler) {
        // Issue #962 adjacency / #894 no-flap invariant: a GENUINE recorded shell
        // (no live agent detection ever binds) must STAY confirmed-shell — the
        // #962 override must not resurrect the "fresh shell flashes Conversation"
        // regression. No markAgentTailLive is ever called here (no agent), so the
        // confirmed-shell verdict is never cleared.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertTrue(
            "#894: a genuine recorded shell is published confirmed-shell",
            "%0" in vm.confirmedShellPaneIds.value,
        )
        // A reconcile / re-seed must NOT clear it (no agent detection event).
        vm.seedPresumedAgentPlaceholderForTest("%0")
        runCurrent()
        assertTrue(
            "#962/#894 no-flap: a genuine recorded shell with NO agent STAYS " +
                "confirmed-shell (the toggle correctly stays hidden)",
            "%0" in vm.confirmedShellPaneIds.value,
        )
    }

    // ─────────────────────── Issue #975 — REAL-PATH classify-miss ───────────
    // The maintainer hit a LIVE Claude session showing ONLY "Terminal" — no
    // Conversation toggle — because the session was recorded `@ps_agent_kind=shell`
    // with a node-wrapped/quiet `claude` started inside it, and the host
    // agent-kind daemon's cgroup-v2/`/proc` classify returns `unknown` (it cannot
    // see the masked process) → no detection binds → the confirmed-shell verdict
    // never clears → the toggle is gone for the session's life (#962 recurrence).
    //
    // These drive the REAL detection chain (resolveForeignKindGuess →
    // AgentKindRemoteSource.classify → AgentConversationRepository) through a fake
    // SSH session that models the masked-live-agent host: the `agents kind` daemon
    // exec returns `unknown` (scope=null) while a fresh `*.jsonl` transcript is
    // genuinely present in the cwd. This is the #780 synthetic-state-injection at
    // the host seam — NOT a `markAgentTailLiveForTest` injection (which #962 used
    // and which CANNOT exercise the failing classify-miss). The end-to-end Docker
    // proof is `ConversationToggleVisibleForLiveAgentInShellRecordedSessionDockerTest`.

    @Test
    fun b1MaskedLiveClaudeInRecordedShellBindsDetectionViaTranscriptDespiteUnknownClassify() =
        runTest(scheduler) {
            // B1: daemon classify = `unknown` (masked agent) + a live Claude
            // transcript in the cwd → the foreign resolver binds Claude detection
            // (the trustworthy-live-agent-evidence fallback). On base (no fix) the
            // foreign resolver returns null on a null kind guess, so NO detection
            // binds and the toggle stays gone — RED. With the fix the transcript
            // fallback binds → GREEN.
            val now = System.currentTimeMillis() / 1000
            val session = MaskedAgentSshSession(
                // The daemon could not read the scope → `unknown`, NOT `none`.
                classifyAgentKind = "unknown",
                // …but a live Claude transcript is plainly present in the cwd.
                detectionOutput =
                    "claude|$now|/workspace/proj|" +
                        "/home/testuser/.claude/projects/-workspace-proj/live.jsonl",
            )
            val vm = newVm()
            vm.connectWithRichPaneForTest(
                paneId = "%0",
                windowId = "@0",
                cwd = "/workspace/proj",
                paneTty = "/dev/pts/7",
                panePid = 4242L,
                session = session,
            )
            runCurrent()
            // The durable tree recorded this session `@ps_agent_kind=shell`.
            vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
            runCurrent()
            assertTrue(
                "#975 precondition: the recorded-shell pane is published confirmed-shell",
                "%0" in vm.confirmedShellPaneIds.value,
            )

            val detection = vm.resolveForeignSessionDetectionForTest("%0", session)
            runCurrent()

            assertNotNull(
                "#975 (B1): a CONFIRMED-SHELL pane whose daemon classify returns " +
                    "`unknown` while a live Claude transcript is present must bind " +
                    "the agent via the transcript-evidence fallback (RED on base — " +
                    "the foreign resolver returned null and no detection bound)",
                detection,
            )
            assertEquals(
                "#975 (B1): the bound detection is the live Claude transcript",
                AgentKind.ClaudeCode,
                detection?.agent,
            )
        }

    @Test
    fun b1GenuineNoneShellGetsNoTranscriptFallbackNoFlap() = runTest(scheduler) {
        // B1 no-flap control (#894): a daemon `none` verdict (a READABLE scope
        // with no agent — a genuine shell) must NOT trigger the transcript
        // fallback even if a STALE transcript lingers in the cwd. `none` is a
        // confident "no agent", unlike the unreadable `unknown`. Detection stays
        // null → the confirmed-shell verdict is preserved → the toggle correctly
        // stays hidden.
        val now = System.currentTimeMillis() / 1000
        val session = MaskedAgentSshSession(
            classifyAgentKind = "none",
            detectionOutput =
                "claude|$now|/workspace/proj|" +
                    "/home/testuser/.claude/projects/-workspace-proj/stale.jsonl",
        )
        val vm = newVm()
        vm.connectWithRichPaneForTest(
            paneId = "%0",
            windowId = "@0",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            panePid = 4242L,
            session = session,
        )
        runCurrent()
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()

        val detection = vm.resolveForeignSessionDetectionForTest("%0", session)
        runCurrent()

        assertNull(
            "#975 (B1 no-flap): a daemon `none` (genuine readable shell) must NOT " +
                "bind a transcript fallback — only the unreadable `unknown` does. " +
                "Detection stays null so the confirmed-shell verdict is preserved.",
            detection,
        )
    }

    @Test
    fun b1UnknownClassifyButNoTranscriptStaysNull() = runTest(scheduler) {
        // B1 boundary: `unknown` classify but NO live transcript in the cwd → the
        // fallback enumerates nothing → null (a genuine shell with an unreadable
        // scope and no agent). The fallback is evidence-driven: no transcript, no
        // bind, no flap.
        val session = MaskedAgentSshSession(
            classifyAgentKind = "unknown",
            detectionOutput = "",
        )
        val vm = newVm()
        vm.connectWithRichPaneForTest(
            paneId = "%0",
            windowId = "@0",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            panePid = 4242L,
            session = session,
        )
        runCurrent()
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()

        val detection = vm.resolveForeignSessionDetectionForTest("%0", session)
        runCurrent()

        assertNull(
            "#975 (B1 boundary): `unknown` classify with NO transcript binds nothing",
            detection,
        )
    }

    @Test
    fun b1ForeignNotConfirmedShellUnknownClassifyGetsNoTranscriptFallback() = runTest(scheduler) {
        // B1 scope guard: the transcript fallback is for a CONFIRMED-SHELL session
        // ONLY — we second-guess a stale recorded-shell verdict, never a clean
        // foreign session. A foreign (not-confirmed-shell) pane whose daemon
        // returns `unknown` keeps the existing null behaviour (the user picks the
        // kind); it does NOT auto-bind a same-cwd transcript.
        val now = System.currentTimeMillis() / 1000
        val session = MaskedAgentSshSession(
            classifyAgentKind = "unknown",
            detectionOutput =
                "claude|$now|/workspace/proj|" +
                    "/home/testuser/.claude/projects/-workspace-proj/live.jsonl",
        )
        val vm = newVm()
        vm.connectWithRichPaneForTest(
            paneId = "%0",
            windowId = "@0",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            panePid = 4242L,
            session = session,
        )
        runCurrent()
        // NOTE: no applyRecordedShellVerdict → the session is FOREIGN, not
        // confirmed-shell.

        val detection = vm.resolveForeignSessionDetectionForTest("%0", session)
        runCurrent()

        assertNull(
            "#975 (B1 scope): a FOREIGN (not-confirmed-shell) pane with `unknown` " +
                "classify gets NO transcript fallback — the fallback only clears a " +
                "stale recorded-shell verdict",
            detection,
        )
    }

    @Test
    fun confirmedShellReClassifiesOnlyOnRealInputChangeNotEveryReconcile() = runTest(scheduler) {
        // Issue #1641 (C2), which SUPERSEDES the #975 B1′ contract this test used
        // to pin. B1′ made a confirmed-shell pane bust its one-shot foreign
        // kind-guess on EVERY probe, so `startAgentDetectionForPane` — invoked for
        // every pane on every reconcile — re-ran the 3.5s `agents kind` daemon
        // classify continuously. Before #1641's transport fix, that classify
        // closed the shared `-CC` lease whenever it exceeded its bound on a slow
        // link, an uncredited entry trigger of the #1610 reconnect storm. The old
        // assertion here ("a re-probe on UNCHANGED input re-queries the daemon
        // once more") was pinning exactly that per-reconcile re-fire as intended
        // behaviour — it is the bug, inverted below.
        //
        // New contract: a confirmed-shell pane re-classifies ONLY on a real
        // trigger — its detection input `(cwd, command, tty)` changing — which is
        // what a `claude`/`codex`/`opencode` started in the shell produces (the
        // pane's foreground command changes, e.g. `node`→`claude`). That preserves
        // #962/#975 (the live agent still binds and clears the confirmed-shell
        // verdict) while killing the storm's per-reconcile RPC.
        //
        // RED on base (the unconditional per-reconcile bust): classifyExecCount
        // climbs by one per reconcile. GREEN: unchanged reconciles fire ZERO
        // classify; a command change fires exactly ONE.
        val session = MaskedAgentSshSession(classifyAgentKind = "unknown", detectionOutput = "")
        val vm = newVm()

        fun shellPane(command: String) = TmuxSessionViewModel.ParsedPane(
            paneId = "%0",
            windowId = "@0",
            sessionId = "$0",
            title = command,
            paneIndex = 0,
            cwd = "/workspace/proj",
            currentCommand = command,
            paneTty = "/dev/pts/7",
            panePid = 4242L,
            sessionName = "work",
        )

        vm.connectWithRichPaneForTest(
            paneId = "%0",
            windowId = "@0",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            panePid = 4242L,
            session = session,
        )
        runCurrent()
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertTrue(
            "#1641 precondition: the recorded-shell pane is published confirmed-shell",
            "%0" in vm.confirmedShellPaneIds.value,
        )

        // Baseline: the daemon classify count once the confirmed-shell verdict has
        // settled (the connect path itself does the one legitimate first probe).
        val afterVerdict = session.classifyExecCount

        // Five plain reconciles with UNCHANGED pane input (command = "node").
        // On base each busted the one-shot guess and re-fired the 3.5s classify;
        // the #1641 one-shot fix fires ZERO — the guess cannot change while the
        // input is unchanged.
        repeat(5) {
            vm.applyParsedPanesForTest(listOf(shellPane(command = "node")))
            runCurrent()
        }
        assertEquals(
            "#1641: a confirmed-shell pane must NOT re-run the agents-kind classify " +
                "on reconciles where its (cwd, command, tty) input is unchanged — " +
                "RED on base where each of the 5 reconciles busted the guess and " +
                "re-fired the 3.5s classify RPC (the #1610 storm entry trigger)",
            afterVerdict,
            session.classifyExecCount,
        )

        // A REAL trigger: the user starts an agent, so the pane's foreground
        // command changes (node→claude). Exactly ONE fresh classify — #962/#975
        // preserved (the daemon re-evaluates so a live agent can bind).
        vm.applyParsedPanesForTest(listOf(shellPane(command = "claude")))
        runCurrent()
        assertEquals(
            "#962/#975 preserved: a real input change (an agent starting in the " +
                "shell → command change) busts the one-shot guess and re-evaluates " +
                "the daemon exactly ONCE so the live agent can bind",
            afterVerdict + 1,
            session.classifyExecCount,
        )

        // The changed input is now the dedup key — subsequent unchanged reconciles
        // are one-shot again (no per-reconcile re-fire).
        repeat(3) {
            vm.applyParsedPanesForTest(listOf(shellPane(command = "claude")))
            runCurrent()
        }
        assertEquals(
            "#1641: after re-classifying on the real trigger, subsequent unchanged " +
                "reconciles do not re-fire the classify",
            afterVerdict + 1,
            session.classifyExecCount,
        )
    }

    @Test
    fun b2ReattachReStampDoesNotDropRememberedAgentPlaceholder() = runTest(scheduler) {
        // B2 (#959 beyond-grace reattach re-stamp): on reconnect the screen
        // re-reads `@ps_agent_kind=shell` and re-applies applyRecordedShellVerdict
        // (isShell=true). On base that UNCONDITIONALLY dropped the just-restored
        // remembered-agent placeholder (#819 A2), re-suppressing the Conversation
        // toggle for a session that WAS a live agent before backgrounding — the
        // raw black Terminal strand. The fix keeps the remembered-agent placeholder
        // (only the fresh #878 auto-seed is dropped); detection re-confirms it.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        // A beyond-grace reattach restored the remembered-agent resolving
        // placeholder (#819 A2 — detection-less, on Conversation).
        vm.seedRememberedAgentPlaceholderForTest("%0")
        runCurrent()
        assertNotNull(
            "#975 (B2): precondition — the remembered-agent placeholder is restored",
            vm.agentConversations.value["%0"],
        )
        assertEquals(
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )

        // The reconnect re-read re-applies the recorded-shell verdict.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()

        assertNotNull(
            "#975 (B2): the reattach re-stamp must NOT drop the remembered-agent " +
                "placeholder (RED on base — applyRecordedShellVerdict tore it down " +
                "and the Conversation toggle disappeared post-reconnect)",
            vm.agentConversations.value["%0"],
        )
        assertTrue(
            "#975 (B2): the surviving row is still the remembered-agent placeholder",
            vm.agentConversations.value["%0"]!!.rememberedAgentPlaceholder,
        )
        assertEquals(
            "#975 (B2): it is still on the Conversation surface (the toggle survives)",
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )
    }

    @Test
    fun b2ReattachReStampStillDropsFreshAutoSeededPlaceholder() = runTest(scheduler) {
        // B2 adjacency (#894): the B2 fix must NOT resurrect the #894 first-open
        // FLASH — a FRESH #878 auto-seeded placeholder (NOT a remembered agent)
        // racing ahead of the recorded-shell read must STILL be dropped on the
        // confirmed-shell verdict. Only the remembered-agent placeholder is spared.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        // The fresh #878 auto-seed is up (autoSeededPlaceholder = true).
        assertTrue(
            "#894: precondition — the fresh auto-seed is up",
            vm.agentConversations.value["%0"]?.autoSeededPlaceholder == true,
        )

        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()

        assertNull(
            "#975 (B2 adjacency / #894): a FRESH auto-seeded placeholder is STILL " +
                "dropped on the confirmed-shell verdict (the first-open flash kill " +
                "is preserved — only the remembered-agent placeholder is spared)",
            vm.agentConversations.value["%0"],
        )
    }

    /**
     * Issue #975: connect + register a single pane carrying a real [cwd], [paneTty]
     * and [panePid] (the foreign-detection inputs the one-shot daemon guess + the
     * transcript fallback need), with [session] installed as the active sessionRef
     * so the REAL detection chain runs against the fake masked-agent host.
     */
    private fun TmuxSessionViewModel.connectWithRichPaneForTest(
        paneId: String,
        windowId: String,
        cwd: String,
        paneTty: String,
        panePid: Long,
        session: SshSession,
        sessionName: String = "work",
    ) {
        replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
            client = FakeTmuxClient(),
            session = session,
        )
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId,
                    windowId,
                    "$0",
                    "node",
                    paneIndex = 0,
                    cwd = cwd,
                    currentCommand = "node",
                    paneTty = paneTty,
                    panePid = panePid,
                    sessionName = sessionName,
                ),
            ),
        )
        setSessionRefForTest(session)
    }

    @Test
    fun terminalDefaultNeverSeedsPlaceholderRegardlessOfShellVerdict() = runTest(scheduler) {
        // G2 class coverage (default = Terminal, both shell and agent): when the
        // user opted into the Terminal default, NOTHING is ever auto-seeded — the
        // raw Terminal IS the intended pre-detection view. The Slice C shell gate
        // must not change that (it is an early-return BEFORE the Terminal check
        // only matters for Conversation), and a presumed-agent pane likewise gets
        // no placeholder on the Terminal default.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        assertNull(
            "#894 (G2): Terminal default never seeds a placeholder (presumed agent)",
            vm.agentConversations.value["%0"],
        )

        // Even a confirmed-shell verdict on the Terminal default seeds nothing.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        vm.seedPresumedAgentPlaceholderForTest("%0")
        runCurrent()
        assertNull(
            "#894 (G2): Terminal default + confirmed shell still seeds nothing",
            vm.agentConversations.value["%0"],
        )
        assertTrue(
            "#894 (G2): the confirmed-shell verdict is still published on Terminal default",
            "%0" in vm.confirmedShellPaneIds.value,
        )
    }

    @Test
    fun freshForeignNoGuessPaneShowsPlaceholderThenDropsOnNullDetection() = runTest(scheduler) {
        // AC1 (class: a FOREIGN / no-guess pane — the daemon does NOT classify
        // it as an agent, so detection comes back NULL). The user STILL sees the
        // detecting placeholder during the detection window (not the black
        // Terminal); when the null verdict lands, the auto-seeded placeholder is
        // dropped so a genuine shell does not linger on "Loading…" → "Failed".
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        val placeholder = vm.agentConversations.value["%0"]
        assertNotNull(
            "#878: even a foreign/no-guess pane shows the detecting placeholder" +
                " during the detection window (not the black Terminal)",
            placeholder,
        )
        assertEquals(SessionTab.Conversation, placeholder!!.selectedTab)
        assertNull(placeholder.detection)
        assertTrue(placeholder.autoSeededPlaceholder)

        // The daemon returns no agent kind → null detection for this pane. The
        // FIRST null is DEFERRED (#878): a transient null mid-detection must not
        // flash the black Terminal, so the placeholder is held and re-confirmed.
        val firstNull = vm.handleNullAgentDetectionForTest("%0")
        runCurrent()
        assertFalse(
            "#878: the FIRST null defers (holds the placeholder, does not flash black Terminal)",
            firstNull,
        )
        assertNotNull(
            "#878: the auto-seeded placeholder survives the first transient null",
            vm.agentConversations.value["%0"],
        )
        assertEquals(
            "#878: the held row is still the detecting placeholder",
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )

        // A SECOND consecutive null (AGENT_EXIT_CONFIRMATIONS = 2) confirms the
        // pane is a genuine shell/foreign → the placeholder is DROPPED so it does
        // not linger on "Loading…" → "Failed".
        val secondNull = vm.handleNullAgentDetectionForTest("%0")
        runCurrent()
        assertTrue(
            "#878: the second confirming null downgrades a genuine shell/foreign pane",
            secondNull,
        )
        assertNull(
            "#878: the auto-seeded placeholder is DROPPED once the shell/foreign verdict is confirmed",
            vm.agentConversations.value["%0"],
        )
    }

    @Test
    fun autoSeedDoesNotOverwriteRememberedTerminalChoiceNoYank() = runTest(scheduler) {
        // AC2 (#815 no-yank): a window whose user previously opted into Terminal
        // must reattach on Terminal — the #878 auto-seed must NOT overwrite the
        // remembered status with the Conversation default.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // The user saw the agent but stayed on Terminal — remember Terminal.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        runCurrent()

        // Reattach: a new pane id under the SAME window. seed-from-memory runs
        // FIRST and restores the remembered Terminal row; the #878 auto-seed
        // then no-ops because a row already exists (current != null).
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        val restored = vm.agentConversations.value["%7"]!!
        assertEquals(
            "#878/#815: the remembered Terminal choice is NOT overwritten by the auto-seed",
            SessionTab.Terminal,
            restored.selectedTab,
        )
        assertFalse(
            "#878: a remembered/explicit row is never an auto-seeded placeholder",
            restored.autoSeededPlaceholder,
        )
    }

    @Test
    fun autoSeedDoesNotOverwriteRememberedConversationRowNoYank() = runTest(scheduler) {
        // AC2 (#815 no-yank): a window whose user previously had a Conversation
        // row must reattach on the REMEMBERED Conversation tab — NOT replaced by
        // a fresh #878 auto-seed placeholder. seed-from-memory wins (it runs
        // first and creates the row, so seedPresumedAgentPlaceholder no-ops).
        //
        // Issue #819 (A2): the remembered row is now restored as a REMEMBERED-
        // agent resolving placeholder (rememberedAgentPlaceholder = true,
        // detection-less) — NOT the remembered detection rendered Live (which
        // could re-show a stale/sibling source). It is still distinct from the
        // #878 AUTO-seed (autoSeededPlaceholder = false), so the no-yank line
        // holds: the remembered Conversation choice survives.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        val restored = vm.agentConversations.value["%7"]!!
        assertEquals(
            "#878/#815: the remembered Conversation choice survives the auto-seed",
            SessionTab.Conversation,
            restored.selectedTab,
        )
        assertNull(
            "#819 (A2): the reattach seed restores a resolving placeholder, not " +
                "the remembered (possibly stale) source rendered Live",
            restored.detection,
        )
        assertTrue(
            "#819 (A2): the remembered row is a remembered-agent resolving placeholder",
            restored.rememberedAgentPlaceholder,
        )
        assertFalse(
            "#878: a restored remembered row is the remembered-agent placeholder, " +
                "NOT the #878 auto-seed placeholder",
            restored.autoSeededPlaceholder,
        )
    }

    @Test
    fun autoSeedSuppressedWhenOpenTimeDefaultIsTerminal() = runTest(scheduler) {
        // AC (opt-out): when the user opted the open-time default to Terminal,
        // the raw Terminal IS the intended pre-detection view, so #878 seeds
        // NOTHING — the pager shows the Terminal as before (no placeholder).
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        assertNull(
            "#878: with the Terminal open-time default, no pre-detection placeholder is seeded",
            vm.agentConversations.value["%0"],
        )
    }

    @Test
    fun midSessionAgentTakeoverDoesNotYankUserOffTerminal() = runTest(scheduler) {
        // Issue #815/#818 (the no-mid-session-yank line): a DIFFERENT agent
        // taking over a pane's window (no same-source continuity) is a
        // detection/refresh on an ALREADY-open session, NOT an open-time event.
        // It must NEVER force the user onto another view — even when the global
        // open-time default is Conversation. A user watching the raw Terminal
        // stays on Terminal through the takeover.
        val vm = newVm()
        // Global default = Conversation (the #818 default) to prove the takeover
        // does NOT apply the open-time default mid-session.
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        // First agent lands and creates the row (open-time default = Conversation).
        vm.markAgentTailLiveForTest("%0", newClaudeDetection())
        runCurrent()
        assertEquals(
            "precondition: the first OPEN lands on the Conversation default",
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )
        // The user deliberately moves to the raw Terminal mid-session.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        runCurrent()
        assertEquals(
            "precondition: the user is now on Terminal",
            SessionTab.Terminal,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )

        // A different agent (Codex) takes over the same pane — no same-source
        // continuity, so the takeover branch (current.detection != detection)
        // rebuilds the row. It must PRESERVE the user's Terminal tab, NOT yank
        // them to the Conversation default.
        vm.markAgentTailLiveForTest("%0", newCodexDetection())
        runCurrent()
        val takenOver = vm.agentConversations.value["%0"]!!
        assertEquals(
            "a mid-session takeover must NOT yank the user off Terminal (#815)",
            SessionTab.Terminal,
            takenOver.selectedTab,
        )
        assertEquals(AgentKind.Codex, takenOver.detection?.agent)
    }

    @Test
    fun midSessionRefreshDoesNotYankUserOffTerminalWithConversationDefault() = runTest(scheduler) {
        // Issue #815/#818: the core no-yank invariant for the SAME-agent
        // refinement path. With the open-time default = Conversation, a user who
        // opened on Conversation and then moved to Terminal must NOT be bounced
        // back to Conversation when live detection re-lands (a reconnect /
        // confidence drift on the same agent + same log).
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.markAgentTailLiveForTest("%0", newClaudeDetection())
        runCurrent()
        // The user moves to the raw Terminal.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        runCurrent()
        assertEquals(SessionTab.Terminal, vm.agentConversations.value["%0"]!!.selectedTab)

        // Same-agent re-detection (only confidence drifted) re-lands.
        vm.markAgentTailLiveForTest(
            "%0",
            newClaudeDetection().copy(confidence = AgentDetection.Confidence.RecentFile),
        )
        runCurrent()
        assertEquals(
            "a same-agent mid-session refresh must NOT yank the user back to the Conversation default",
            SessionTab.Terminal,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )
    }

    @Test
    fun nonAgentShellNeverGetsAConversationRow() = runTest(scheduler) {
        // Issue #815 guard: a plain shell pane (no agent detected) never gets a
        // conversation row at all — markAgentTailLive is only ever called once an
        // agent is detected — so it stays on the raw Terminal. This proves
        // detection-driven row creation can NEVER touch a non-agent/shell pane.
        val vm = newVm()
        // Issue #878: pin the open-time default to Terminal so the pane-add
        // auto-seed no-ops and this test keeps its original "never-agent window
        // has NO seed, so the first null downgrades immediately" semantics. The
        // #878 auto-seed + transient-null-defer behaviour for a shell under the
        // Conversation default is covered by
        // freshForeignNoGuessPaneShowsPlaceholderThenDropsOnNullDetection.
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        // The pane is a plain "shell" (see connectWithPaneForTest's ParsedPane).
        runCurrent()

        // No detection lands. handleNullAgentDetection is the path taken when live
        // detection comes back null for a never-agent window.
        val downgraded = vm.handleNullAgentDetectionForTest("%0")
        runCurrent()

        assertTrue("a never-agent window has no seed to protect", downgraded)
        assertNull(
            "a non-agent shell pane has no conversation row, so it stays on raw Terminal",
            vm.agentConversations.value["%0"],
        )
    }

    @Test
    fun rememberedConversationChoiceIsHonoredOnReattach() = runTest(scheduler) {
        // Issue #815: detection never changes the tab, but a remembered/explicit
        // user choice must still WIN. A user who deliberately moved to
        // Conversation must be put back on Conversation on reattach, NOT reset to
        // the Terminal default. (Seed-from-memory honours wasOnConversation
        // BEFORE markAgentTailLive runs, so the remembered Conversation row
        // already exists and live re-detection refines it without resetting it.)
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // The user deliberately moved to Conversation.
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Reattach: a new pane id under the same window; memory restores the row.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()
        assertEquals(
            "remembered Conversation choice is restored on reattach, not reset to the Terminal default",
            SessionTab.Conversation,
            vm.agentConversations.value["%7"]!!.selectedTab,
        )

        // Live re-detection lands for the SAME agent + same log: it must still
        // honour the remembered Conversation tab (same-source refinement preserves it).
        vm.markAgentTailLiveForTest(
            "%7",
            newClaudeDetection().copy(confidence = AgentDetection.Confidence.RecentFile),
        )
        runCurrent()
        assertEquals(
            "same-agent refinement keeps the user's remembered Conversation tab",
            SessionTab.Conversation,
            vm.agentConversations.value["%7"]!!.selectedTab,
        )
    }

    @Test
    fun reconnectDoesNotResurrectAgentThatExited() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Live detection reports the window no longer hosts an agent (the
        // user exited Claude). This reconciles the remembered status.
        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()

        // Reconnect: the same window must NOT resurrect the EXITED agent. The
        // remembered status was forgotten on exit, so seed-from-memory restores
        // nothing.
        //
        // Issue #878: the pane-add auto-seed DOES paint a transient detecting
        // placeholder during the (now-pending) re-detection — this is the
        // black-screen cure, identical to a fresh open: we don't know yet that
        // the window is now a shell. Crucially the placeholder carries NO
        // detection (autoSeededPlaceholder=true), so the EXITED agent is NOT
        // resurrected — agentForWindow stays null — and the null re-detection
        // verdict drops the placeholder (asserted below).
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        val transient = vm.agentConversations.value["%7"]
        if (transient != null) {
            assertTrue(
                "#878: any row on the reconnected window is only the detection-less" +
                    " auto-seed placeholder, never the resurrected agent",
                transient.autoSeededPlaceholder && transient.detection == null,
            )
        }
        assertNull(
            "the EXITED agent is never resurrected on reconnect (no detection lights up)",
            vm.agentForWindow("@0"),
        )

        // The null re-detection verdict lands. If an auto-seeded placeholder is
        // up (#878), the first null is DEFERRED (held + re-confirmed) so the
        // detecting placeholder does not flash the black Terminal; the SECOND
        // confirming null drops it. Drive consecutive nulls until the window
        // settles with no conversation row.
        var attempts = 0
        while (vm.agentConversations.value["%7"] != null && attempts < AGENT_EXIT_CONFIRMATIONS + 1) {
            vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            attempts++
        }
        assertNull(
            "an exited agent's window settles with no conversation row after the null verdict",
            vm.agentConversations.value["%7"],
        )
        assertNull("no Conversation tab for the exited agent's window", vm.agentForWindow("@0"))
    }

    // ─── Issue #554: transient null detection must not forget the agent ──

    /**
     * Issue #554: on reconnect, live detection routinely reads "no agent" for
     * a beat before the agent's JSONL log / process is observable on the fresh
     * connection. A remembered (seeded) agent window must NOT be downgraded to
     * a plain shell on that FIRST transient null — the seeded Conversation UI
     * stays up and detection re-confirms. Downgrading there was the
     * "we forget it's an agent and bounce to plain-shell-then-back" regression.
     */
    @Test
    fun transientNullDetectionDoesNotForgetRememberedAgentOnReconnect() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Reconnect: a rotated pane id under the same window, seeded from
        // memory so the agent UI shows immediately.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()
        // Issue #819 (A2): the reattach seed is a detection-less remembered-agent
        // resolving placeholder (the agent UI is available, but the route-true
        // source is bound by live re-detection, not the remembered one).
        assertTrue(
            "precondition: remembered-agent resolving placeholder restored on reattach",
            vm.agentConversations.value["%7"]?.rememberedAgentPlaceholder == true,
        )

        // First live-detection null right after the reattach: DEFER, do not
        // forget. The seeded agent UI must survive. This is the #554 no-flap
        // guarantee — A2 preserves it for the detection-less placeholder via
        // shouldDeferAgentDowngrade keying off rememberedAgentPlaceholder.
        val downgraded = vm.handleNullAgentDetectionForTest("%7")
        runCurrent()
        assertFalse("first transient null must be deferred, not a downgrade", downgraded)
        assertNotNull(
            "the seeded agent UI must survive a single transient null",
            vm.agentConversations.value["%7"],
        )
        assertTrue(
            "the remembered-agent resolving placeholder survives the transient null",
            vm.agentConversations.value["%7"]?.rememberedAgentPlaceholder == true,
        )
        assertEquals(
            "the Conversation tab stays selected for the window",
            SessionTab.Conversation,
            vm.agentConversations.value["%7"]?.selectedTab,
        )
    }

    @Test
    fun degradedDetectionDoesNotForgetRememberedAgentOrConsumeExitConfirmations() = runTest(scheduler) {
        val detections = listOf(
            newClaudeDetection(),
            newCodexDetection(),
            newOpenCodeDetection(),
        )

        detections.forEachIndexed { index, detection ->
            val windowId = "@$index"
            val vm = newVm()
            vm.connectWithPaneForTest(paneId = "%0", windowId = windowId, sessionName = "work-$index")
            vm.startAgentConversationForTest("%0", detection)
            vm.selectSessionTab("%0", SessionTab.Conversation)
            runCurrent()

            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        "%7",
                        windowId,
                        "$0",
                        "shell",
                        paneIndex = 0,
                        sessionName = "work-$index",
                    ),
                ),
            )
            runCurrent()

            // Issue #819 (A2): the reattach seed is a detection-less remembered-
            // agent resolving placeholder. A degraded probe must keep retrying
            // it (#897) — it must NOT be treated as an agent exit, and the
            // remembered placeholder stays visible on the Conversation tab.
            repeat(AGENT_EXIT_CONFIRMATIONS) {
                val downgraded = vm.handleUnavailableAgentDetectionForTest("%7")
                runCurrent()
                assertFalse(
                    "#897: degraded ${detection.agent} probe must not be treated as agent exit",
                    downgraded,
                )
                val row = vm.agentConversations.value["%7"]
                assertNotNull("#897: remembered ${detection.agent} row stays visible", row)
                assertEquals(SessionTab.Conversation, row!!.selectedTab)
                assertTrue(
                    "#897/#819 (A2): the remembered-agent resolving placeholder survives the degraded probe",
                    row.rememberedAgentPlaceholder,
                )
            }

            val firstCleanNull = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            assertFalse(
                "#897: degraded probes must not consume the clean-null exit confirmation budget",
                firstCleanNull,
            )
            assertTrue(
                "#819 (A2): the remembered placeholder survives the first clean null",
                vm.agentConversations.value["%7"]?.rememberedAgentPlaceholder == true,
            )

            val secondCleanNull = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            assertTrue("clean nulls still confirm a genuine ${detection.agent} exit", secondCleanNull)
            assertNull(vm.agentConversations.value["%7"])
            assertNull(vm.agentForWindow(windowId))
            vm.clearForTest()
        }
    }

    /**
     * Issue #942 (black-screen B2, reopen-class D31): a remembered Conversation
     * row collapsed to the raw black Terminal after 2× consecutive
     * successful-but-EMPTY (`Resolved(null)`) detections on a wedged-but-alive
     * channel — the capture/grep raced behind a busy agent (#470/#835) and read
     * "no agent" while the agent was very much alive and still streaming output.
     * #897 protected only the `Unavailable` (probe-threw) branch; the empty-grep
     * `Resolved(null)` branch still counted toward [AGENT_EXIT_CONFIRMATIONS] and
     * tore the row down. The maintainer's 2026-06-24 Claude `faq-assistant` black
     * capture.
     *
     * RED (no fix): each empty null on the streaming channel counts toward exit;
     * the second confirms and the remembered Conversation row is cleared.
     * GREEN (fix): the still-streaming channel (recent `%output`) marks the empty
     * detection as wedged-but-alive, it does NOT count toward exit confirmation,
     * and the remembered Conversation row is preserved across both nulls.
     *
     * Class-cover: Claude, Codex AND OpenCode kinds.
     */
    @Test
    fun emptyDetectionOnWedgedButAliveChannelDoesNotCollapseRememberedConversation() = runTest(scheduler) {
        val detections = listOf(
            newClaudeDetection(),
            newCodexDetection(),
            newOpenCodeDetection(),
        )

        detections.forEachIndexed { index, detection ->
            val windowId = "@$index"
            val vm = newVm()
            vm.connectWithPaneForTest(paneId = "%0", windowId = windowId, sessionName = "wedged-$index")
            vm.startAgentConversationForTest("%0", detection)
            vm.selectSessionTab("%0", SessionTab.Conversation)
            runCurrent()

            // Reattach: the pane comes back as a remembered-agent resolving
            // placeholder (the #495/#819 A2 seed) — the exact remembered
            // Conversation state the maintainer had open when it went black.
            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        "%7",
                        windowId,
                        "$0",
                        "shell",
                        paneIndex = 0,
                        sessionName = "wedged-$index",
                    ),
                ),
            )
            runCurrent()
            assertTrue(
                "#819 (A2): the remembered ${detection.agent} placeholder is up before the empty detections",
                vm.agentConversations.value["%7"]?.rememberedAgentPlaceholder == true,
            )

            // The `-CC` channel is WEDGED-but-ALIVE: it is still streaming
            // `%output` for this pane (a busy agent), so the grep raced an empty
            // read. Inject 2× empty `Resolved(null)` while output keeps arriving.
            repeat(AGENT_EXIT_CONFIRMATIONS) {
                vm.recordPaneOutputActivityForTest("%7")
                assertTrue(
                    "#942: a freshly-streaming channel reads wedged-but-alive",
                    vm.isChannelWedgedButAliveForTest("%7"),
                )
                val downgraded = vm.handleNullAgentDetectionForTest("%7")
                runCurrent()
                assertFalse(
                    "#942: an empty grep on a streaming (wedged-but-alive) ${detection.agent} channel must NOT confirm agent exit",
                    downgraded,
                )
                assertTrue(
                    "#942: the remembered ${detection.agent} Conversation row survives the empty detection (no black Terminal)",
                    vm.agentConversations.value["%7"]?.rememberedAgentPlaceholder == true,
                )
            }

            vm.clearForTest()
        }
    }

    /**
     * Issue #942: the wedged-channel guard must NOT over-protect — a GENUINE
     * agent exit stops emitting output, so its empty `Resolved(null)` arrives on
     * a now-IDLE channel and must still tear the Conversation row down after
     * [AGENT_EXIT_CONFIRMATIONS] consecutive nulls. Class-cover Claude/Codex/
     * OpenCode so a kind-specific over-protection regression is caught.
     */
    @Test
    fun emptyDetectionOnIdleChannelStillTearsDownAGenuinelyExitedAgent() = runTest(scheduler) {
        val detections = listOf(
            newClaudeDetection(),
            newCodexDetection(),
            newOpenCodeDetection(),
        )

        detections.forEachIndexed { index, detection ->
            val windowId = "@$index"
            val vm = newVm()
            vm.connectWithPaneForTest(paneId = "%0", windowId = windowId, sessionName = "exited-$index")
            vm.startAgentConversationForTest("%0", detection)
            vm.selectSessionTab("%0", SessionTab.Conversation)
            runCurrent()

            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        "%7",
                        windowId,
                        "$0",
                        "shell",
                        paneIndex = 0,
                        sessionName = "exited-$index",
                    ),
                ),
            )
            runCurrent()

            // The agent exited: the channel went IDLE (no `%output`). The empty
            // grep is now a TRUE "no agent" verdict, not a wedged race.
            vm.clearPaneOutputActivityForTest("%7")
            assertFalse(
                "#942: an idle channel must not read wedged-but-alive",
                vm.isChannelWedgedButAliveForTest("%7"),
            )

            val firstNull = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            assertFalse(
                "#554: the first clean null defers (confirmation window) for ${detection.agent}",
                firstNull,
            )

            val secondNull = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            assertTrue(
                "#942: a real ${detection.agent} exit on an idle channel still tears the row down (no over-protection)",
                secondNull,
            )
            assertNull(
                "#942: the genuinely-exited ${detection.agent} Conversation row is gone",
                vm.agentConversations.value["%7"],
            )
            assertNull(vm.agentForWindow(windowId))
            vm.clearForTest()
        }
    }

    /**
     * Issue #554: the deferral is a confirmation window, not a permanent
     * pin. A genuinely-exited agent (null detection persisting past
     * [AGENT_EXIT_CONFIRMATIONS]) still reconciles away so a stale
     * Conversation tab does not linger.
     */
    @Test
    fun persistentNullDetectionEventuallyDowngradesAnExitedAgent() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        // Drive null detections up to the confirmation threshold. The last one
        // must downgrade the window to a plain shell.
        var downgraded = false
        repeat(AGENT_EXIT_CONFIRMATIONS) {
            downgraded = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
        }

        assertTrue("a persistently-null agent must eventually downgrade", downgraded)
        assertNull(
            "an agent that genuinely exited must reconcile away after confirmation",
            vm.agentConversations.value["%7"],
        )
        assertNull("no Conversation tab once the agent is confirmed gone", vm.agentForWindow("@0"))
    }

    /**
     * Issue #554: a null detection for a window that was NEVER an agent (no
     * remembered status, no seeded UI) downgrades immediately — the
     * confirmation window is only for protecting a remembered agent seed (or,
     * per #878, an auto-seeded detecting placeholder), not for delaying the
     * normal plain-shell verdict when there is nothing to protect.
     */
    @Test
    fun nullDetectionDowngradesImmediatelyWhenWindowWasNeverAnAgent() = runTest(scheduler) {
        val vm = newVm()
        // Issue #878: pin the open-time default to Terminal so there is no
        // auto-seeded placeholder to protect — this isolates the original #554
        // "nothing to protect → downgrade immediately" path. (The #878
        // placeholder-defer behaviour under the Conversation default is covered
        // by freshForeignNoGuessPaneShowsPlaceholderThenDropsOnNullDetection.)
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        val downgraded = vm.handleNullAgentDetectionForTest("%0")
        runCurrent()

        assertTrue("a never-agent window has no seed to protect — downgrade now", downgraded)
        assertNull(vm.agentConversations.value["%0"])
    }

    @Test
    fun liveDetectionRefiningSameAgentKeepsRestoredConversationTab() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()
        // Seed restored Conversation.
        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%7"]!!.selectedTab)

        // Live re-detection lands for the SAME agent + same log but with a
        // drifted confidence. The user must NOT be bounced to Terminal.
        vm.markAgentTailLiveForTest(
            "%7",
            newClaudeDetection().copy(confidence = AgentDetection.Confidence.RecentFile),
        )
        runCurrent()

        val state = vm.agentConversations.value["%7"]!!
        assertEquals(
            "same-agent refinement preserves the user's Conversation tab",
            SessionTab.Conversation,
            state.selectedTab,
        )
        assertEquals(
            AgentDetection.Confidence.RecentFile,
            state.detection?.confidence,
        )
    }

    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
        private var wcOutput: String = "0\n",
        private var initialEventsOutput: String = "",
        private val detectionOutput: String = "",
        private val recordedKindOutput: String = "",
        private var recordedSourceGenerationOutput: String = "",
        private var recordedSourceOutput: String = "",
    ) : SshSession {
        @Volatile
        var closed: Boolean = false

        val execCommands: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

        fun setRecordedSourceGenerationOutput(value: String) {
            recordedSourceGenerationOutput = value
        }

        fun setRecordedSourceOutput(value: String) {
            recordedSourceOutput = value
        }

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            val stdout = when {
                command.contains("@@PS_RECORDED_KIND@@") -> buildString {
                    append(recordedKindOutput.trim())
                    append("\n@@PS_RECORDED_KIND@@\n")
                    append(recordedSourceGenerationOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                    append(recordedSourceOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE@@\n")
                    append(detectionOutput)
                    append("\n@@PS_CLAUDE_WINDOW@@\n")
                }
                command.contains("show-options -v") && command.contains("@ps_agent_kind") ->
                    recordedKindOutput
                command.contains("@@PS_RECORDED_SOURCE_GENERATION@@") -> buildString {
                    append(recordedSourceGenerationOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                    append(recordedSourceOutput.trim())
                }
                command.contains("show-options -v") && command.contains("@ps_agent_source") ->
                    recordedSourceOutput
                command.contains("@@PS_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_WINDOW@@\n$initialEventsOutput"
                command.contains("wc -l < ") -> wcOutput
                command.startsWith("tail -n ") -> initialEventsOutput
                command.contains("netstat -tlnp") || command.contains("ss -tln") -> ""
                command.contains(".claude") ||
                    command.contains(".codex") ||
                    command.contains("opencode") -> detectionOutput
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError()

        override fun startShell(): SshShell = throw NotImplementedError()

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
        }
    }

    /**
     * Issue #975: an [SshSession] double modelling the MASKED-LIVE-AGENT host the
     * maintainer hit -- the agent-kind daemon's cgroup-v2/`/proc` classify returns
     * `unknown` (or `none`) for a node-wrapped/quiet `claude`, while the agent's
     * `*.jsonl` transcript is genuinely present in the cwd-encoded log dir. It
     * answers exactly the two execs the foreign-detection chain issues:
     *   - the `pocketshell agents kind` daemon classify (JSON envelope, with
     *     [classifyAgentKind] one of `claude`/`codex`/`opencode`/`none`/`unknown`),
     *   - the cwd-scoped candidate enumeration ([detectionCommand], `claude_dir=`),
     *     returning [detectionOutput].
     * This is the #780 synthetic-state injection at the host seam -- it reproduces
     * the classify-miss the real Docker fixture also produces (scope=null in a
     * non-systemd container), exercising the REAL resolver, not a markAgentTailLive
     * injection.
     */
    private class MaskedAgentSshSession(
        private val classifyAgentKind: String,
        private val detectionOutput: String = "",
        private val hostWideProcessOutput: String = "",
    ) : SshSession {
        override val isConnected: Boolean = true

        // Issue #1001: count `agents kind` daemon classify round-trips so the B1'
        // dedup-ordering test can assert the cache-bust forced a FRESH daemon
        // re-evaluation deterministically -- instead of relying on the (now-fixed)
        // race that left the re-probe unfinished when `runCurrent()` returned.
        var classifyExecCount: Int = 0
            private set

        override suspend fun exec(command: String): ExecResult {
            val stdout = when {
                command.contains("agents kind") -> {
                    classifyExecCount++
                    val paneIds = PANE_ID_RE.findAll(command).map { it.groupValues[1] }.toList()
                    buildString {
                        append("{\"results\":[")
                        paneIds.forEachIndexed { index, paneId ->
                            if (index > 0) append(',')
                            append("{\"pane_id\":\"").append(paneId).append("\",")
                            append("\"agent_kind\":\"").append(classifyAgentKind).append("\",")
                            append("\"scope\":null}")
                        }
                        append("]}")
                    }
                }
                command.contains("claude_dir=") -> detectionOutput
                command.contains("ps -eo pid,ppid,tty,comm,args") -> hostWideProcessOutput
                command.contains("ps -eo pid,tty,comm,args") -> hostWideProcessOutput
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()
        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job = Job()
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError()
        override fun startShell(): SshShell = throw NotImplementedError()
        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used")
        override fun close() = Unit

        private companion object {
            val PANE_ID_RE = Regex("\"pane_id\":\"([^\"]+)\"")
        }
    }
}
