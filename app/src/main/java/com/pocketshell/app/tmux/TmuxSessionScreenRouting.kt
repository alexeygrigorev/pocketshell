package com.pocketshell.app.tmux

import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.app.session.SessionTab
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.terminal.selection.LocalhostUrl
import com.pocketshell.uikit.model.SessionAgentKind

internal data class PortForwardNavigationTarget(
    val remotePort: Int,
    val autoOpenLocalhostUrl: LocalhostUrl?,
)

internal fun acceptedLocalhostForwardNavigation(localhostUrl: LocalhostUrl): PortForwardNavigationTarget =
    PortForwardNavigationTarget(
        remotePort = localhostUrl.remotePort,
        autoOpenLocalhostUrl = localhostUrl,
    )

internal fun detectedPortForwardNavigation(remotePort: Int): PortForwardNavigationTarget =
    PortForwardNavigationTarget(
        remotePort = remotePort,
        autoOpenLocalhostUrl = null,
    )

internal data class TmuxSessionTabState(
    val labels: List<String>,
    val selectedIndex: Int,
    val showsConversationTab: Boolean,
)

/**
 * Issue #1085 (freeze F3 / R2): the STABLE projection of the surface pane's
 * agent conversation that the [TmuxSessionScreen] body chrome reads. The agent
 * transcript tail flushes a fresh `agentConversations` map every ~60ms while an
 * agent streams; that flush changes the conversation's `events` list but NOT
 * these fields ([detection] / [selectedTab] are unchanged mid-stream, and
 * [hasEvents] / [exists] only flip ONCE — on the first event / first row).
 *
 * The body derives this via `derivedStateOf`, so the per-flush map churn
 * re-runs only the (cheap) projection lambda and invalidates the body's ROOT
 * restart group ONLY when one of these stable fields actually changes — never
 * once per streaming flush. It is a `data class` so `derivedStateOf`'s default
 * structural-equality policy correctly suppresses no-op notifications. The
 * high-frequency `events` list is deliberately NOT a field here: it is read
 * inside the `surfaceContent` child scope (its own restart group) so a flush
 * recomposes only the transcript, not the chrome/composer/terminal.
 */
internal data class SurfaceConversationChrome(
    val detection: AgentDetection?,
    val selectedTab: SessionTab?,
    val hasEvents: Boolean,
    val exists: Boolean,
)

/**
 * Issue #716 (Slice A): is the visible pane an agent OR a *presumed* agent?
 *
 * The maintainer's #1 complaint: agent-detection is slow/uncertain right after
 * attach/switch/send, so `detection == null` for a while even though the
 * session IS an agent. The old gating keyed the whole agent surface
 * (Conversation tab, agent-aware chips, agent send-routing) on the single
 * positive signal `detection != null` — so the composer/agent surface
 * *vanished* during that window and the user couldn't type to the agent.
 *
 * The fix is to default to **presumed-agent** during uncertainty: a pane is
 * presumed-agent when
 *  - live detection landed (`detection != null`), OR
 *  - this pane was/is known to be an agent (the #462 `stickyAgentForPane` /
 *    `paletteAgent` last-known kind), OR
 *  - detection simply has not positively confirmed a shell yet.
 *
 * The agent surface is hidden ONLY on a positively-confirmed shell verdict
 * ([confirmedShell]). Issue #894 (Slice C) WIRES that verdict: the call site
 * now passes a real `confirmedShell` from the durable per-session recorded
 * `@ps_agent_kind=shell` record (via `TmuxSessionViewModel.confirmedShellPaneIds`),
 * NOT the old "no agent matched → assume shell" absence. So a genuine shell
 * pane collapses the surface (and never flashes the #878 "Loading
 * conversation…" placeholder), while a foreign / not-yet-classified pane keeps
 * the presumed-agent surface available throughout the detection window — the
 * #878 black-screen cure stays intact.
 *
 * Net effect: the composer/agent surface is available unless/until a trustworthy
 * shell verdict exists. A foreign agent session may show an empty Conversation
 * tab in the interim — an accepted trade-off (the maintainer's explicit non-goal
 * is a per-session toggle).
 */
