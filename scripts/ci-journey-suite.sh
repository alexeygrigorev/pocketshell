#!/usr/bin/env bash
# Per-push CI journey suite — load-bearing subset (issue #691, epic #657).
#
# The #638 mandate: the load-bearing session-switch / reconnect JOURNEY proofs
# must run in REGULAR per-push CI, not only nightly + the release gate. Before
# this, `tests.yml` ran no `androidTest` at all, so a PR could merge green while
# silently regressing within-grace ride-through, beyond-grace reattach, the
# A->B->C->A no-bleed switch, no-resurrect, and reseed-on-reattach — exactly the
# #685 failure mode.
#
# This driver runs inside the `reactivecircus/android-emulator-runner`
# `script:` step, so the emulator is already booted and `adb` is on PATH. The
# deterministic Docker `agents` fixture (agents:2222 -> emulator 10.0.2.2:2222)
# was started + waited-healthy by the workflow before this script runs.
#
# It runs ONLY the load-bearing subset (NOT the full connected suite — that
# stays nightly). Every class below uses the plain deterministic `agents:2222`
# fixture; NONE need the opt-in toxiproxy network-fault proxies, so the job is
# deterministic and does not depend on the proxy family the per-push job
# deliberately leaves down. `pocketshellCi=true` selects the generous E2E
# timeout ceilings so a slow CI runner does not flake these out.
#
# Load-bearing subset and what each pins:
#   * DeepLinkSessionSwitchE2eTest (issue #470)
#       - picker-FREE programmatic attach via the production deep-link intent
#         (MainActivity.EXTRA_OPEN_SESSION_*): lands directly on
#         AppDestination.TmuxSession so TmuxSessionScreen auto-connects via the
#         real `tmux -CC` attach. Asserts per attach: terminal view attaches
#         (NEVER black/blank), the attached session's own seed marker is visible
#         (correct/non-stale/re-seeded), no Disconnected band/EOF, and the OTHER
#         session's marker does not bleed in. The A->B "switch" is a fresh
#         deep-link to B. This is the load-bearing terminal-attach/switch proof.
#
# PICKER-DRIVEN journeys (issue #705): the shared session-PICKER enumeration
# (FolderListGateway.listSessionsWithFolder over the warm SSH lease) was WEDGED
# on the AVD — every test that taps a host row and waited on
# `waitForSessionInPicker` stalled (picker never left Loading). That wedge is
# now FIXED (the lease-bound cold connect/handshake of #687 + the picker fix in
# #702), and the over-broad header assertion that collided with the #628
# previous-session toggle controls is corrected in #705. So three picker-driven
# journeys (MultiSessionSwitchJourneyE2eTest,
# ColdRestoreGoneSessionNoResurrectE2eTest, ReconnectRepaintE2eTest) re-join
# this per-push subset below — each was proven GREEN on a pooled AVD before
# re-adding (no flake, no CI email spam).
#
# RE-ADDED (issue #707): BackgroundGraceReconnectE2eTest now re-joins this subset.
# The triage (#707) established the within-grace `terminal_foreground_reattach`
# diagnostic was a BENIGN fan-out marker, not a real reconnect: since #548 /
# commit 1271a60e, App.kt fires dispatchTmuxForeground() unconditionally on every
# foreground (even within grace) so a stale transport is probed early, and that
# call records `terminal_foreground_reattach` purely as a dispatch label. The
# E2E `assertNoReconnectOrReattachDiagnostics` is now narrowed to forbid only
# the GENUINE reconnect signals (reconnect_start, network_reconnect_start, the
# VM-level foreground_reattach, foreground_runtime_probe_failed,
# terminal_background_teardown) — none of which fire within grace — so both its
# tests are green. No production code changed; the triage confirmed the #685
# grace path is correct.
#
# The heavy/long-running + toxiproxy network-fault + bootstrap-matrix suites
# stay in nightly-extensive.yml. This is intentionally the fast per-push subset.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

# Make the soft IME deterministic on the booted journey AVD (issue #736).
#
# PromptComposerImeSquishProofTest must RAISE the soft keyboard to validate the
# keyboard-up squish. On a fresh swiftshader AVD the soft IME can refuse to show
# when a hardware keyboard is assumed present, so we enable the IME-with-hard-
# keyboard secure setting on every connected device before running the suite.
# This is the device-side half of the determinism fix; the test itself also
# explicitly re-issues WindowInsetsControllerCompat.show(ime()) and HARD-FAILS
# (no silent skip) if the keyboard still cannot be raised. `adb` is on PATH and
# the emulator is already booted inside the android-emulator-runner `script:`
# step, so this runs against the live AVD. Best-effort: never abort the suite if
# a device momentarily rejects the setting (the test's own hard-fail guard is
# the real backstop).
for _serial in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
  adb -s "$_serial" shell settings put secure show_ime_with_hard_keyboard 1 \
    >/dev/null 2>&1 || true
done

ARTIFACT_DIR="$REPO_ROOT/artifacts/ci-journey"
mkdir -p "$ARTIFACT_DIR"
SUMMARY="$ARTIFACT_DIR/summary.md"

GRADLEW="$REPO_ROOT/gradlew"

FQCN_PREFIX="com.pocketshell.app.proof"

