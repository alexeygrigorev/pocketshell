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
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
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

    @Test
    fun toWindowSummariesKeepsFirstPaneOrderAndDeduplicatesWindows() {
        val panes = listOf(
            pane(paneId = "%0", windowId = "@1"),
            pane(paneId = "%1", windowId = "@0"),
            pane(paneId = "%2", windowId = "@1"),
            pane(paneId = "%3", windowId = "@2"),
        )

        val windows = panes.toWindowSummaries()

        // Per #158: title is now a 1-based ordinal derived from
        // pane-order arrival, not the bare `@N` tmux ID. The
        // deduplication semantics are unchanged.
        assertEquals(
            listOf(
                WindowSummary(windowId = "@1", title = "Window 1"),
                WindowSummary(windowId = "@0", title = "Window 2"),
                WindowSummary(windowId = "@2", title = "Window 3"),
            ),
            windows,
        )
    }

    @Test
    fun toWindowSummariesPreservesTmuxWindowIndexForDirectNavigation() {
        val panes = listOf(
            pane(paneId = "%0", windowId = "@9", windowIndex = 1),
            pane(paneId = "%1", windowId = "@4", windowIndex = 0),
            pane(paneId = "%2", windowId = "@9", windowIndex = 1),
        )

        val windows = panes.toWindowSummaries()

        assertEquals(
            listOf(1, 0),
            windows.map { it.windowIndex },
        )
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
        // Presumed-agent, no live detection: the Conversation tab EXISTS (it
        // does not vanish during slow detection), but the active index stays on
        // Terminal because there is no transcript to switch to yet.
        val state = tmuxSessionTabState(
            currentAgentConversation = null,
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

    @Test
    fun isAgentPaneFollowsPresumedAgent() {
        // Agent chips/affordances follow presumed-agent, so they don't flip to
        // shell chips during the slow-detection window.
        assertTrue(tmuxSessionIsAgentPane(hasLiveDetection = false, presumedAgent = true))
        assertTrue(tmuxSessionIsAgentPane(hasLiveDetection = true, presumedAgent = false))
        assertTrue(!tmuxSessionIsAgentPane(hasLiveDetection = false, presumedAgent = false))
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

    private fun pane(
        paneId: String,
        windowId: String,
        windowIndex: Int? = null,
        title: String = paneId,
    ): TmuxPaneState =
        TmuxPaneState(
            paneId = paneId,
            windowId = windowId,
            windowIndex = windowIndex,
            sessionId = "\$0",
            title = title,
            terminalState = TerminalSurfaceState(),
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