internal fun tmuxSessionPresumedAgent(
    hasLiveDetection: Boolean,
    stickyAgent: AgentKind?,
    confirmedShell: Boolean,
): Boolean {
    // A trustworthy confirmed-shell verdict is the ONLY thing that collapses
    // the agent surface. Live detection or a known agent kind obviously make
    // the pane presumed-agent; but even with neither, absence of a positive
    // shell verdict means "not yet known to be shell" — which #716 treats as
    // presumed-agent so the composer/agent surface stays available during the
    // slow-detection window. (Slice C, issue #894, supplies the real verdict
    // from the durable recorded `@ps_agent_kind=shell` record; Slice A passed a
    // hard-wired `false`.)
    //
    // Issue #962: a recorded `@ps_agent_kind=shell` session with a live agent
    // started INSIDE it is re-classified OUT of confirmed-shell by the VM the
    // instant live detection binds the pane's agent
    // (`TmuxSessionViewModel.markAgentTailLive` → `applyRecordedShellVerdict(
    // isShell = false)`), so `confirmedShell` here is already false for such a
    // pane and the Conversation toggle shows. (Driving the override off the
    // AUTHORITATIVE detection event — not the pane's `#{pane_current_command}`,
    // which is the wrapper shell `comm` for a node-wrapped agent — is what makes
    // it fire for a real node-wrapped claude.)
    if (confirmedShell) return false
    return true
}

/**
 * Issue #716 (Slice A): the visible pane wears agent-aware chrome (agent
 * chips, no snippet picker, agent-payload send) for a live-detected agent OR a
 * presumed agent. Equivalent to [presumedAgent] today since presumed-agent
 * already subsumes the live-detection case, but kept as a named helper so the
 * call site reads intentionally and so a future confirmed-shell signal flows
 * through one place.
 */
internal fun tmuxSessionIsAgentPane(
    hasLiveDetection: Boolean,
    presumedAgent: Boolean,
): Boolean = hasLiveDetection || presumedAgent

/**
 * Issue #1235: quick-reply chips must require positive agent evidence. The
 * broader [tmuxSessionIsAgentPane] predicate intentionally includes the
 * optimistic presumed-agent default, which is useful for keeping agent chrome
 * reachable during detection but too broad for injecting reply chips over an
 * unknown shell.
 */
internal fun tmuxSessionHasPositiveAgentEvidence(
    hasLiveDetection: Boolean,
    hasStickyAgent: Boolean,
    recordedAgentKind: Boolean,
): Boolean = hasLiveDetection || hasStickyAgent || recordedAgentKind

/**
 * Issue #805 (regression of #744/#716): whether the bottom-bar chrome wears its
 * CONVERSATION layout — i.e. drops the Terminal-tab chips (`Enter` /
 * `show keyboard` / `hotkeys`) so the composer launcher fits.
 *
 * This must follow the Conversation TAB, not the detection-gated transcript.
 * The screen has two distinct "conversation is showing" signals:
 *
 *  - [showConversationTranscript] — the heavyweight live transcript is mounted
 *    (detection has landed). The OLD bottom-bar predicate.
 *  - [showConversationDetectingPlaceholder] — the user is on the Conversation
 *    tab but the agent engine is still being detected (`detection == null`), so
 *    the "Loading conversation…" placeholder is shown instead.
 *
 * On v0.4.7 the bottom bar keyed its Conversation chrome off the transcript
 * signal ALONE. During detection (placeholder shown, transcript not yet mounted)
 * the bar fell back to the THREE Terminal chips, which overflowed the primary
 * cluster and pushed the composer launcher off the right edge — the maintainer's
 * "composer absent while the agent is being detected" symptom (#805). The #744
 * invariant is that the composer stays reachable throughout the detection
 * window; this helper restores it by treating the WHOLE Conversation tab
 * (detecting OR loaded) as conversation chrome.
 */