# The load-bearing journey classes. Keep this list aligned with the issue #691
# named subset; every class here MUST use only the deterministic agents:2222
# fixture (no toxiproxy) so the per-push job stays deterministic.
#
# Issue #705: three picker-driven journeys re-join the picker-FREE
# DeepLinkSessionSwitchE2eTest now that the picker enumeration wedge is fixed
# (#687 + #702) and the over-broad header assertion is scoped to the breadcrumb
# crumb (#705). Each was proven GREEN on a pooled AVD before re-adding.
# BackgroundGraceReconnectE2eTest re-joins this subset (#707): the within-grace
# `terminal_foreground_reattach` it forbade was a benign fan-out marker, not a
# reconnect; the assertion is narrowed to genuine reconnect signals only.
#
# Issue #710 (RE-ADDED): MultiSessionSwitchJourneyE2eTest is back in this
# per-push subset. It was quarantined under #691 (58d9957f) because it wedged
# (never finished) on the GitHub android-emulator-runner AVD while passing on
# the local pooled AVD — the rapid A->B->C->A switch backgrounded a just-switched
# runtime whose VM-clear park then stalled the main thread on an UNBOUNDED
# `cancelAndJoin()` over a pane job wedged in a non-cooperative `-CC` socket read
# (run 27368527630 burned ~67 min to the job cap). #710 bounds that teardown
# (`CachedTmuxRuntime.closeCachedRuntime` + `closeCachedRuntimesBlocking`) at
# SYNC_DETACH_TIMEOUT_MS so the main thread is guaranteed to return, removing the
# CI-AVD wedge. The per-test `timeout_msec` backstop below stays as defense in
# depth so ANY future wedge fails fast (~5 min) instead of hanging the job.
JOURNEY_CLASSES=(
  "$FQCN_PREFIX.DeepLinkSessionSwitchE2eTest"
  # RE-ADDED (#710): the CI-AVD wedge was the unbounded VM-clear park teardown,
  # now bounded at SYNC_DETACH_TIMEOUT_MS. See the block comment above.
  "$FQCN_PREFIX.MultiSessionSwitchJourneyE2eTest"
  # ADDED (#758): the maintainer's priority-#1 back→open-another-session
  # reconnect. Opens session A, taps BACK to the picker, triggers a discovery
  # reconcile over the shared SSH lease (with ONE deterministically-injected
  # poll-time stale-channel symptom via the inert
  # SshFolderListGateway.forcedStaleChannelSymptoms test hook — production
  # default 0), then opens session B and asserts the VISIBLE warm-reuse
  # invariants: ZERO fresh SSH_HANDSHAKE_ATTEMPTS delta, NO full-screen
  # Connecting overlay, NO Disconnected band. Uses ONLY the deterministic
  # agents:2222 fixture (no toxiproxy), so it belongs in this per-push subset.
  # Pre-fix this would go RED (the gateway's unconditional `disconnect` tore down
  # the lease A held → cold re-dial); the #758 refcount-aware `evictIdle`
  # (no-op while the session VM holds the lease) makes it GREEN. Does NOT
  # self-skip on CI.
  "$FQCN_PREFIX.BackThenOpenSecondSessionReusesWarmLeaseE2eTest"
  "$FQCN_PREFIX.ColdRestoreGoneSessionNoResurrectE2eTest"
  "$FQCN_PREFIX.ReconnectRepaintE2eTest"
  # Issue #754 (slice 1c-iv-c): this class is the per-PR-CI deterministic regression
  # catcher for the within-grace "Attaching…" reconnect bug. It now (a) forbids the
  # TMUX_SWITCHING_LOADING_TAG "Attaching…" overlay on every within-grace foreground
  # and (b) asserts the within-grace reattach is the NEW driver-owned reseed-only
  # effect (`foreground_reattach outcome=reseed_only`) with NO inline probe
  # (`tmux_probe_result`). It runs on the deterministic agents:2222 fixture and needs
  # NO toxiproxy, so it stays in this per-push subset. On `main` the within-grace
  # foreground runs the inline probe (records tmux_probe_result, never reseed_only),
  # so these assertions FAIL on `main` and PASS after the fix. The strongest
  # CONFIRMED-DEAD-within-grace reproduction lives in WithinGraceResumeRideThroughE2eTest
  # (toxiproxy clean-cut, `withinGraceForegroundConfirmedDeadDoesNotShowAttachingOverlayOrReconnect`),
  # which is opt-in (assumeNetworkFaultProofsEnabled self-skips on CI since tests.yml
  # keeps this job toxiproxy-free); it is the local/manual fault-injection proof.
  "$FQCN_PREFIX.BackgroundGraceReconnectE2eTest"
  # PROMOTED (#727, epic #657 Wave 1 / S1): the share-auth journey pair. The
  # maintainer's recurring share-auth breakages had nightly-only / advisory E2E
  # coverage, so a share-auth regression was NOT caught at PR time. Both classes
  # drive ONLY the default deterministic `agents` fixture via DEFAULT_HOST /
  # DEFAULT_PORT / DEFAULT_USER (AgentsFixtureTarget -> 10.0.2.2:2222, or the
  # pool-allocated port under `--pool`), so no new Docker fixture/port is needed.
  # Neither class self-skips on CI (no assumeFalse(isRunningOnCi())). These live
  # under com.pocketshell.app.share, not the com.pocketshell.app.proof prefix, so
  # they carry their fully-qualified names directly.
  "com.pocketshell.app.share.ShareTargetE2eTest"
  "com.pocketshell.app.share.SharePassphraseDialogE2eTest"
  # PROMOTED (#732, Finding B): the host server-PROFILE discovery journey. The
  # picker false-greens on ToolUnavailable if the host `pocketshell profiles
  # list --json` branch or the seeded `~/.zlaude` profile silently regresses, so
  # this discovery proof must run at PR time, not only in the release gate. It
  # drives ONLY the default deterministic `agents` fixture (DEFAULT_HOST /
  # DEFAULT_PORT / DEFAULT_USER -> 10.0.2.2:2222, or the pool-allocated port
  # under `--pool`) — the same fixture every class above uses — so no new Docker
  # service/port is needed. The emulator-journey workflow brings up `agents`
  # with `up -d --build` (tests.yml), so CI gets the new `profiles list` branch
  # + seed, not a stale cached image. It does NOT self-skip on CI. This class
  # lives under com.pocketshell.app.projects, not the com.pocketshell.app.proof
  # prefix, so it carries its fully-qualified name directly.
  "com.pocketshell.app.projects.ProfileDiscoveryPickerDockerTest"
  # PROMOTED (#898, reviewer Blocker B): the in-session "+ New session" rich-sheet
  # create journey. A second "New session" in the SAME folder as an existing one
  # must get a deterministic `-2` suffix, not silently collide via `tmux
  # new-session -A`. The bug lives in the SCREEN wiring (each entry point must
  # load the host's known session names before opening the sheet) — the per-push
  # JVM guards only pin the pure `derivedSessionName` wrapper, so a regression
  # that drops the load on any entry point is per-push-INVISIBLE without this.
  # The class drives the REAL MainActivity → attach → kebab AND switcher "+ New
  # session" → Create over the default deterministic `agents` fixture
  # (DEFAULT_HOST/PORT/USER -> 10.0.2.2:2222, or the pool-allocated port under
  # `--pool`) — no new Docker service/port. It does NOT self-skip on CI. Lives
  # under com.pocketshell.app.tmux, so it carries its FQCN directly.
  "com.pocketshell.app.tmux.TmuxInSessionNewSessionCollisionDockerTest"
  # PROMOTED (#736, follow-up to the #567 review): the composer keyboard-up
  # SQUISH regression proof. The maintainer's #1 process complaint area is the
  # composer being crushed when the soft keyboard is up (draft collapsed to one
  # line, header clipped off the top, controls jammed at the keyboard). This
  # proof reproduces that exact state (multi-line draft + 2 staged attachment
  # tiles, IME raised) and asserts the body is NOT squished. It ran only in the
  # unfiltered full connected suite before; per the "load-bearing journeys run
  # at PR time" principle (#638/#657) it must run per-push so the squish can't
  # silently regress. It uses NO Docker fixture (pure Compose-rule UI test, no
  # SSH/tmux). The IME is raised DETERMINISTICALLY (the test re-issues
  # WindowInsetsControllerCompat.show(ime()), and this script sets
  # `show_ime_with_hard_keyboard 1` on the booted AVD near the top) so the
  # keyboard reliably appears on the CI swiftshader pixel_7 AVD; if it still cannot be
  # raised after the bounded attempt the test FAILS LOUD (no silent assumeTrue
  # skip — that was the #736 review blocker: a skip would let this gate go green
  # with zero squish protection). Its geometry assertions are framed relative to
  # the measured room above the keyboard (body-fits-room, send/attach-above-IME),
  # so they hold on the CI pixel_7 AVD (the same profile the journey AVD uses).
  # It lives under com.pocketshell.app.composer, not the proof prefix, so it
  # carries its fully-qualified name directly.
  "com.pocketshell.app.composer.PromptComposerImeSquishProofTest"

  # Issue #801: the keyboard-up squish on a REALISTIC TIGHT screen — the v0.4.6
  # regression of #567. The #780 proof above composes the composer in a GENEROUS
  # 740dp host, so the legacy `availableAboveKeyboard - reserve` floor never
  # fired there and the regression slipped through (a too-roomy proxy of the
  # reported state). This proof reproduces the maintainer's ACTUAL Pixel-7-class
  # geometry (~460dp host, ~184dp above a 300dp keyboard) where the old math
  # floored the scroll region to 88dp — below the draft's 96dp min — crushing the
  # field to one line and cramming the control row. It asserts (via the #657 / F1
  # containment helpers, not bare assertIsDisplayed) the draft keeps a multi-line
  # height and the Send/attach/mic controls are full-size and above the keyboard,
  # for BOTH a shell pane and an agent (ClaudeCode) pane. Like the #780 proof it
  # uses a SYNTHETIC ime() inset (no real keyboard, deterministic on CI
  # swiftshader) and NO Docker fixture, HARD-asserts the inset applied (no skip),
  # and runs per-push so this exact regression cannot silently return. It carries
  # its fully-qualified name directly.
  "com.pocketshell.app.composer.PromptComposerImeTightScreenSquishProofTest"

  # Issue #887: the terminal must stay FIXED when the soft keyboard shows —
  # NEITHER resized NOR panned. The #457 design kept the terminal from RESIZING
  # but PANNED it up (`graphicsLayer { translationY = panOffsetPx() }`), blacking
  # out the top of the terminal (the maintainer's #887 screenshot). The fix sets
  # the activity window to SOFT_INPUT_ADJUST_NOTHING and drops the in-app pan, so
  # the keyboard overlays the bottom rows and the terminal does not move. This
  # proof composes the PRODUCTION fixed terminal-column modifier under a SYNTHETIC
  # ime() inset (the #780 model — deterministic on CI swiftshader, no real
  # keyboard, HARD-asserts the inset applied, no assumeTrue skip) and asserts the
  # terminal node's boundsInRoot are IDENTICAL keyboard-up vs keyboard-down (no
  # pan, no resize) while the composer (`.imePadding()`) stays above the keyboard.
  # A second case composes the DELETED #457 pan shape and asserts it WOULD move
  # the terminal — the red→green guard proving the bounds-unchanged assertion is
  # load-bearing (G1/G10). No Docker fixture; runs per-push so this exact
  # regression cannot silently return. Carries its fully-qualified name directly.
  "com.pocketshell.app.tmux.Issue887TerminalFixedUnderImeProofTest"

  # Issues #870/#880: the Android-recognizer recording-surface dogfood pair.
  # #880 — the recording elapsed timer was frozen at 00:00 with the Android
  # SpeechRecognizer (the Whisper PCM sampler that ticks recordingElapsedMs never
  # ran for that path). #870 — a long live partial transcript clipped the END so
  # the newest recognized words scrolled out of view. This connected proof drives
  # the PRODUCTION PromptComposerViewModel through the real Android-speech FSM and
  # renders the REAL RecordingSurface, HARD-asserting the on-screen timer advances
  # from 00:00 and the live transcript shows its tail. Pure Compose, no fixture.
  "com.pocketshell.app.composer.PromptComposerRecordingTimerAndTailTest"

  # Issue #870 (reopen): the live Android-recognizer transcript was STILL cut on
  # device — the width-independent 90-char tail did not fit two lines at the real
  # panel width, so the trailing ellipsis re-clipped the NEWEST words. This renders
  # the PRODUCTION RecordingSurface in a NARROW panel (the on-device condition the
  # wide AVD masked), feeds a long growing partial, and HARD-asserts the visible
  # live transcript ends with the newest words AND fits the two-line area at the
  # real width (measured) — i.e. not clipped. Pure Compose, no fixture.
  "com.pocketshell.app.composer.PromptComposerLiveTranscriptTwoLineTest"

  # Issue #745: composer Send feedback on a DEGRADED connection. The maintainer
  # dogfooded a blind send: tapping Send cleared the draft and showed nothing —
  # no "Sending…" indicator, no connection-lost banner, and the "Not sent"
  # result surfaced only after a long blind wait. This regression covers the
  # four feedback states: (a) immediate in-flight "Sending…" spinner + disabled
  # Send, (b) the draft is RETAINED through the failure (no optimistic empty),
  # (c) the failure resolves within a BOUNDED time, (d) the connection-lost
  # indicator is visible up front. It is a load-bearing dogfood report, so per
  # the "load-bearing journeys run at PR time" principle (#638/#691) it must run
  # per-push. It uses NO Docker fixture (pure Compose-rule UI test driving the
  # real PromptComposerViewModel send wiring), so it slots in next to the IME
  # squish proof. The in-flight assertion is held open deterministically (the
  # collector parks on a test-controlled CompletableDeferred), so it does not
  # race a wall-clock timeout. It carries its fully-qualified name directly.
  "com.pocketshell.app.composer.PromptComposerDegradedSendE2eTest"
  # PROMOTED (#746): the composer "Not sent" DISCARD + draft session-scoping
  # journey. The maintainer's dogfood report: a "Not sent" draft authored in one
  # session had no Discard control (only Send + the close ×, which PRESERVES the
  # draft) AND bled into other sessions (the activity-scoped composer is shared
  # across every session on a host). This proof drives the real
  # PromptComposerViewModel and asserts (1) the "Not sent" banner shows a Discard
  # button that clears text + attachments + banner, and (2) a draft authored in
  # session A is gone after the composer is re-targeted to session B (no bleed).
  # It coexists with the #745 send-feedback states (the failed send routes
  # through restoreFailedSend, which folds the attachment into the draft). It
  # uses NO Docker fixture (pure Compose-rule UI test, no SSH/tmux) and does NOT
  # self-skip on CI. Per the "load-bearing journeys run at PR time" principle
  # (#638/#691) it runs per-push so the no-discard / cross-session-bleed
  # regression cannot silently return. It lives under com.pocketshell.app.composer,
  # not the proof prefix, so it carries its fully-qualified name directly.
  "com.pocketshell.app.composer.PromptComposerDiscardE2eTest"
  # ADDED (#585): Telegram-style hold-and-pull-up on the composer mic must start
  # recording and lock it hands-free in one gesture, with the real
  # ModalBottomSheet still mounted so the mic detector competes with the sheet
  # drag detector. This was repeatedly emulator-green but device-broken, so the
  # focused journey is gate-wired per D31/G9; it uses no Docker fixture.
  "com.pocketshell.app.composer.PromptComposerMicSwipeLockJourneyTest"
  # ADDED (#832 — durable per-session composer draft store): a draft authored in
  # session A and lost on a FAILED attachment-send (despite the "Your draft was
  # kept" banner) must survive. The ComposerDraftStore persists the per-session
  # draft so restoreFailedSend re-folds it back into the composer instead of
  # dropping it, and switching A→B→A restores A's draft (no cross-session bleed).
  # RED on the unfixed code (the failed send drops the text); the durable store
  # flips it GREEN. Pure Compose-rule UI test, NO Docker fixture (no SSH/tmux),
  # does NOT self-skip on CI. Per the "load-bearing journeys run at PR time"
  # principle (#638/#691) it runs per-push so the draft-loss regression cannot
  # silently return (D31/G9 — an on-device regression test no gate runs is the
  # same as no test). Lives under com.pocketshell.app.composer, not the proof
  # prefix, so it carries its fully-qualified name directly.
  "com.pocketshell.app.composer.ComposerDraftDurabilityE2eTest"
  # ADDED (epic #687 Phase 1 / P1 — the device-truth gate J3, #686/#658): switch
  # A→B while session A keeps STREAMING (a late/stale capture from A races the
  # switch) must show B in the rendered PANE BODY + header label and paint NO
  # stray SessionBoundaryDivider(sessionName=A). Asserts the pane body (the
  # rendered terminal transcript), NOT header text nodes only — the header-only
  # #686 test misses the maintainer's wrong-session-on-switch bug. RED on the
  # unfixed code (the pager still paints A's panes after the switch to B); the
  # P1 screen-keyed-to-target-sessionId fix (default-NEW connection path drives
  # the RevealStateMachine, panes/divider keyed to the target session) flips it
  # GREEN. Uses ONLY the deterministic agents:2222 fixture (no toxiproxy, no
  # assumeFalse(isRunningOnCi())), so it belongs in this per-push subset.
  "$FQCN_PREFIX.SwitchStaleCaptureSessionBodyJourneyE2eTest"
  # ADDED (epic #687 Phase 2 / P2 — the device-truth gate J1, #635): a
  # background→foreground WITHIN grace where the `-CC` socket DROPPED while
  # backgrounded (WiFi→cellular handoff / Doze modelled by a `kill -9` of the
  # app's own sshd worker from a sidecar session) must RE-SEED the pane with the
  # prior content and surface NO Reconnecting/Disconnected/Connecting/Attaching
  # band or overlay. RED on the unfixed/OLD path (the dropped socket makes the
  # within-grace reseed gate decline — dead lease / paused passive — so the
  # foreground falls into the reconnect ladder and paints a band); the P2
  # single-grace-owner fix (under NEW the App-level within-grace window is the
  # SOLE grace owner — the inline passive grace clock is disabled while
  # backgrounded, and a within-grace foreground SILENTLY heals the dropped channel
  # then reseeds) flips it GREEN. Uses ONLY the deterministic agents:2222 fixture
  # (the kill is a CI-compatible sidecar `kill -9`, no toxiproxy), and does NOT
  # self-skip on CI, so it belongs in this per-push subset.
  "$FQCN_PREFIX.WithinGraceSocketDropForegroundJourneyE2eTest"
  # ADDED (epic #687 Phase 3 / P3 — the device-truth gate J2, #553): a within-grace
  # reattach that leaves the pane PARTIALLY blank (one live timer line painting, the
  # static viewport above it wiped by a reflow during a brief link blip) must restore
  # the FULL prior viewport, not just the live line. The warm `-CC` client is RETAINED
  # across the (no-socket-drop) within-grace background, and the post-reflow partial
  # blank is reproduced DIRECTLY on the retained emulator (a local `CSI 2J`+`CSI H`+one
  # timer line — the REMOTE tmux grid is untouched, so capture-pane still holds the full
  # banner). RED on the unfixed/OLD path (the non-blank timer line makes
  # `reseedBlankVisiblePanes` SKIP the pane → banner never restored); the P3 id-tagged
  # FULL-VIEWPORT reseed under the NEW path (`reseedActivePaneForReattach` —
  # UNCONDITIONAL, not gated on full-blank, keyed to the target session id) restores the
  # banner and flips it GREEN. Uses ONLY the deterministic agents:2222 fixture (the
  # partial blank is injected LOCALLY on the emulator, no toxiproxy), and does NOT
  # self-skip on CI, so it belongs in this per-push subset.
  "$FQCN_PREFIX.ReconnectPartialBlankReseedJourneyE2eTest"
  # ADDED (#892 — the manual "Redraw" kebab escape hatch): the maintainer dogfooded a
  # connected Claude session that came back ~100% BLACK with only a stray cursor cell
  # (the partial-repaint class) and asked for a one-tap Redraw that force-repaints the
  # whole terminal. This journey attaches a full-viewport static-banner pane, wipes the
  # LIVE emulator to that black state (a local `CSI 2J`+`CSI H` — the REMOTE tmux grid
  # still holds the banner), then OPENS the kebab and TAPS the stable-tagged Redraw item.
  # RED on base (no `redrawActivePane()` reseed wired → nothing re-captures the warm pane
  # → banner never returns); GREEN with the fix (the warm-session full-viewport reseed —
  # the SAME #553/#879 `reseedActivePaneForReattach` path, UNCONDITIONAL — restores the
  # full banner, with NO reconnect/detach/new lease and the session staying Connected).
  # Two tests cover BOTH a shell pane AND an idle alt-screen (Claude/Codex) pane (D32 G2).
  # Uses ONLY the deterministic agents:2222 fixture (the black state is injected LOCALLY
  # on the emulator, no toxiproxy) and does NOT self-skip on CI, so it belongs here.
  "$FQCN_PREFIX.RedrawFullViewportReseedJourneyE2eTest"
  # ADDED (epic #687 slice 2, #717 — reveal/reflow-heal absorbed from #658): after a
  # voice-send the active pane must NEVER go black. The composer/keyboard dismissal
  # shrinks the IME inset → `resizeRemotePty` → `maybeRefreshControlClientSize`; for an
  # IDLE full-screen agent pane the reflow during the IME transition wipes the LOCAL
  # emulator while the idle agent emits no fresh `%output`, AND when the dismiss resolves
  # to the SAME grid dims already applied the whole resize block SHORT-CIRCUITS so no
  # heal ran at all. The journey models the black pane DIRECTLY on the retained emulator
  # (a local `CSI 2J`+`CSI H` wipe — the REMOTE tmux grid still holds the banner) and
  # drives the EXACT same-dimension short-circuit production branch. RED on base (the
  # short-circuit returns blindly → banner never restored); GREEN after slice 2 (the
  # active-pane heal re-captures the full viewport, and the post-reflow heal is now the
  # UNCONDITIONAL `reseedActivePaneForReattach`, not blank-only). Uses ONLY the
  # deterministic agents:2222 fixture (no toxiproxy) and does NOT self-skip on CI.
  "$FQCN_PREFIX.VoiceSendActivePaneStaysVisibleE2eTest"
  # ADDED (#782, D30 / D28(3)): the pre-existing multi-window `[wN]`
  # switcher-entry journey. PocketShell no longer manages tmux windows; a session
  # that already has >1 window on the remote is surfaced as separate `<session>
  # [wN]` switcher entries, each attaching to THAT window's pane over the warm
  # lease. This proof taps `[w0]` (its attach-time `capture-pane` seed must paint,
  # not a black pane — the #662 regression), backs out and taps `[w1]` (ITS own
  # seed), then re-selects `[w0]` (warm-lease instant re-attach, no reconnect). It
  # is a load-bearing tmux journey, so per D28(3)/#638/#691 it runs per-push so a
  # black-pane / wrong-window-content regression can't silently return. It uses
  # ONLY the deterministic agents:2222 fixture (no toxiproxy) that `tests.yml`
  # already brings up — no workflow change needed — and does NOT self-skip on CI.
  "$FQCN_PREFIX.PreExistingMultiWindowSeedE2eTest"
  # ADDED (#783, epic #657 / F-wiring): the project-tree torn-down prune
  # journey. A window closed ON THE HOST while the user is NOT on the tree
  # screen (they navigated into the session screen, so FolderListScreen disposed
  # and called stopPolling()) must still prune the `[wN]` node from the
  # maintained tree the instant `%window-close` arrives on the warm `-CC`
  # channel — no manual pull-to-refresh, far inside the 15-min staleness gate.
  # The #783 reviewer asked for this to be PR-gated: it is the torn-down
  # (collector-cancelled) lifecycle edge that the bound-only JVM unit test
  # (FolderListViewModelWindowCloseTest) structurally cannot exercise — the
  # exact #657 A3 anti-pattern (event emitted while the subscriber is still
  # alive). It drives the PRODUCTION FolderListViewModel + gateway + a REAL
  # `tmux -CC` control client against the deterministic `agents` host (DEFAULT_HOST
  # / DEFAULT_PORT -> 10.0.2.2:2222) — the SAME fixture every class above uses,
  # no toxiproxy, no new Docker service/port — and does NOT self-skip on CI. RED
  # on base (the dispose-time stopPolling cancelled the %window-close
  # subscription so the event dropped on a dead collector); GREEN after the fix
  # ties the subscription to the warm-lease lifetime, not screen composition. It
  # lives under com.pocketshell.app.projects, not the proof prefix, so it carries
  # its fully-qualified name directly.
  "com.pocketshell.app.projects.FolderListWindowCloseAfterStopPollingDockerTest"
  # ADDED (#883): window-aware "Stop session". The tree shows each tmux WINDOW
  # as its own `[wN]` row, but in-session Stop used to always `kill-session`,
  # taking the whole session (every window) down. This end-to-end test creates
  # a real 2-window tmux session on the `agents` fixture (DEFAULT_HOST/PORT ->
  # 10.0.2.2:2222), attaches the production TmuxSessionViewModel, and confirms
  # a Stop on window 0 via killCurrentSession(windowIndex = 0). It asserts —
  # over a FRESH SSH-exec `tmux list-windows` — that ONLY window 0 is gone and
  # window 1 + the session SURVIVE, and that the tree drops only the killed
  # window row. RED on base (kill-session destroyed both windows + the session,
  # so list-windows fails); GREEN after the kill-window fix. Same fixture as the
  # siblings above; no toxiproxy, no new Docker service; does NOT self-skip.
  "com.pocketshell.app.projects.FolderListKillWindowDockerTest"
  # ADDED (#795, follow-up to #794): the ~90s STEADY-HOLD no-flap proof. #794
  # fixed the ~11s app-initiated SSH transport flap (the maintainer's
  # black-screen / "conversations don't work" report): on a quiet foreground
  # hold the dashboard's 10s `list-sessions` poll tripped the CommandTimeoutGate
  # idle-deadline watchdog, which escalated the poll to a FATAL transport
  # teardown and reattached every ~11s. The regression proof
  # (LongRunningSessionStabilityTest#steadyForegroundHoldDoesNotFlapTransportEveryTenSeconds)
  # holds the session foreground quietly for ~90s — covering ~8 of the 10s poll
  # cycles — and HARD-asserts ZERO `ssh-read-eof` / `ssh-read-failed` transport
  # teardowns (no assumeTrue self-skip on the load-bearing assertEquals(0, ...)).
  # It shipped in #794 but ran ONLY in the opt-in release gate, so the flap could
  # silently regress at PR time. Per the "load-bearing connection-lifecycle
  # journeys run in regular per-PR CI" mandate (#638/#691), it joins this subset.
  # Target the SINGLE @Test method by FQCN#method so the sibling opt-in
  # 10-minute `tenMinuteForegroundHold…` test (which self-skips without
  # `pocketshellLongRunningTest=1`) is NOT even selected here. It drives ONLY the
  # deterministic agents:2222 fixture (DEFAULT_HOST/DEFAULT_PORT/DEFAULT_USER ->
  # 10.0.2.2:2222) that `tests.yml` already brings up — no new Docker
  # service/port, no toxiproxy — and does NOT self-skip on CI.
  "$FQCN_PREFIX.LongRunningSessionStabilityTest#steadyForegroundHoldDoesNotFlapTransportEveryTenSeconds"
  # ADDED (#785, EPIC #687 slice 3 / D28(3)): the attachment -> NO-reconnect journey.
  # Tapping the 📎 attach button launches the separate-process `OpenMultipleDocuments`
  # picker, briefly backgrounding the app WITHIN the 60s grace window; on return the
  # attach handler must TRUST the still-warm lease (the within-grace silent heal is
  # already restoring the session) instead of firing a LOUD `reconnect()` that blanks
  # then restores the viewport. This journey opens a real `tmux -CC` session, simulates
  # the picker bg->fg round-trip, drives the production `stagePromptAttachments(uris)`,
  # and asserts ZERO reconnect/EOF diagnostics (`reconnect_tapped`/`reconnect_start`/…)
  # + NO Connecting/Reconnecting/Disconnected/Tap-Reconnect/Attaching surface + the
  # viewport still shows the seeded marker (never blanked). RED on base (the attach
  # handler's unconditional `reconnect()` records `reconnect_tapped` and raises the
  # band); GREEN after the slice-3 warm-lease-trust fix. It is the regression net for
  # the controller-owned reconnect ladder (the attach path now aligns with it). Uses
  # ONLY the deterministic agents:2222 fixture (no toxiproxy) that `tests.yml` already
  # brings up — no workflow change needed — and does NOT self-skip on CI.
  "$FQCN_PREFIX.AttachmentNoReconnectE2eTest"
  # ADDED (#872): the send-path sibling of AttachmentNoReconnectE2eTest. AC1's
  # only on-device regression net for "send must not fire a spurious reconnect /
  # send-flap". Opens a real `tmux -CC` session, drives the production send path,
  # and asserts ZERO reconnect/EOF diagnostics + NO Connecting/Reconnecting/
  # Disconnected/Tap-Reconnect surface + the seeded viewport marker is never
  # blanked. RED on base (the send path's unconditional reconnect); GREEN after
  # the warm-lease-trust fix. Uses ONLY the deterministic agents:2222 fixture
  # that `tests.yml` already brings up (no toxiproxy, no workflow change) and
  # does NOT self-skip on CI.
  "$FQCN_PREFIX.SendNoReconnectE2eTest"
  # ADDED (#875 Angle C, cross-links #874/#879): the STABLE-WIFI no-spurious-reconnect
  # journey. The maintainer re-reported (v0.4.14) random ~1s reconnects on stable wifi
  # while idle / recording a voice note. Confirmed root cause Angle C: the #548
  # validated-handoff predicate keyed on Network.networkHandle equality ALONE, but a
  # physically stable wifi mints a NEW handle on a supplicant reassoc (band-steer
  # 2.4↔5GHz, mesh/extender roam) → false handoff → scheduleNetworkReconnect tore the
  # warm `-CC` lease and re-dialed → the visible ~1s flap (and, coupled with the reseed,
  # a transiently black Terminal). Fix: the handoff identity now treats a same-transport
  # pure-{WIFI} reassoc as the SAME network (suppressed at the detector), while a real
  # transport change (WIFI→CELLULAR / VPN) still reconnects. This journey opens a real
  # `tmux -CC` session and drives a same-SSID reassoc DETERMINISTICALLY by pushing a
  # synthetic pure-WIFI snapshot (new handle, same {WIFI} transports) through the
  # PRODUCTION TerminalNetworkObserver detector + emit pipeline (the AVD can't mint a
  # new handle on demand), then HARD-asserts the detector SUPPRESSED the emit, ZERO
  # reconnect diagnostics fired, NO Connecting/Reconnecting/Disconnected/Tap-Reconnect
  # band, and the viewport stayed painted — plus a positive control that a real
  # WIFI→CELLULAR handoff still emits (class coverage, suppression is not blanket). RED
  # on base (the reassoc emits a handoff → the live session reconnects); GREEN after the
  # Angle-C fix. Uses ONLY the deterministic agents:2222 fixture tests.yml already brings
  # up (no toxiproxy, no workflow change) and does NOT self-skip on CI.
  "$FQCN_PREFIX.StableWifiNoSpuriousReconnectE2eTest"
  # ADDED (#796 H3): the composer-open -> terminal-relayout collision regression
  # catcher. The maintainer's exact v0.4.6 Codex freeze: a bursting Codex pane +
  # OPENING the Prompt Composer (showMicSheet toggles in the body root group)
  # recomposed the heavy terminal subtree on the main thread -> ANR. The H3 fix
  # hoists the terminal HorizontalPager into the SKIPPABLE TmuxTerminalPager fed
  # stable inputs, so a composer-open toggle skips it (zero main-thread terminal
  # recomposition work). This DETERMINISTIC scope proof drives 12 composer-open
  # toggles against the REAL production TmuxTerminalPager (real TerminalSurface,
  # no stand-in) and HARD-asserts O(1) pager recompositions (production stable =
  # 0), with a sibling RED guard pinning the pre-fix inline fresh-lambda shape at
  # ~N (=24). It uses NO Docker fixture (in-process Compose UI test, unattached
  # TerminalSurfaceState) and does NOT self-skip on CI (no assumeTrue /
  # assumeFalse(isRunningOnCi()) on the load-bearing assertion — process.md F3).
  # Per the "load-bearing journeys run at PR time" principle (#638/#657) it runs
  # per-push so the composer-open ANR collision cannot silently return. It lives
  # under com.pocketshell.app.tmux, not the proof prefix, so it carries its
  # fully-qualified name directly. The heavier live-burst on-device acceptance
  # (Issue796ComposerOpenDuringCodexBurstProofTest) stays out of this fast subset.
  "com.pocketshell.app.tmux.Issue796ComposerOpenTerminalScopeProofTest"
  # ADDED (#810): the composer-launcher ALWAYS-PRESENT switch journey. The
  # maintainer dogfooded the composer launcher DROPPING OUT after a session
  # switch (A->B->C->A): the launcher chip rendered on the first session but
  # vanished on subsequent switches, so there was no way to open the composer
  # without backing all the way out. This proof switches across THREE live
  # sessions (A->B->C->A) and, after EACH switch, asserts the composer launcher
  # is present AND fully within the viewport (assertNodeFullyWithinRoot, the
  # #657/F1 containment helper — not a bare assertIsDisplayed). RED on base (the
  # launcher drops on the 2nd+ switch); GREEN after the fix. It is a
  # load-bearing session-switch journey, so per the #638 "switch journeys run in
  # regular per-PR CI" mandate it joins this per-push subset. It uses ONLY the
  # deterministic agents:2222 fixture (DEFAULT_HOST/DEFAULT_PORT -> 10.0.2.2:2222)
  # that tests.yml already brings up — no toxiproxy, no new Docker service/port —
  # and does NOT self-skip on CI (no assumeFalse(isRunningOnCi()) on the
  # load-bearing assertion). It lives under the com.pocketshell.app.proof prefix.
  "$FQCN_PREFIX.ComposerAlwaysPresentSwitchJourneyE2eTest"
  # ADDED (#778/#848): tapping Conversation while live detection is still null
  # must be honoured, not swallowed. This connected proof uses a plain-shell
  # pane against the deterministic agents:2222 fixture and asserts the VM records
  # a detection-less Conversation placeholder row. It has no transport-drop
  # reconnect step (unlike its sibling local-only reconnect proof), so it is safe
  # to run per-push after retiring its old C1 self-skip.
  "com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#conversationTapIsHonouredBeforeDetectionLands"
  # ADDED (#818/#815/#878): an agent pane must OPEN on the user's configured
  # default view (Conversation by default; opt-out to Terminal honoured), and
  # detection must NOT YANK the tab mid-session. #878 extends this case with the
  # PRE-DETECTION assertion: the Conversation placeholder row is seeded at
  # pane-add (before the detection round-trip), so the user sees the detecting
  # placeholder, NOT the black raw Terminal, for the whole detection window.
  # The auto-switch-to-Conversation behaviour
  # (af32522b, #807) was reverted as jarring; only the open-time initial
  # selection is governed by the setting. This connected case
  # (AgentConversationReconnectDockerTest#agentOpensOnDefaultViewAndIsNotYankedMidSessionShellGetsNoConversationRow)
  # drives the production TmuxSessionViewModel against the deterministic
  # agents:2222 fixture through the FRESH-attach path (no reconnect): an AGENT
  # pane (claude-named process + fresh JSONL) gets a conversation row on
  # detection and the open-time default selection follows the setting, then the
  # no-yank invariant (#815) is exercised explicitly — a later detection on the
  # SAME live session does NOT re-apply the default once the user has moved to
  # Terminal; a plain SHELL pane never gets a conversation row at all. It uses
  # ONLY agents:2222 (DEFAULT_HOST/DEFAULT_PORT -> 10.0.2.2:2222) that tests.yml
  # already brings up, and its load-bearing assertions do NOT self-skip on CI
  # (only the SIBLING reconnect cases in the same class are assumeFalse-gated as
  # local-only flaky-reconnect evidence). Target the single @Test method by
  # FQCN#method so the CI-gated reconnect cases are NOT selected here.
  "com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#agentOpensOnDefaultViewAndIsNotYankedMidSessionShellGetsNoConversationRow"
  # ADDED (#813): the composer-launcher NARROW / LARGE-FONT clip proof. The
  # maintainer dogfooded (2026-06-18 07:53) the launcher being CLIPPED off the
  # right edge of the bottom bar by the 4-chip primary cluster on a narrow /
  # large-system-font device (the snippets-wraps-to-two-lines tell). This proof
  # renders the PRODUCTION TmuxTerminalBottomControls pinned to a narrow logical
  # width (360dp) AND a large font scale (1.5x, injected synthetically via a
  # LocalDensity override so the clip state is produced deterministically on any
  # AVD) and HARD-asserts the launcher lies fully within the bottom-bar band
  # (containment, not a bare assertIsDisplayed) plus all four primary chips stay
  # reachable. RED on base (launcher=Rect(0,0,0,0) — clipped off-edge); GREEN
  # after the bottom-bar rework reserves the launcher width first. It does NOT
  # self-skip on CI (no assumeTrue / assumeFalse(isRunningOnCi()) on the
  # load-bearing assertion). It lives under com.pocketshell.app.tmux.
  "com.pocketshell.app.tmux.TmuxComposerLauncherNarrowFontClipProofTest"
  # ADDED (#750, 3rd occurrence — D31 durable-fix gate): the tmux non-Connected
  # SINGLE-INDICATOR regression guard. The maintainer's "two loading indicators
  # on the reconnect/reattach screen" symptom has now shipped THREE times because
  # no CI gate ran the guard. This class HARD-asserts (via the
  # ProgressBarRangeInfo.Indeterminate count, not a bare assertIsDisplayed) that
  # EVERY non-Connected state shows EXACTLY ONE animated indicator — the centered
  # "Attaching…" spinner while held, the pull-to-reconnect box spinner in the
  # steady Reconnecting state (the under-header ReconnectingProgressRow band no
  # longer carries its own linear bar after the class-wide #750 fix), and ZERO
  # spinners in the idle Disconnected state. It wires the REAL production
  # composables (ReconnectingProgressRow, FailedConnectionRow,
  # SessionSurfaceReconnectWrapper). It is a PURE Compose screenshot/assertion test
  # — NO Docker fixture, NO SSH/tmux, NO toxiproxy, NO 2222/2226 port — so it needs
  # no tests.yml service change, and it does NOT self-skip on CI (no assumeTrue /
  # assumeFalse(isRunningOnCi())). It lives under com.pocketshell.app.tmux, so it
  # carries its fully-qualified name directly.
  "com.pocketshell.app.tmux.TmuxConnectingStatesScreenshotTest"
  # ADDED (#823): the manual-reconnect AFFORDANCE proof. The maintainer's ask:
  # "there's not even a button to reconnect — maybe we'll do it the same way we
  # refresh the session tree (pull down)." This proof composes the production
  # SessionSurfaceReconnectWrapper (the same composable the session screen mounts)
  # and HARD-asserts: (1) in a dropped/Reconnecting state a pull-down gesture fires
  # the existing reconnect() entrypoint, (2) a VISIBLE, tappable "Reconnect" button
  # is present in that state and a tap fires reconnect(), (3) the button stays
  # tappable during the "Attaching…" hold, and (4) on a live (Connected) session
  # BOTH affordances are absent so neither steals the terminal's gestures. It is a
  # PURE Compose assertion test — NO Docker fixture, NO SSH/tmux, NO toxiproxy, NO
  # port — so it needs no tests.yml service change, and it does NOT self-skip on CI
  # (no assumeTrue / assumeFalse(isRunningOnCi())). Per G9 (a test per acceptance
  # criterion, wired into a running gate) it must run per-push so the affordances
  # cannot silently regress. It lives under com.pocketshell.app.tmux, so it carries
  # its fully-qualified name directly.
  "com.pocketshell.app.tmux.SessionSurfaceReconnectWrapperTest"
  # ADDED (#868): the previous-session-toggle REMOVED regression guard (AC4).
  # The maintainer's #628 previous-session toggle button was hard-cut from the
  # Conversation bottom composer; this proof composes the production
  # TmuxConversationBottomComposer (real Compose-rule seam) and HARD-asserts the
  # toggle control is absent so the removed button cannot silently return. It is
  # a PURE Compose screenshot/assertion test — NO Docker fixture, NO SSH/tmux,
  # NO toxiproxy, NO port — so it needs no tests.yml service change, and it does
  # NOT self-skip on CI (no assumeTrue / assumeFalse(isRunningOnCi())). Per G9
  # (a test per acceptance criterion, wired into a running gate) it must run
  # per-push so the toggle's removal is durably guarded. It lives under
  # com.pocketshell.app.tmux, so it carries its fully-qualified name directly.
  "com.pocketshell.app.tmux.TmuxConversationBottomComposerScreenshotTest"
  # ADDED (#842 — surface transcript images): rendered-UI proof that an image
  # referenced by a transcript (an image content block / tool-result image /
  # pasted-image-by-path) is SURFACED inline — the bug was it being dropped or
  # shown as a raw path. The loader is injected via LocalConversationImageLoader
  # so it is a PURE Compose render proof (NO Docker fixture, NO SSH/tmux, NO
  # port) — no tests.yml service change needed — and it does NOT self-skip on CI
  # (no assumeTrue / assumeFalse(isRunningOnCi())). Per G9 it must run per-push so
  # the inline-image render + the path-text FALLBACK on a failed fetch stay
  # durably guarded. It lives under com.pocketshell.app.conversation.
  "com.pocketshell.app.conversation.ConversationImageContentRenderTest"
  # ADDED (epic #792 Slice D, #822/V7a + #823 — D31 durable-fix gate): the
  # PROACTIVE silent mid-session drop detection + auto-recovery journey. The
  # maintainer's headline #822 bug: SSH silently drops on stable Wi-Fi while the
  # user is reading / recording a voice note (no command in flight), so the dead
  # channel is invisible for up to ~60s and the session wedges, recoverable only
  # by switching to another session and back. The new LivenessProbe fixes it. This
  # journey injects the silent drop DETERMINISTICALLY via the probe's synthetic-
  # drop seam (TmuxSessionViewModel.forceLivenessProbeDeadForTest) + its injectable
  # timing knobs (LivenessProbeTestOverride) on the plain agents:2222 channel, so
  # it runs per-PR WITHOUT the toxiproxy proxy family this job deliberately leaves
  # down (the toxiproxy-faithful half-open sibling SilentMidSessionDropDetection
  # runs nightly). It asserts the USER-VISIBLE contract: (1) on a LIVE+IDLE session
  # the connection-lost indicator surfaces within the (shortened) probe window with
  # NO send, and (2) once the fault clears the SAME session auto-recovers + a fresh
  # marker streams back through the recovered `-CC` channel — no switch dance. It
  # drives ONLY the deterministic agents:2222 fixture (DEFAULT_HOST/PORT/USER ->
  # 10.0.2.2:2222) that tests.yml already brings up, and does NOT self-skip on CI
  # (no assumeFalse(isRunningOnCi()) on the load-bearing assertions — process.md
  # F3 / D31). It lives under the com.pocketshell.app.proof prefix.
  "$FQCN_PREFIX.SilentDropSyntheticSeamJourneyE2eTest"
  # Issue #833 (Slice C resilience follow-up): a CLEAN sustained outage (clean
  # FIN/connection-refused for the outage window, then the link returns) is the
  # EOF-oracle sibling of the half-open silent drop above. The old silent-reattach
  # grace loop tried a fresh transport exactly ONCE then spun, so the SAME session
  # stayed wedged (stuck `Reconnecting`/non-Connected) until the full grace
  # elapsed, forcing the switch-session dance. This journey injects the clean
  # sustained outage DETERMINISTICALLY via two VM seams on the plain agents:2222
  # channel (TmuxSessionViewModel.triggerCleanPassiveDropForTest fires the clean
  # ReaderEof passive-disconnect path; forceCleanOutageForTest makes the grace
  # loop's reconnect primitives fail-fast as if the link were down), so it runs
  # per-PR WITHOUT the toxiproxy proxy family (the toxiproxy-faithful
  # disableProxyFor sibling silentDropAutoRecoversWithoutSessionSwitchDance runs
  # nightly). It asserts the USER-VISIBLE contract: a connection-lost indicator
  # during the outage, then once the link returns the SAME session auto-recovers
  # WITHOUT a switch dance and a fresh marker streams back through the recovered
  # `-CC` channel. It drives ONLY the deterministic agents:2222 fixture
  # (DEFAULT_HOST/PORT/USER -> 10.0.2.2:2222) tests.yml already brings up, and does
  # NOT self-skip on CI (no assumeFalse(isRunningOnCi()) on the load-bearing
  # assertions — process.md F3 / D31). It lives under com.pocketshell.app.proof.
  "$FQCN_PREFIX.CleanOutageReattachResilienceE2eTest"
  # Issue #895 (switch-while-black freeze): the R1 trigger — a transport drop that
  # lands while the VM is in the Switching (Attaching) window was SWALLOWED by the
  # old `inlineConnectionStatus as? Connected ?: return` gate, leaving the user
  # frozen on a black pane with NO escapable affordance ("it froze, had to
  # restart"). This journey attaches a real session, drives it into the Switching
  # window + injects a transport drop DETERMINISTICALLY via two VM seams on the
  # plain agents:2222 channel (forceAttachingStateForTest enters the Switching
  # window a same-host fast switch holds; triggerCleanPassiveDropForTest fires the
  # production passive-disconnect handler), and asserts the USER-VISIBLE contract:
  # an ESCAPABLE band (Reconnecting band / Reconnect affordance) surfaces promptly
  # and the session screen stays mounted (no freeze). It drives ONLY the
  # deterministic agents:2222 fixture tests.yml already brings up, and does NOT
  # self-skip on CI (no assumeFalse(isRunningOnCi()) on the load-bearing assertion
  # — process.md F3 / D31). It lives under com.pocketshell.app.proof.
  "$FQCN_PREFIX.Issue895SwitchWhileBlackBandJourneyE2eTest"
  # Epic #821 Slice 1: manual session classification (Option B + change-kind).
  # The epic exists because agent-kind fixes keep recurring, so the foreign →
  # pick → durable round-trip MUST be gated at PR time (D31). This connected
  # test drives SshFolderListGateway.setRecordedKind -> ManualKindWriter against
  # the deterministic `agents` fixture (DEFAULT_HOST/PORT/USER -> 10.0.2.2:2222,
  # or the pool-allocated port under --pool — no new Docker service/port), and
  # asserts: a foreign session reads back recordedKind=null (Unknown signal, no
  # guess), picking a kind writes `@ps_agent_kind` and reads back as that kind,
  # change-kind rewrites it, and the kind PERSISTS host-side across a fresh SSH
  # session (the reconnect/restart durability AC). It does NOT self-skip on CI.
  # Lives under com.pocketshell.app.projects, so it carries its FQCN directly.
  "com.pocketshell.app.projects.ManualKindWriterDockerTest"
  # Issue #852 (epic #848): one gated recorded-kind read-back journey replacing
  # the deleted broad agent-kind journeys. It launches Claude, Codex, and
  # OpenCode through the production SshFolderListGateway.createSession path
  # against the deterministic agents fixture, waits for the fixture wrapper to
  # write host-side `@ps_agent_kind`, then re-reads through the production
  # listSessionsWithFolder path and asserts recordedKind + rendered agentKind
  # agree. No new Docker service/port; no self-skip.
  "com.pocketshell.app.projects.AgentRecordedKindReadBackDockerTest"
  # ADDED (#889 reopen, D31/D32 G10): the FALSE z.ai chip on a session
  # relaunched as a DEFAULT Claude. End-to-end against the `agents` fixture
  # (DEFAULT_HOST/PORT/USER -> 10.0.2.2:2222, no new Docker service/port): a
  # z.ai-profile launch SETS `@ps_agent_profile`, a default relaunch in the
  # SAME tmux session UNSETS it (the fixture `pocketshell agent` now mirrors the
  # real wrapper's record_agent_kind), and the REAL SshFolderListGateway read-
  # back + REAL HostTreeModel reconcile DROP the stale chip — covering BOTH the
  # host clear (v0.4.14) AND the client-side sticky-merge root cause the prior
  # wrapper-only fix could not reach. It does NOT self-skip on CI. Lives under
  # com.pocketshell.app.projects, so it carries its FQCN directly.
  "com.pocketshell.app.projects.ProfileChipRelaunchDockerTest"
  # ADDED (#853, epic #848): the OUTDATED-host agent-launch friendly-hint guard
  # (#759). When the host `pocketshell` predates the `agent` subcommand (the
  # maintainer's v0.3.34 dogfood failure) an agent launch must surface the
  # friendly "update pocketshell" hint — not the raw Click `No such command
  # 'agent'` — and must NOT type the doomed launch line into the new pane. This
  # is the ONE existing old-host guard and it previously ran in NO suite at all
  # (epic #848 gate gap), so the version-mismatch class was invisible to CI.
  # It drives the PRODUCTION SshFolderListGateway.createSessionOnSession against
  # the REUSABLE on-device FakeOldHostSshSession seam (no Docker fixture, no
  # SSH/tmux, no toxiproxy, no port) — so it needs NO tests.yml service change
  # and runs deterministically on CI. It does NOT self-skip on CI. It lives
  # under com.pocketshell.app.projects, so it carries its FQCN directly.
  "com.pocketshell.app.projects.AgentLaunchVersionMismatchHintE2eTest"
  # ADDED (#849, epic #848): the OLD-CLI cold-start tree-HYDRATE connect proof
  # — the gate half of the v0.4.10 P0 (#847). When the host `pocketshell` is
  # OLDER than the client (no `tree` subcommand), the cold-start hydrate's
  # `tree get` errors; on v0.4.10 the freshening reconcile never ran after it,
  # so the app hung on "loading tree" and would not connect. This connected
  # test drives the PRODUCTION FolderListViewModel + a REAL TreeRemoteSource
  # against the dedicated `agents-old-cli` fixture (port 2238) and HARD-asserts
  # the app leaves Loading and renders the LIVE tree (the seeded session
  # appears) within the connect window. RED on v0.4.10; GREEN after the #847
  # fix. The emulator-journey workflow now brings up the 2238 fixture (and the
  # pre-release gate already did), so this no longer self-skips on CI — it IS
  # the v0.4.10 connect-break regression net. It lives under
  # com.pocketshell.app.projects, so it carries its FQCN directly.
  "com.pocketshell.app.projects.FolderListOldCliHydrateDockerTest"
  # ADDED (#849, epic #848): the OLD-CLI bootstrap-Skip → tree connect JOURNEY.
  # Tapping a host whose CLI is older than the app raises the "Host setup
  # needed" sheet; tapping Skip RELEASES the warm `warm-host-connect` lease and
  # the tree's cold-start reconcile must still reach a usable state — it must
  # NOT land on the #847 "Session list didn't load within 12000ms" error panel.
  # This connected UI journey drives createAndroidComposeRule<MainActivity> +
  # the #788 seed-before-launch harness against the `agents-old-cli` fixture
  # (port 2238). RED on the un-fixed timeout-inversion; GREEN after the #847
  # bound. Like its sibling above it no longer self-skips on CI now the 2238
  # fixture is started by the workflow. It lives under
  # com.pocketshell.app.projects, so it carries its FQCN directly.
  "com.pocketshell.app.projects.FolderListBootstrapSkipTreeLoadsDockerTest"
  # ADDED (#867): the stale-while-revalidate INSTANT-RENDER journey. A cold
  # connect must paint the LAST-KNOWN tree instantly from the per-host CLIENT
  # cache (Ready, NOT the empty 'No folders yet / 0 projects' rebuild flash the
  # maintainer hit), and the silent reconcile must then confirm it against the
  # LIVE host. Drives the production FolderListViewModel + SshFolderListGateway
  # + a pre-seeded TreeClientCache against the standard `agents` fixture (port
  # 2222 — already started by emulator-journey, no new service). RED before the
  # cache seed (first state is Loading); GREEN after. Lives under
  # com.pocketshell.app.projects, so it carries its FQCN directly.
  "com.pocketshell.app.projects.FolderListClientCacheInstantRenderDockerTest"
  # ADDED (#869): the composer-Send ACK-GATE on-device submit JOURNEY — the
  # load-bearing real-agent proof the #869 reviewer required (BLOCKED-G4). The
  # maintainer's symptom: "most of the time when I click Send it's not really
  # sending; I have to press Enter after." The fix ack-gates the submit Enter on
  # a `capture-pane` confirming the paste landed, but its correctness hinges on
  # the needle matching a REAL agent input-box echo — especially a WRAPPED long
  # prompt — which cannot be proven on a JVM FakeTmuxClient. This journey drives
  # a MINIMAL deterministic fake-agent input box (`pocketshell-fake-agent`,
  # baked into the agents image) that echoes typed chars + reflows a long line,
  # runs the production send path for BOTH a multi-word prompt AND a long
  # wrapping prompt, and asserts from the terminal viewport + the
  # `agent_submit_ack result=ack_observed` diagnostic that the line ACTUALLY
  # SUBMITTED (needle matched the reflowed echo, input box left empty) — not the
  # fallback. Uses ONLY the deterministic agents:2222 fixture that tests.yml
  # already brings up (no toxiproxy, no new Docker service/port) and does NOT
  # self-skip on CI (no assumeFalse(isRunningOnCi()) on the load-bearing
  # assertion). It lives under the com.pocketshell.app.proof prefix.
  "$FQCN_PREFIX.AgentSubmitAckJourneyE2eTest"
  # ADDED (#881, follow-up to #858 — D32 G9): the agent-PROFILE on-device
  # journeys. The #858 feature visibly distinguishes a z.ai-profile Claude from a
  # default Claude — the session tree shows a compressed provider chip (e.g.
  # "Z.AI"/"Work") on profiled rows and NONE on default/no-profile rows, and the
  # "What is this session?" picker surfaces the recorded provider/profile line.
  # Those journeys shipped with #858 but ran in NO suite, so the visible chip +
  # provider line had no per-push gate (the data path is Unit/Python-gated; this
  # is the UI-render coverage gap). Target the SINGLE profile @Test method of
  # FolderListScreenE2eTest by FQCN#method (its many unrelated layout/overflow
  # cases stay out of this fast subset), and the whole SessionKindPickerUiTest
  # (its AC3 recordedProfileShowsProviderProfileLine + the negative
  # noRecordedProfileOmitsProviderProfileLine prove the picker provider line;
  # the sibling kind-picker cases are cheap pure-Compose tests). Both are PURE
  # Compose-rule UI tests (in-memory DAO + fake gateway for the tree case,
  # plain ComponentActivity for the picker) — NO Docker fixture, NO SSH/tmux, NO
  # toxiproxy, NO port — so they need no tests.yml service change and run on the
  # already-booted AVD, and NEITHER self-skips on CI (no assumeTrue /
  # assumeFalse(isRunningOnCi())). Both live under com.pocketshell.app.projects,
  # so they carry their fully-qualified names directly.
  "com.pocketshell.app.projects.FolderListScreenE2eTest#profiledSessionsShowProfileChipDefaultSessionsDoNot"
  "com.pocketshell.app.projects.SessionKindPickerUiTest"
  # ADDED (#863, D32 G9): per-criterion gate for the residual-TextButton sweep.
  # #863 migrated raw Material TextButtons (+ a private AppBarTextButton wrapper)
  # in FileExplorerScreen, FileViewerScreen, SnippetPickerSheet, FolderListScreen
  # (the ~:901 "Ask" submit), and CrashReportsScreen to the shared
  # PocketShellButton(variant = Text). Each migrated site must keep firing its
  # onClick / enabled gating — so these PURE-Compose UI tests (createComposeRule /
  # createAndroidComposeRule<ComponentActivity>, in-memory state — NO Docker,
  # NO SSH/tmux, NO toxiproxy, NO port; none self-skip on CI) performClick the
  # migrated buttons by test tag:
  #   * SnippetTemplateDialogButtonsTest — the template-param dialog Send (enabled
  #     gating + expanded-body dispatch) and Cancel (NEW; the site had no test).
  #   * ConfirmDeleteAllDialogButtonsTest — the delete-all confirm dialog Cancel
  #     (migrated off AppBarTextButton) dispatches dismiss-not-confirm, and the
  #     sibling confirm still fires (NEW; the site had no test).
  #   * FileExplorerScaffoldTest — the migrated Go-To dialog Go/Cancel (NEW
  #     cases; the site had no test), Retry, and transfer Dismiss methods.
  #   * FileViewerScaffoldTest — the migrated Retry + locate-candidate methods.
  # The FileExplorer/FileViewer classes are targeted by FQCN#method (their many
  # unrelated render cases — including two pre-existing ModalBottomSheet
  # multi-root sheet cases in FileViewerScaffoldTest that are #863-unrelated —
  # stay out of this fast subset). FolderListScreen's migrated "Ask" submit is
  # already gated above by FolderListScreenE2eTest
  # (FOLDER_LIST_ASSISTANT_SUBMIT_TAG.performClick()). All carry their
  # fully-qualified names directly (not under the proof prefix).
  "com.pocketshell.app.snippets.SnippetTemplateDialogButtonsTest"
  "com.pocketshell.app.crash.ConfirmDeleteAllDialogButtonsTest"
  "com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#goToDialogGoButtonDispatchesPath"
  "com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#goToDialogCancelDismissesWithoutNavigating"
  "com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#failedStateShowsMessageAndRetry"
  "com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#successTransferShowsDismissibleBanner"
  "com.pocketshell.app.fileviewer.FileViewerScaffoldTest#cannotPreviewStateShowsMessageAndRetry"
  "com.pocketshell.app.fileviewer.FileViewerScaffoldTest#cannotPreviewWithLocateCandidatesOffersOpenRows"
  # ADDED (#885, D32 G9): the post-update "Host ready" success sheet must show
  # ONLY Continue — the "Open Usage" CTA (#117) was removed (hard cut, D22).
  # This connected test drives the REAL HostBootstrapSheet in its Success state
  # and HARD-asserts the open-usage button does NOT exist + Continue is the
  # accent-filled primary action. PURE Compose (createComposeRule, no Docker /
  # SSH / tmux / port — runs on the already-booted AVD), no self-skip. Carries
  # its fully-qualified name directly (not under the proof prefix).
  "com.pocketshell.app.bootstrap.HostReadyPrimaryActionTest"
)

