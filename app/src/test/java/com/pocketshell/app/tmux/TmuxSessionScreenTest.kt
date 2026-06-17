package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.layout.imeKeyboardPanOffsetPx
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.session.conversationSyncStatusLabel
import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.terminal.selection.LocalhostUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxSessionScreenTest {
    @Test
    fun reconnectUiRenderingRecordsCauseTrail() {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            recordTmuxReconnectUiStateRendered(
                status = TmuxSessionViewModel.ConnectionStatus.Reconnecting(
                    host = "alpha.example",
                    port = 22,
                    user = "alex",
                    attempt = 2,
                    maxAttempts = 3,
                    retryDelayMs = 1_000L,
                    reason = "network changed",
                ),
                hostId = 7L,
                canReconnect = true,
            )

            val trail = diagnostics.eventsNamed("cause_trail").single()
            assertEquals("ui_reconnect_state", trail.fields["stage"])
            assertEquals("rendered", trail.fields["outcome"])
            assertEquals("connection_status_reconnecting", trail.fields["cause"])
            assertEquals(7L, trail.fields["hostId"])
            assertEquals(2, trail.fields["attempt"])
            assertEquals(3, trail.fields["maxAttempts"])
            assertEquals(1_000L, trail.fields["retryDelayMs"])
            assertEquals(true, trail.fields["canReconnect"])
        } finally {
            diagnostics.close()
        }
    }

    // --- Issue #652: tap-A-must-open-A under a unified-pager reorder. ---

    @Test
    fun settleOnStaleIndexAfterSwitchDoesNotSwitchBackToOtherSession() {
        // Regression for #652: user taps session A. `connect(A)` makes A active
        // and `unifiedPanes` reorders to [A-panes..., B-panes(cached)...]. The
        // pager still holds its pre-switch index, which now resolves to a B pane
        // (a DIFFERENT, unrelated project). Until the pager realigns to the
        // freshly-tapped target, that stale settle must be ignored — otherwise
        // the app warm-switches into B and routes the next prompt to B.
        val switchTo = settleSessionSwitchTarget(
            settledPaneSession = "project-b",
            navTargetSession = "project-a",
            pagerAlignedToNavTarget = false,
            userDraggedSinceAlignment = false,
        )

        assertNull(
            "a stale settled index before realignment must NOT trigger a switch",
            switchTo,
        )
    }

    @Test
    fun settleOnAlignedTargetSessionDoesNotSelfSwitch() {
        // Once the pager is aligned and sitting on the nav target's own pane,
        // settling there is a no-op (we're already in the right session).
        val switchTo = settleSessionSwitchTarget(
            settledPaneSession = "project-a",
            navTargetSession = "project-a",
            pagerAlignedToNavTarget = true,
            userDraggedSinceAlignment = true,
        )

        assertNull(switchTo)
    }

    @Test
    fun settleOnDifferentSessionWhenAlignedIsAGenuineUserSwipe() {
        // A real cross-session swipe: pager already aligned to A, the user
        // physically dragged the pager (userDraggedSinceAlignment = true) and
        // it settled on a B pane. THIS is the legitimate #626 warm-switch
        // trigger.
        val switchTo = settleSessionSwitchTarget(
            settledPaneSession = "project-b",
            navTargetSession = "project-a",
            pagerAlignedToNavTarget = true,
            userDraggedSinceAlignment = true,
        )

        assertEquals("project-b", switchTo)
    }

    @Test
    fun settleOnDifferentSessionWithoutAUserDragIsIgnored() {
        // Issue #634 (C->A return-to-origin content-bleed): the pager is
        // aligned to A, but the cross-session settle to a DIFFERENT session
        // arrived WITHOUT the user dragging the pager — it is a stale-index
        // recomposition echo of the switch we just performed (or the app's own
        // realignment scroll), NOT a genuine swipe. Honoring it warm-switched
        // the user back to the session they just left, leaving both sessions'
        // frames co-resident in the viewport. With no user drag, it must be
        // ignored so the deliberate return-to-origin switch sticks.
        val switchTo = settleSessionSwitchTarget(
            settledPaneSession = "project-c",
            navTargetSession = "project-a",
            pagerAlignedToNavTarget = true,
            userDraggedSinceAlignment = false,
        )

        assertNull(
            "a cross-session settle with no preceding user drag is a stale " +
                "echo and must NOT switch sessions",
            switchTo,
        )
    }

    @Test
    fun settleWithUnresolvableSessionDuringRebuildIsIgnored() {
        // Mid-rebuild the settled pane may not resolve to any session yet.
        val switchTo = settleSessionSwitchTarget(
            settledPaneSession = null,
            navTargetSession = "project-a",
            pagerAlignedToNavTarget = true,
            userDraggedSinceAlignment = true,
        )

        assertNull(switchTo)
    }

    @Test
    fun sessionSwitcherPagesMirrorReadySessionRowsAndMarkCurrent() {
        val pages = sessionSwitcherPages(
            state = HostTmuxSessionPickerState.Ready(
                request = pickerRequest(),
                rows = listOf(
                    HostTmuxSessionRow(name = "work"),
                    HostTmuxSessionRow(name = "logs", attached = true),
                ),
            ),
            currentSessionName = "work",
        )

        assertEquals(
            listOf(
                SessionSwitcherPage(name = "work", statusLabel = "current", selectable = false),
                SessionSwitcherPage(name = "logs", statusLabel = "attached", selectable = true),
            ),
            pages,
        )
    }

    @Test
    fun sessionSwitcherPagesKeepCurrentSessionWhenListIsTemporarilyStale() {
        val pages = sessionSwitcherPages(
            state = HostTmuxSessionPickerState.Ready(
                request = pickerRequest(),
                rows = listOf(HostTmuxSessionRow(name = "logs")),
            ),
            currentSessionName = "work",
        )

        assertEquals(
            listOf(
                SessionSwitcherPage(name = "work", statusLabel = "current", selectable = false),
                SessionSwitcherPage(name = "logs", statusLabel = "available", selectable = true),
            ),
            pages,
        )
    }

    @Test
    fun sessionSwitcherPagesKeepCurrentSessionFirstWhenRowsAreActivitySorted() {
        val pages = sessionSwitcherPages(
            state = HostTmuxSessionPickerState.Ready(
                request = pickerRequest(),
                rows = listOf(
                    HostTmuxSessionRow(name = "logs", attached = true),
                    HostTmuxSessionRow(name = "work"),
                ),
            ),
            currentSessionName = "work",
        )

        assertEquals(
            listOf(
                SessionSwitcherPage(name = "work", statusLabel = "current", selectable = false),
                SessionSwitcherPage(name = "logs", statusLabel = "attached", selectable = true),
            ),
            pages,
        )
    }

    @Test
    fun sessionSwitcherPagesFallbackIsNotSelectable() {
        val pages = sessionSwitcherPages(
            state = HostTmuxSessionPickerState.Fallback(
                request = pickerRequest(),
                message = "pocketshell/tmux is not available on this host.",
            ),
            currentSessionName = "work",
        )

        assertEquals(
            listOf(
                SessionSwitcherPage(
                    name = "work",
                    statusLabel = "pocketshell/tmux is not available on this host.",
                    selectable = false,
                ),
            ),
            pages,
        )
    }

    @Test
    fun handleTmuxSessionSelectionIgnoresCurrentSessionWithoutDismissing() {
        val events = mutableListOf<String>()

        handleTmuxSessionSelection(
            currentSessionName = "work",
            selectedSessionName = "work",
            onDismiss = { events += "dismiss" },
            onReplace = { events += "replace:$it" },
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun handleTmuxSessionSelectionDismissesThenReplacesDifferentSession() {
        val events = mutableListOf<String>()

        handleTmuxSessionSelection(
            currentSessionName = "work",
            selectedSessionName = "logs",
            onDismiss = { events += "dismiss" },
            onReplace = { events += "replace:$it" },
        )

        assertEquals(listOf("dismiss", "replace:logs"), events)
    }

    @Test
    fun acceptedLocalhostForwardNavigationCarriesAutoOpenUrl() {
        val localhostUrl = LocalhostUrl(
            remotePort = 5173,
            scheme = "https",
            pathAndQuery = "/preview?tab=logs#tail",
        )

        val target = acceptedLocalhostForwardNavigation(localhostUrl)

        assertEquals(5173, target.remotePort)
        assertEquals(localhostUrl, target.autoOpenLocalhostUrl)
    }

    @Test
    fun detectedPortForwardNavigationDoesNotRequestBrowserAutoOpen() {
        val target = detectedPortForwardNavigation(5173)

        assertEquals(5173, target.remotePort)
        assertEquals(null, target.autoOpenLocalhostUrl)
    }

    @Test
    fun projectCrumbLabelReturnsLeafFolderName() {
        assertEquals("pocketshell", projectCrumbLabel("/home/alexey/git/pocketshell"))
        assertEquals("pocketshell", projectCrumbLabel("/home/alexey/git/pocketshell/"))
        assertEquals("work", projectCrumbLabel("~/work"))
        assertEquals("~", projectCrumbLabel("~"))
        assertEquals("/", projectCrumbLabel("/"))
        assertEquals("/", projectCrumbLabel("   "))
    }

    /**
     * Issue #686 (D28, reveal/session-identity slice 1) — the header project
     * crumb must be keyed to the SINGLE target session identity. While a
     * cross-session switch is loading (switchHidesTerminal == true) the crumb is
     * derived from the LEAVING pane's cwd; if it is NOT suppressed it desyncs
     * from the session label (which already shows the target name) and the
     * header paints two identities at once — the v0.3.34 dogfood report.
     *
     * Fail-first: before the fix, [keyedProjectCrumbLabel] returned the leaving
     * project label during the switch window; the suppression-while-hidden
     * assertion below is RED. After the fix it returns null while hidden -> GREEN.
     */
    @Test
    fun keyedProjectCrumbSuppressedWhileSwitchHidesTerminal() {
        // During a switch the visible pane is still the LEAVING session's, so its
        // cwd resolves to the leaving project. The crumb MUST be suppressed so
        // the header shows the single target identity (the session label), not a
        // second, stale project identity.
        assertNull(
            "the header project crumb must be suppressed while a switch is hiding " +
                "the terminal — otherwise it wears the LEAVING session's project " +
                "label and the header shows two identities at once (#686)",
            keyedProjectCrumbLabel(
                projectPath = "/home/alexey/git/git-3d-models",
                switchHidesTerminal = true,
                targetSessionName = "session-b",
                visiblePaneSessionName = "session-a",
            ),
        )
        // Belt-and-braces: even a non-blank leaving path is suppressed.
        assertNull(
            keyedProjectCrumbLabel(
                projectPath = "/home/alexey/git/git-ai-shipping-labs",
                switchHidesTerminal = true,
                targetSessionName = "session-b",
                visiblePaneSessionName = "session-a",
            ),
        )
    }

    /**
     * Issue #686 — the second keying guard (independent of [switchHidesTerminal]):
     * the back->open-B path has sub-windows where the switch gate is briefly
     * false while the VISIBLE pane is still the LEAVING session's. In that state
     * the crumb would wear the leaving session's project while the label already
     * shows the target — two identities at once. Keying on the visible-pane
     * session vs the target session suppresses it.
     */
    @Test
    fun keyedProjectCrumbSuppressedWhenVisiblePaneIsNotTargetSession() {
        assertNull(
            "the crumb must be suppressed when the visible pane belongs to a " +
                "NON-target session — otherwise it wears that session's project " +
                "while the label shows the target (the #686 desync), even with the " +
                "switch gate already cleared",
            keyedProjectCrumbLabel(
                projectPath = "/home/alexey/git/git-3d-models",
                switchHidesTerminal = false,
                targetSessionName = "session-b",
                visiblePaneSessionName = "session-a",
            ),
        )
    }

    /**
     * Issue #686 — once the switch settles the visible pane IS the target's and
     * the crumb returns, keyed to the target pane's cwd. This proves the fix does
     * not permanently hide the crumb (no functional regression of the #463
     * project switcher) — it only suppresses the stale leaving crumb.
     */
    @Test
    fun keyedProjectCrumbShowsTargetLeafWhenVisiblePaneIsTarget() {
        assertEquals(
            "git-ai-shipping-labs",
            keyedProjectCrumbLabel(
                projectPath = "/home/alexey/git/git-ai-shipping-labs",
                switchHidesTerminal = false,
                targetSessionName = "session-b",
                visiblePaneSessionName = "session-b",
            ),
        )
        // A null visible-pane session (unknown — steady state) renders the crumb.
        assertEquals(
            "git-ai-shipping-labs",
            keyedProjectCrumbLabel(
                projectPath = "/home/alexey/git/git-ai-shipping-labs",
                switchHidesTerminal = false,
                targetSessionName = "session-b",
                visiblePaneSessionName = null,
            ),
        )
        // No crumb when the project path is unknown (cwd not yet known).
        assertNull(
            keyedProjectCrumbLabel(
                projectPath = null,
                switchHidesTerminal = false,
                targetSessionName = "session-b",
                visiblePaneSessionName = "session-b",
            ),
        )
    }

    @Test
    fun tmuxSessionTabStateShowsOnlyTerminalForNonAgentPane() {
        val state = tmuxSessionTabState(null)

        assertEquals(listOf("Terminal"), state.labels)
        assertEquals(0, state.selectedIndex)
        assertTrue(!state.showsConversationTab)
    }

    @Test
    fun tmuxSessionTabStateShowsConversationForAgentPane() {
        val state = tmuxSessionTabState(
            AgentConversationUiState(
                detection = claudeDetection(),
                selectedTab = SessionTab.Terminal,
            ),
        )

        assertEquals(listOf("Terminal", "Conversation"), state.labels)
        assertEquals(0, state.selectedIndex)
        assertTrue(state.showsConversationTab)
    }

    @Test
    fun tmuxSessionTabStateSelectsConversationOnlyForAgentPane() {
        val state = tmuxSessionTabState(
            AgentConversationUiState(
                detection = claudeDetection(),
                selectedTab = SessionTab.Conversation,
            ),
        )

        assertEquals(listOf("Terminal", "Conversation"), state.labels)
        assertEquals(1, state.selectedIndex)
        assertTrue(state.showsConversationTab)
    }

    // ─── Issue #716 (Slice A): presumed-agent keeps the composer/agent ──
    // surface available during the slow/uncertain detection window. ────────

    @Test
    fun presumedAgentIsTrueForFreshlyAttachedPaneWithNoDetection() {
        // A freshly attached pane with no live detection and no sticky agent
        // still defaults to presumed-agent — there is no positive shell verdict
        // yet, so the composer/agent surface must stay available (criterion #2).
        assertTrue(
            tmuxSessionPresumedAgent(
                hasLiveDetection = false,
                stickyAgent = null,
                confirmedShell = false,
            ),
        )
    }

    @Test
    fun presumedAgentIsTrueForStickyAgentDuringTransientNull() {
        // A pane that WAS detected as an agent (sticky kind present) but whose
        // live detection transiently dropped to null stays presumed-agent so
        // the surface does not flicker out (criterion #3, reuses #462 sticky).
        assertTrue(
            tmuxSessionPresumedAgent(
                hasLiveDetection = false,
                stickyAgent = AgentKind.ClaudeCode,
                confirmedShell = false,
            ),
        )
    }

    @Test
    fun presumedAgentIsFalseOnlyOnConfirmedShell() {
        // The ONLY thing that collapses the agent surface is a positively
        // confirmed shell verdict. Slice A never supplies one (Slice C will),
        // but the guard must honour it when it arrives.
        assertTrue(
            !tmuxSessionPresumedAgent(
                hasLiveDetection = false,
                stickyAgent = null,
                confirmedShell = true,
            ),
        )
        // Even a known agent kind is overridden by a confirmed-shell verdict
        // (an explicit agent->shell downgrade event, not mere absence).
        assertTrue(
            !tmuxSessionPresumedAgent(
                hasLiveDetection = true,
                stickyAgent = AgentKind.ClaudeCode,
                confirmedShell = true,
            ),
        )
    }

    @Test
    fun tmuxSessionTabStateShowsConversationTabForPresumedAgentWithoutDetection() {
        // Presumed-agent, no live detection, NOT yet selected: the Conversation
        // tab EXISTS (it does not vanish during slow detection) and the active
        // index stays on Terminal because the user hasn't tapped it.
        val state = tmuxSessionTabState(
            currentAgentConversation = null,
            presumedAgent = true,
        )

        assertEquals(listOf("Terminal", "Conversation"), state.labels)
        assertEquals(0, state.selectedIndex)
        assertTrue(state.showsConversationTab)
    }

    @Test
    fun tmuxSessionTabStateSelectsConversationForPresumedAgentWithoutDetection() {
        // Issue #778: tapping Conversation on a presumed-agent pane records the
        // intent as `selectedTab = Conversation` on a detection-less conversation
        // row. The active index MUST become 1 even though `detection == null` —
        // the tap is honoured and the placeholder is shown, rather than the tap
        // being a no-op that leaves the user stuck on Terminal. (Was the
        // documented #716 limitation, now fixed.)
        val state = tmuxSessionTabState(
            currentAgentConversation = AgentConversationUiState(
                detection = null,
                selectedTab = SessionTab.Conversation,
            ),
            presumedAgent = true,
        )

        assertEquals(listOf("Terminal", "Conversation"), state.labels)
        assertEquals(1, state.selectedIndex)
        assertTrue(state.showsConversationTab)
    }

    @Test
    fun tmuxSessionTabStateStaysTerminalForNonPresumedConversationSelectionWithoutDetection() {
        // Defensive: a NON-presumed pane (confirmed shell) must never land on the
        // Conversation index even if a stale row claims `selectedTab =
        // Conversation` with no detection — there is no Conversation tab to show.
        val state = tmuxSessionTabState(
            currentAgentConversation = AgentConversationUiState(
                detection = null,
                selectedTab = SessionTab.Conversation,
            ),
            presumedAgent = false,
        )

        assertEquals(listOf("Terminal"), state.labels)
        assertEquals(0, state.selectedIndex)
        assertTrue(!state.showsConversationTab)
    }

    @Test
    fun tmuxSessionTabStateHidesConversationTabWhenNotPresumedAndNoDetection() {
        // The only way the Conversation tab is absent is a non-presumed pane
        // (a confirmed shell) with no live detection.
        val state = tmuxSessionTabState(
            currentAgentConversation = null,
            presumedAgent = false,
        )

        assertEquals(listOf("Terminal"), state.labels)
        assertEquals(0, state.selectedIndex)
        assertTrue(!state.showsConversationTab)
    }

    @Test
    fun tmuxSessionTabStateConfirmedAgentOnConversationTabIsUnchanged() {
        // A confirmed agent already on the Conversation tab selects index 1,
        // exactly as before #716 (criterion (b): confirmed agent unchanged).
        val state = tmuxSessionTabState(
            currentAgentConversation = AgentConversationUiState(
                detection = claudeDetection(),
                selectedTab = SessionTab.Conversation,
            ),
            presumedAgent = true,
        )

        assertEquals(listOf("Terminal", "Conversation"), state.labels)
        assertEquals(1, state.selectedIndex)
        assertTrue(state.showsConversationTab)
    }

    @Test
    fun isAgentPaneFollowsPresumedAgent() {
        // Agent chips/affordances follow presumed-agent, so they don't flip to
        // shell chips during the slow-detection window.
        assertTrue(tmuxSessionIsAgentPane(hasLiveDetection = false, presumedAgent = true))
        assertTrue(tmuxSessionIsAgentPane(hasLiveDetection = true, presumedAgent = false))
        assertTrue(!tmuxSessionIsAgentPane(hasLiveDetection = false, presumedAgent = false))
    }

    // ─── Issue #761 / #454: the snippet chip is a shell-pane affordance ─────
    // gated on the ACTUAL agent signal, NOT the optimistic presumed-agent. ───

    @Test
    fun snippetChipShownOnFreshTmuxShellPaneWithNoAgentEvidence() {
        // The #761 bug: a freshly attached tmux shell pane (host persisted, no
        // live detection, no sticky agent) is presumed-agent under #716, which
        // wrongly suppressed the snippet chip. The chip MUST show here — this is
        // the exact state both failing tests drive (a seeded tmux shell).
        assertTrue(
            tmuxSessionShowsSnippetChip(
                hasHost = true,
                hasLiveDetection = false,
                hasStickyAgent = false,
            ),
        )
    }

    @Test
    fun snippetChipHiddenOnLiveDetectedAgentPane() {
        // A pane with a live-detected agent is a real agent pane — the snippet
        // chip is omitted (the composer's `{}` inserts saved prompts, #453).
        assertTrue(
            !tmuxSessionShowsSnippetChip(
                hasHost = true,
                hasLiveDetection = true,
                hasStickyAgent = false,
            ),
        )
    }

    @Test
    fun snippetChipHiddenOnStickyAgentPaneDuringTransientNull() {
        // A pane that WAS an agent (sticky kind present) but whose live
        // detection transiently dropped to null is still a known agent pane —
        // the snippet chip stays hidden (no flicker back to the shell chip).
        assertTrue(
            !tmuxSessionShowsSnippetChip(
                hasHost = true,
                hasLiveDetection = false,
                hasStickyAgent = true,
            ),
        )
    }

    @Test
    fun snippetChipHiddenWithoutPersistedHost() {
        // Snippets are host-scoped; a transient/zero host id has none to pick,
        // so the chip is omitted even on an otherwise shell-style pane.
        assertTrue(
            !tmuxSessionShowsSnippetChip(
                hasHost = false,
                hasLiveDetection = false,
                hasStickyAgent = false,
            ),
        )
    }

    @Test
    fun composerSendRoutesToAgentPayloadForPresumedAgentWithoutDetection() {
        // Criterion (a): a presumed-agent pane (sticky kind, no live detection)
        // routes the composer send to the agent payload path, NOT raw bytes.
        assertEquals(
            TmuxComposerSendRoute.AgentPayload,
            tmuxComposerSendRoute(
                viewingConversation = false,
                liveAgent = null,
                presumedAgentKind = AgentKind.ClaudeCode,
                withEnter = true,
            ),
        )
    }

    @Test
    fun composerSendRoutesToConversationForConfirmedAgentOnConversationTab() {
        // A live-detected agent on the Conversation tab keeps the optimistic
        // agent-conversation send (criterion (b): confirmed agent unchanged).
        assertEquals(
            TmuxComposerSendRoute.AgentConversation,
            tmuxComposerSendRoute(
                viewingConversation = true,
                liveAgent = AgentKind.ClaudeCode,
                presumedAgentKind = null,
                withEnter = true,
            ),
        )
    }

    @Test
    fun composerSendKeepsRawBytesForConfirmedAgentOnTerminalTab() {
        // A confirmed (live) Claude agent on the Terminal tab still writes raw
        // bytes — #716 must NOT change this (only the Codex with-Enter case and
        // the presumed-agent-without-detection case route to agent payload).
        assertEquals(
            TmuxComposerSendRoute.RawBytes,
            tmuxComposerSendRoute(
                viewingConversation = false,
                liveAgent = AgentKind.ClaudeCode,
                presumedAgentKind = null,
                withEnter = true,
            ),
        )
    }

    @Test
    fun composerSendKeepsCodexWithEnterPayloadSpecialCase() {
        // The pre-#716 Codex-on-Terminal-tab with-Enter payload formatting is
        // preserved.
        assertEquals(
            TmuxComposerSendRoute.AgentPayload,
            tmuxComposerSendRoute(
                viewingConversation = false,
                liveAgent = AgentKind.Codex,
                presumedAgentKind = null,
                withEnter = true,
            ),
        )
    }

    @Test
    fun composerSendFallsBackToRawBytesForGenuineNoAgentPane() {
        // No live agent, no sticky/presumed kind: a genuine no-agent send still
        // writes raw bytes.
        assertEquals(
            TmuxComposerSendRoute.RawBytes,
            tmuxComposerSendRoute(
                viewingConversation = false,
                liveAgent = null,
                presumedAgentKind = null,
                withEnter = false,
            ),
        )
    }

    @Test
    fun conversationSyncStatusLabelsExposeLiveStaleAndUnavailable() {
        assertEquals("Live", conversationSyncStatusLabel(AgentConversationSyncStatus.Live))
        assertEquals("Stale", conversationSyncStatusLabel(AgentConversationSyncStatus.Stale))
        assertEquals("Retrying", conversationSyncStatusLabel(AgentConversationSyncStatus.Retrying))
        assertEquals(
            "Log unavailable",
            conversationSyncStatusLabel(AgentConversationSyncStatus.LogUnavailable),
        )
    }

    // ─── Issues #177 / #249: breadcrumb status mapping ──────────────────

    @Test
    fun toUiStatusMapsConnectedToConnected() {
        assertEquals(
            com.pocketshell.uikit.model.ConnectionStatus.Connected,
            TmuxSessionViewModel.ConnectionStatus.Connected("h", 22, "u").toUiStatus(),
        )
    }

    @Test
    fun toUiStatusMapsConnectingToConnecting() {
        // Connecting drives the amber pulse + "Reconnecting" pill while a
        // background-detach reattach handshake (#177) is in flight.
        assertEquals(
            com.pocketshell.uikit.model.ConnectionStatus.Connecting,
            TmuxSessionViewModel.ConnectionStatus.Connecting("h", 22, "u").toUiStatus(),
        )
    }

    @Test
    fun toUiStatusMapsFailedToError() {
        // Failed is the dropped-socket state #249 rides on; it must read
        // as a red "Disconnected" indicator, not a steady-state dot.
        assertEquals(
            com.pocketshell.uikit.model.ConnectionStatus.Error,
            TmuxSessionViewModel.ConnectionStatus.Failed("Disconnected from ...").toUiStatus(),
        )
    }

    @Test
    fun toUiStatusMapsIdleToIdle() {
        assertEquals(
            com.pocketshell.uikit.model.ConnectionStatus.Idle,
            TmuxSessionViewModel.ConnectionStatus.Idle.toUiStatus(),
        )
    }

    // ─── Issue #797: composer + Conversation tab + input-routing + detection ──
    // must survive a switch onto a settled CACHED (non-active) pane, where the
    // active-only `currentPane` is null. These proofs drive the PRODUCTION gate
    // helpers exactly as `TmuxSessionScreen` wires them (surface-pane → presumed
    // agent → tab state → send route), in the maintainer's reported cached-pane
    // state — NOT the pure presumed-agent helper in isolation (the #797
    // test-validity gap the research spike flagged: the old tests exercised a
    // proxy that could never reproduce the symptom). ──────────────────────────

    /**
     * The user-visible outcome of the composer / Conversation / input gate for a
     * given pager/switch state, derived through the SAME production helpers and
     * in the SAME order as [TmuxSessionScreen] (#797). Modelling the gate here —
     * rather than calling [tmuxSessionPresumedAgent] alone — reproduces the
     * call-site short-circuit that was the actual bug (`presumedAgent =
     * surfacePane != null && …` and the `surfacePane?.let { }` composer wrapper).
     */
    private data class SessionSurfaceOutcome(
        val composerShown: Boolean,
        val conversationTabShown: Boolean,
        val sendRouteTargetPaneId: String?,
    )

    private fun sessionSurfaceOutcome(
        visibleUnifiedPane: TmuxPaneState?,
        switchHidesTerminal: Boolean,
        agentConversations: Map<String, AgentConversationUiState>,
        stickyAgentByPaneId: Map<String, AgentKind>,
    ): SessionSurfaceOutcome {
        // 1. Surface pane (#797 Shape A): follows the VISIBLE pane unless a
        //    switch is hiding the terminal.
        val surfacePane = tmuxSessionSurfacePane(
            visibleUnifiedPane = visibleUnifiedPane,
            switchHidesTerminal = switchHidesTerminal,
        )
        // 2. Detection lookup + sticky/palette agent follow the surface pane.
        val currentAgentConversation = surfacePane?.paneId?.let { agentConversations[it] }
        val liveAgent = currentAgentConversation?.detection?.agent
        val paletteAgent = liveAgent
            ?: surfacePane?.paneId?.let { stickyAgentByPaneId[it] }
        // 3. Presumed-agent gate (the line that was `currentPane != null && …`).
        val presumedAgent = surfacePane != null &&
            tmuxSessionPresumedAgent(
                hasLiveDetection = currentAgentConversation?.detection != null,
                stickyAgent = paletteAgent,
                confirmedShell = false,
            )
        // 4. Conversation tab state.
        val tabState = tmuxSessionTabState(currentAgentConversation, presumedAgent)
        // 5. Composer launcher: the `surfacePane?.let { }` wrapper at the bottom
        //    controls — present iff there is a surface pane.
        val composerShown = surfacePane != null
        // 6. Send route target paneId: the surface pane's id (the visible/intended
        //    session). Null when no surface pane (switch in flight).
        return SessionSurfaceOutcome(
            composerShown = composerShown,
            conversationTabShown = tabState.showsConversationTab,
            sendRouteTargetPaneId = surfacePane?.paneId,
        )
    }

    private fun fakePane(paneId: String): TmuxPaneState =
        TmuxPaneState(
            paneId = paneId,
            windowId = "@0",
            sessionId = "\$0",
            title = "work",
            cwd = "/repo",
            currentCommand = "bash",
            paneTty = "/dev/pts/1",
            terminalState = com.pocketshell.core.terminal.ui.TerminalSurfaceState(),
        )

    /**
     * The ORIGIN/MAIN (pre-#797) gate outcome: composer / Conversation tab /
     * send route were all keyed on the active-only `currentPane` (null for a
     * cached pane). This baseline reproduces the BUG so the fix's RED→GREEN is
     * visible in one file: with a cached pane (`currentPane == null`) it returns
     * composer hidden + tab empty + no route, exactly the maintainer's symptom.
     */
    private fun originMainCachedPaneGateIsBroken(): SessionSurfaceOutcome {
        // currentPane == null for a cached pane (origin/main:
        // `val currentPane = if (isActiveSessionPane) currentUnifiedPane else null`).
        val currentPane: TmuxPaneState? = null
        val agentConversations = emptyMap<String, AgentConversationUiState>()
        val currentAgentConversation = currentPane?.paneId?.let { agentConversations[it] }
        // origin/main: `presumedAgent = currentPane != null && …`.
        val presumedAgent = currentPane != null &&
            tmuxSessionPresumedAgent(
                hasLiveDetection = currentAgentConversation?.detection != null,
                stickyAgent = null,
                confirmedShell = false,
            )
        val tabState = tmuxSessionTabState(currentAgentConversation, presumedAgent)
        return SessionSurfaceOutcome(
            // origin/main: composer wrapped in `currentPane?.let { }`.
            composerShown = currentPane != null,
            conversationTabShown = tabState.showsConversationTab,
            // origin/main: send used `val pane = currentPane`.
            sendRouteTargetPaneId = currentPane?.paneId,
        )
    }

    @Test
    fun originMainBaselineReproducesTheBugOnACachedPane() {
        // RED baseline: the pre-#797 gate (active-only `currentPane`) on a cached
        // pane hides the composer, empties the Conversation tab, and has no send
        // route — the exact reported state. The #797 fix
        // ([settledCachedPaneKeepsComposerAndConversationTabAndRoutesInput])
        // flips all three to the restored state with the SAME cached-pane input.
        val broken = originMainCachedPaneGateIsBroken()
        assertTrue("baseline: composer hidden on cached pane", !broken.composerShown)
        assertTrue(
            "baseline: Conversation tab empty on cached pane",
            !broken.conversationTabShown,
        )
        assertNull("baseline: no input route on cached pane", broken.sendRouteTargetPaneId)
    }

    @Test
    fun settledCachedPaneKeepsComposerAndConversationTabAndRoutesInput() {
        // #797 PRIMARY proof (warm-switch-STALLS path): the pager is settled on a
        // genuine CACHED pane (its paneId is NOT in the active `_panes.value`, so
        // production sets `currentPane == null`) and the switch is NOT hiding the
        // terminal (the user is parked there indefinitely — the maintainer's
        // "until I switched several times" stall). The composer launcher MUST be
        // present, the Conversation tab MUST be available, and a send MUST route
        // to the VISIBLE cached pane's own id (the intended session) — not vanish
        // and not target a stale/null pane.
        //
        // RED on origin/main: `tmuxSessionSurfacePane` did not exist; the gate
        // used active-only `currentPane`, which was null here → composer hidden,
        // tab empty, no route. GREEN with the surface-pane fix.
        val cachedPane = fakePane("%cached")
        val outcome = sessionSurfaceOutcome(
            visibleUnifiedPane = cachedPane,
            switchHidesTerminal = false,
            // Detection only runs over the ACTIVE session's panes, so the cached
            // pane has NO conversation row — exactly the maintainer's "not
            // detected as an agent / Conversation tab shows nothing" condition.
            agentConversations = emptyMap(),
            stickyAgentByPaneId = emptyMap(),
        )

        assertTrue(
            "composer launcher must stay present on a settled cached pane",
            outcome.composerShown,
        )
        assertTrue(
            "Conversation tab must stay available on a settled cached pane " +
                "(presumed-agent default), not empty",
            outcome.conversationTabShown,
        )
        assertEquals(
            "a send must route to the VISIBLE cached pane's own id (intended " +
                "session), restoring input routing",
            "%cached",
            outcome.sendRouteTargetPaneId,
        )
    }

    @Test
    fun settledCachedPaneWithStickyAgentSurfacesAgentSurface() {
        // #797: a cached pane the user is parked on that WAS detected as an agent
        // (sticky kind recorded for its paneId) keeps the agent surface — the
        // presumed-agent default plus its sticky kind drive the composer + tab.
        val cachedPane = fakePane("%cached-agent")
        val outcome = sessionSurfaceOutcome(
            visibleUnifiedPane = cachedPane,
            switchHidesTerminal = false,
            agentConversations = emptyMap(),
            stickyAgentByPaneId = mapOf("%cached-agent" to AgentKind.ClaudeCode),
        )

        assertTrue(outcome.composerShown)
        assertTrue(outcome.conversationTabShown)
        assertEquals("%cached-agent", outcome.sendRouteTargetPaneId)
    }

    @Test
    fun warmSwitchCompletedActivePaneKeepsComposerAndTabAndRoutes() {
        // #797 (warm-switch-COMPLETES path): once promotion lands, the visible
        // pane is the ACTIVE pane (its row is now present with live detection).
        // The surface still shows the composer + tab and routes to that pane —
        // i.e. the fix does not regress the normal active-pane case.
        val activePane = fakePane("%active")
        val outcome = sessionSurfaceOutcome(
            visibleUnifiedPane = activePane,
            switchHidesTerminal = false,
            agentConversations = mapOf(
                "%active" to AgentConversationUiState(
                    detection = claudeDetection(),
                    selectedTab = SessionTab.Terminal,
                ),
            ),
            stickyAgentByPaneId = emptyMap(),
        )

        assertTrue(outcome.composerShown)
        assertTrue(outcome.conversationTabShown)
        assertEquals("%active", outcome.sendRouteTargetPaneId)
    }

    @Test
    fun switchInFlightSuppressesSurfaceSoNoStaleSessionBleed() {
        // #797 no-content-bleed proof (#661/#634/#636): while a cross-session
        // switch is hiding the terminal, the VISIBLE pane is the LEAVING session.
        // The surface MUST be suppressed so neither the composer nor a send can
        // route to / leak from the stale leaving session. Composer hidden +
        // tab hidden + NO send-route target during the switch-in-flight window;
        // it reappears the instant the target is revealed (the active-pane test
        // above).
        val leavingSessionPane = fakePane("%leaving")
        val outcome = sessionSurfaceOutcome(
            visibleUnifiedPane = leavingSessionPane,
            switchHidesTerminal = true,
            // The leaving session even still has a live agent row — must NOT be
            // surfaced or routed to during the switch.
            agentConversations = mapOf(
                "%leaving" to AgentConversationUiState(
                    detection = claudeDetection(),
                    selectedTab = SessionTab.Conversation,
                ),
            ),
            stickyAgentByPaneId = mapOf("%leaving" to AgentKind.ClaudeCode),
        )

        assertTrue(
            "composer must be suppressed while a switch hides the terminal " +
                "(no stale-session surface)",
            !outcome.composerShown,
        )
        assertTrue(
            "Conversation tab must be suppressed while a switch hides the terminal",
            !outcome.conversationTabShown,
        )
        assertNull(
            "no send may route to the leaving session while a switch is in flight",
            outcome.sendRouteTargetPaneId,
        )
    }

    // ─── Issue #797: promotion-on-settle restores input routing + detection ───
    // for a settled cached pane WITHOUT reintroducing the #661 stale-settle yank.

    @Test
    fun settledCachedPaneIsPromotedToRestoreRoutingAndDetection() {
        // The persistent-stall fix: a pager settled (aligned, at rest) on a
        // genuinely CACHED other-session pane must trigger pane-promotion (warm
        // switch) so `clientRef`/`_panes.value` rebind input + detection to it.
        // This is what makes the surface-shown composer actually able to send and
        // be detected as an agent (not the "fixed-but-still-broken" trap).
        assertTrue(
            tmuxSessionShouldPromoteSettledCachedPane(
                settledPaneSession = "project-b",
                navTargetSession = "project-a",
                settledPaneIsActiveSession = false,
                pagerAlignedToNavTarget = true,
                switchHidesTerminal = false,
            ),
        )
    }

    @Test
    fun activeSettledPaneIsNotPromoted() {
        // Nothing to promote when the settled pane already belongs to the active
        // session (input + detection already bound).
        assertTrue(
            !tmuxSessionShouldPromoteSettledCachedPane(
                settledPaneSession = "project-a",
                navTargetSession = "project-a",
                settledPaneIsActiveSession = true,
                pagerAlignedToNavTarget = true,
                switchHidesTerminal = false,
            ),
        )
    }

    @Test
    fun unalignedSettleIsNotPromoted() {
        // #661/#634/#636: a settle before the pager realigns to the nav target is
        // a stale-index artifact of a just-completed switch — never promote it
        // (that is the wrong/stale-session content-bleed the guard exists for).
        assertTrue(
            !tmuxSessionShouldPromoteSettledCachedPane(
                settledPaneSession = "project-b",
                navTargetSession = "project-a",
                settledPaneIsActiveSession = false,
                pagerAlignedToNavTarget = false,
                switchHidesTerminal = false,
            ),
        )
    }

    @Test
    fun switchInFlightSettleIsNotPromoted() {
        // Never promote off a settle while a cross-session switch is already
        // hiding the terminal — the visible pane is the leaving session.
        assertTrue(
            !tmuxSessionShouldPromoteSettledCachedPane(
                settledPaneSession = "project-b",
                navTargetSession = "project-a",
                settledPaneIsActiveSession = false,
                pagerAlignedToNavTarget = true,
                switchHidesTerminal = true,
            ),
        )
    }

    @Test
    fun settleOnNavTargetSessionIsNotPromoted() {
        // A settle resolving to the nav target's own session is a no-op (already
        // the intended session); only a genuinely OTHER cached session promotes.
        assertTrue(
            !tmuxSessionShouldPromoteSettledCachedPane(
                settledPaneSession = "project-a",
                navTargetSession = "project-a",
                settledPaneIsActiveSession = false,
                pagerAlignedToNavTarget = true,
                switchHidesTerminal = false,
            ),
        )
    }

    private fun pickerRequest(): HostTmuxSessionPickerRequest =
        HostTmuxSessionPickerRequest(
            host = HostEntity(
                id = 1L,
                name = "alpha",
                hostname = "alpha.example",
                port = 22,
                username = "alex",
                keyId = 1L,
            ),
            keyPath = "/keys/alpha",
            passphrase = null,
        )

    private fun claudeDetection(): AgentDetection =
        AgentDetection(
            agent = AgentKind.ClaudeCode,
            sourcePath = "/home/u/.claude/sessions/abc.jsonl",
            sessionId = "abc",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

    // ---------------------------------------------------------------- #457
    // Keyboard-pan offset: PAN the terminal column up by the keyboard
    // overlap instead of resizing the terminal (which would reflow tmux).

    @Test
    fun panOffsetIsZeroWhenKeyboardHidden() {
        // Hidden IME reports a 0 bottom inset; the nav bar inset is still
        // present. The offset must clamp at 0 so the column is NOT panned
        // (and definitely never pushed DOWN by a negative translation).
        assertEquals(0, imeKeyboardPanOffsetPx(imeBottomPx = 0, navBarBottomPx = 48))
    }

    @Test
    fun panOffsetUsesFullImeInset() {
        // Some Android/keyboard combinations report IME and nav-bar insets
        // separately. Panning by the full IME inset keeps the hotkey bar
        // clear of the keyboard instead of leaving only a thin strip visible.
        assertEquals(
            900,
            imeKeyboardPanOffsetPx(imeBottomPx = 900, navBarBottomPx = 48),
        )
    }

    @Test
    fun panOffsetDoesNotSubtractLargeNavInset() {
        // Defensive: a small non-zero IME inset still represents keyboard
        // obstruction. It must not be erased by a larger nav-bar inset.
        assertEquals(30, imeKeyboardPanOffsetPx(imeBottomPx = 30, navBarBottomPx = 48))
    }
}