internal fun tmuxSessionBottomControlsShowsConversation(
    showConversationTranscript: Boolean,
    showConversationDetectingPlaceholder: Boolean,
): Boolean = showConversationTranscript || showConversationDetectingPlaceholder

/**
 * Issue #761 / #454: whether the bottom controls surface the saved-snippet
 * picker chip (`+ snippet`).
 *
 * The snippet chip is a SHELL-pane affordance (#454: "shell panes keep the
 * saved-snippet picker"); agent panes intentionally omit it because the
 * composer's `{}` affordance already inserts saved prompts (#453). The chip is
 * therefore the inverse of being a *known* agent pane.
 *
 * Crucially it is gated on the ACTUAL agent signal — live detection OR a sticky
 * known-agent kind ([paletteAgent]) — NOT on the optimistic presumed-agent
 * default from #716. #716 makes every freshly-attached tmux pane presumed-agent
 * so the *composer / conversation surface* never vanishes during the
 * slow-detection window; but reusing that optimistic flag to gate the snippet
 * chip suppressed it on EVERY tmux pane, including genuine shells that had
 * never hosted an agent (the bug behind #761: the `session:add-snippet-chip`
 * tag was never in the tree on a shell pane). Gating on real agent evidence
 * keeps the composer available on a fresh pane (#716) while still showing the
 * snippet chip there until/unless the pane is actually known to be an agent —
 * symmetric with how the `/ commands` chip is gated on `paletteAgent != null`.
 *
 * @param hasHost the visible host is persisted (snippets are host-scoped; a
 *   transient/zero host id has no snippets to pick).
 * @param hasLiveDetection a live agent detection landed for this pane.
 * @param hasStickyAgent a previously-detected agent kind is remembered for this
 *   pane ([paletteAgent] / #462 sticky resilience).
 */
internal fun tmuxSessionShowsSnippetChip(
    hasHost: Boolean,
    hasLiveDetection: Boolean,
    hasStickyAgent: Boolean,
): Boolean = hasHost && !hasLiveDetection && !hasStickyAgent

/**
 * Issue #797 (Shape A — surface-follows-visible-pane): the pane that drives the
 * COMPOSER SURFACE + Conversation tab + detection lookup + send/input routing.
 *
 * The bug: the old gate keyed every one of those off `currentPane`, which is
 * `null` whenever the unified pager is settled on a CACHED (non-active) session
 * pane (`TmuxSessionViewModel.isActiveSessionPane` checks only `_panes.value`).
 * When a warm switch had not yet promoted the target into `_panes.value`, the
 * user was parked on a cached pane with `currentPane == null` → the
 * presumed-agent default (#716/#744) short-circuited to false → the composer
 * launcher vanished AND the Conversation tab emptied (the maintainer's live
 * repro: "composer button gone, Conversation tab shows nothing, not detected as
 * an agent"). Both symptoms collapse to the single `currentPane == null`
 * condition.
 *
 * The surface must follow the VISIBLE pane (whether active or cached) so the
 * composer + Conversation tab never vanish while the user is settled on a real
 * pane. The ONE exception is while a cross-session switch is hiding the terminal
 * (`switchHidesTerminal` — the reveal-hold overlay): the visible pane is then
 * the LEAVING session and we must NOT route anything to it (#661/#634/#636
 * stale-session content-bleed hazard), so the surface is suppressed for that
 * brief switch-in-flight window only and reappears the instant the target is
 * revealed/promoted.
 *
 * Returning the visible cached pane here is SAFE for routing because the cached
 * pane is exactly the session the user is parked on (their intended target) and
 * the companion promotion path ([tmuxSessionShouldPromoteSettledCachedPane])
 * promotes it to active — once active, `clientRef`/`_panes.value` bind input +
 * detection to it. Routing always targets the surface pane's own `paneId`, never
 * a stale leaving-session pane.
 *
 * @param visibleUnifiedPane the pane the pager is actually showing
 *   (`unifiedPanes[currentPage]`), active or cached.
 * @param switchHidesTerminal true while a cross-session switch holds the reveal
 *   (the leaving session's content must not be routed to or surfaced).
 */