echo "=========================================================="
echo "Per-push CI journey suite (issue #691) — load-bearing subset"
echo "Included classes:"
for c in "${JOURNEY_CLASSES[@]}"; do
  echo "  - $c"
done
echo "  (pocketshellCi=true; deterministic agents:2222 only, no toxiproxy)"
echo "  (per-class retry-once for CI-AVD infra flakes — issue #712)"
echo "=========================================================="

# Issue #691 (S2 defense-in-depth): pass AndroidJUnitRunner's per-test
# `timeout_msec` so a wedged test is interrupted and FAILS FAST (~5 min) instead
# of hanging the whole job to the runner cap. A hang is worse than a clean fail
# (the whole cap burned + a failure email every push); the timeout converts any
# future CI-AVD wedge into a fast, legible red. 300000 ms = 5 min/test is far
# above the generous `pocketshellCi=true` E2E ceilings, so it never trips a
# legitimately slow CI test — it only catches a genuine deadlock.
#
# ---------------------------------------------------------------------------
# Issue #835: SUITE-LEVEL time budget so the recurring #470 enumeration stall
# can never burn the whole 45-min job to a `cancelled` (which writes NO
# summary.md and mis-routes the workflow classifier to the #771
# "EMULATOR INFRA UNAVAILABLE" branch).
#
# The per-test `timeout_msec` above bounds ONE @Test method, but a single
# stalling CLASS still costs ~2 × 5 min (attempt + retry), and SIX such
# session/reconnect classes stalling on #470 in one run (run 27845074217) added
# up past the job cap → the step was SIGKILLed mid-loop before
# `summary.md` was ever written. Without an artifact the classifier cannot tell a
# #470 time-budget stall from a never-booted emulator.
#
# Fix (#908): the suite owns its OWN deadline (JOURNEY_STEP_BUDGET_SECS,
# default 20 min). Arithmetic against the workflow cap is explicit:
# 45-min job cap (2700s) - worst-case emulator boot (900s) - default suite
# budget (1200s) = 600s for setup, classifier, Docker log collection, and
# artifact upload.
# When the remaining budget is exhausted the suite STOPS launching new classes,
# records the not-run classes as a distinct BUDGET-timeout bucket, ALWAYS writes
# summary.md (with the greppable `JOURNEY_STEP_TIMEOUT` marker), and exits
# non-zero. So a #470 stall now surfaces as a legible, correctly-labelled red
# verdict WITH an artifact instead of a `cancelled` step with none. Each
# individual class attempt is ALSO hard-capped (via `timeout`) at the smaller of
# its own ceiling and the budget remaining, so one wedged class can never run
# past the suite deadline.
#
# Override knobs (used by the suite's own unit test; CI uses the defaults):
#   JOURNEY_STEP_BUDGET_SECS   — total wall-clock budget for the class loop.
#   JOURNEY_CLASS_TIMEOUT_SECS — hard ceiling for ONE class attempt (default
#                                420s = 7 min: above the 300s per-test
#                                timeout_msec so the runner's own interrupt is
#                                preferred, but a backstop if even that wedges).
#   JOURNEY_GRADLE_STOP_TIMEOUT_SECS
#                              — bounded cleanup window after a class attempt is
#                                killed by `timeout` (default 60s).
#   JOURNEY_CLASS_KILL_AFTER_SECS
#                              — SIGKILL backstop after the per-class timeout
#                                sends TERM (default 30s).
JOURNEY_STEP_BUDGET_SECS="${JOURNEY_STEP_BUDGET_SECS:-1200}"
JOURNEY_CLASS_TIMEOUT_SECS="${JOURNEY_CLASS_TIMEOUT_SECS:-420}"
JOURNEY_GRADLE_STOP_TIMEOUT_SECS="${JOURNEY_GRADLE_STOP_TIMEOUT_SECS:-60}"
JOURNEY_CLASS_KILL_AFTER_SECS="${JOURNEY_CLASS_KILL_AFTER_SECS:-30}"

