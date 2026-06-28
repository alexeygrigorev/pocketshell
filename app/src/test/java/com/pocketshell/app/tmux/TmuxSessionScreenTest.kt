package com.pocketshell.app.tmux

import com.pocketshell.app.composer.OutboundRoute
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
    fun presumedAgentTrueWhenConfirmedShellClearedByLiveDetection() {
        // Issue #962: a session recorded `@ps_agent_kind=shell` with claude/codex/
        // opencode started INSIDE it is re-classified OUT of confirmed-shell by
        // the VM when live detection binds the pane (markAgentTailLive →
        // applyRecordedShellVerdict(isShell=false)). So `confirmedShell` becomes
        // false and the agent surface (Conversation toggle) is restored.
        assertTrue(
            tmuxSessionPresumedAgent(
                hasLiveDetection = true,
                stickyAgent = AgentKind.ClaudeCode,
                confirmedShell = false,
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
    fun tmuxSessionTabStateHonoursUserConversationSelectionOnConfirmedShell() {
        // Issue #1057 (was the OLD "stays Terminal" defensive case — that
        // behaviour WAS the maintainer's "conversation is not visible" bug): a
        // NON-presumed pane (confirmed shell / mis-classified agent) whose row
        // records a DELIBERATE `selectedTab = Conversation` must now keep the
        // Conversation tab reachable and land on the Conversation index, even
        // with no live detection. The user explicitly opened the surface; the
        // per-pane choice is honoured and persists (criterion #3) instead of
        // being silently swallowed.
        val state = tmuxSessionTabState(
            currentAgentConversation = AgentConversationUiState(
                detection = null,
                selectedTab = SessionTab.Conversation,
            ),
            presumedAgent = false,
        )

        assertEquals(listOf("Terminal", "Conversation"), state.labels)
        assertEquals(1, state.selectedIndex)
        assertTrue(state.showsConversationTab)
    }

    @Test
    fun tmuxSessionTabStateShowsConversationForConfirmedShellWithExistingTranscript() {
        // Issue #1057, class case (a): a pane recorded/mis-classified as a
        // confirmed shell (presumedAgent == false) with NO current live
        // detection but an existing transcript (events present — e.g. an agent
        // that has since exited, or a reattach where detection has not re-bound)
        // must keep the Conversation tab reachable so the user can read it.
        // On base (the pre-#1057 `hasLiveDetection || presumedAgent` gate) this
        // FAILS — the tab is hidden and the conversation is unreachable.
        val state = tmuxSessionTabState(
            currentAgentConversation = AgentConversationUiState(
                detection = null,
                events = listOf(
                    com.pocketshell.core.agents.ConversationEvent.Message(
                        id = "m1",
                        agent = AgentKind.ClaudeCode,
                        role = com.pocketshell.core.agents.ConversationRole.Assistant,
                        text = "hello from the agent",
                    ),
                ),
                selectedTab = SessionTab.Terminal,
            ),
            presumedAgent = false,
        )

        assertEquals(listOf("Terminal", "Conversation"), state.labels)
        // Not opened yet, so the active index stays on Terminal — but the tab
        // is now reachable (the bug fix).
        assertEquals(0, state.selectedIndex)
        assertTrue(state.showsConversationTab)
    }

    @Test
    fun tmuxSessionTabStateShowsConversationForConfirmedShellWithRememberedPlaceholder() {
        // Issue #1057, class case (a) cont.: a remembered-agent placeholder row
        // (the #495/#819 reattach seed) on a pane that is momentarily NOT
        // presumed-agent must still expose the Conversation tab so the remembered
        // conversation is reachable while live re-detection re-anchors it.
        val state = tmuxSessionTabState(
            currentAgentConversation = AgentConversationUiState(
                detection = null,
                selectedTab = SessionTab.Terminal,
                rememberedAgentPlaceholder = true,
            ),
            presumedAgent = false,
        )

        assertEquals(listOf("Terminal", "Conversation"), state.labels)
        assertEquals(0, state.selectedIndex)
        assertTrue(state.showsConversationTab)
    }

    @Test
    fun tmuxSessionTabStateShowsConversationForDetectionPendingPresumedAgent() {
        // Issue #1057, class case (b): a detection-pending pane (presumed-agent,
        // detection == null, no events yet) must keep the Conversation tab
        // reachable throughout the slow-detection window so the user is never
        // locked out of the conversation while detection warms.
        val state = tmuxSessionTabState(
            currentAgentConversation = AgentConversationUiState(
                detection = null,
                selectedTab = SessionTab.Terminal,
            ),
            presumedAgent = true,
        )

        assertEquals(listOf("Terminal", "Conversation"), state.labels)
        assertEquals(0, state.selectedIndex)
        assertTrue(state.showsConversationTab)
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

    // ─── Issue #1057: tap-to-switch content routing. The Conversation tab being
    // reachable is only half the fix — opening it must actually render the
    // conversation (transcript or placeholder), not silently stay on Terminal. ─

    @Test
    fun conversationSurfaceIsTerminalWhenTerminalTabSelected() {
        // On the Terminal tab the content area is the Terminal, regardless of
        // detection / events.
        assertEquals(
            TmuxConversationSurface.Terminal,
            tmuxSessionConversationSurface(
                showsConversationTab = true,
                isActivePane = true,
                hasSurfacePane = true,
                selectedTab = SessionTab.Terminal,
                hasDetection = true,
                hasEvents = true,
            ),
        )
    }

    @Test
    fun conversationSurfaceShowsTranscriptForExistingEventsWithoutDetection() {
        // Issue #1057 class case (a): the user opens Conversation on an
        // active pane recorded as a shell whose agent has since dropped its live
        // detection but whose transcript events are still loaded. The existing
        // transcript must render (Transcript), NOT the Terminal. On base
        // (`showConversation` required `detection != null`) this routed to
        // Terminal — the conversation was unreachable even after tapping.
        assertEquals(
            TmuxConversationSurface.Transcript,
            tmuxSessionConversationSurface(
                showsConversationTab = true,
                isActivePane = true,
                hasSurfacePane = true,
                selectedTab = SessionTab.Conversation,
                hasDetection = false,
                hasEvents = true,
            ),
        )
    }

    @Test
    fun conversationSurfaceShowsPlaceholderForOpenedConversationWithoutTranscript() {
        // Issue #1057: the user opened Conversation on a confirmed-shell pane
        // (no detection, no events yet). The lightweight placeholder must render
        // (with a way back to Terminal), not the Terminal — the old gate required
        // `presumedAgent`, so a confirmed-shell tap stayed on the Terminal.
        assertEquals(
            TmuxConversationSurface.Placeholder,
            tmuxSessionConversationSurface(
                showsConversationTab = true,
                isActivePane = true,
                hasSurfacePane = true,
                selectedTab = SessionTab.Conversation,
                hasDetection = false,
                hasEvents = false,
            ),
        )
    }

    @Test
    fun conversationSurfaceShowsTranscriptForLiveDetectedAgent() {
        // Unchanged happy path: a live-detected agent on the Conversation tab
        // mounts the transcript.
        assertEquals(
            TmuxConversationSurface.Transcript,
            tmuxSessionConversationSurface(
                showsConversationTab = true,
                isActivePane = true,
                hasSurfacePane = true,
                selectedTab = SessionTab.Conversation,
                hasDetection = true,
                hasEvents = false,
            ),
        )
    }

    @Test
    fun conversationSurfaceStaysTerminalWhenTabNotReachableAndNoTranscript() {
        // A genuine shell the user never opened Conversation on (tab hidden, no
        // transcript) stays on the Terminal — no empty Conversation surface.
        assertEquals(
            TmuxConversationSurface.Terminal,
            tmuxSessionConversationSurface(
                showsConversationTab = false,
                isActivePane = true,
                hasSurfacePane = true,
                selectedTab = SessionTab.Terminal,
                hasDetection = false,
                hasEvents = false,
            ),
        )
    }

    @Test
    fun isAgentPaneFollowsPresumedAgent() {
        // Agent chips/affordances follow presumed-agent, so they don't flip to
        // shell chips during the slow-detection window.
        assertTrue(tmuxSessionIsAgentPane(hasLiveDetection = false, presumedAgent = true))
        assertTrue(tmuxSessionIsAgentPane(hasLiveDetection = true, presumedAgent = false))
        assertTrue(!tmuxSessionIsAgentPane(hasLiveDetection = false, presumedAgent = false))
    }

    // ─── Issue #805 (regression of #744/#716): the bottom-bar chrome follows ──
    // the Conversation TAB (detecting OR loaded), not the detection-gated ──────
    // transcript — so the composer launcher is never pushed off-screen by the ──
    // Terminal chips during agent-engine detection. ───────────────────────────

    @Test
    fun bottomControlsShowConversationWhileDetectingAgentEngine() {
        // The #805 regression state: the user is on the Conversation tab, the
        // agent engine is still being detected (`detection == null`), so the
        // "Loading conversation…" placeholder is shown and the live transcript
        // is NOT yet mounted. The bottom bar MUST still wear conversation chrome
        // here — otherwise it renders the Terminal chips (`Enter` /
        // `show keyboard` / `hotkeys`) that overflow the row and push the
        // composer launcher off-screen (the #744 invariant break on v0.4.7).
        assertTrue(
            tmuxSessionBottomControlsShowsConversation(
                showConversationTranscript = false,
                showConversationDetectingPlaceholder = true,
            ),
        )
    }

    @Test
    fun bottomControlsShowConversationOnceTranscriptLoaded() {
        // Once detection lands and the live transcript mounts, the bar stays in
        // conversation chrome (this path already worked pre-#805; covered so the
        // loaded state is not regressed by the detecting-state fix).
        assertTrue(
            tmuxSessionBottomControlsShowsConversation(
                showConversationTranscript = true,
                showConversationDetectingPlaceholder = false,
            ),
        )
    }

    @Test
    fun bottomControlsStayTerminalChromeOffTheConversationTab() {
        // On the Terminal tab (no transcript, no conversation placeholder) the
        // bar keeps its Terminal chrome — the fix must not steal Terminal chips
        // from a genuine terminal/shell surface.
        assertTrue(
            !tmuxSessionBottomControlsShowsConversation(
                showConversationTranscript = false,
                showConversationDetectingPlaceholder = false,
            ),
        )
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
    fun composerOutboundRouteMapsAllRoutes() {
        assertEquals(
            OutboundRoute.AgentConversation,
            tmuxComposerOutboundRoute(TmuxComposerSendRoute.AgentConversation),
        )
        assertEquals(
            OutboundRoute.AgentPayload,
            tmuxComposerOutboundRoute(TmuxComposerSendRoute.AgentPayload),
        )
        assertEquals(
            OutboundRoute.RawBytes,
            tmuxComposerOutboundRoute(TmuxComposerSendRoute.RawBytes),
        )
    }

    @Test
    fun composerSendTargetSnapshotCapturesSessionPaneRouteAndAgentToken() {
        val snapshot = tmuxComposerSendTargetSnapshot(
            sessionKey = "host-1/session-a",
            paneId = "%3",
            route = TmuxComposerSendRoute.AgentPayload,
            agentKind = AgentKind.Codex,
        )

        assertEquals("host-1/session-a", snapshot.sessionKey)
        assertEquals("%3", snapshot.paneId)
        assertEquals(OutboundRoute.AgentPayload, snapshot.route)
        assertEquals("codex", snapshot.agentKind)
    }

    @Test
    fun composerSendTargetSnapshotAllowsMissingPane() {
        val snapshot = tmuxComposerSendTargetSnapshot(
            sessionKey = "host-1/session-a",
            paneId = null,
            route = TmuxComposerSendRoute.RawBytes,
            agentKind = null,
        )

        assertEquals("host-1/session-a", snapshot.sessionKey)
        assertEquals("", snapshot.paneId)
        assertEquals(OutboundRoute.RawBytes, snapshot.route)
        assertNull(snapshot.agentKind)
    }

    @Test
    fun composerAgentKindFromTokenAcceptsQueueTokens() {
        assertEquals(AgentKind.ClaudeCode, tmuxComposerAgentKindFromToken("claude"))
        assertEquals(AgentKind.Codex, tmuxComposerAgentKindFromToken("codex"))
        assertEquals(AgentKind.OpenCode, tmuxComposerAgentKindFromToken("opencode"))
    }

    @Test
    fun composerAgentKindFromTokenAcceptsLegacyEnumTokens() {
        assertEquals(AgentKind.ClaudeCode, tmuxComposerAgentKindFromToken("claudeCode"))
        assertEquals(AgentKind.OpenCode, tmuxComposerAgentKindFromToken("open_code"))
    }

    @Test
    fun composerAgentKindFromTokenRejectsUnknownToken() {
        assertNull(tmuxComposerAgentKindFromToken(null))
        assertNull(tmuxComposerAgentKindFromToken("shell"))
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
     * in the SAME order as [TmuxSessionScreen] (#797 / #810). Modelling the gate
     * here — rather than calling [tmuxSessionPresumedAgent] alone — reproduces the
     * call-site short-circuit that was the actual bug.
     *
     * Issue #810 (hard-cut, D22): the composer launcher is now STRUCTURALLY
     * UNCONDITIONAL — the old `surfacePane?.let { }` wrapper that dropped the
     * bottom controls (and the launcher) whenever the surface pane was null is
     * DELETED. So [composerShown] is always `true`. The send/chip ROUTE remains
     * surface-pane-gated ([sendRouteTargetPaneId] is null with no surface pane) so
     * a tap can never reach a stale leaving session even though the launcher is
     * present. The two properties are now independent.
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
        // 5. Composer launcher (issue #810, hard-cut D22): the bottom controls
        //    that host the launcher render UNCONDITIONALLY — the old
        //    `surfacePane?.let { }` wrapper is DELETED, so presence no longer keys
        //    off the surface pane (or detection, or the selected tab). The
        //    launcher is ALWAYS present; only its pane-routed callbacks go inert
        //    when there is no surface pane.
        val composerShown = true
        // 6. Send route target paneId: the surface pane's id (the visible/intended
        //    session). Null when no surface pane (switch in flight) — so a send /
        //    chip tap can never route to a stale leaving session even though the
        //    launcher itself stays present.
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
    fun switchInFlightKeepsComposerPresentButSuppressesRouteSoNoStaleSessionBleed() {
        // #797 no-content-bleed proof (#661/#634/#636) + #810 always-present
        // composer: while a cross-session switch is hiding the terminal, the
        // VISIBLE pane is the LEAVING session.
        //
        // #810 (hard-cut, D22): the composer LAUNCHER stays PRESENT throughout the
        // switch — it is the session's only input affordance and must never vanish
        // (the maintainer's multi-release regression). What MUST stay suppressed is
        // anything that could route to / leak from the stale leaving session: the
        // Conversation tab content AND the send/chip ROUTE target. So during the
        // switch-in-flight window: composer present, tab hidden, NO route target.
        // The route + tab reappear the instant the target is revealed (the
        // active-pane test above).
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
            "composer launcher must STAY PRESENT while a switch hides the terminal " +
                "(issue #810 — never gate the composer on a transient null surface)",
            outcome.composerShown,
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

    // ─── Issue #810 (epic #809): the composer launcher is ALWAYS present ──────
    // on every live session — independent of agent detection, pane-cache state,
    // selected tab, and switch transitions. The multi-release regression came
    // back each time because a fix gated the composer on some piece of state;
    // these proofs lock in that PRESENCE has no gate at all (hard-cut, D22).

    @Test
    fun composerLauncherIsPresentIndependentOfDetectionCacheTabAndSwitchState() {
        // Cross-product of the four state dimensions the issue calls out:
        //   - detection: live-detected / sticky-only / undetected,
        //   - pane-cache: active pane vs settled cached pane (currentPane null),
        //   - selected tab: Terminal vs Conversation,
        //   - switch transition: at rest vs switch-hides-terminal (surface null).
        // In EVERY combination the composer launcher must be present. The send
        // route may legitimately be null (switch in flight) — presence and route
        // are independent post-#810 — but presence is never false.
        val tabs = listOf(SessionTab.Terminal, SessionTab.Conversation)
        val detections = listOf<AgentConversationUiState?>(
            // live-detected
            AgentConversationUiState(detection = claudeDetection(), selectedTab = SessionTab.Terminal),
            // undetected (no row)
            null,
        )
        val stickies = listOf(emptyMap<String, AgentKind>(), mapOf("%p" to AgentKind.Codex))
        for (switchHides in listOf(false, true)) {
            for (tab in tabs) {
                for (det in detections) {
                    for (sticky in stickies) {
                        val pane = fakePane("%p")
                        val convo = det?.copy(selectedTab = tab)
                        val outcome = sessionSurfaceOutcome(
                            visibleUnifiedPane = pane,
                            switchHidesTerminal = switchHides,
                            agentConversations = convo?.let { mapOf("%p" to it) } ?: emptyMap(),
                            stickyAgentByPaneId = sticky,
                        )
                        assertTrue(
                            "issue #810: composer launcher must be present for " +
                                "switchHides=$switchHides tab=$tab detected=${det != null} " +
                                "sticky=${sticky.isNotEmpty()} — presence has NO gate",
                            outcome.composerShown,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun composerLauncherIsPresentEvenWithNoVisiblePaneAtAll() {
        // Defensive: even when the pager has no pane to show yet (null visible
        // pane, e.g. the brief waiting-for-panes window), the composer launcher
        // is still present (the bottom controls render unconditionally). Only the
        // pane-routed callbacks are inert.
        val outcome = sessionSurfaceOutcome(
            visibleUnifiedPane = null,
            switchHidesTerminal = false,
            agentConversations = emptyMap(),
            stickyAgentByPaneId = emptyMap(),
        )
        assertTrue(
            "issue #810: composer launcher present even with no visible pane",
            outcome.composerShown,
        )
        assertNull(
            "no send route when there is no pane to route to",
            outcome.sendRouteTargetPaneId,
        )
    }

    /**
     * The PRE-#810 (origin/main `03ef2fe7`) composer-presence gate: the bottom
     * controls were wrapped in `surfacePane?.let { … }`, so the composer launcher
     * was present IFF there was a surface pane. During a cross-session switch
     * (`switchHidesTerminal == true`) the surface pane is null, so the launcher
     * VANISHED — the maintainer's multi-release "composer disappears on
     * switch-back" regression. This baseline reproduces that gate so the #810
     * fix's RED→GREEN is visible in one file.
     */
    private fun pre810ComposerShown(switchHidesTerminal: Boolean): Boolean {
        // origin/main: `surfacePane = if (switchHidesTerminal) null else visiblePane`,
        // and the composer launcher was `surfacePane?.let { … }` → present iff
        // surfacePane != null.
        val surfacePane = tmuxSessionSurfacePane(
            visibleUnifiedPane = fakePane("%p"),
            switchHidesTerminal = switchHidesTerminal,
        )
        return surfacePane != null
    }

    @Test
    fun pre810BaselineHidesComposerWhileSwitchHidesTerminal() {
        // RED baseline: the pre-#810 `surfacePane?.let { }` gate dropped the
        // composer launcher from the tree whenever a switch was hiding the
        // terminal (surface pane null) — the disappearance the maintainer hit.
        assertTrue(
            "baseline: pre-#810 the composer was present while NOT switching",
            pre810ComposerShown(switchHidesTerminal = false),
        )
        assertTrue(
            "baseline: pre-#810 the composer VANISHED while a switch hid the " +
                "terminal (surface pane null) — the #810 regression",
            !pre810ComposerShown(switchHidesTerminal = true),
        )
    }

    @Test
    fun post810ComposerSurvivesTheSwitchInFlightWindowThePre810GateHid() {
        // GREEN: the #810 fix renders the bottom controls (and the launcher)
        // unconditionally, so the composer survives EXACTLY the switch-in-flight
        // window the pre-#810 gate hid it in — with the SAME input. Contrasted
        // directly against [pre810BaselineHidesComposerWhileSwitchHidesTerminal]
        // so the RED→GREEN is unambiguous.
        val outcome = sessionSurfaceOutcome(
            visibleUnifiedPane = fakePane("%p"),
            switchHidesTerminal = true,
            agentConversations = emptyMap(),
            stickyAgentByPaneId = emptyMap(),
        )
        // The pre-#810 gate hid it here…
        assertTrue(
            "precondition: the pre-#810 gate hid the composer in this exact state",
            !pre810ComposerShown(switchHidesTerminal = true),
        )
        // …the #810 fix keeps it present.
        assertTrue(
            "issue #810: the composer launcher survives the switch-in-flight window " +
                "the pre-#810 surfacePane gate hid it in",
            outcome.composerShown,
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

    // --- Issue #750: single-indicator gate on the top ReconnectingProgressRow. ---

    private val reconnectingStatus = TmuxSessionViewModel.ConnectionStatus.Reconnecting(
        host = "alpha.example",
        port = 22,
        user = "alex",
        attempt = 1,
        maxAttempts = 3,
        retryDelayMs = 0L,
        reason = "Reconnecting…",
    )

    @Test
    fun topReconnectBarSuppressedWhileTerminalHeldDuringReattach() {
        // Regression for the post-#766 two-loaders symptom: a recoverable drop
        // projects Reconnecting (the top bar) WHILE the reveal machine holds the
        // terminal in Seeding (effectiveHidesTerminal == true) and paints the
        // centered "Attaching…" spinner. The top bar MUST be suppressed so only the
        // centered indicator shows.
        assertEquals(
            false,
            shouldShowReconnectingProgressRow(
                status = reconnectingStatus,
                effectiveHidesTerminal = true,
            ),
        )
    }

    @Test
    fun topReconnectBarShownWhenReconnectingWithTerminalNotHeld() {
        // The top bar is the fallback indicator for a (currently unreached)
        // reconnect that keeps a live frame painted (terminal NOT held): it then
        // renders so a reconnect is never left with zero indicators.
        assertTrue(
            shouldShowReconnectingProgressRow(
                status = reconnectingStatus,
                effectiveHidesTerminal = false,
            ),
        )
    }

    @Test
    fun topReconnectBarNeverShownForNonReconnectingStatus() {
        // The top bar renders ONLY for Reconnecting — so it is never the sole
        // indicator for any other state. Connecting has its own full-screen
        // overlay; Connected/Switching/Failed/Idle drive their own affordances.
        val nonReconnecting = listOf(
            TmuxSessionViewModel.ConnectionStatus.Idle,
            TmuxSessionViewModel.ConnectionStatus.Connecting("h", 22, "u"),
            TmuxSessionViewModel.ConnectionStatus.Switching("h", 22, "u"),
            TmuxSessionViewModel.ConnectionStatus.Connected("h", 22, "u"),
            TmuxSessionViewModel.ConnectionStatus.Failed("boom"),
        )
        nonReconnecting.forEach { status ->
            assertEquals(
                "no top progress bar for $status",
                false,
                shouldShowReconnectingProgressRow(status, effectiveHidesTerminal = false),
            )
            assertEquals(
                "no top progress bar for $status (terminal held)",
                false,
                shouldShowReconnectingProgressRow(status, effectiveHidesTerminal = true),
            )
        }
    }

    @Test
    fun outboundQueueAutoFlushRequeuesOnLiveAndSuppressesSameWindowRetryLoop() {
        val controller = OutboundQueueAutoFlushController()
        val requeuedTargets = mutableListOf<String>()
        val retryExclusions = mutableListOf<Set<String>>()

        controller.onConnectionWindowChanged(
            sessionLive = false,
            targetSessionId = "1/session-a",
        ) {
            requeuedTargets += "should-not-run"
        }
        assertNull(
            controller.onQueueSnapshotChanged(sessionLive = false) {
                error("offline queue snapshots must not dispatch")
            },
        )
        assertTrue(requeuedTargets.isEmpty())

        controller.onConnectionWindowChanged(
            sessionLive = true,
            targetSessionId = "1/session-a",
        ) {
            requeuedTargets += "1/session-a"
        }
        val firstRetry = controller.onQueueSnapshotChanged(sessionLive = true) { excludingIds ->
            retryExclusions += excludingIds
            "failed-1"
        }
        val sameWindowRetry = controller.onQueueSnapshotChanged(sessionLive = true) { excludingIds ->
            retryExclusions += excludingIds
            null
        }

        assertEquals("failed-1", firstRetry)
        assertNull(sameWindowRetry)
        assertEquals(listOf("1/session-a"), requeuedTargets)
        assertEquals(listOf(emptySet<String>(), setOf("failed-1")), retryExclusions)

        controller.onConnectionWindowChanged(
            sessionLive = false,
            targetSessionId = "1/session-a",
        ) {
            requeuedTargets += "should-not-run"
        }
        controller.onConnectionWindowChanged(
            sessionLive = true,
            targetSessionId = "1/session-a",
        ) {
            requeuedTargets += "1/session-a"
        }
        val retryAfterReconnect = controller.onQueueSnapshotChanged(sessionLive = true) { excludingIds ->
            retryExclusions += excludingIds
            "failed-1"
        }

        assertEquals("failed-1", retryAfterReconnect)
        assertEquals(listOf("1/session-a", "1/session-a"), requeuedTargets)
        assertEquals(emptySet<String>(), retryExclusions.last())
    }

    // --- Issue #993: kebab "Reconnect" enable gate ---------------------------------

    @Test
    fun reconnectKebabEnabledForDroppedSessionWithTarget() {
        // The maintainer's exact case: the session dropped and auto-reconnect did NOT
        // fire — the app is sitting on Failed (or a stale Connected) WITH a target to
        // reconnect to. The Reconnect item MUST be enabled so the user can recover in
        // place without the switch-away-and-back dance.
        assertTrue(
            "Failed-with-target must enable Reconnect (the dropped-session escape hatch)",
            reconnectKebabEnabled(
                canReconnect = true,
                status = TmuxSessionViewModel.ConnectionStatus.Failed("Disconnected"),
            ),
        )
        assertTrue(
            "a (possibly stale) Connected with a target still enables a manual reconnect",
            reconnectKebabEnabled(
                canReconnect = true,
                status = TmuxSessionViewModel.ConnectionStatus.Connected("h", 22, "u"),
            ),
        )
    }

    @Test
    fun reconnectKebabDisabledWithoutTarget() {
        // No target to reconnect to (VM never opened / initial-connect race) — the item
        // must be disabled so a tap is never a silent no-op (AC4).
        listOf(
            TmuxSessionViewModel.ConnectionStatus.Idle,
            TmuxSessionViewModel.ConnectionStatus.Failed("boom"),
            TmuxSessionViewModel.ConnectionStatus.Connected("h", 22, "u"),
        ).forEach { status ->
            assertEquals(
                "no target → Reconnect disabled for $status",
                false,
                reconnectKebabEnabled(canReconnect = false, status = status),
            )
        }
    }

    @Test
    fun kebabReconnectThenLiveWindowFlushesQueuedOutboundMessage() {
        // Issue #993 — the maintainer's full journey, end-to-end at the screen-controller
        // seam: a queued outbound message is pending, the session is DROPPED (not live), the
        // user taps the kebab Reconnect (which is ENABLED in this dropped-with-target state),
        // the session recovers (the live window flips), and the #900 outbound queue
        // auto-flushes the pending message — WITHOUT a session switch.
        val controller = OutboundQueueAutoFlushController()
        val flushed = mutableListOf<String>()
        val sessionId = "1/issue993-proof"

        // 1) DROPPED state: the session is not live, a connection-lost band is up. The kebab
        //    Reconnect item MUST be actionable so the user has an in-session escape hatch.
        controller.onConnectionWindowChanged(sessionLive = false, targetSessionId = sessionId) {
            error("offline must not requeue/flush")
        }
        assertTrue(
            "the kebab Reconnect must be enabled while dropped-with-target (the escape hatch)",
            reconnectKebabEnabled(
                canReconnect = true,
                status = TmuxSessionViewModel.ConnectionStatus.Failed("Disconnected"),
            ),
        )
        // While dropped, the queue does NOT flush (the message stays queued).
        assertNull(
            controller.onQueueSnapshotChanged(sessionLive = false) {
                error("a dropped session must not flush the queue")
            },
        )
        assertTrue("nothing flushed while the session is dropped", flushed.isEmpty())

        // 2) Tapping the kebab Reconnect drives viewModel.reconnect() → the SAME session
        //    recovers IN PLACE (no switch). At the screen that surfaces as `sessionLive`
        //    flipping true for the SAME targetSessionId — NOT a different session id (the
        //    old switch-away-and-back workaround would change the target).
        controller.onConnectionWindowChanged(sessionLive = true, targetSessionId = sessionId) {
            // requeue-stale-in-flight on becoming live (no pending in-flight here).
        }
        val flushedId = controller.onQueueSnapshotChanged(sessionLive = true) { excludingIds ->
            assertTrue("first flush after reconnect excludes nothing", excludingIds.isEmpty())
            flushed += "queued-1"
            "queued-1"
        }

        assertEquals("the queued message must auto-flush after the in-place reconnect", "queued-1", flushedId)
        assertEquals(listOf("queued-1"), flushed)
    }

    @Test
    fun reconnectKebabDisabledWhileReconnectAlreadyInFlight() {
        // A connect/reconnect is already running — a manual reconnect would be a
        // redundant re-dial that yanks an already-recovering session, so the item is
        // disabled even with a target present (AC: "sensible state mid-reconnect").
        listOf(
            TmuxSessionViewModel.ConnectionStatus.Connecting("h", 22, "u"),
            TmuxSessionViewModel.ConnectionStatus.Switching("h", 22, "u"),
            reconnectingStatus,
        ).forEach { status ->
            assertEquals(
                "in-flight → Reconnect disabled for $status",
                false,
                reconnectKebabEnabled(canReconnect = true, status = status),
            )
        }
    }
}