internal fun tmuxSessionSurfacePane(
    visibleUnifiedPane: TmuxPaneState?,
    switchHidesTerminal: Boolean,
): TmuxPaneState? = if (switchHidesTerminal) null else visibleUnifiedPane

/**
 * Issue #797 (promotion-on-settle): decide whether a unified-pager settle that
 * came to rest on a CACHED (non-active) pane should trigger a warm switch
 * (pane-promotion) so input routing + agent detection are RESTORED for the
 * visible session.
 *
 * Why this is needed beyond the surface fix: a cached pane's bytes/detection are
 * owned by a DIFFERENT tmux runtime client than the active `clientRef`, so
 * showing a composer over a not-yet-promoted cached pane would be the
 * "fixed-but-still-broken" trap — the composer reappears but Send can't reach
 * the session and detection never fires. Promotion (the existing warm switch,
 * `sessionSwitchRequest` → connect → `clientRef`/`_panes.value` adopt the
 * target) is what actually rebinds input + detection.
 *
 * The drag-gated swipe path ([settleSessionSwitchTarget]) intentionally requires
 * a fresh user drag to avoid the #661/#634/#636 stale-settle yank. But that left
 * the PERSISTENT-STALL hole the maintainer hit: a cached pane the user is
 * genuinely parked on (no recorded drag, e.g. it became cached via a reconcile /
 * reorder) never promotes, so input stays dead "until I switched sessions several
 * times". This helper closes that hole WITHOUT reintroducing the stale-settle
 * bleed: it fires only when the pager is ALIGNED to the nav target and at rest on
 * a genuinely-cached OTHER session's pane. The #661 stale-during-connect artifact
 * is still guarded independently on the VM side
 * ([TmuxSessionViewModel.onUnifiedPageSettled]'s `connectingTarget`/`connectJob`
 * check), which suppresses a promote toward anything other than an in-flight
 * deliberate destination — so a stale index observed while a connect to a
 * DIFFERENT session runs never promotes the wrong session.
 *
 * @param settledPaneSession the session owning the settled pane (`null` when
 *   unresolved / mid-rebuild).
 * @param navTargetSession the nav destination's session (the screen's target).
 * @param settledPaneIsActiveSession whether the settled pane already belongs to
 *   the active session (`_panes.value`); if so there is nothing to promote.
 * @param pagerAlignedToNavTarget whether the pager has realigned to the nav
 *   target since the last target change (settles before realignment are
 *   stale-index artifacts).
 * @param switchHidesTerminal true while a switch holds the reveal — never promote
 *   off a stale settle while a deliberate switch is already in flight.
 */
internal fun tmuxSessionShouldPromoteSettledCachedPane(
    settledPaneSession: String?,
    navTargetSession: String,
    settledPaneIsActiveSession: Boolean,
    pagerAlignedToNavTarget: Boolean,
    switchHidesTerminal: Boolean,
): Boolean {
    if (switchHidesTerminal) return false
    if (!pagerAlignedToNavTarget) return false
    if (settledPaneIsActiveSession) return false
    if (settledPaneSession == null) return false
    if (settledPaneSession == navTargetSession) return false
    return true
}

/**
 * Issue #1158 (recurrence of #962/#1057): whether the tree has RECORDED this
 * session as a known agent kind (Claude / Codex / OpenCode), independent of
 * whether live agent-detection or conversation-source binding has succeeded.
 *
 * This is the "record, don't guess" signal (epic #821): a session that
 * PocketShell launched (or the user classified) as an agent carries a durable
 * `@ps_agent_kind` tmux option that reads back as one of these kinds. The
 * detection / transcript-source layer FREQUENTLY fails to bind for the
 * maintainer's real fleet — node-wrapped Claude, Codex (needs a
 * `/proc/<pid>/fd` process-match), and glm/Z.AI-Claude (transcript at a
 * different path/format, the #820 class) — but we STILL know it is an agent
 * because we recorded the kind. So the Conversation tab's *presence* must
 * follow the recorded kind, not the fragile live binding, or the toggle
 * vanishes for the life of the session (the maintainer's #1158 symptom).
 *
 * [SessionAgentKind.Shell] (a recorded plain shell), [SessionAgentKind.Probing]
 * / [SessionAgentKind.Exited] (transient), [SessionAgentKind.Unknown], and
 * `null` (foreign / not-yet-classified) are NOT recorded-agent kinds: they must
 * NOT force the tab, so the #894 "a confirmed shell with no agent evidence
 * hides the Conversation tab" no-flap invariant stays intact. (A foreign /
 * unknown session already keeps the tab via the presumed-agent path.)
 */