# budget_remaining — seconds left in the suite-level budget (never negative).
budget_remaining() {
  local elapsed=$((SECONDS - SUITE_START))
  local remaining=$((JOURNEY_STEP_BUDGET_SECS - elapsed))
  (( remaining < 0 )) && remaining=0
  echo "$remaining"
}

# budget_exhausted — true (0) once the suite-level budget is spent. Checked
# before launching each class so the loop stops cleanly instead of being
# SIGKILLed by the workflow job cap mid-class (which writes no summary).
budget_exhausted() {
  (( $(budget_remaining) <= 0 ))
}

cleanup_gradle_after_timeout() {
  local fqcn="$1"
  local stop_rc

  echo "GRADLE_TIMEOUT_CLEANUP: $fqcn timed out; stopping Gradle daemons before any retry"
  if timeout --signal=TERM --kill-after=10 "${JOURNEY_GRADLE_STOP_TIMEOUT_SECS}s" \
      "$GRADLEW" --stop; then
    echo "GRADLE_TIMEOUT_CLEANUP: Gradle daemon stop completed for $fqcn"
    return 0
  fi

  stop_rc=$?
  echo "GRADLE_TIMEOUT_CLEANUP: Gradle daemon stop exited $stop_rc for $fqcn; continuing with retry/budget handling" >&2
  return 0
}

LAST_RUN_CLASS_TIMEOUT_HIT=0
LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=0

is_timeout_rc() {
  [[ "$1" -eq 124 || ( "$1" -eq 137 && "${LAST_RUN_CLASS_TIMEOUT_HIT:-0}" -eq 1 ) ]]
}

class_attempt_hit_time_budget() {
  is_timeout_rc "$1" || [[ "${LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT:-0}" -eq 1 ]]
}

needs_gradle_cleanup_after_class_abort() {
  [[ "$1" -eq 124 || "$1" -eq 137 ]]
}

# run_class <FQCN> — runs ONE journey class as its own gradle connected-test
# invocation and returns gradle's exit code (0 == that class passed). Running
# one class per invocation (rather than all classes comma-joined into a single
# invocation) is what makes the per-class retry below clean: the gradle exit
# code IS the per-class verdict, with no XML parsing or fragile result-file
# scraping required.
#
# Issue #835: wrap the gradle invocation in `timeout` capped at the SMALLER of
# the per-class ceiling and the budget remaining, so a single #470-stalled class
# can never run past the suite deadline and starve the rest of the suite of the
# chance to write a summary. `timeout` exit 124 == this class hit its cap; the
# caller treats that as a failed attempt (the retry/budget logic handles it).
run_class() {
  local fqcn="$1"
  local remaining cap rc attempt_start attempt_elapsed
  LAST_RUN_CLASS_TIMEOUT_HIT=0
  LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=0
  remaining="$(budget_remaining)"
  cap="$JOURNEY_CLASS_TIMEOUT_SECS"
  (( remaining < cap )) && cap="$remaining"
  # Guard: if the budget is already spent, don't even start gradle — return the
  # `timeout` 124 code so the caller records it as a (non-)attempt uniformly.
  if (( cap <= 0 )); then
    LAST_RUN_CLASS_TIMEOUT_HIT=1
    LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=1
    return 124
  fi
  attempt_start=$SECONDS
  timeout --signal=TERM --kill-after="$JOURNEY_CLASS_KILL_AFTER_SECS" "${cap}s" \
    "$GRADLEW" --no-daemon :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=300000 \
    -Pandroid.testInstrumentationRunnerArguments.class="$fqcn" \
    --stacktrace
  rc=$?
  attempt_elapsed=$((SECONDS - attempt_start))
  if [[ $rc -eq 124 || ( $rc -eq 137 && $attempt_elapsed -ge $cap ) ]]; then
    LAST_RUN_CLASS_TIMEOUT_HIT=1
  fi
  if budget_exhausted; then
    LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=1
  fi
  if needs_gradle_cleanup_after_class_abort "$rc"; then
    cleanup_gradle_after_timeout "$fqcn"
  fi
  return "$rc"
}