internal fun tmuxSessionRecordedAgentKind(recordedKind: SessionAgentKind?): Boolean =
    when (recordedKind) {
        SessionAgentKind.Claude,
        SessionAgentKind.Codex,
        SessionAgentKind.OpenCode -> true
        SessionAgentKind.Shell,
        SessionAgentKind.Probing,
        SessionAgentKind.Exited,
        SessionAgentKind.Unknown,
        null -> false
    }

internal fun tmuxSessionTabState(
    currentAgentConversation: AgentConversationUiState?,
    presumedAgent: Boolean = false,
    recordedAgentKind: Boolean = false,
    altBufferAgent: Boolean = false,
): TmuxSessionTabState {
    // The Conversation tab exists for a live-detected agent OR a presumed
    // agent (#716). Issue #778: the active index now follows the user's
    // `selectedTab` choice on ANY pane that shows the Conversation tab — a
    // presumed agent counts, even before live detection lands. The old gate
    // required `hasLiveDetection`, which made a Conversation tap a no-op during
    // the slow-detection window (the tab was drawn but the index could never
    // become 1). Honouring the presumed-agent selection lets the screen render
    // a "waiting for agent" placeholder instead of swallowing the tap; the real
    // transcript replaces it the instant detection seeds.
    //
    // Issue #1057 (maintainer dogfood blocker — "conversation is not visible in
    // this app"): the old gate ALSO hid the Conversation tab entirely whenever a
    // pane was NOT presumed-agent (a confirmed shell) and had no live detection.
    // That made an agent conversation that genuinely EXISTS on the pane
    // permanently unreachable when detection was wrong/pending/dropped — exactly
    // the maintainer's symptom. The tab is now reachable whenever a conversation
    // EXISTS or COULD exist for the pane, independent of correct auto-detection:
    //  - a live-detected agent (`hasLiveDetection`), OR
    //  - a presumed agent during the slow-detection window (`presumedAgent`), OR
    //  - a transcript that already exists for the pane (events present, or a
    //    seeded/remembered placeholder row) even though detection is currently
    //    null and the pane is recorded as a shell (`hasConversationContent`), OR
    //  - the user has DELIBERATELY opened the Conversation surface for this pane
    //    (`userOpenedConversation`) — so the choice is honoured and persists
    //    (per-pane `selectedTab`) even across a re-classification to shell.
    // Hard-cut (D22): this replaces the "hide it on a confirmed shell" gate; no
    // settings flag or legacy fallback is kept.
    val hasLiveDetection = currentAgentConversation?.detection != null
    val hasConversationContent = currentAgentConversation?.let { conversation ->
        conversation.events.isNotEmpty() ||
            conversation.autoSeededPlaceholder ||
            conversation.rememberedAgentPlaceholder
    } == true
    val userOpenedConversation =
        currentAgentConversation?.selectedTab == SessionTab.Conversation
    // Issue #1158 (recurrence of #962/#1057): a RECORDED agent kind
    // (Claude / Codex / OpenCode) is a first-class tab-presence signal on its
    // own — the tab shows even when live detection AND transcript-source binding
    // both failed. This is the durable fix for the maintainer's fleet where the
    // detection/source layer can't bind (node-wrapped Claude, Codex `/proc`
    // process-match, glm/Z.AI alternate transcript path) yet the tree KNOWS the
    // session is an agent. The existing detection/content/presumed-agent signals
    // are kept as ADDITIONAL signals (recorded-kind OR detection → show tab), so
    // the vanilla-Claude case and the #894 confirmed-shell-hides-tab no-flap
    // invariant are unchanged. When the tab is shown but the source can't bind,
    // the user reaches the existing Placeholder/Failed surface
    // ([tmuxSessionConversationSurface]) — the whole tab is NEVER collapsed on a
    // source-binding hiccup.
    //
    // Issue #1158 (REOPENED — the maintainer still can't reach conversations when
    // an agent is launched DIRECTLY inside an existing shell session, so nothing
    // ever recorded `@ps_agent_kind` and live detection never binds for the
    // node-wrapped-Claude / Codex-`/proc` / Z.AI fleet). [altBufferAgent] is a
    // detection-INDEPENDENT positive agent signal: the visible pane's emulator is
    // on the ALTERNATE screen buffer, which a full-screen agent TUI holds for its
    // whole run while a plain shell at a prompt does not. It is fed STICKY from the
    // VM ([TmuxSessionViewModel.altBufferAgentPaneIds]) — once a pane/session has
    // been seen on the alt-buffer the tab stays for the session's life even if the
    // buffer later leaves alt-mode or detection drops. Because it is a POSITIVE
    // signal, a genuine plain interactive shell (main buffer) still shows NO
    // Conversation tab, preserving the #894/#815 no-flap invariant.
    val showsConversationTab =
        hasLiveDetection || presumedAgent || hasConversationContent ||
            userOpenedConversation || recordedAgentKind || altBufferAgent
    return TmuxSessionTabState(
        labels = if (showsConversationTab) listOf("Terminal", "Conversation") else listOf("Terminal"),
        selectedIndex = if (showsConversationTab && userOpenedConversation) 1 else 0,
        showsConversationTab = showsConversationTab,
    )
}