# ---------------------------------------------------------------------------
# Issue #724: optional cross-lane sharding.
#
# The DEFAULT path (below this block) is unchanged: a SINGLE serial loop over
# the load-bearing classes on one emulator + agents:2222, WITH the #712
# per-class retry-once. That is the clean fallback CI relies on — the GitHub
# android-emulator-runner gives one AVD and the workflow brings up one `agents`
# on 2222, so `POCKETSHELL_JOURNEY_SHARD` is never set there and this block is
# skipped entirely.
#
# When run on the multi-lane dev box, opt in with `POCKETSHELL_JOURNEY_SHARD=1`
# (requires scripts/avd-pool.sh + scripts/agents-pool.sh warmed). Each lane is a
# background `connected-test.sh --pool` invocation that self-allocates a free
# (emulator serial, agents port) pair, so the classes run across however many
# lanes are free instead of strictly serially. The number of concurrent lanes
# is bounded by POCKETSHELL_JOURNEY_LANES (default 2). connected-test's own
# per-lane flock means we never oversubscribe a serial/port; lanes that can't
# claim a pair simply wait, so this degrades gracefully to serial when only one
# lane is free. Each lane carries the SAME pocketshellCi + timeout_msec args as
# the serial run_class, and a failing lane is retried once (parity with #712)
# so a CI-AVD infra flake on a sharded run recovers exactly as in serial.
shard_class() {
  # Run ONE class on a self-allocated pool lane, returning the lane's exit code.
  local idx="$1"
  local fqcn="$2"
  "$REPO_ROOT/scripts/connected-test.sh" --pool --suffix "ij$idx" \
    -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=300000 \
    -Pandroid.testInstrumentationRunnerArguments.class="$fqcn" \
    --stacktrace
}

shard_run() {
  local lanes="${POCKETSHELL_JOURNEY_LANES:-2}"
  echo "=========================================================="
  echo ">>> SHARDED journey run (issue #724): up to ${lanes} concurrent lanes"
  echo "    each lane self-allocates (emulator serial, agents port) via"
  echo "    scripts/connected-test.sh --pool  (retry-once per class — #712)"
  echo "=========================================================="

  local rc=0
  local idx=0
  local -a pids=()
  local -a pid_classes=()
  local -a pid_idx=()
  for fqcn in "${JOURNEY_CLASSES[@]}"; do
    # Throttle to `lanes` concurrent background runs.
    while (( $(jobs -rp | wc -l) >= lanes )); do
      wait -n 2>/dev/null || true
    done
    idx=$((idx + 1))
    local logf
    logf="$ARTIFACT_DIR/shard-lane-$idx-$(basename "$fqcn").log"
    echo ">>> dispatch lane $idx: $fqcn (log: $logf)"
    (
      # Per-class retry-once (parity with the serial #712 path): a CI-AVD infra
      # flake clears on the next attempt; a real regression fails both.
      if shard_class "$idx" "$fqcn"; then
        exit 0
      fi
      echo "SHARD_LANE_RETRY: $fqcn failed attempt 1 — retrying once"
      if shard_class "$idx" "$fqcn"; then
        echo "JOURNEY_FLAKE_RECOVERED: $fqcn passed on retry (sharded lane $idx)"
        exit 0
      fi
      exit 1
    ) > "$logf" 2>&1 &
    pids+=("$!")
    pid_classes+=("$fqcn")
    pid_idx+=("$idx")
  done

  # Collect every lane's verdict.
  local i
  for i in "${!pids[@]}"; do
    if ! wait "${pids[$i]}"; then
      echo "SHARD_LANE_FAILED: ${pid_classes[$i]} (lane ${pid_idx[$i]}, log: $ARTIFACT_DIR/shard-lane-${pid_idx[$i]}-$(basename "${pid_classes[$i]}").log)"
      rc=1
    else
      echo "SHARD_LANE_PASS: ${pid_classes[$i]} (lane ${pid_idx[$i]})"
    fi
  done
  return "$rc"
}

if [[ "${POCKETSHELL_JOURNEY_SHARD:-0}" == "1" ]]; then
  if shard_run; then
    echo "Sharded journey run: PASS"
    exit 0
  else
    echo "Sharded journey run: FAIL (see shard-lane-*.log under $ARTIFACT_DIR)"
    exit 1
  fi
fi

# Issue #712: per-class retry-once for CI-AVD infra flakes.
#
# The per-push job runs on the GitHub `android-emulator-runner` AVD — a 2-core
# swiftshader VM that occasionally stalls the in-emulator SSH+tmux
# `list-sessions` enumeration past the 60s picker wait (the #470 enumeration
# stall). That stall is an infra limitation of the slow CI AVD, not a code
# regression: the SAME commit passes on the next run. Without a retry the job
# goes red and spams a failure email on every such flake.
#
# Strategy: run each journey CLASS on its own; if a class FAILS, re-run ONLY
# that class once (NOT the whole suite). The job is marked red only if a class
# fails BOTH the original run and the retry.
#
# Why this does NOT mask real regressions: a genuine behavior bug fails
# CONSISTENTLY — it fails the original run AND the retry — so it still turns the
# job red and is still caught. Only a true infra flake (passes on the very next
# attempt of the same class) recovers to green. When a recovery happens we print
# a LOUD, greppable `JOURNEY_FLAKE_RECOVERED:` line so a degrading flake trend
# stays visible in the logs and is never silently hidden.
#
# Note on `Process crashed` / signal-9 (sibling-install SIGKILL): on the CI
# emulator-runner this job is the only installer, so that collision class does
# not arise here. If it ever did it would surface as a non-zero exit and the
# retry-once below would recover it exactly as for any other transient failure.

RECOVERED_CLASSES=()  # classes that failed first then PASSED on retry
FAILED_CLASSES=()     # classes that failed BOTH attempts (real failures)
PASSED_FIRST_TRY=()   # classes that passed on the first attempt
BUDGET_TIMEOUT_CLASSES=()  # issue #835: classes not run / cut short because the
                           # suite-level budget was exhausted (the #470 stall
                           # ate the time). A DISTINCT bucket from a real failure
                           # so the classifier labels it "journey timeout / #470
                           # stall", NOT "EMULATOR INFRA UNAVAILABLE".
STEP_TIMEOUT_HIT=0    # issue #835: set to 1 once the suite-level budget is spent.

SUITE_START=$SECONDS

echo ">>> Suite-level time budget (issue #835): ${JOURNEY_STEP_BUDGET_SECS}s"
echo "    (per-class attempt cap: ${JOURNEY_CLASS_TIMEOUT_SECS}s; workflow job cap: 45 min)"

for fqcn in "${JOURNEY_CLASSES[@]}"; do
  # Issue #835: stop launching new classes once the suite-level budget is spent.
  # A #470 enumeration stall earlier in the run can eat the budget; rather than
  # let the workflow job SIGKILL us mid-class (which writes NO summary and
  # mis-routes the classifier to the #771 infra branch), we bail cleanly here,
  # bucket the remaining classes as BUDGET-timeouts, and fall through to ALWAYS
  # write the summary below with the `JOURNEY_STEP_TIMEOUT` marker.
  if budget_exhausted; then
    STEP_TIMEOUT_HIT=1
    echo "JOURNEY_STEP_TIMEOUT: suite budget (${JOURNEY_STEP_BUDGET_SECS}s) exhausted before $fqcn — not run (issue #835 / #470 stall)"
    BUDGET_TIMEOUT_CLASSES+=("$fqcn")
    continue
  fi

  echo "=========================================================="
  echo ">>> JOURNEY CLASS: $fqcn (attempt 1) [budget remaining: $(budget_remaining)s]"
  echo "=========================================================="
  class_start=$SECONDS

  run_class "$fqcn"
  rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "JOURNEY_PASS: $fqcn passed on attempt 1 (elapsed $((SECONDS - class_start))s)"
    PASSED_FIRST_TRY+=("$fqcn")
    continue
  fi

  # Issue #835: if the budget is now spent (this attempt was cut by `timeout`, or
  # an earlier attempt drained the clock), do NOT burn the remaining-class retry
  # on a stalled AVD — bucket this class as a BUDGET-timeout and move on so the
  # summary still gets written before the workflow job cap.
  if [[ "${LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT:-0}" -eq 1 ]]; then
    STEP_TIMEOUT_HIT=1
    echo "JOURNEY_STEP_TIMEOUT: $fqcn attempt 1 exhausted the suite budget (rc=$rc) — not retried (issue #835 / #470 stall)"
    BUDGET_TIMEOUT_CLASSES+=("$fqcn")
    continue
  fi
  if budget_exhausted; then
    echo "JOURNEY_FAILED: $fqcn failed before retry and cleanup exhausted the suite budget (rc=$rc)"
    FAILED_CLASSES+=("$fqcn")
    continue
  fi

  # Attempt 1 failed. Re-run ONLY this class once — a CI-AVD infra flake
  # (e.g. the #470 enumeration stall) typically clears on the next attempt;
  # a real regression fails again and keeps the job red.
  echo "=========================================================="
  echo ">>> JOURNEY CLASS: $fqcn FAILED attempt 1 — retrying once (attempt 2) [budget remaining: $(budget_remaining)s]"
  echo "=========================================================="
  retry_start=$SECONDS

  run_class "$fqcn"
  rc=$?
  if [[ $rc -eq 0 ]]; then
    # Loud, greppable recovery marker so masked flakes stay visible and a
    # degrading trend is detectable in the CI logs.
    echo "JOURNEY_FLAKE_RECOVERED: $fqcn passed on retry (attempt 2) (retry elapsed $((SECONDS - retry_start))s)"
    RECOVERED_CLASSES+=("$fqcn")
  elif class_attempt_hit_time_budget "$rc"; then
    # rc=124 == `timeout` sent TERM at the class cap; rc=137 == the command
    # survived TERM and hit the SIGKILL backstop. Either way this is a time-
    # budget casualty (the #470 stall), not a clean twice-failed regression.
    # Bucket it distinctly so the classifier labels the red as a journey
    # timeout, not a real test failure and not an infra abort.
    STEP_TIMEOUT_HIT=1
    echo "JOURNEY_STEP_TIMEOUT: $fqcn retry was cut by the suite budget (rc=$rc) (issue #835 / #470 stall)"
    BUDGET_TIMEOUT_CLASSES+=("$fqcn")
  elif budget_exhausted; then
    echo "JOURNEY_FAILED: $fqcn retry failed and cleanup exhausted the suite budget (rc=$rc)"
    FAILED_CLASSES+=("$fqcn")
  else
    echo "JOURNEY_FAILED: $fqcn failed twice"
    FAILED_CLASSES+=("$fqcn")
  fi
done

# ---------------------------------------------------------------------------
# Issue #803: core-terminal VT-append main-thread responsiveness proof.
#
# The load-bearing classes above are all `:app` connected tests (FQCN-targeted
# via `:app:connectedDebugAndroidTest`). The #803 regression proof lives in the
# `shared:core-terminal` module (it composes the production TerminalSurface +
# the real vendored TerminalView and drives a dense colored-diff `%output`
# append burst), so it runs as its OWN module connected-test task. It HARD-
# asserts (no assumeTrue / CI self-skip — process.md F3) that a heavy
# colored-diff burst keeps the main thread responsive AND renders the final
# diff state correctly (byte order + final byte preserved — the #651/#658
# ordering guard). Per the "load-bearing journeys run at PR time" principle
# (#638/#657) it joins the per-push set so a future regression that reintroduces
# an unbounded back-to-back VT-append drain, or breaks ordering, is caught here.
# Uses NO Docker fixture (in-process Compose UI test, no SSH/tmux).
CORE_TERMINAL_APPEND_BURST_CLASS="com.pocketshell.core.terminal.ui.CodexAppendBurstMainThreadProofTest"
APPEND_BURST_STATUS="PASS"

run_core_terminal_append_burst() {
  "$GRADLEW" :shared:core-terminal:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$CORE_TERMINAL_APPEND_BURST_CLASS" \
    --stacktrace
}

# Issue #835: if the suite budget was already spent by a #470 stall in the
# journey loop above, SKIP the core-terminal proofs and go straight to writing
# the summary — running another ~2 proofs would push us into the workflow job
# cap and lose the artifact. A skipped proof is recorded as SKIPPED (not PASS,
# not FAIL) so the summary is honest; the budget-timeout label below makes the
# job red regardless.
if budget_exhausted; then
  STEP_TIMEOUT_HIT=1
  APPEND_BURST_STATUS="SKIPPED"
  echo "JOURNEY_STEP_TIMEOUT: skipping #803 append-burst proof — suite budget exhausted (issue #835 / #470 stall)"
else
  echo "=========================================================="
  echo ">>> CORE-TERMINAL #803 APPEND-BURST PROOF: $CORE_TERMINAL_APPEND_BURST_CLASS (attempt 1)"
  echo "=========================================================="
  append_burst_start=$SECONDS
  if run_core_terminal_append_burst; then
    echo "APPEND_BURST_PASS: passed on attempt 1 (elapsed $((SECONDS - append_burst_start))s)"
  else
    echo ">>> APPEND-BURST PROOF FAILED attempt 1 — retrying once (CI-AVD infra flake / sibling-install)"
    if run_core_terminal_append_burst; then
      echo "APPEND_BURST_FLAKE_RECOVERED: passed on retry (attempt 2)"
    else
      echo "APPEND_BURST_FAILED: #803 proof failed twice"
      APPEND_BURST_STATUS="FAIL"
    fi
  fi
fi

# ---------------------------------------------------------------------------
# Issue #796 (REOPENED): core-terminal Codex `%output`-burst keyboard-up ANR
# proof. The sibling of the #803 append-burst proof above. It composes the
# production TerminalSurface for an AGENT pane (`affordanceScannersEnabled =
# false`) with a synthetic `ime()` inset up (#780 model, HARD-asserted — no
# CI self-skip), drives a tight `%output` burst, and asserts the agent pane
# runs NO per-frame viewport scanner AND keeps the main thread under the 1s
# stall budget (well within the 5s ANR window). It also runs the shell-pane
# inverse (scanners STILL run for a non-agent pane). This guard was the #638/
# #657 CI gap: it caught the ANR only at the release confidence gate (stage 09),
# not at PR time. Adding it here closes the loop so the Codex ANR cannot reach a
# green `main` again. Uses NO Docker fixture (in-process Compose UI test).
CORE_TERMINAL_OUTPUT_BURST_IME_CLASS="com.pocketshell.core.terminal.ui.CodexOutputBurstImeMainThreadProofTest"
OUTPUT_BURST_IME_STATUS="PASS"

run_core_terminal_output_burst_ime() {
  "$GRADLEW" :shared:core-terminal:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$CORE_TERMINAL_OUTPUT_BURST_IME_CLASS" \
    --stacktrace
}

# Issue #835: same budget guard as the #803 proof above.
if budget_exhausted; then
  STEP_TIMEOUT_HIT=1
  OUTPUT_BURST_IME_STATUS="SKIPPED"
  echo "JOURNEY_STEP_TIMEOUT: skipping #796 output-burst-IME proof — suite budget exhausted (issue #835 / #470 stall)"
else
  echo "=========================================================="
  echo ">>> CORE-TERMINAL #796 OUTPUT-BURST-IME PROOF: $CORE_TERMINAL_OUTPUT_BURST_IME_CLASS (attempt 1)"
  echo "=========================================================="
  output_burst_ime_start=$SECONDS
  if run_core_terminal_output_burst_ime; then
    echo "OUTPUT_BURST_IME_PASS: passed on attempt 1 (elapsed $((SECONDS - output_burst_ime_start))s)"
  else
    echo ">>> OUTPUT-BURST-IME PROOF FAILED attempt 1 — retrying once (CI-AVD infra flake / sibling-install)"
    if run_core_terminal_output_burst_ime; then
      echo "OUTPUT_BURST_IME_FLAKE_RECOVERED: passed on retry (attempt 2)"
    else
      echo "OUTPUT_BURST_IME_FAILED: #796 proof failed twice"
      OUTPUT_BURST_IME_STATUS="FAIL"
    fi
  fi
fi

# --------------------------------------------------------------------------
# Issue #866 multi-chunk seed attach ANR proof (core-terminal androidTest).
# Attaching a Codex (alt-screen) pane whose capture-pane seed is SEVERAL 64 KB
# chunks must NOT pin the main thread on "Attaching…" → ANR. Pre-#866 the
# on-main seed drain only honored the time budget for the FINAL chunk, so every
# earlier chunk's VT parse ran inline and pinned the looper for seconds. The
# proof attaches via the real production path (awaitSeed), feeds a multi-chunk
# alt-screen snapshot on the main thread, and asserts the synchronous on-main
# feed is bounded (the budget + tail pump) while the whole seed still paints.
# Uses NO Docker fixture (in-process Compose UI test). The JVM sibling
# SshTerminalBridgeTest#onMainMultiChunkSeedRespectsTimeBudgetAcrossWholeFeed...
# proves the same property deterministically in the per-push Unit job.
CORE_TERMINAL_MULTICHUNK_SEED_CLASS="com.pocketshell.core.terminal.ui.CodexMultiChunkSeedAttachMainThreadProofTest"
MULTICHUNK_SEED_STATUS="PASS"

run_core_terminal_multichunk_seed() {
  "$GRADLEW" :shared:core-terminal:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$CORE_TERMINAL_MULTICHUNK_SEED_CLASS" \
    --stacktrace
}

if budget_exhausted; then
  STEP_TIMEOUT_HIT=1
  MULTICHUNK_SEED_STATUS="SKIPPED"
  echo "JOURNEY_STEP_TIMEOUT: skipping #866 multi-chunk seed attach proof — suite budget exhausted (issue #835 / #470 stall)"
else
  echo "=========================================================="
  echo ">>> CORE-TERMINAL #866 MULTI-CHUNK SEED ATTACH PROOF: $CORE_TERMINAL_MULTICHUNK_SEED_CLASS (attempt 1)"
  echo "=========================================================="
  multichunk_seed_start=$SECONDS
  if run_core_terminal_multichunk_seed; then
    echo "MULTICHUNK_SEED_PASS: passed on attempt 1 (elapsed $((SECONDS - multichunk_seed_start))s)"
  else
    echo ">>> MULTI-CHUNK SEED ATTACH PROOF FAILED attempt 1 — retrying once (CI-AVD infra flake / sibling-install)"
    if run_core_terminal_multichunk_seed; then
      echo "MULTICHUNK_SEED_FLAKE_RECOVERED: passed on retry (attempt 2)"
    else
      echo "MULTICHUNK_SEED_FAILED: #866 proof failed twice"
      MULTICHUNK_SEED_STATUS="FAIL"
    fi
  fi
fi

# --------------------------------------------------------------------------
# Issue #871 agent-pane link-affordance off-main proof (core-terminal androidTest).
# #871 is itself a regression of #803's over-correction: the #796 ANR fix gated
# ALL per-frame on-main affordance scanners OFF agent panes, which also removed
# the tappable file-path + URL affordance the maintainer relies on (agents emit
# paths constantly). The fix restores the affordance via an OFF-main, debounced
# overlay. This proof asserts BOTH (a) a file path AND a URL are tappable again
# on a Codex AND a Claude agent pane, and (b) the LOAD-BEARING ANR-safety
# property — the agent-pane scan runs OFF the main thread and is debounced, NOT
# the per-frame on-main scan that caused the #803/#866/#796 ANR. Because #871 is
# a regression of #803's over-correction, an un-gated test invites the exact
# recurrence — so it runs in this per-push gate alongside the #803/#796/#866
# proofs. Uses NO Docker fixture (in-process Compose UI test).
CORE_TERMINAL_AGENT_LINK_AFFORDANCE_CLASS="com.pocketshell.core.terminal.ui.AgentPaneLinkAffordanceOffMainProofTest"
AGENT_LINK_AFFORDANCE_STATUS="PASS"

run_core_terminal_agent_link_affordance() {
  "$GRADLEW" :shared:core-terminal:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$CORE_TERMINAL_AGENT_LINK_AFFORDANCE_CLASS" \
    --stacktrace
}

if budget_exhausted; then
  STEP_TIMEOUT_HIT=1
  AGENT_LINK_AFFORDANCE_STATUS="SKIPPED"
  echo "JOURNEY_STEP_TIMEOUT: skipping #871 agent-pane link-affordance proof — suite budget exhausted (issue #835 / #470 stall)"