/**
 * Issue #1057: which surface the in-session content area renders for the visible
 * pane — the heavyweight Conversation transcript, the lightweight Conversation
 * placeholder ("Loading conversation…" / Failed), or the Terminal pager.
 *
 * Extracted from the inline `showConversation` / `showConversationPlaceholder`
 * flags in [TmuxSessionScreen] so the tap-to-switch content routing is unit
 * testable: when the user opens the Conversation tab on a pane whose agent was
 * mis-classified as a shell (the maintainer's "can't see conversation"
 * scenario), the existing transcript must render — and when no transcript has
 * loaded yet, the placeholder must render — instead of silently staying on the
 * Terminal.
 *
 *  - [Transcript]: mount [TmuxConversationPane]. Requires the ACTIVE pane
 *    (`isActivePane`, the promoted runtime's pane — preserves the #797/#605
 *    transcript mount/teardown invariants) AND a real transcript to show
 *    (live detection OR already-loaded events). The #793 loading/failed/empty
 *    sub-states are handled by the caller's branches on the row's `loadState`.
 *  - [Placeholder]: a lightweight "Loading conversation…" / Failed placeholder
 *    for a Conversation tab the user opened that has no transcript yet
 *    (detection null AND no events). Requires only the surface pane and that the
 *    Conversation tab is reachable ([showsConversationTab]) — so it shows on a
 *    confirmed-shell pane the user deliberately switched to Conversation, not
 *    only on a presumed-agent pane (the old `presumedAgent` gate left such a
 *    tap rendering the Terminal).
 *  - [Terminal]: the user is on the Terminal tab, or the pane has nothing to
 *    show on the Conversation surface.
 */
internal enum class TmuxConversationSurface { Transcript, Placeholder, Terminal }

internal fun tmuxSessionConversationSurface(
    showsConversationTab: Boolean,
    isActivePane: Boolean,
    hasSurfacePane: Boolean,
    selectedTab: SessionTab?,
    hasDetection: Boolean,
    hasEvents: Boolean,
): TmuxConversationSurface {
    if (selectedTab != SessionTab.Conversation) return TmuxConversationSurface.Terminal
    if (isActivePane && (hasDetection || hasEvents)) return TmuxConversationSurface.Transcript
    if (hasSurfacePane && showsConversationTab && !hasDetection && !hasEvents) {
        return TmuxConversationSurface.Placeholder
    }
    return TmuxConversationSurface.Terminal
}