else
  echo "=========================================================="
  echo ">>> CORE-TERMINAL #871 AGENT-PANE LINK-AFFORDANCE PROOF: $CORE_TERMINAL_AGENT_LINK_AFFORDANCE_CLASS (attempt 1)"
  echo "=========================================================="
  agent_link_affordance_start=$SECONDS
  if run_core_terminal_agent_link_affordance; then
    echo "AGENT_LINK_AFFORDANCE_PASS: passed on attempt 1 (elapsed $((SECONDS - agent_link_affordance_start))s)"
  else
    echo ">>> AGENT-PANE LINK-AFFORDANCE PROOF FAILED attempt 1 — retrying once (CI-AVD infra flake / sibling-install)"
    if run_core_terminal_agent_link_affordance; then
      echo "AGENT_LINK_AFFORDANCE_FLAKE_RECOVERED: passed on retry (attempt 2)"
    else
      echo "AGENT_LINK_AFFORDANCE_FAILED: #871 proof failed twice"
      AGENT_LINK_AFFORDANCE_STATUS="FAIL"
    fi
  fi
fi

# --------------------------------------------------------------------------
# Issue #879 beyond-grace reconnect black-screen render proof (core-terminal
# androidTest). After a background→foreground BEYOND-grace reconnect the pane,
# its TerminalSurfaceState and its TerminalView are RE-CREATED; the capture-pane
# seed fires the full-repaint signal BEFORE the fresh surface binds its repaint
# collector (the #640 seed-before-reveal ordering). With `_fullRepaintRequests`
# as a replay=0 flow the late-subscribing TerminalView NEVER received that
# request → the renderer's #469 dirty clip painted only changed rows over a
# black surface → ~95% black (the maintainer's screenshot). This proof drives
# the EXACT production wiring (real TerminalSurfaceState flow + the real
# TerminalSurface collector shape + a real TerminalView/TerminalRenderer)
# through the re-create ordering and asserts the late subscriber forces a FULL
# repaint (PEEK_FULL, every row repaints over black) — RED on replay=0, GREEN
# on replay=1. The #553/#721 partial-blank class on the previously-untested
# full-reconnect path; it joins the per-push gate so it cannot reach a green
# `main` again. Uses NO Docker fixture (in-process render test). The proof drives
# a REAL TerminalView (its onDraw under the platform dirty clip + the real
# forceFullRepaint() + the real TerminalSurfaceState.fullRepaintRequests
# collector), not a renderer-model stand-in.
CORE_TERMINAL_REATTACH_REPAINT_CLASS="com.termux.view.TerminalViewReattachLateSubscribeRepaintInstrumentedTest"
REATTACH_REPAINT_STATUS="PASS"

run_core_terminal_reattach_repaint() {
  "$GRADLEW" :shared:core-terminal:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$CORE_TERMINAL_REATTACH_REPAINT_CLASS" \
    --stacktrace
}

if budget_exhausted; then
  STEP_TIMEOUT_HIT=1
  REATTACH_REPAINT_STATUS="SKIPPED"
  echo "JOURNEY_STEP_TIMEOUT: skipping #879 reattach-repaint proof — suite budget exhausted (issue #835 / #470 stall)"
else
  echo "=========================================================="
  echo ">>> CORE-TERMINAL #879 REATTACH-REPAINT PROOF: $CORE_TERMINAL_REATTACH_REPAINT_CLASS (attempt 1)"
  echo "=========================================================="
  reattach_repaint_start=$SECONDS
  if run_core_terminal_reattach_repaint; then
    echo "REATTACH_REPAINT_PASS: passed on attempt 1 (elapsed $((SECONDS - reattach_repaint_start))s)"
  else
    echo ">>> REATTACH-REPAINT PROOF FAILED attempt 1 — retrying once (CI-AVD infra flake / sibling-install)"
    if run_core_terminal_reattach_repaint; then
      echo "REATTACH_REPAINT_FLAKE_RECOVERED: passed on retry (attempt 2)"
    else
      echo "REATTACH_REPAINT_FAILED: #879 proof failed twice"
      REATTACH_REPAINT_STATUS="FAIL"
    fi
  fi
fi

SUITE_ELAPSED=$((SECONDS - SUITE_START))

# The job is red iff at least one class failed BOTH attempts, OR the #803
# append-burst proof failed, OR the #796 output-burst-IME proof failed, OR the
# #866 multi-chunk seed attach proof failed, OR the suite-level budget was
# exhausted by a #470 stall (issue #835). A budget timeout is NOT green — it
# still turns the job red — but it is labelled distinctly below so the
# classifier reports "journey timeout / #470 stall" instead of "EMULATOR INFRA
# UNAVAILABLE".
if [[ "${#FAILED_CLASSES[@]}" -eq 0 && "$STEP_TIMEOUT_HIT" -eq 0 \
      && "$APPEND_BURST_STATUS" == "PASS" && "$OUTPUT_BURST_IME_STATUS" == "PASS" \
      && "$MULTICHUNK_SEED_STATUS" == "PASS" && "$AGENT_LINK_AFFORDANCE_STATUS" == "PASS" \
      && "$REATTACH_REPAINT_STATUS" == "PASS" ]]; then
  JOURNEY_EXIT=0
  journey_status="PASS"
elif [[ "$STEP_TIMEOUT_HIT" -eq 1 && "${#FAILED_CLASSES[@]}" -eq 0 \
        && "$APPEND_BURST_STATUS" != "FAIL" && "$OUTPUT_BURST_IME_STATUS" != "FAIL" \
        && "$MULTICHUNK_SEED_STATUS" != "FAIL" && "$AGENT_LINK_AFFORDANCE_STATUS" != "FAIL" \
        && "$REATTACH_REPAINT_STATUS" != "FAIL" ]]; then
  # Only the budget timeout fired (no class failed BOTH attempts on its own
  # merits): a pure #470-stall time-budget casualty.
  JOURNEY_EXIT=1
  journey_status="STEP_TIMEOUT"
else
  JOURNEY_EXIT=1
  journey_status="FAIL"
fi

echo "=========================================================="
echo "Per-push CI journey suite — done (elapsed ${SUITE_ELAPSED}s, exit ${JOURNEY_EXIT}, status ${journey_status})"
echo "  passed first try: ${#PASSED_FIRST_TRY[@]}"
echo "  recovered on retry: ${#RECOVERED_CLASSES[@]}"
echo "  failed twice: ${#FAILED_CLASSES[@]}"
echo "  budget-timeout (issue #835 / #470 stall): ${#BUDGET_TIMEOUT_CLASSES[@]}"
echo "=========================================================="

# Build the markdown summary. Quote arrays defensively — an empty array under
# `set -u` must not abort the script during summary generation.
{
  echo "# Per-push CI journey suite — summary"
  echo
  echo "| Selection | Args | Exit | Elapsed | Result |"
  echo "| --- | --- | --- | --- | --- |"
  echo "| ${#JOURNEY_CLASSES[@]} load-bearing journey classes (per-class retry-once) | \`pocketshellCi=true\` | $JOURNEY_EXIT | ${SUITE_ELAPSED}s | **$journey_status** |"
  echo
  echo "Classes exercised:"
  for c in "${JOURNEY_CLASSES[@]}"; do
    echo "- \`$c\`"
  done
  echo
  echo "Core-terminal #803 append-burst proof (\`shared:core-terminal\`): **$APPEND_BURST_STATUS**"
  echo "- \`$CORE_TERMINAL_APPEND_BURST_CLASS\`"
  echo
  echo "Core-terminal #796 output-burst-IME ANR proof (\`shared:core-terminal\`): **$OUTPUT_BURST_IME_STATUS**"
  echo "- \`$CORE_TERMINAL_OUTPUT_BURST_IME_CLASS\`"
  echo
  echo "Core-terminal #866 multi-chunk seed attach ANR proof (\`shared:core-terminal\`): **$MULTICHUNK_SEED_STATUS**"
  echo "- \`$CORE_TERMINAL_MULTICHUNK_SEED_CLASS\`"
  echo
  echo "Core-terminal #871 agent-pane link-affordance off-main proof (\`shared:core-terminal\`): **$AGENT_LINK_AFFORDANCE_STATUS**"
  echo "- \`$CORE_TERMINAL_AGENT_LINK_AFFORDANCE_CLASS\`"
  echo
  echo "Core-terminal #879 beyond-grace reattach-repaint proof (\`shared:core-terminal\`): **$REATTACH_REPAINT_STATUS**"
  echo "- \`$CORE_TERMINAL_REATTACH_REPAINT_CLASS\`"
  if [[ "${#PASSED_FIRST_TRY[@]}" -gt 0 ]]; then
    echo
    echo "Passed first try:"
    for c in "${PASSED_FIRST_TRY[@]}"; do
      echo "- \`$c\`"
    done
  fi
  if [[ "${#RECOVERED_CLASSES[@]}" -gt 0 ]]; then
    echo
    echo "Recovered on retry (CI-AVD flake — \`JOURNEY_FLAKE_RECOVERED\`):"
    for c in "${RECOVERED_CLASSES[@]}"; do
      echo "- \`$c\`"
    done
  fi
  # Issue #835: emit the `JOURNEY_STEP_TIMEOUT` section whenever the suite-level
  # time budget was exhausted (typically by the recurring #470 in-emulator tmux
  # `list-sessions` enumeration stall). The workflow's classify step greps this
  # marker to label the red as a journey timeout / #470 stall — DISTINCT from a
  # genuine `JOURNEY_FAILED` regression and from a "no summary at all" #771
  # EMULATOR INFRA UNAVAILABLE abort. Writing this summary at all (instead of
  # being SIGKILLed mid-loop by the 45-min job cap) is the whole point: an
  # artifact exists, so the classifier can attribute the red correctly.
  if [[ "$STEP_TIMEOUT_HIT" -eq 1 ]]; then
    echo
    echo "Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 / #470 stall — job red):"
    echo "Budget: ${JOURNEY_STEP_BUDGET_SECS}s; elapsed: ${SUITE_ELAPSED}s. The in-emulator tmux"
    echo "\`list-sessions\` enumeration (picker/tree) stalled and consumed the budget before all"
    echo "load-bearing classes could run. This is the #470 enumeration stall, NOT a never-booted"
    echo "emulator (#771) and NOT a genuine test regression. Classes cut short / not run:"
    if [[ "${#BUDGET_TIMEOUT_CLASSES[@]}" -gt 0 ]]; then
      for c in "${BUDGET_TIMEOUT_CLASSES[@]}"; do
        echo "- \`$c\`"
      done
    else
      echo "- (none individually bucketed — budget spent during summary/proof phase)"
    fi
  fi
  # Emit the `JOURNEY_FAILED` / "Failed BOTH attempts" section whenever ANY
  # load-bearing check failed twice — the journey classes AND/OR the #803
  # append-burst proof. The workflow's classify step
  # (.github/workflows/tests.yml "Classify emulator-journey result") greps this
  # summary for `JOURNEY_FAILED|Failed BOTH attempts` to distinguish a genuine
  # test regression from a #771 EMULATOR INFRA UNAVAILABLE abort, and its `awk`
  # extracts the failing class names from under this exact header. If the
  # append-burst proof failed but all journey classes passed, FAILED_CLASSES is
  # empty — so we MUST still write the header (with the append-burst class)
  # here, otherwise an append-burst-only regression falls through to the grep's
  # else-branch and is mislabeled as an infra abort, burying the real cause.
  if [[ "${#FAILED_CLASSES[@]}" -gt 0 || "$APPEND_BURST_STATUS" == "FAIL" || "$OUTPUT_BURST_IME_STATUS" == "FAIL" \
        || "$MULTICHUNK_SEED_STATUS" == "FAIL" || "$AGENT_LINK_AFFORDANCE_STATUS" == "FAIL" \
        || "$REATTACH_REPAINT_STATUS" == "FAIL" ]]; then
    echo
    echo "Failed BOTH attempts (\`JOURNEY_FAILED\` — job red):"
    for c in "${FAILED_CLASSES[@]}"; do
      echo "- \`$c\`"
    done
    if [[ "$APPEND_BURST_STATUS" == "FAIL" ]]; then
      echo "- \`$CORE_TERMINAL_APPEND_BURST_CLASS\` (#803 append-burst proof)"
    fi
    if [[ "$OUTPUT_BURST_IME_STATUS" == "FAIL" ]]; then
      echo "- \`$CORE_TERMINAL_OUTPUT_BURST_IME_CLASS\` (#796 output-burst-IME ANR proof)"
    fi
    if [[ "$MULTICHUNK_SEED_STATUS" == "FAIL" ]]; then
      echo "- \`$CORE_TERMINAL_MULTICHUNK_SEED_CLASS\` (#866 multi-chunk seed attach ANR proof)"
    fi
    if [[ "$AGENT_LINK_AFFORDANCE_STATUS" == "FAIL" ]]; then
      echo "- \`$CORE_TERMINAL_AGENT_LINK_AFFORDANCE_CLASS\` (#871 agent-pane link-affordance off-main proof)"
    fi
    if [[ "$REATTACH_REPAINT_STATUS" == "FAIL" ]]; then
      echo "- \`$CORE_TERMINAL_REATTACH_REPAINT_CLASS\` (#879 beyond-grace reattach-repaint proof)"
    fi
  fi
} > "$SUMMARY"

echo "----------------------------------------------------------"
cat "$SUMMARY"
echo "----------------------------------------------------------"

exit "$JOURNEY_EXIT"