/**
 * Issue #716 (Slice A): which send path the unified prompt composer uses for
 * the visible pane.
 *
 *  - [AgentConversation]: live-detected agent on the Conversation tab — submit
 *    to the agent AND echo the optimistic user turn into the transcript
 *    (`sendToAgentPaneResult`). Unchanged from before #716.
 *  - [AgentPayload]: route through the agent payload formatter
 *    (`sendAgentPayloadToPaneResult`) so the prompt reaches the agent without a
 *    raw-bytes fallthrough. Two cases land here, both preserving prior
 *    behaviour for confirmed agents:
 *      1. The pre-#716 Codex-on-Terminal-tab `withEnter` special case
 *         (`liveAgent == Codex`).
 *      2. #716: a *presumed* agent with NO live detection yet
 *         (`liveAgent == null`) but a known/last-known agent kind
 *         ([presumedAgentKind]) — the slow-detection window. The prompt still
 *         reaches the agent (no raw-bytes fallthrough); there is no transcript
 *         to echo into yet, so no optimistic turn is inserted.
 *  - [RawBytes]: the plain shell write path. A *confirmed* agent
 *    (`liveAgent != null`) on the Terminal tab keeps its pre-#716 raw-bytes
 *    behaviour here (except the Codex special case above), so a confirmed agent
 *    is unchanged. RawBytes is also the path for a genuine no-agent pane.
 */
internal enum class TmuxComposerSendRoute { AgentConversation, AgentPayload, RawBytes }

internal fun tmuxComposerSendRoute(
    viewingConversation: Boolean,
    liveAgent: AgentKind?,
    presumedAgentKind: AgentKind?,
    withEnter: Boolean,
): TmuxComposerSendRoute = when {
    // Conversation tab of a live-detected agent: agent submit + optimistic echo.
    viewingConversation -> TmuxComposerSendRoute.AgentConversation
    // Codex on the Terminal tab keeps its with-Enter payload formatting (the
    // pre-#716 special case) — route through the agent payload path.
    withEnter && liveAgent == AgentKind.Codex -> TmuxComposerSendRoute.AgentPayload
    // Confirmed agent (live detection) on the Terminal tab: unchanged pre-#716
    // raw-bytes behaviour. Only Codex (above) deviates.
    liveAgent != null -> TmuxComposerSendRoute.RawBytes
    // #716: presumed-agent without a live transcript yet — still route to the
    // agent, not raw bytes, using the known/last-known agent kind.
    presumedAgentKind != null -> TmuxComposerSendRoute.AgentPayload
    else -> TmuxComposerSendRoute.RawBytes
}

/**
 * Issue #1207: true when [text] is an agent TUI slash-command — an alt-screen
 * interaction (`/model`, `/config`, `/login`, `/agents`, permission pickers …)
 * that the agent handles in its terminal UI and writes NOTHING to the JSONL
 * transcript. Such input can NEVER appear on the Conversation surface by
 * construction, so it must NOT get an optimistic transcript bubble, and the user
 * must be offered the Terminal (the only surface that can drive the picker).
 *
 * The grammar is deliberately narrow so a normal prompt or a filesystem path is
 * NOT misclassified:
 *  - the trimmed text is a single line (a multi-line message is a prompt);
 *  - its first whitespace-delimited token is `/word[...]` where `word` starts
 *    with a letter and contains only `[A-Za-z0-9_-]` (no further `/`), so
 *    `/model`, `/model sonnet`, `/config` match but `/home/user/file`,
 *    `/`, and `/2 + 2` (a leading-slash math prompt) do not.
 */
internal fun tmuxComposerIsTuiSlashCommand(text: String): Boolean {
    val trimmed = text.trim()
    if (!trimmed.startsWith("/")) return false
    // A multi-line message is a prompt the user typed, not a slash-command.
    if (trimmed.contains('\n') || trimmed.contains('\r')) return false
    val firstToken = trimmed.substringBefore(' ').substringBefore('\t')
    return firstToken.matches(Regex("^/[A-Za-z][A-Za-z0-9_-]*$"))
}

/**
 * Issue #1207: which agent-conversation send path a composer submit takes.
 *  - [Echo]: a normal prompt — submit to the agent AND echo the optimistic user
 *    turn into the transcript (`sendToAgentPaneResult`), unchanged from before.
 *  - [TuiCommandNoEcho]: a TUI-only slash-command — deliver the keystrokes to
 *    the pane WITHOUT an optimistic transcript bubble and raise the
 *    Open-in-Terminal notice, because the picker it opens shows only on the
 *    covered Terminal pane, never on the Conversation surface.
 */
internal enum class TmuxAgentConversationSend { Echo, TuiCommandNoEcho }

internal fun tmuxAgentConversationSend(text: String): TmuxAgentConversationSend =
    if (tmuxComposerIsTuiSlashCommand(text)) {
        TmuxAgentConversationSend.TuiCommandNoEcho
    } else {
        TmuxAgentConversationSend.Echo
    }

/**
 * Issue #1207: resolve the load state the Conversation-tab placeholder renders
 * when it has NO backing conversation row (`rowLoadState == null`).
 *
 * The stranded-spinner bug: the 2-consecutive-null detection teardown
 * (`AGENT_EXIT_CONFIRMATIONS`) can remove the conversation row BEFORE the 12s
 * load watchdog fires. The placeholder is still shown (the tab stays reachable
 * via `presumedAgent`), but with no row there is no watchdog behind it — so a
 * `?: Loading` fallback spins FOREVER. A missing row means no load is in flight
 * and nothing can ever flip it to a terminal state, so it MUST resolve to a
 * terminal legible state ([ConversationLoadState.Empty] — "No conversation yet,
 * the agent is live in the Terminal tab"), never [ConversationLoadState.Loading].
 * When a row exists we honour its own load state (a `Loading` row always has the
 * watchdog armed behind it).
 */
internal fun tmuxConversationPlaceholderLoadState(
    rowLoadState: ConversationLoadState?,
): ConversationLoadState = rowLoadState ?: ConversationLoadState.Empty

internal fun tmuxComposerOutboundRoute(route: TmuxComposerSendRoute): OutboundRoute = when (route) {
    TmuxComposerSendRoute.AgentConversation -> OutboundRoute.AgentConversation
    TmuxComposerSendRoute.AgentPayload -> OutboundRoute.AgentPayload
    TmuxComposerSendRoute.RawBytes -> OutboundRoute.RawBytes
}

internal fun tmuxComposerAgentToken(agent: AgentKind?): String? = when (agent) {
    AgentKind.ClaudeCode -> "claude"
    AgentKind.Codex -> "codex"
    AgentKind.OpenCode -> "opencode"
    null -> null
}

internal fun tmuxComposerAgentKindFromToken(token: String?): AgentKind? = when (token?.lowercase()) {
    "claude", "claudecode", "claude_code" -> AgentKind.ClaudeCode
    "codex" -> AgentKind.Codex
    "opencode", "open_code" -> AgentKind.OpenCode
    else -> null
}

internal fun tmuxComposerSendTargetSnapshot(
    sessionKey: String,
    paneId: String?,
    route: TmuxComposerSendRoute,
    agentKind: AgentKind?,
): PromptComposerViewModel.SendTargetSnapshot =
    PromptComposerViewModel.SendTargetSnapshot(
        sessionKey = sessionKey,
        paneId = paneId.orEmpty(),
        route = tmuxComposerOutboundRoute(route),
        agentKind = tmuxComposerAgentToken(agentKind),
    )

internal fun handleTmuxSessionSelection(
    currentSessionName: String,
    selectedSessionName: String,
    onDismiss: () -> Unit,
    onReplace: (String) -> Unit,
) {
    if (selectedSessionName == currentSessionName) return
    onDismiss()
    onReplace(selectedSessionName)
}
