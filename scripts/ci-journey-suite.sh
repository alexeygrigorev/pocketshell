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
  # Issue #666 REOPEN (2026-07-06): a session killed on the host must NOT be
  # RESURRECTED when the app REATTACHES to it (lifecycle/reconnect path) with the
  # tmux SERVER still alive. The prior fix only guarded the process-death
  # cold-restore path; the reattach path still fell through to `new-session -A`
  # (attach-OR-create) and recreated the killed session — the maintainer's exact
  # dogfood report. This journey seeds a KEEPALIVE session so the server survives
  # the target kill (the server-alive-session-gone branch, distinct from #998's
  # dead-server), attaches, kills only the target, backgrounds past grace, and
  # foregrounds (a LifecycleReattach reconnect). It asserts the target is NOT
  # recreated, the server stayed alive (keepalive present), and the app drops to
  # the session list. Runs on agents:2222; does NOT self-skip on CI.
  "$FQCN_PREFIX.LifecycleReattachGoneSessionNoResurrectE2eTest"
  # Issue #998: a remote tmux SERVER death (host reboot / OOM / `kill-server`)
  # must NOT be silently resurrected via `new-session -A` into a blank
  # "Connected" session. This journey attaches, `tmux kill-server`s the whole
  # server (every session vanishes), lets the EOF drive the auto-reconnect, and
  # asserts the reattach drops to the host list — NOT a resurrected empty
  # session — and the server stays DEAD (no `new-session -A` resurrection). It
  # runs on the deterministic agents:2222 fixture (no toxiproxy) and does NOT
  # self-skip on CI, so the server-death class regression cannot silently return.
  "$FQCN_PREFIX.ServerDeathReconnectNoResurrectE2eTest"
  # Issue #1072 (v0.4.19 release blocker): attaching a file dropped the live
  # connection AND the post-attach drop wedged reconnect (had to restart the
  # app). Failure 2 — "a post-attach drop must RECOVER WITHOUT AN APP RESTART" —
  # is a reconnect-core (D28) change and the maintainer's "worse half"; the
  # v0.4.19 review BLOCKED because it had only a VM/Robolectric proof, no
  # end-to-end journey on the real path. This journey attaches a real `tmux -CC`
  # session, starts a REAL large attachment upload over the warm `-CC` lease,
  # INDUCES a drop DURING the in-flight upload via the deterministic
  # liveness-probe drop seam (no toxiproxy — gates per-push), asserts the
  # teardown OWNS + CANCELS the in-flight upload
  # (tmux_attachment_stage_cancelled_by_teardown — the deterministic red→green
  # discriminator; RED on base where the un-owned upload races teardown and
  # wedges reconnect), then drives a manual Reconnect and asserts the SAME
  # session recovers to Live + a fresh marker round-trips WITHOUT an app restart
  # (the literal acceptance criterion). agents:2222, no toxiproxy; no
  # assumeFalse(isRunningOnCi()) on the load-bearing assertions.
  "$FQCN_PREFIX.AttachmentDropReconnectRecoversE2eTest"
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
  #
  # Issue #959: this class now ALSO carries the beyond-grace "terminal frozen (no
  # I/O) but app responsive" reproduction
  # (postGraceReattachLeavesTerminalLiveWithFreshInputEcho): after the post-grace
  # reattach it types a unique token through the real input path and requires the
  # shell's FRESH echo to render — the live-terminal property a frozen reattach
  # fails. The whole class FQCN runs every method, so the #959 method is gated
  # here at PR time with no extra entry. agents:2222, no toxiproxy.
  "$FQCN_PREFIX.BackgroundGraceReconnectE2eTest"
  # REWRITTEN (#1123, bounded-grace D21 update — supersedes the #977/#1021
  # indefinite-hold journey): the BOUNDED-grace session-hold journey. Attaches
  # MainActivity to a live Docker `agents` tmux session and proves both halves of
  # the bounded grace contract with a short injected grace: WITHIN grace the hold
  # is seamless (notification held, return with no reconnect band); BEYOND grace
  # the app fully tears down — `-CC` detached (0 orphan clients, #215), the tmux
  # session persists, the foreground-service hold notification clears (no wake-lock
  # past grace) — and a return after grace cleanly reconnects. Deterministic agents
  # fixture; passed focused API-35 proof locally.
  "$FQCN_PREFIX.BoundedGraceSessionHoldJourneyE2eTest"
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
  # ADDED (#1108): the composer send/dismiss + ATTACHMENT-on-failed-send journey.
  # The maintainer hit a "resend loses the file" report (#694 regression): on a
  # degraded-connection "Not sent" the composer must keep the attachment as a
  # TILE (the #872/#971 model — the path lives in the tile, NOT folded into the
  # draft text) so the reconnect-then-resend STILL carries the file. It uses NO
  # Docker fixture (pure Compose-rule UI test driving the real
  # PromptComposerViewModel send wiring) and does NOT self-skip on CI, so per the
  # "load-bearing journeys run at PR time" principle (#638/#691) it runs per-push
  # and the silent-drop regression cannot return. It lives under
  # com.pocketshell.app.composer, so it carries its fully-qualified name directly.
  "com.pocketshell.app.composer.PromptComposerSendDismissE2eTest"
  # ADDED (#848 audit / #900): the foreground outbound queue surface was covered
  # by a connected androidTest but was not in any per-push gate. This pins the
  # visible queued/failed/in-flight composer rows, collapsed count, delete, and
  # retry callbacks in the same per-push androidTest lane as the send-feedback
  # composer proofs. Pure Compose, no Docker fixture.
  "com.pocketshell.app.composer.PromptComposerOutboundQueueTest"
  # ADDED (#1308): batch "Resend all" for the queued backlog. The presence/absence
  # + callback + within-root containment of the new button live in
  # PromptComposerOutboundQueueTest above; this class is its keyboard-UP occlusion
  # sibling — it composes the production SheetContent with the queue expanded and
  # >= 2 Failed rows under a SYNTHETIC ime() inset (the #780 model — deterministic
  # on CI swiftshader, no real keyboard, HARD-asserts the inset applied, no
  # assumeTrue skip) and asserts the Resend all button stays ABOVE the keyboard via
  # the #657/F1 assertNodeFullyAboveImeOrKeyboard containment helper. Pure Compose,
  # no Docker fixture. Carries its fully-qualified name directly.
  "com.pocketshell.app.composer.ComposerResendAllImeProofTest"
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
  # ADDED (#1152): the recording-not-locked composer bottom row overflowed its
  # width and CLIPPED `Send` off the right edge (the cyan "S/e" sliver — audit
  # D1). This proof composes the production SheetContent in the recording state
  # (NOT-LOCKED and LOCKED), keyboard-UP via the #780 synthetic-inset model,
  # pinned to a fixed phone-width band, at font scale 1.0 AND 1.3, and HARD-asserts
  # every recording-row pill (Discard/Lock/Insert/Send) + the still-mounted
  # editing-tools group is fully CONTAINED within the band (band-relative, not a
  # bare assertIsDisplayed() a clipped pill still passes — #657/F1). RED on the
  # unfixed single-row layout (Send spills off the band); GREEN with the two-row
  # fit. No Docker fixture, no self-skip. Lives under com.pocketshell.app.composer,
  # not the proof prefix, so it carries its fully-qualified name directly.
  "com.pocketshell.app.composer.PromptComposerRecordingRowFitProofTest"
  # ADDED (#1245): the hands-free "lock" was REMOVED from the composer entirely
  # (the inline lock toggle, the swipe-up-to-lock gesture, the locked indicator,
  # the hint). This journey drives the real PromptComposerSheet inside its
  # ModalBottomSheet and asserts RED→GREEN that (a) no lock control/indicator/hint
  # renders while recording and (b) Discard now rides the SAME balanced action row
  # as Insert/Send (moved out of its old separate row above Send). Gate-wired per
  # D31/G9; no Docker fixture.
  "com.pocketshell.app.composer.PromptComposerRecordingNoLockJourneyTest"
  # ADDED (#585 REOPENED — the TRUE desired behavior): the ENTRY gesture on the
  # composer LAUNCHER button, not the mic inside the already-open sheet. Hold the
  # launcher (SESSION_COMPOSER_LAUNCHER_TAG) + swipe UP → the Prompt Composer opens
  # AND recording begins immediately, locked hands-free, in one gesture; a plain
  # tap still opens the composer with NO recording. Drives the real
  # ConversationComposerLauncherRow launcher + real PromptComposerSheet
  # (autoStartRecording) inside its ModalBottomSheet so the launcher gesture
  # competes with the same ancestor arbitration it does on device. RED on base (a
  # launcher hold+swipe opens with NO recording); GREEN with the fix. This is the
  # 7th reopen of #585 — gate-wired per D31/G9. No Docker fixture (fakes only).
  "com.pocketshell.app.voice.ComposerLauncherHoldSwipeUpJourneyTest"
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
  # ADDED (#1206 — fresh Claude pane whose FIRST prewarm capture-pane comes back EMPTY on a
  # busy shared -CC channel lands fragments-over-black instead of a painted grid). The prewarm
  # seed is single-shot: seedPrewarmedPane returned on the empty/wedged first capture and only
  # future incremental %output painted → the initial Claude TUI frame was unrecoverable. This
  # journey seeds exactly two sessions (A attach + B prewarm target), opens the in-session
  # switcher to fire prewarmLikelySwitchTargets, and uses a #780 synthetic injection
  # (PrewarmSeedFaultTestOverride) to force B's FIRST seed capture EMPTY while the pane HAS
  # content — the exact non-happy state the happy real-agent workbench structurally cannot
  # enter. It HARD-asserts the fault was consumed by the prewarm seed path (the #1206 code
  # path ran — no vacuous pass) AND that B lands on a PAINTED grid (its marker visible, not
  # blank) after switching to it (the retry/deferred-reseed recovered the full grid). The clean
  # red→green for the retry is at the JVM layer (TmuxSessionViewModelTest.prewarmSeedRetries*);
  # this is the on-device GREEN acceptance (G4/G10). Deterministic agents:2222 only, no
  # toxiproxy, no CI self-skip — belongs in this per-push subset.
  "$FQCN_PREFIX.Issue1206PrewarmEmptyCaptureSeedRetryJourneyE2eTest"
  # ADDED (#1181 — BLACK terminal on tapping the connection notification / background→
  # foreground resume while the FGS keeps the connection ALIVE): a port-forward pin (#1159
  # Part 3) SUPPRESSES the bounded-grace teardown, so the VM never stashes a pendingReattach
  # and the `-CC` client stays live across the background. On the notification-tap foreground
  # return beyond grace, `onAppForegrounded(false)` finds nothing pending and drove ZERO
  # repaint — the only live-connection foreground path that repaints nothing → permanent black.
  # This journey attaches a full-viewport banner pane, PINS a port-forward, wipes the LIVE
  # emulator to that black state (local `CSI 2J`+`CSI H`; the REMOTE tmux grid still holds the
  # banner), backgrounds PAST a short grace (assert the pin HELD and NO teardown ran), delivers
  # the REAL session-notification contentIntent and foregrounds. RED on base (the resume fires
  # no reseed → no capture-pane → banner stays black); GREEN with the fix (the SAME #553/#892
  # `reseedActivePaneForReattach` full-viewport reseed restores the banner, staying Connected,
  # no reconnect band). Two tests cover a shell pane AND an idle alt-screen agent pane (D32 G2).
  # Deterministic agents:2222 only, no toxiproxy, no self-skip — belongs in this per-push subset.
  "$FQCN_PREFIX.NotificationTapLivePinnedForegroundReseedJourneyE2eTest"
  # ADDED (#989 — Redraw must NEVER clear-to-black on a NEAR-BLANK remote capture): the
  # #892 sibling above wipes the LOCAL emulator while the REMOTE tmux grid still holds the
  # full banner, so its capture-pane returns CONTENT-RICH — it never exercises the #989 root
  # cause, an IDLE alt-screen agent whose REMOTE grid is itself near-blank. This journey
  # seeds a genuinely idle near-blank REMOTE pane, feeds a RICH banner LOCALLY (local=rich,
  # remote=near-blank — the idle-agent mismatch), then TAPS Redraw so the warm capture-pane
  # comes back near-blank. RED on base (the near-blank capture is painted after a `CSI 2J`
  # clear → the viewport collapses to black); GREEN with the #989 fix (the non-destructive
  # swap REFUSES the near-blank capture and KEEPS the last rich frame, and the forced
  # `send-keys C-l` repaint makes the idle agent re-emit). The missing near-blank-REMOTE
  # fixture the happy banner-in-remote fixture masked (G10). Deterministic agents:2222 only
  # (rich frame injected LOCALLY, no toxiproxy), no CI self-skip — belongs in this subset.
  "$FQCN_PREFIX.RedrawNonDestructiveNearBlankCaptureE2eTest"
  # ADDED (#993 — the manual "Reconnect" kebab escape hatch): the maintainer dogfooded a
  # session that DROPPED while he was sending a message, with no in-session way to recover
  # (the connection manager doesn't auto-reconnect a single dropped session reliably yet) —
  # the old workaround was switch to ANOTHER session and back. This journey attaches a live
  # session, DROPS it via the clean-passive-disconnect seam (the same EOF body a real reader
  # EOF drives, no toxiproxy), confirms a USER-VISIBLE connection-lost band, then OPENS the
  # kebab and TAPS the stable-tagged Reconnect item. RED on base (no kebab Reconnect item →
  # the [TMUX_RECONNECT_BUTTON_TAG] node doesn't exist → the tap can't recover the session);
  # GREEN with the fix (the item drives the VM's single reconnect entrypoint → the SAME
  # session recovers to Connected IN PLACE over a FRESH `-CC` client, no switch dance, and a
  # post-reconnect send round-trips — the precondition the #900 outbound-queue auto-flush
  # needs). Uses ONLY the deterministic agents:2222 fixture and does NOT self-skip on CI, so
  # it belongs in this per-push subset.
  "$FQCN_PREFIX.ReconnectKebabInPlaceJourneyE2eTest"
  # ADDED (#966/#967 — the DISCRIMINATING render-death-on-a-LIVE-transport journey): the
  # maintainer dogfooded a connected Claude session that went BLACK with only stray
  # FRAGMENTS (a lone cursor, a "3", a scattered status line) while the transport stayed
  # CONNECTED. The v0.4.17 black-screen heal only engages on a FULLY-blank / ≤3-live-line
  # pane, so a black-WITH-fragments pane reads "not blank" and the heal SKIPS it (the #966
  # oracle gap). This journey attaches a full-viewport banner, injects the scattered-
  # fragment / fully-blank / stale-after-burst render state on the LIVE emulator (the
  # REMOTE tmux grid still holds the banner, so the transport is GUARANTEED LIVE — the
  # discriminator), then asserts BOTH: transport stays Connected (no reconnect surface,
  # client not disconnected) AND the widened divergence heal re-renders the full viewport
  # from tmux's authoritative `capture-pane`. RED on base (the v0.4.17 oracle reads
  # not-blank → the fragment pane is never healed); GREEN with the widened
  # `visibleScreenDivergesFromCapture` oracle + the stale-render watchdog. Three tests
  # class-cover fully-blank / scattered-fragment / stale-after-burst (D32 G2). Uses ONLY
  # the deterministic agents:2222 fixture (state injected LOCALLY, no toxiproxy) and does
  # NOT self-skip on CI, so it belongs here.
  "$FQCN_PREFIX.StaleRenderHealOnLiveTransportJourneyE2eTest"
  # ADDED (#1138): the SEMI/PARTIAL-black on a live AGENT ALT-SCREEN pane (v0.4.19 dogfood,
  # reproduced on BOTH Codex and Claude). The agent redraws with cursor-addressed writes, so
  # only its live status line repaints locally while the upper ALT-SCREEN rows stay black. An
  # alt-screen agent frame is SPARSE (header + a big blank conversation area + status), so the
  # surviving status line is a LARGE fraction of it — ABOVE the #966 25% divergence ceiling, so
  # the v0.4.18 steady-state stale-render watchdog (whose ONLY predicate was
  # `visibleScreenDivergesFromCapture`) read it "healthy" and never healed. This journey seeds a
  # SPARSE alt-screen app, attaches, injects the partial-black (clear + only the status line) on
  # the LIVE emulator (the REMOTE tmux grid keeps the full sparse alt frame → transport
  # GUARANTEED LIVE), asserts the pane is partial-black + Connected, then drives ONE watchdog
  # tick and asserts the FULL alt frame (header marker + upper rows) re-renders. RED on base
  # (divergence-only skips the sparse frame — unit-proven in PartialBlackPaneHealTest); GREEN
  # with the union predicate `visibleRenderLostFrameVsCapture`. Uses ONLY the deterministic
  # agents:2222 fixture (state injected LOCALLY, no toxiproxy) and does NOT self-skip on CI.
  "$FQCN_PREFIX.AgentAltScreenPartialBlackHealJourneyE2eTest"
  # ADDED (#1205 — pane delivery-backlog / seed-gate OVERFLOW must self-heal, not latch a dead
  # `surfaceError` card): per-pane `%output` delivery is a bounded `Channel(4096)` fed by
  # non-blocking `trySend`; a sustained Claude alt-screen burst on a contended main thread
  # overflows and drops. Before #1205 the FIRST dropped frame cancelled the producer, detached
  # the pane, and latched `surfaceError` — a permanently dead pane the blank/stale watchdog and
  # heal oracle early-return on, so the user had to tap "Recreate terminal" (the 2 MB seed-gate
  # overflow latched the same way). This journey attaches a full-viewport banner, wipes the LIVE
  # emulator to black (the REMOTE tmux grid keeps the banner → transport GUARANTEED LIVE), then
  # trips a REAL overflow through the SAME private handler the `outputBacklogOverflows` collector
  # calls (only the trigger is synthetic — the #780 model; a 4096-deep channel overflow can't be
  # forced deterministically on swiftshader). GREEN: no dead surfaceError card, a FRESH
  # capture-pane reseed restores the banner, the producer reattaches to the LIVE client, session
  # stays Connected (renderer backpressure, no reconnect); RED on base (the pane latches
  # surfaceError). Three tests class-cover backlog + seed-gate self-heal AND the bounded-retry
  # EXHAUSTION give-up (a still-saturated channel lands on the Recreate-terminal card exactly once
  # — no reseed storm). Deterministic agents:2222 only (state injected LOCALLY, no toxiproxy) and
  # does NOT self-skip on CI, so it belongs in this per-push subset.
  "$FQCN_PREFIX.PaneOutputOverflowRecoveryJourneyE2eTest"
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
  # ADDED (#1214 — mostly-empty-model reveal-time leg of the #1208 fragments-over-black audit): the
  # reveal/resize/switch nets gate an authoritative `capture-pane` diff on the cheap LOCAL pre-check
  # `visibleRenderMayHaveLostFrame`, which before #1214 only flagged a live-fraction in (0.5, 0.75]
  # or a ≤3-line partial-blank. A mostly-empty pane with >3 scattered live lines but live-fraction
  # BELOW 0.5 read "healthy" at reveal → it REVEALED UNHEALED (fragments-over-black), only maybe
  # caught ~4s later by the steady watchdog. This journey seeds a FULL 40-row banner, attaches,
  # injects a mostly-empty model (local `CSI 2J`+`CSI H` + 5 scattered live lines — >3 lines and
  # <0.5 fraction; the REMOTE tmux grid keeps the full banner → transport GUARANTEED LIVE), then
  # drives the EXACT same-dimension (no-op) resize heal branch. RED on base (the sub-0.5 pane is
  # skipped → banner never restored in the SHORT window); GREEN with #1214 (the widened pre-check
  # flags it → the reveal-time heal re-captures the full banner AT reveal, not ~4s later). Uses ONLY
  # the deterministic agents:2222 fixture (state injected LOCALLY, no toxiproxy) and does NOT
  # self-skip on CI.
  "$FQCN_PREFIX.MostlyEmptyModelHealsAtRevealJourneyE2eTest"
  # ADDED (#1302/#1208 — the COMPOSITE recovery journey, the campaign's acceptance gate that
  # PROVES the reconciler ends the maintainer's fragments-over-black once and for all). The
  # maintainer's residual black (recurred 2026-07-06 AFTER v0.4.23) needs three ingredients the
  # old happy fixture cannot produce (G10): a mostly-empty model, an idle Claude that repaints
  # ONLY a spinner (so it can never self-heal a lost grid), and a recovery layer that is not
  # healing the pane. This journey drives the REAL idle-incremental fixture
  # (tests/docker/agent-fixtures/idle-incremental-claude.sh, reuses agents:2222) into
  # fragments-over-black on the LIVE emulator, then asserts the visible pane converges to
  # AUTHORITATIVE tmux content via the PERIODIC reconciler alone (no explicit reveal/resize/
  # switch/heal trigger). Within-run RED->GREEN (#780 synthetic): RED = the reconciler is
  # suppressed (auto-arm off + watchdog cancelled) -> the pane STAYS fragments-over-black for a
  # full reconcile window (the maintainer's unhealable persistent black; a fresh capture-pane
  # proves tmux's grid still holds the banner the render lost — the authoritative count-diff);
  # GREEN = re-arm the reconciler -> it ticks on its own cadence and converges the pane to the
  # full banner, transport stays Connected, no reconnect surface. A busy-agent lane
  # (busy-agent-burst.sh) proves the merged #1297 wedge-proof lane reconciles under -CC
  # saturation. Deterministic agents:2222 only (state injected LOCALLY, no toxiproxy) and does
  # NOT self-skip on CI, so it belongs in this per-push subset.
  "$FQCN_PREFIX.IdleClaudeFragmentsOverBlackRecoveryJourneyE2eTest"
  # ADDED (#1153 — send-with-attachment half-black, v0.4.21 dogfood): a composer Send WITH AN
  # ATTACHMENT is ALWAYS multi-line (it appends an "Attached files:" block), so it takes the
  # bracketed-paste + submit branch and the alt-screen agent clear+redraws its WHOLE viewport; the
  # overpaint leaves a >3-line HALF-BLACK band (input box + a few conversation lines + status) over
  # a large black region, on a LIVE transport (no reconnect). The pre-#1153 #941 send heal gated on
  # the LOCAL-ONLY `blank || partialBlank` heuristic (≤3 live lines) and SKIPPED the >3-line band,
  # and its fixed 350 ms one-shot lost the race against the bigger attachment redraw. This journey
  # seeds a FULL-screen frame, attaches, DISABLES the steady-state watchdog (so ONLY the SEND heal
  # can green the pane), injects the >3-line half-black on the LIVE emulator (REMOTE tmux grid keeps
  # the full frame → transport GUARANTEED LIVE), then drives a REAL with-attachment (multi-line)
  # send and asserts the SEND heal restores the full frame with the transport still Connected and
  # NO reconnect. RED on base (the local-only gate skips the >3-line band — unit-proven in
  # PartialBlackPaneHealTest); GREEN with the widened capture-diff heal
  # (`visibleRenderLostFrameVsCapture` case c + the bounded send-heal poll). Uses ONLY the
  # deterministic agents:2222 fixture (state injected LOCALLY, no toxiproxy) and does NOT self-skip.
  "$FQCN_PREFIX.SendWithAttachmentStaysVisibleE2eTest"
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
  #
  # ALSO COVERS (#981): the class now carries
  # realValidatedHandoffWhileTransportProvenAliveDoesNotReconnect — a REAL validated
  # WIFI→CELLULAR identity flip (which DOES emit past #875) on a STABLE wifi while the
  # live SSH transport is provably alive (forceTransportProvenAliveForTest pinned, the
  # #780 synthetic-inject model — no self-skip) must NOT tear down + redial the healthy
  # socket (the #974 stable-wifi drop). RED on base (the emitted flip → scheduleNetwork-
  # Reconnect raises the Reconnecting band); GREEN once the #981 liveness gate rides the
  # proven-alive transport through. Same deterministic agents:2222 fixture; runs on CI.
  "$FQCN_PREFIX.StableWifiNoSpuriousReconnectE2eTest"
  # ADDED (#997, network sub-cluster after #995/#981): the BARE-NETWORK-LOSS
  # proactive-detection + fast-reconnect-on-restore journey. The pre-#997 detector
  # returned null for any non-validated snapshot (TerminalNetworkObserver.kt:328)
  # AND for a same-identity restore (:333), so a clean drop (onLost / airplane
  # mode) produced NO TerminalNetworkChange at all — the only thing that noticed
  # was the ~90s keepalive ride-through or sshj EOF, both reactive and slow,
  # leaving the UI on a live-but-dead session. This journey opens a real `tmux -CC`
  # session and drives a bare LOSS then a same-identity RESTORE DETERMINISTICALLY by
  # pushing synthetic NoValidatedNetwork → Validated snapshots through the PRODUCTION
  # TerminalNetworkObserver detector + emit pipeline (the AVD can't enter airplane
  # mode without killing the test ADB link — the #780 synthetic-inject model, no
  # self-skip). HARD-asserts: the loss surfaces a NetworkLost + the VM HOLDS the
  # lease with NO redial churn during the loss window (network_loss_hold recorded);
  # the same-identity restore surfaces a NetworkRestored and fires a FAST
  # network_restore_reconnect_start well inside the ~90s budget; the session settles
  # back to Connected with a painted viewport. RED on base (loss + same-identity
  # restore both swallowed → no NetworkLost, no fast reconnect); GREEN after #997.
  # Same deterministic agents:2222 fixture; runs on CI.
  #
  # ISSUE #1042: this class is now the GENUINELY-DEAD-SOCKET preservation guard —
  # it synthetically injects a dead transport across the restore (keepalive
  # NOT-proven-alive + the bounded restore probe DEAD) so the #1042 liveness-first
  # gate must fall through to the #997 fresh-lease redial. The companion
  # ride-through (socket SURVIVED ⇒ NO redial) journey is below.
  "$FQCN_PREFIX.BareNetworkLossRestoreReconnectE2eTest"
  # ADDED (#1042): stop SPURIOUS reconnects on mobile/cellular when the link/socket
  # ACTUALLY SURVIVED the dip. Three deterministic agents:2222 journeys (no toxiproxy,
  # synthetic snapshot injection through the production TerminalNetworkObserver — the
  # #780 hard-inject model, no self-skip):
  #   (a) a brief NetworkLost→NetworkRestored where the transport SURVIVED (keepalive
  #       pinned proven-alive) → the #1042 liveness-first restore RIDES THROUGH with NO
  #       redial. RED on base (the restore arm redialled unconditionally →
  #       network_restore_reconnect_start); GREEN with #1042 (network_restore_ride_through,
  #       ZERO redial diagnostics).
  #   (c) a {CELLULAR}→{CELLULAR} same-transport reassoc (new handle) is SUPPRESSED by
  #       the detector → no event → no redial. RED on base (the same-identity relaxation
  #       was pure-{WIFI}-only → network_reconnect_start); GREEN with #1042.
  #   (d) the scope guard: a REAL cross-transport WIFI↔CELLULAR handoff STILL redials
  #       (network_reconnect_start) — the #548 proactive-handoff feature is preserved.
  "$FQCN_PREFIX.MobileSpuriousReconnectE2eTest"
  # ADDED (#1082): the END-TO-END WIFI→CELLULAR dead-socket handoff journey — the
  # real-path sibling of #1078's VM-unit proof (audit #843 gap G1, highest
  # mobile-stability impact). #1078 fixed the headline ~90s FROZEN-but-Live handoff
  # stall but proved it red→green at the unit level only; this drives the REAL
  # MainActivity → attach → live `-CC` transport and exercises the same production
  # onNetworkChanged → reducer → suppressNetworkTransportProvenAlive bounded-probe
  # arm. Two deterministic agents:2222 journeys (no toxiproxy, the #780 synthetic-
  # inject model — no assumeFalse(isRunningOnCi()), no self-skip on the load-bearing
  # assertion):
  #   (AC1) deadSocketWifiCellularHandoffRedialsWithinProbeBudgetNotFrozenLive: a
  #     validated WIFI→CELLULAR handoff while PASSIVELY proven alive
  #     (forceTransportProvenAliveForTest=true) but the bounded active probe DEAD
  #     (forceLivenessProbeDeadForTest=true — genuinely dead socket post-handoff)
  #     must REDIAL within the probe budget — network_reconnect_start
  #     (classification=proactive_network_handoff) fires well inside the bounded
  #     window (NOT a ~90s frozen-Live stall) — and the SAME session recovers to
  #     Connected with a fresh marker round-trip (input routing restored). RED on
  #     base (the suppress arm rode through on the passive timestamp with no probe/no
  #     redial → frozen Live ~90s → no redial in the window); GREEN with #1078.
  #   (AC2) aliveSocketWifiCellularHandoffRidesThroughWithNoSpuriousRedial: the same
  #     passively-proven-alive handoff but the bounded probe ANSWERS over the live
  #     socket (probe seam OFF) → RIDE THROUGH with ZERO redial diagnostics, attributed
  #     to network_reconnect_skip cause=transport_proven_alive probeConfirmed=true (the
  #     #981/#974/#1058 win preserved; class coverage D32 G2). Distinct from
  #     MobileSpuriousReconnectE2eTest(d), which pins forceTransportProvenAliveForTest
  #     =false and so never reaches the #1078 suppress arm. Uses ONLY the deterministic
  #     agents:2222 fixture tests.yml already brings up — no new Docker service/port.
  "$FQCN_PREFIX.Issue1078DeadSocketHandoffRedialJourneyE2eTest"
  # ADDED (#970): the realistic-wifi STABILITY regression gate — the durable proof
  # for #964 (D31/D32/D33). Only the DETERMINISTIC method is run by FQCN here; it
  # reproduces the #964 budget mismatch on the plain deterministic agents:2222
  # fixture (no toxiproxy, no new Docker service) using ONLY base-available seams:
  # the `-CC` LivenessProbe is made to report DEAD for a BOUNDED window (a
  # momentarily-slow control channel, NOT a permanent wedge) WHILE the agents:2222
  # SSH transport stays genuinely LIVE, so the always-on keepalive keeps proving the
  # link alive. On BASE (rc/0.4.18 WITHOUT #964) the probe force-redials the FINE
  # link the instant it reaches its (shortened) budget — records
  # liveness_probe_silent_drop, bumps TMUX_CONNECT_ATTEMPTS, raises the Reconnecting
  # band — so the ZERO-reconnect assertions FAIL (RED). With #964 the probe DEFERS
  # to the still-healthy keepalive and never redials (GREEN). BOTH methods in this
  # class run per-push and HARD-assert on CI — NEITHER self-skips (#1081): the
  # `realisticJitteryWifiOnRealLinkNeverRedials` method was rebuilt on the #780
  # synthetic-injection model (repeated synthetic `-CC` probe-failure bursts on the
  # keepalive-proven-live agents:2222 transport — NO toxiproxy, NO
  # assumeNetworkFaultProofsEnabled gate), replacing the old opt-in toxiproxy variant
  # that self-skipped on CI and provided ZERO protection while appearing covered.
  #
  # INTEGRATION NOTE: this gate is RED until #964 lands, so it MUST be integrated
  # WITH or AFTER #964 — never before — or it reds the per-push journey job.
  "$FQCN_PREFIX.RealisticWifiStabilityNoSpuriousReconnectE2eTest"
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
  # ADDED (#1085 F3): the voice-first recomposition-jank scope proof. The
  # maintainer's "app KEEPS FREEZING" report; F3 is the recomposition vector —
  # inline-dictation amplitude (20 Hz) and agent-transcript streaming (60ms) were
  # read at the ROOT of the 7k-line TmuxSessionScreen body, so the whole body
  # recomposed at the flow's frequency while dictating / while an agent streamed.
  # The fix derives the dictation MODE (not the amplitude) and a STABLE
  # SurfaceConversationChrome projection (not the streaming events) in the body,
  # and reads the high-frequency events list inside the surfaceContent child
  # scope. This DETERMINISTIC scope proof drives a 20-sample amplitude burst and a
  # 20-flush streaming burst against the production read structure (the real
  # SurfaceConversationChrome data class + derivedStateOf projection) and
  # HARD-asserts O(1) body recompositions, with sibling RED guards pinning the
  # pre-fix whole-state / direct-map reads at ~N. In-process Compose UI test, NO
  # Docker fixture, NO assumeTrue / assumeFalse(isRunningOnCi()) on the
  # load-bearing assertion (process.md F3). Runs per-push so the recomposition
  # jank cannot silently return (#638/#657). Lives under com.pocketshell.app.tmux,
  # so it carries its fully-qualified name directly.
  "com.pocketshell.app.tmux.Issue1085RecompositionScopeProofTest"
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
  # ADDED (#874 residual black-screen, conversation-source area — sibling of
  # #878/#894/#975, D31/D32): a presumed-agent pane RECONCILED rather than freshly
  # added — a beyond-grace reattach (#959) or a switch-back to a REBUILT cached
  # runtime — whose Conversation row was DROPPED (the R3-B 2-null collapse) has NO
  # row on restore (restoreCachedRuntime only restarts rows that had a live
  # detection, and never reconciles), so it falls to the always-mounted raw
  # TmuxTerminalPager → the #807 black void. The fix re-seeds the #878 Conversation
  # placeholder when the recorded-kind verdict resolves the session NOT-shell
  # (applyRecordedShellVerdict(isShell=false), AFTER the verdict so #894's
  # no-flash-on-shell invariant holds). This connected proof drives the production
  # TmuxSessionViewModel against the deterministic agents:2222 fixture through the
  # FRESH-attach path, then injects the residual-void state synthetically (#780/D33
  # — the wedged-channel row-drop-then-restore cannot be driven deterministically
  # on the shared AVD): drop the row, drive the not-shell verdict (the EXACT
  # refreshCurrentSessionRecordedKind production path), and assert the Conversation
  # placeholder is re-seeded — never the raw black Terminal void. It uses ONLY
  # agents:2222 (DEFAULT_HOST/DEFAULT_PORT -> 10.0.2.2:2222) that tests.yml already
  # brings up, and does NOT self-skip on CI (no assumeTrue / assumeFalse). The fast
  # JVM sibling is TmuxSessionViewModelTest.reconciledPresumedAgentWithDroppedRow*.
  "com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#reconciledPresumedAgentWithDroppedRowReseedsConversationPlaceholderOnDevice"
  # ADDED (#962/#975, conversation-source area — sibling of #819/#821/#894,
  # D31/D32): a live agent (claude) started INSIDE a session recorded
  # `@ps_agent_kind=shell` must regain its Terminal <-> Conversation TOGGLE, EVEN
  # when the host agent-kind daemon's classify returns `unknown` for the masked
  # node-wrapped/quiet `claude` (the B1 classify-miss the maintainer hit on
  # v0.4.18). On base the recorded-shell verdict (#894) collapses presumedAgent and
  # the `unknown` classify left detection null for the life of the session, hiding
  # the toggle (the maintainer's exact dogfood report). This class runs BOTH
  # directions on the REAL path:
  #   - conversationToggleReturnsForMaskedLiveClaudeInRecordedShellSession (#975 B1):
  #     the LOAD-BEARING real-path red->green. The agents fixture ships a live Claude
  #     transcript at ~/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl
  #     AND its daemon returns `unknown`/scope=null (non-systemd container) — exactly
  #     the masked-live-agent state. On base the foreign resolver returns null and NO
  #     toggle appears; the #975 transcript-evidence fallback binds the agent so the
  #     toggle returns. (The fast JVM sibling is TmuxSessionViewModelTest.b1Masked* +
  #     b1Prime* + b2Reattach*.)
  #   - plainShellRecordedSessionShowsNoConversationToggle (#894 no-flap ADJACENCY):
  #     a recorded-shell session with NO agent transcript shows NO toggle (a recorded
  #     shell must not flash the Conversation tab).
  #   - conversationTogglePresentForRecordedCodexAgentWhenSourceBindingFails (#1158):
  #     a session recorded `@ps_agent_kind=codex` whose conversation source cannot
  #     bind (no `/proc` match, no cwd transcript in-fixture) STILL shows a present +
  #     tappable Terminal/Conversation toggle, and tapping it routes to the
  #     Conversation placeholder (never collapses the tab). Tab presence follows the
  #     RECORDED agent kind, independent of the fragile live binding. This is the
  #     second kind in the class (Codex), covering the recurrence #962/#975 missed by
  #     only proving vanilla Claude. (Fast JVM sibling: TmuxSessionScreenTest
  #     .tmuxSessionTabStateShowsConversationForRecorded{Claude,Codex,ZaiClaude,OpenCode}*.)
  #   - conversationToggleAppearsForAltBufferAgentDirectlyLaunchedInShellSession
  #     (#1158 REOPENED — the maintainer's EXACT dogfood path): an agent launched
  #     DIRECTLY inside an existing shell tmux session (NOT the `pocketshell agent`
  #     wrapper), so `@ps_agent_kind` stays `shell`, the confirmed-shell verdict is
  #     never cleared, and live detection never binds — every prior signal false, the
  #     toggle GONE for the session's life. The detection-INDEPENDENT alt-buffer
  #     signal (a full-screen agent TUI holds the alternate screen buffer) restores
  #     it, STICKY. The pane's alt-buffer state is injected synthetically on the REAL
  #     connected pane (#780 model — tmux -CC cannot mirror a remote alt-buffer into
  #     the client emulator), hard-failing (no assumeTrue). RED on base (no
  #     `|| altBufferAgent`): the toggle stays absent even with the alt-buffer set.
  #     (Fast JVM siblings: TmuxSessionScreenTest.tmuxSessionTabStateShowsConversation
  #     ForAltBufferAgent* + TmuxSessionViewModelTest.altBufferAgent*.)
  # It uses ONLY agents:2222 (DEFAULT_HOST/DEFAULT_PORT -> 10.0.2.2:2222) that
  # tests.yml already brings up, and does NOT self-skip on CI (no assumeTrue /
  # assumeFalse(isRunningOnCi())). Lives under com.pocketshell.app.tmux.
  "com.pocketshell.app.tmux.ConversationToggleVisibleForLiveAgentInShellRecordedSessionDockerTest"
  # ADDED (#1057): the conversation stays REACHABLE after live detection drops.
  # The maintainer dogfood report (2026-06-28): "conversation is not visible in
  # this app" — they cannot reach the agent conversation view. The #975 sibling
  # above proves the toggle RETURNS when detection re-binds; this class proves the
  # OTHER half of the class — a conversation that genuinely EXISTS (a loaded
  # transcript) must stay reachable + readable when live detection DROPS and never
  # rebinds (the agent exited / masked no-rebind). It drives the REAL VM:
  #   - conversationStaysReachableAfterLiveDetectionDropsWithLoadedTranscript: the
  #     LOAD-BEARING real-path red->green. Detection binds via the #975 transcript
  #     fallback + the transcript tails into events; tapping Conversation renders
  #     the transcript (AC2 tap-to-switch); then the production agent-exit teardown
  #     (clearAgentDetectionForPane, the #780 synthetic-injection of the
  #     null-detection transition the deduped in-fixture detector won't fire) is
  #     driven on the live row. On base the row is DROPPED -> the toggle vanishes ->
  #     the conversation is unreachable (RED); with the #1057 fix the events-bearing
  #     row is KEPT -> the toggle stays + tapping still shows the transcript (GREEN).
  #   - plainShellShowsNoConversationToggleEvenThroughTeardown (#894 no-flap
  #     ADJACENCY): a genuine no-agent recorded shell shows NO toggle and the
  #     teardown does not synthesize one (the keep-events change must not resurrect
  #     a toggle for a conversation-less shell).
  # Uses ONLY agents:2222 that tests.yml already brings up; no self-skip on CI.
  "com.pocketshell.app.tmux.ConversationStaysReachableAfterDetectionDropsDockerTest"
  # ADDED (#1207 — reviewer BLOCKED-G4 residual, D33/G10): the composer-send
  # INTEGRATION on the REAL TmuxSessionScreen that the component/JVM proofs
  # could not cover. A fresh Claude session opens on the Conversation view; a
  # `/model` (an alt-screen TUI picker that writes NOTHING to the JSONL) used to
  # echo a MISLEADING optimistic user bubble and offer no way forward. This class
  # drives the REAL app + REAL Docker agent:
  #   - modelCommandFromConversationShowsNoticeNoEchoBubbleThenTapOpensTerminal:
  #     the LOAD-BEARING real-path red->green. Detection binds via the #975
  #     transcript-evidence fallback (so the composer routes AgentConversation);
  #     `/model` is typed into the REAL composer and Sent. On base (echo-always)
  #     sendToAgentPaneResult inserts a misleading /model User bubble and no
  #     Open-in-Terminal notice appears (RED); with the #1207 fix the no-echo path
  #     runs -> NO optimistic bubble + the notice IS shown + one tap lands the user
  #     on SessionTab.Terminal and the notice self-clears (GREEN).
  #   - confirmedShellShowsNoConversationNoticeOrPlaceholder (#894 no-flap
  #     ADJACENCY): a genuine no-agent recorded shell shows NO Conversation tab, so
  #     neither the #1207 notice nor the Conversation placeholder can appear on a
  #     confirmed shell.
  # Uses ONLY agents:2222 that tests.yml already brings up; no self-skip on CI.
  "com.pocketshell.app.tmux.ConversationTuiCommandJourneyDockerTest"
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
  # ADDED (#1320): the Terminal/Conversation TOGGLE-CLIP regression guard — the
  # REAL (layout) cause behind the 5×-recurring "Conversation tab missing"
  # (#962/#975/#1057/#1158). The toggle used to live in a `weight(1f, fill=false)`
  # slot that split width 50/50 with the title's own `weight(1f)`, so a long
  # agent title starved the toggle and its "Conversation" segment ellipsised away
  # (detection had already fired — the header showed the agent name — so the prior
  # detection-gate fixes never addressed this). This proof renders the PRODUCTION
  # ConsolidatedTopChrome at a narrow phone width with a long title (+ status pill
  # + long crumb + narrowest-width variants) and HARD-asserts the Conversation
  # segment renders at its FULL intrinsic width (compared to a reference
  # unconstrained SegmentedToggle — density-independent, no pixel threshold), not
  # a bare assertIsDisplayed (a squeezed/ellipsised segment still reports
  # displayed). RED on base (segment starved ~119px vs ~291px intrinsic); GREEN
  # after the toggle is pulled OUT of the weighted slot and reserved at intrinsic
  # width. Pure Compose-rule UI test — NO Docker fixture, NO SSH/tmux, NO
  # toxiproxy, NO 2222/2226 port — so it needs no tests.yml service change, and it
  # does NOT self-skip on CI (no assumeTrue / assumeFalse(isRunningOnCi())). It
  # lives under com.pocketshell.app.tmux, so it carries its FQCN directly.
  "com.pocketshell.app.tmux.TmuxChromeConversationTogglePresentTest"
  # ADDED (#859 Slice B): the typed-card RENDERER REGISTRY proof. The session
  # feed is now a generic typed-card list dispatched through
  # SessionCardRenderers (no `when (type)` in the feed). This connected Compose
  # test renders a HETEROGENEOUS feed (checklist + the new `note` type + a
  # genuinely unknown type) through the registry and asserts: the checklist
  # still renders + ticks (no regression), the `note` card renders + marks-read
  # via the registry (new type, zero feed code change), and an unknown type
  # degrades to the graceful "unsupported card" row. It is a PURE Compose test —
  # NO Docker fixture, NO SSH/tmux, NO toxiproxy, NO 2222/2226 port — so it
  # needs no tests.yml service change, and it does NOT self-skip on CI (no
  # assumeTrue / assumeFalse(isRunningOnCi())). Lives under
  # com.pocketshell.app.cards.
  "com.pocketshell.app.cards.SessionCardFeedRegistryTest"
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
  # ADDED (#1239): the host-card one-tap "Resume last session" affordance proof.
  # Seeds a persisted `last_session` snapshot + two saved hosts, launches the
  # real MainActivity, and HARD-asserts the Resume row is displayed under the
  # matching host, absent for the other host, and that tapping it LEAVES the host
  # list (deep-links into the session screen). It needs NO Docker fixture (the
  # session screen renders optimistically, so "left the host list" is observable
  # without SSH), NO toxiproxy, NO port — so no tests.yml service change — and it
  # does NOT self-skip on CI (no assumeTrue / assumeFalse(isRunningOnCi())). Per
  # G9 (a test per acceptance criterion, wired into a running gate) it must run
  # per-push so the affordance cannot silently regress. It lives under
  # com.pocketshell.app.hosts, so it carries its fully-qualified name directly.
  "com.pocketshell.app.hosts.HostResumeLastSessionE2eTest"
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
  # ADDED (#1207 — black-screen audit UX leg, D33/G10): a fresh Claude session
  # opens on the Conversation view with zero transcript; `/model` (an alt-screen
  # TUI picker that writes NOTHING to the JSONL) showed a silent nothing + a
  # misleading optimistic bubble, and a torn-down row stranded an eternal
  # "Loading conversation…" spinner. This class renders the REAL production
  # composables (ConversationTuiCommandNotice + ConversationDetectingPlaceholder
  # via the production tmuxConversationPlaceholderLoadState fallback) and asserts
  # the Open-in-Terminal affordance is shown + the terminal Empty state renders
  # instead of the eternal spinner. PURE Compose (NO Docker fixture, NO SSH/tmux,
  # NO port) — no tests.yml service change — and it does NOT self-skip on CI (no
  # assumeTrue / assumeFalse(isRunningOnCi())). Per G9 it must run per-push so the
  # TUI-command UX + spinner-race fix stay durably guarded. It lives under
  # com.pocketshell.app.conversation.
  "com.pocketshell.app.conversation.ConversationTuiCommandNoticeRenderTest"
  # ADDED (#931 — D33/G10 reproduce-first): the port-forward panel LazyColumn
  # duplicate-key crash guard. The maintainer's captured crash
  # (IllegalArgumentException: Key "22" already used) was a real Compose
  # duplicate-key crash because the table keyed each row on `it.remotePort`, and
  # the forwarding list can hold two rows sharing a remote port. This class
  # renders a REAL Compose composition: it HARD-asserts the OLD `it.remotePort`
  # keying crashes on the duplicate-22 list (the red reproduction) and that the
  # production `tunnelRowKeys` renders the same list (plus duplicate-local-port,
  # fully-identical-rows, and empty-list cases) with NO crash. It is a PURE
  # Compose composition test — NO Docker fixture, NO SSH/tmux, NO port — so it
  # needs no tests.yml service change, and it does NOT self-skip on CI (no
  # assumeTrue / assumeFalse(isRunningOnCi())). Per G9/G10 it must run per-push so
  # the crash class cannot silently regress. It lives under
  # com.pocketshell.app.portfwd. The RED reproduction (the OLD `it.remotePort`
  # keying crashing) lives in PortForwardDuplicateKeyCrashTest; the GREEN render
  # proofs of the production keying live in PortForwardDuplicateKeyRenderTest —
  # split so the intentional crash cannot poison the GREEN tests' compose rule.
  "com.pocketshell.app.portfwd.PortForwardDuplicateKeyCrashTest"
  "com.pocketshell.app.portfwd.PortForwardDuplicateKeyRenderTest"
  # ADDED (#1058 — #843 audit R1, trigger T11 / coverage gap C1, D33/G10): the
  # port-forward tunnel must ride through a cellular handoff / bare loss / restore
  # the SAME liveness-first way the terminal transport does (#981/#997/#1042/#1045)
  # instead of force-redialling on EVERY network change (the cellular-churn defect).
  # Stands up a REAL forward against the deterministic agents:2222 fixture (real
  # SshSession + always-on keepalive), injects the network event SYNTHETICALLY
  # through the production TerminalNetworkObserver detector + emit pipeline (the
  # SAME `changes` stream the controller subscribes to — the #780 hard-inject
  # model, no self-skip), and asserts from the controller's `portforward`
  # diagnostics across all three arms: (a) NetworkLost → HOLD (zero redial),
  # (b) proven-alive handoff/restore → RIDE THROUGH (zero redial, no restoring
  # churn), (c) genuinely-dead handoff → REDIAL. RED on base (the old controller
  # recorded no loss_hold/ride_through and force-redialled the loss + proven-alive
  # restore); GREEN with #1058. Same agents:2222 fixture; runs on CI.
  "com.pocketshell.app.portfwd.ForwardingNetworkRideThroughE2eTest"
  # ADDED (#1202 — D31/D32 durable-fix gate, on-device regression): the port-forward
  # "Stop" no-op + double-notification bug. The maintainer reported (v0.4.23) that
  # while a session is pinned for a forward, TWO tray notifications stack ("Port
  # forwarding running" + "Port forwarding active") and Stop on the "active" one only
  # ends the session hold — a no-op for the tunnels. The #1202/#1198 fix (hard-cut D22)
  # SUPPRESSES the session FGS while a forward is active so ForwardingService is the
  # SOLE notification owner, and its Stop actually tears the tunnels down.
  # `sessionPinnedForForward_hasExactlyOneForwardNotification_andStopTearsDownTunnels`
  # reproduces the exact reported state on the REAL path (App.kt mirrors the
  # active-host count into SessionServiceController.setPortForwardActive, exactly as
  # production) and HARD-asserts the REAL tray: exactly ONE app foreground
  # notification while pinned (RED on base — the session-channel second notification
  # posts), then fires the Stop action's PendingIntent and asserts the forward is
  # actually torn down (active host count → 0) + the notification gone. It does NOT
  # self-skip on CI (the screenshot proof in the sibling test method does; this one
  # is pure activeNotifications + Stop). NO Docker fixture / SSH / port — in-process
  # ActiveTmuxClients + ForwardingController doubles — so no tests.yml service change.
  "com.pocketshell.app.portfwd.ForwardingNotificationE2eTest#sessionPinnedForForward_hasExactlyOneForwardNotification_andStopTearsDownTunnels"
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
  # F3 / D31). It lives under the com.pocketshell.app.proof prefix. Issue #964/#822
  # adds a second @Test in this same class — the slow-but-live wifi journey threaded
  # against wedged-`-CC` recovery: a slow-but-live `-CC` blip that recovers while the
  # transport keepalive proves the link alive (forceTransportProvenAliveForTest) must
  # NOT spuriously redial (#964); but a SUSTAINED wedged `-CC` on a still-healthy
  # keepalive must STILL recover (#822 not suppressed by the deferral). Both methods
  # run per-PR.
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
  # Issue #1098 (item 3): the genuinely-UNRECOVERABLE-host counterpart of the clean
  # outage above. When the host is truly gone (sshd dead / port blackholed / a cut
  # that stays cut past the reconnect ladder's bound), the bounded ladder must
  # EXHAUST and surface a CLEAR disconnect band (TMUX_SESSION_ERROR_TAG
  # FailedConnectionRow + "Tap to reconnect", message "Disconnected from …") instead
  # of leaving the maintainer's reported frozen-but-live screen — WITHOUT re-alarming
  # the items-1+2 recoverable ride-through. A kill-the-worker-only fixture cannot
  # reach the failing state (the sshd LISTENER stays up so the fresh-transport re-dial
  # recovers — the round-3 finding), so this journey ARMS the genuinely-unrecoverable
  # seam (TmuxSessionViewModel.forceUnrecoverableHostForTest) which fails BOTH the
  # silent-reattach grace loop AND the auto-reconnect ladder's fresh dial, then does
  # the real pause->kill-app-sshd-worker->resume sequence (#173 real SSHException) so
  # the drop genuinely exhausts. It asserts the USER-VISIBLE contract: no crash on
  # resume, the disconnect band surfaces on genuine exhaustion, and once the seam is
  # disarmed (host reachable again) tapping Reconnect heals the SAME session — proving
  # the band is the honest recoverable error, not a permanent dead end (no false-alarm
  # regression). It drives ONLY the deterministic agents:2222 fixture
  # (DEFAULT_HOST/PORT/USER -> 10.0.2.2:2222) tests.yml already brings up, and does
  # NOT self-skip on CI (no assumeFalse(isRunningOnCi()) on the load-bearing
  # assertions — process.md F3 / D31). It lives under com.pocketshell.app.proof.
  "$FQCN_PREFIX.BackgroundResumeSocketDeathE2eTest"
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
  # ADDED (#965): the SCALE ANR proof — the folder list at the maintainer's
  # reported scale (71 projects / 12 sessions, mixed agent kinds) must NOT block
  # the Main thread loading the folder list. Drives the production
  # FolderListViewModel.bind() against a tree cache seeded at 71/12 with the
  # process-wide StrictMode policy active on Main, and HARD-asserts ZERO
  # Main-thread `disk_read` violations — the synchronous tree-cache read + JSON
  # parse on Main inside hydrateFromClientCache was the dominant cold-start ANR
  # cause. RED on base (the read trips disk_read at scale); GREEN with the
  # off-Main read. Pure on-device (no Docker/SSH fixture — the cache read is on
  # the bind() path before any network), deterministic on the CI swiftshader AVD,
  # does NOT self-skip on CI. The JVM fast-first backstop (the off-Main projection
  # split + frame budget) is HostTreeModelProjectionOffMainTest (per-push Unit
  # job). It lives under com.pocketshell.app.projects, so it carries its FQCN
  # directly.
  "com.pocketshell.app.projects.FolderListScaleAnrStrictModeDockerTest"
  # ADDED (#839, epic #821 workstream C — the #837 durable-tree daemon journey):
  # the END-TO-END durable-tree proof on a REAL device + REAL daemon. #837 was
  # approved on a JVM FakeTreeDaemon proxy; this drives the PRODUCTION
  # FolderListViewModel + a REAL TreeRemoteSource against the `agents-daemon`
  # fixture (port 2239) whose `pocketshell` is the genuine Python package, so
  # `tree get|upsert|reconcile` PERSIST a host-side JSON registry that survives an
  # app kill + relaunch. The journey collapses a folder + holds an order, KILLS +
  # RELAUNCHES the app (a fresh VM + a NEW TreeRemoteSource), asserts the
  # cold-start hydrate renders the held order/collapse INSTANTLY (folder stays
  # collapsed), then a resume reconcile prunes a gone session as a DELTA against
  # the LIVE `tmuxctl list` and a refresh picks up an added one. RED on a broken
  # durable path (the test's pre-condition guard fails if the daemon doesn't
  # persist); GREEN with the real daemon. The emulator-journey workflow now brings
  # up the 2239 fixture (+ a real-tree persist sanity check), so this does NOT
  # self-skip on CI. The always-runnable JVM backstop is
  # FolderListViewModelTreeDurabilityTest (per-push Unit job). It lives under
  # com.pocketshell.app.projects, so it carries its FQCN directly.
  "com.pocketshell.app.projects.FolderListDurableTreeDaemonDockerTest"
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
  # ADDED (#1184, D32 G9): the new-session picker's editable "Session name"
  # field — prefilled with the directory-derived default, threads a typed
  # custom label onto SessionTypeChoice.customName, and emits null when blank
  # so the caller falls back to the derived default. Component test (drives
  # SessionTypePickerContent directly, no SSH), so its FQCN is listed here to
  # run in the emulator-journey gate.
  "com.pocketshell.app.projects.SessionTypePickerNameFieldUiTest"
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
  # ADDED (#921, D32 G9): rendered Markdown shows GFM pipe tables as laid-out
  # cells, not raw `|---|---|` delimiter text. Drives the REAL FileViewerScaffold
  # with a benchmark/report `.md` and HARD-asserts the table surface is present
  # and the delimiter row is NOT shown as literal text. PURE Compose (no Docker /
  # SSH / tmux / port — runs on the already-booted AVD), no self-skip.
  "com.pocketshell.app.fileviewer.FileViewerScaffoldTest#markdownRenderedPipeTableShowsCellsNotRawDelimiter"
  # ADDED (#885, D32 G9): the post-update "Host ready" success sheet must show
  # ONLY Continue — the "Open Usage" CTA (#117) was removed (hard cut, D22).
  # This connected test drives the REAL HostBootstrapSheet in its Success state
  # and HARD-asserts the open-usage button does NOT exist + Continue is the
  # accent-filled primary action. PURE Compose (createComposeRule, no Docker /
  # SSH / tmux / port — runs on the already-booted AVD), no self-skip. Carries
  # its fully-qualified name directly (not under the proof prefix).
  "com.pocketshell.app.bootstrap.HostReadyPrimaryActionTest"

  # ===========================================================================
  # Issue #933 (#928 D9 — detection net). Kept in its OWN block to minimize
  # merge friction with the sibling #931 edits to this file.
  #
  # The recurring meta-problem: the maintainer hits freezes/crashes/ANRs that CI
  # did NOT catch, because the per-PR journeys carry no freeze/ANR/crash
  # DETECTOR. This is the safety net. One on-device end-to-end class exercises
  # all three D9 detectors with a RED (symptom present) and a GREEN (symptom
  # absent) case each:
  #   * P1 StrictMode — a real MAIN-THREAD DISK READ (the #926/#928-D1 freeze
  #     class that `detectNetwork()` misses) trips the process-wide policy
  #     App.onCreate installs and records a `strictmode.violation`; the same read
  #     off the main thread records none.
  #   * P2 responsiveness probe — a synthetically-BLOCKED main thread
  #     (Thread.sleep(2000) on Main, the freeze signature) is detected as a
  #     heartbeat gap beyond the frame budget; a responsive main thread passes.
  #   * P3 zero-crash gate — a crash persisted to the REAL CrashReportStore FAILS
  #     the gate; a clean store passes.
  # It uses NO Docker fixture, NO SSH/tmux, NO toxiproxy, NO port — a pure
  # on-device detector exercise, deterministic on the CI swiftshader AVD — so it
  # needs no tests.yml service change, and it does NOT self-skip on CI (no
  # assumeTrue / assumeFalse(isRunningOnCi()) on the load-bearing assertions —
  # process.md F3 / D33). It lives under the com.pocketshell.app.proof prefix.
  "$FQCN_PREFIX.StrictModeMainThreadIoDetectorE2eTest"

  # ===========================================================================
  # Issue #949 (epic #859 Slice A — host→app card push, Phase-1 verify-gone,
  # D33). Kept in its OWN block to minimize merge friction with the sibling
  # blocks above.
  #
  # Phase-1 (the host CLI `pocketshell push checklist|get|check|status` + the
  # app's SessionCardsRemoteSource + the checklist chip / bottom-sheet) shipped
  # code-complete on `main` but WITHOUT an end-to-end emulator+Docker acceptance
  # journey, so the real warm-session card-push path was UNPROVEN (D33). This
  # journey closes that gap and gate-wires it per-push so the transport cannot
  # silently regress.
  #
  # It exercises the REAL path, not a unit proxy: a host (the deterministic
  # agents fixture) runs `pocketshell push checklist --session <s> --item …`
  # over a real SSH session to write the per-session card store; the PRODUCTION
  # SessionCardsRemoteSource.getCards reads it back over the SAME warm session
  # (D21) and parses a ChecklistCard; the PRODUCTION checklist chip +
  # ChecklistCardsContent render the real feed and the test asserts the card +
  # its items ACTUALLY appear; tapping an item drives the PRODUCTION
  # setChecklistItemChecked tick exec, and re-reading the host (`push status` +
  # getCards) confirms the tick ROUND-TRIPPED (untick too). The
  # readPathBroken_wrongSessionName_yieldsEmptyFeed_redGuard case is the
  # inverted RED→GREEN guard (D32 G10) that pins the load-bearing
  # "card-reaches-the-feed" assertions. The fixture `pocketshell push` verb is
  # now faithful to cards.py (item ids <slug>-<index>, full-replace on re-push,
  # the JSON `get` contract the app reads, unknown-id error). It uses ONLY the
  # deterministic agents:2222 fixture (DEFAULT_HOST/PORT/USER -> 10.0.2.2:2222,
  # or the pool-allocated port under `--pool`) that tests.yml already brings up
  # with `up -d --build` (so CI gets the new `push` verb, not a stale image) —
  # no new Docker service/port, no toxiproxy — and does NOT self-skip on CI. It
  # lives under com.pocketshell.app.cards, so it carries its FQCN directly.
  "com.pocketshell.app.cards.SessionChecklistPushJourneyDockerTest"
  # ADDED (#947): the host-version-mismatch banner's one-tap Update button — the
  # UI gate (#641/#567/#657/G9) for a maintainer-reported UI control. The
  # maintainer asked for an Update button on the FolderList host-version banner
  # (the arrow in the issue screenshot) so the host `pocketshell` upgrade runs
  # over the warm session instead of copy-paste. This connected proof composes the
  # PRODUCTION CliVersionMismatchBanner (internal, no proxy/stand-in) in all THREE
  # states — Idle (Update + Dismiss), Running (spinner replaces the buttons, no
  # double-run), Failure (error line + Retry + Dismiss, no stuck spinner) — pinned
  # to the bottom edge like its real placement, and HARD-asserts viewport
  # CONTAINMENT (assertNodeFullyWithinRoot, the #657/F1 helper — NOT a bare
  # assertIsDisplayed, which a control pushed off the right edge by the long
  # version message would still satisfy) plus reachability (enabled + clickable)
  # of the Update + Dismiss controls. Each state writes a full-device screenshot
  # (the maintainer's arrow-target acceptance: Update next to Dismiss). It uses NO
  # Docker fixture, NO SSH/tmux/port (pure Compose UI test on the booted AVD) and
  # does NOT self-skip on CI (no assumeTrue / assumeFalse(isRunningOnCi()) on the
  # load-bearing assertions — process.md F3 / D33). It lives under
  # com.pocketshell.app.projects, not the proof prefix, so it carries its
  # fully-qualified name directly.
  "com.pocketshell.app.projects.CliVersionMismatchBannerUpdateButtonTest"
  # ---------------------------------------------------------------------------
  # Issue #951 (#928 D2 — launch ANR / crash-loop). The reproduce-first
  # end-to-end for the D2 finding: MainActivity.onCreate used to do an
  # unguarded `runBlocking { resolveDefaultHostLaunchDestination(...) }` — two
  # Room reads + a key-file stat — ON the Main thread on every default-host cold
  # launch (UI-thread block = ANR/jank; a DB-read throw escaping onCreate =
  # launch crash-loop). This journey drives the REAL launch path
  # (ActivityScenario.launch(MainActivity) with a default host seeded in Room),
  # with a capturing main-thread StrictMode disk-read policy active, and asserts
  # the launch-time resolution trips NO MainActivity-attributed main-thread
  # disk-read violation (red on base, green with the off-Main fix) WHILE the
  # default host still auto-opens its FolderList (the #305 contract). NO Docker
  # fixture / SSH / tmux / toxiproxy / port — a pure on-device launch exercise,
  # deterministic on the CI swiftshader AVD, so it needs no tests.yml service
  # change, and it does NOT self-skip on CI (no assumeTrue /
  # assumeFalse(isRunningOnCi()) on the load-bearing assertion — process.md
  # F3 / D33). It lives under the com.pocketshell.app.proof prefix.
  "$FQCN_PREFIX.LaunchNoMainThreadRoomReadE2eTest"
  # ADDED (#972 — D33/G4/G10: prove the host connection-log mirror's VM GLUE on
  # the real path). The #969-part-3 ConnectionLogHostMirror writer + its driver
  # wiring landed JVM-tested, but the load-bearing VM glue —
  # `mirrorConnectionLogToHost()` -> `ConnectionTarget.toLeaseSessionTarget()`,
  # the BYTE-IDENTICAL lease-key mapping that makes the warm-lease borrow real —
  # had ZERO coverage of any kind (the writer test used a FAKE lease; the driver
  # test only counted the callback). A wrong lease-key field mapping would
  # reproduce the exact "wired but the host file never lands" failure this issue
  # closes, uncaught. This connected journey drives the REAL production
  # TmuxSessionViewModel against the deterministic agents:2222 fixture: it seeds a
  # real reconnect-cause trail in a real DiagnosticRecorder, sets activeTarget to
  # the agents:2222 host, PRE-WARMS the SSH lease for that exact key (one cold
  # handshake), then fires the production mirror glue and asserts the host file
  # `~/.pocketshell/connection-log.jsonl` ACTUALLY LANDS over the WARM lease
  # (read back over a FRESH independent SSH exec) carrying the seeded keepalive_dead
  # trail — with ZERO extra handshakes (warm reuse), proving toLeaseSessionTarget()
  # produced a byte-identical key. The RED guard
  # (wrongLeaseKeyMappingDoesNotLandOverTheWarmLease) drives the SAME glue through a
  # deliberately-MISMATCHED key and asserts a SECOND cold handshake fires + the warm
  # transport is NOT reused — pinning the byte-identical-key property as the
  # load-bearing assertion (G6/G10). It uses ONLY the deterministic agents:2222
  # fixture (DEFAULT_HOST/PORT/USER -> 10.0.2.2:2222, or the pool-allocated port
  # under `--pool`) that tests.yml already brings up — no new Docker service/port,
  # no toxiproxy — and does NOT self-skip on CI (no assumeTrue /
  # assumeFalse(isRunningOnCi()) on the load-bearing assertions). It lives under
  # com.pocketshell.app.diagnostics, so it carries its FQCN directly.
  "com.pocketshell.app.diagnostics.ConnectionLogHostMirrorReconnectDockerTest"
  # ADDED (#1094, durability for #1092): the env edit/add on-device journeys. The
  # #1092 review shipped the edit-in-place + empty-state-CTA fix proven by a
  # reviewer-run local connected pass + gated JVM tests, but EnvScreenE2eTest
  # itself sat on the pre-existing unwired allowlist, so the env journeys did NOT
  # auto-run in CI — a D31/G9 durability gap for the env feature class
  # (edit-in-place, reveal-into-editor, add/create first key, D24 stdin-not-argv).
  # Wiring the whole class here runs ALL its methods at PR/batched time:
  # envScreenListsRevealsAddsAndCopies, editExistingKeyInPlaceUpdatesValueViaSetKeys
  # (the #1092 fix), and emptyFolderSurfacesAddKeyCtaThatCreatesFirstKey. The class
  # drives the production EnvScreen via createComposeRule against an in-memory DAO +
  # a fake EnvGateway (the env CLI wire contract is proven separately against the
  # real `pocketshell env` CLI), so it needs NO Docker fixture / SSH / tmux /
  # toxiproxy / port — a pure Compose-rule UI journey, deterministic on the CI
  # swiftshader AVD, so it needs no tests.yml service change — and it does NOT
  # self-skip on CI (no assumeTrue / assumeFalse(isRunningOnCi())). It lives under
  # com.pocketshell.app.env, so it carries its FQCN directly.
  "com.pocketshell.app.env.EnvScreenE2eTest"
  # ADDED (#1169 — Codex/agent pane rendered CUT: tmux window resized too small
  # and NOT restored). The maintainer's agent pane showed only the top ~10 rows
  # with the rest BLACK because a transient (keyboard/composer/switch/within-grace
  # foreground) shrank the tmux window and the warm-reattach REPLAYED the cached
  # shrunk size instead of re-deriving the live viewport (`window-size latest`
  # makes the shrink persist, so Terminus inherits the cut too). This journey
  # attaches at the full phone grid, drives the EXACT production cached-replay path
  # with a shrunk grid (TmuxSessionViewModel.replayCachedControlClientSizeForTest —
  # verbatim what restoreCachedRuntime + launchCachedRuntimeRemoteRefresh do), then
  # asserts `#{window_width}x#{window_height}` returns to the full viewport (NOT
  # stuck shrunk) and the pane fills the screen (no black-below). Load-bearing
  # red→green; runs the specific method so the pre-existing #285 auto-resize test
  # in the same class is unaffected. Uses only the deterministic agents:2222
  # fixture tests.yml already brings up (no toxiproxy / new port), and does NOT
  # self-skip on CI. Lives under com.pocketshell.app.tmux, so it carries its FQCN.
  "com.pocketshell.app.tmux.TmuxResizeSessionE2eTest#cachedSizeReplayRestoresFullWindowAndAgentPaneIsNotCut"
  # ADDED (#1241): the landing app-bar usage GLANCE PILL on-device proof. The
  # routine non-warning "how much have I burned this week" question was 2-3 taps
  # deep (Settings->Diagnostics / in-session kebab); this small most-constraining-
  # percent pill surfaces it on the landing app bar next to Settings and taps into
  # UsageScreen. This connected UI test composes the PRODUCTION HostsAppBar in the
  # real PocketShellTheme and asserts: the percent renders, the pill is HIDDEN with
  # no data / no route, tapping routes to Usage, a stale reading renders honestly
  # ("cached from HH:mm"), and the pill + Settings gear are BOTH fully within the
  # window root (the #418 declutter / no-crowd guard — assertNodeFullyWithinRoot,
  # not a bare assertIsDisplayed). Pure Compose-rule UI test (like EnvScreenE2eTest):
  # no Docker fixture / SSH / tmux / toxiproxy / port, so it needs NO tests.yml
  # service change, is deterministic on the CI swiftshader AVD, and does NOT
  # self-skip on CI. The pure state-derivation backstop (most-constraining pick,
  # hidden-when-no-data, kind, stale flag) is the per-push Unit-job
  # UsageGlancePillStateTest. It lives under com.pocketshell.app.usage, so it
  # carries its FQCN directly.
  "com.pocketshell.app.usage.UsageGlancePillE2eTest"
  # ADDED (#1318): on-device render acceptance for the quse-v0.0.9 strict-schema
  # usage pipeline — the G4/D33 proof the BLOCKED reviewer round asked for. Drives
  # the PRODUCTION UsageRemoteSource.fetchUsage (real strict PocketshellUsageJsonParser)
  # with the authoritative quse-0.0.9 FLATTENED per-provider NDJSON, maps to the real
  # UsageScreenState, and composes the PRODUCTION UsageScreen. HARD-asserts all four
  # provider cards render with the unified window labels straight from quse's `window`
  # field ("Weekly limit"/zai + "Monthly limit"/copilot — labels the OLD parser could
  # not produce), "4 providers · 1 hosts", and NO "Refresh usage failed" band. A
  # companion @Test reproduces the exact v0.4.24 broken panel through the SAME real
  # render path: the un-flattened provider-keyed blob must fail LOUD ("0 providers ·
  # 0 hosts" + "Refresh usage failed"), never a silent wrong render. Pure Compose-rule
  # UI test (like UsageGlancePillE2eTest): no Docker fixture / SSH / tmux / toxiproxy /
  # port, so it needs NO tests.yml service change, is deterministic on the CI
  # swiftshader AVD, and does NOT self-skip on CI. The same-input base-vs-fix red→green
  # for the schema change is the per-push Unit-job PocketshellUsageJsonParserTest. It
  # lives under com.pocketshell.app.usage, so it carries its FQCN directly.
  "com.pocketshell.app.usage.Usage1318StrictSchemaRenderE2eTest"
)

# ---------------------------------------------------------------------------
# Issue #835 (REOPENED): CI-matrix sharding of the journey class list.
#
# Root cause of the persistent emulator-journey RED/cancel: ~83 journey classes
# + 6 core-terminal proofs run STRICTLY SERIALLY on ONE swiftshader AVD take
# ~69+ min of wall-clock AND degrade the single emulator (the #470 enumeration
# stall worsens as the one AVD is churned by ~89 install/run/teardown cycles).
# Even the right-sized 4200s budget / 95-min cap (#1055) could not finish: its
# own validation run (commit 98384e91, GH Actions run 28307686762) ran the full
# ~95 min and was CANCELLED — no green verdict. Daemon-reuse + a bigger budget cut
# per-class overhead but the SERIAL wall-clock and single-emulator fragility
# remained the structural blocker.
#
# Fix: shard the class list across a CI MATRIX of runners (.github/workflows/
# tests.yml `strategy.matrix.shard`). Each matrix leg is its OWN runner with its
# OWN cold-booted emulator + its OWN Docker `agents` fixtures, and runs only its
# 1/N slice of the journey classes (round-robin by index so the heavy
# reconnect/switch journeys at the FRONT of the list distribute evenly across
# shards rather than all landing on one). This (a) cuts each leg's wall-clock to
# ~1/N so it finishes comfortably inside the budget, and (b) keeps each emulator
# far healthier (~1/N install/teardown cycles), which directly attacks the #470
# enumeration-stall root (a churned, degraded AVD).
#
# POCKETSHELL_JOURNEY_CI_SHARD_TOTAL / _INDEX are set by the workflow matrix.
# Default (unset / TOTAL<=1) = the unsharded serial path, UNCHANGED — so local
# runs and the budget self-test still run the FULL set. This is ORTHOGONAL to the
# #724 POCKETSHELL_JOURNEY_SHARD dev-box pool sharding (multiple emulators on ONE
# host); the two never combine on CI (one AVD per matrix leg). The six
# core-terminal proofs are NOT sharded — they are cheap in-process Compose UI
# tests (no Docker churn) and run on EVERY shard so a regression in any of them is
# caught on every leg.
JOURNEY_CI_SHARD_TOTAL="${POCKETSHELL_JOURNEY_CI_SHARD_TOTAL:-1}"
JOURNEY_CI_SHARD_INDEX="${POCKETSHELL_JOURNEY_CI_SHARD_INDEX:-0}"
[[ "$JOURNEY_CI_SHARD_TOTAL" =~ ^[0-9]+$ ]] || JOURNEY_CI_SHARD_TOTAL=1
[[ "$JOURNEY_CI_SHARD_INDEX" =~ ^[0-9]+$ ]] || JOURNEY_CI_SHARD_INDEX=0
(( JOURNEY_CI_SHARD_TOTAL < 1 )) && JOURNEY_CI_SHARD_TOTAL=1
(( JOURNEY_CI_SHARD_INDEX < 0 || JOURNEY_CI_SHARD_INDEX >= JOURNEY_CI_SHARD_TOTAL )) && JOURNEY_CI_SHARD_INDEX=0

EFFECTIVE_JOURNEY_CLASSES=()
if (( JOURNEY_CI_SHARD_TOTAL <= 1 )); then
  EFFECTIVE_JOURNEY_CLASSES=("${JOURNEY_CLASSES[@]}")
else
  for _shard_i in "${!JOURNEY_CLASSES[@]}"; do
    if (( _shard_i % JOURNEY_CI_SHARD_TOTAL == JOURNEY_CI_SHARD_INDEX )); then
      EFFECTIVE_JOURNEY_CLASSES+=("${JOURNEY_CLASSES[$_shard_i]}")
    fi
  done
  echo ">>> CI journey shard ${JOURNEY_CI_SHARD_INDEX}/${JOURNEY_CI_SHARD_TOTAL} (issue #835): running ${#EFFECTIVE_JOURNEY_CLASSES[@]} of ${#JOURNEY_CLASSES[@]} journey classes (round-robin), plus all 6 core-terminal proofs"
fi

echo "=========================================================="
echo "Per-push CI journey suite (issue #691) — load-bearing subset"
echo "Included classes:"
for c in "${EFFECTIVE_JOURNEY_CLASSES[@]}"; do
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
# can never burn the whole job to a `cancelled` (which writes NO
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
# Fix (#908): the suite owns its OWN deadline (JOURNEY_STEP_BUDGET_SECS).
# When the remaining budget is exhausted the suite STOPS launching new classes,
# records the not-run classes as a distinct BUDGET-timeout bucket, ALWAYS writes
# summary.md (with the greppable `JOURNEY_STEP_TIMEOUT` marker), and exits
# non-zero. Each individual class attempt is ALSO hard-capped (via `timeout`) at
# the smaller of its own ceiling and the budget remaining, so one wedged class
# can never run past the suite deadline.
#
# Right-sizing (#835 REOPENED): the 1200s (20-min) budget could not run the full
# load-bearing selection (~83 journey classes + 6 core-terminal proofs ≈ 89
# connected-test invocations) to a verdict SERIALLY, so the slow heavy
# reconnect/switch journeys at the front always ate the 1200s before
# BackgroundGrace / LiveHold / the share/composer/folder tail could run. The root
# cause is harness arithmetic, NOT an unbounded tmux hang (every enumeration
# round-trip is hard-bounded post #687/#702/#1041). Two changes make the WHOLE
# selection reach a verdict in one pass:
#
#   1. Kill the per-class cold-Gradle tax. Each journey class used to run as its
#      OWN cold `--no-daemon` Gradle invocation (~30-60s of fresh-JVM config +
#      APK install + runner spin-up BEFORE a single assertion) — ~89 of those is
#      ~40-80 min of pure overhead, which no sane job cap can fit. `run_class`
#      now reuses the Gradle DAEMON across invocations (the `--no-daemon` flag is
#      dropped), so config/JVM-start is paid ~once instead of ~89×. This PRESERVES
#      per-class isolation: every class is STILL its own `:app:
#      connectedDebugAndroidTest` task targeting one `class=` FQCN, run in its own
#      on-device `am instrument` process, so the #1042 grace-state-leak-across-
#      test-methods-in-one-process hazard does NOT apply (we never batch multiple
#      classes into one instrumentation process). It also matches how the six
#      core-terminal proofs below ALREADY invoke Gradle (daemon, no `--no-daemon`).
#      The #918 file-hash-lock poisoning a KILLED invocation could leave is still
#      cleared by `cleanup_gradle_after_timeout` (`gradlew --stop` after a class
#      is killed by `timeout`), so a wedged class cannot poison its retry.
#
#   2. Right-size the budget + job cap to the daemon-warm serial wall-clock.
#      `timeout-minutes` is a CAP, not a fixed duration: the job ENDS when the
#      suite ends (early on a healthy run), so a generous budget is pure
#      correctness upside — it only lets the worst case finish instead of being
#      SIGKILLed mid-loop.
#
# Arithmetic against the workflow cap is explicit (the same 600s post-boot slack
# the #908 invariant preserved, scaled up):
#   95-min job cap (5700s) - worst-case emulator boot (900s) - default suite
#   budget (4200s) = 600s for setup, classifier, Docker log collection, and
#   artifact upload.
# A suite-budget timeout SKIPS the workflow retry: a budget timeout is
# deterministic given the class set vs the budget (boot happens BEFORE
# SUITE_START, so a slow boot never eats the suite budget), so a retry would only
# burn the cap again. The job therefore never runs two full-budget attempts.
#
# Durable guard (#835 REOPENED, D31): a budget timeout is now a HARD RED, not an
# advisory-green. The suite already exits non-zero on STEP_TIMEOUT; the workflow
# classifier no longer downgrades a `JOURNEY_STEP_TIMEOUT` summary to green. So
# if the stall ever returns — or a future class addition pushes the tail back
# over the budget edge — the job goes RED with the cut-short classes named,
# instead of silently masking a missing verdict (the exact reopen complaint).
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
JOURNEY_STEP_BUDGET_SECS="${JOURNEY_STEP_BUDGET_SECS:-4200}"
JOURNEY_CLASS_TIMEOUT_SECS="${JOURNEY_CLASS_TIMEOUT_SECS:-420}"
JOURNEY_GRADLE_STOP_TIMEOUT_SECS="${JOURNEY_GRADLE_STOP_TIMEOUT_SECS:-60}"
JOURNEY_CLASS_KILL_AFTER_SECS="${JOURNEY_CLASS_KILL_AFTER_SECS:-30}"

# ---------------------------------------------------------------------------
# Issue #1056: NO-OUTPUT (silence) watchdog — hard-kill a WEDGED child.
#
# The wall-clock `timeout` cap above bounds the TOTAL duration of a class
# attempt, but it is a coarse backstop: a `connectedDebugAndroidTest` that WEDGES
# and emits ZERO output still burns the full `JOURNEY_CLASS_TIMEOUT_SECS` ceiling
# (and its retry burns it again) before the summary can be written. In run
# 28307686762 a wedged child that emitted nothing for ~24 min let the job-level
# 95-min wall CANCEL the whole step — beating the #835 STEP_TIMEOUT classifier so
# the verdict was LOST (no trustworthy summary.md at all).
#
# A wedged instrumentation child is SILENT while it hangs, so silence is the
# earliest, most reliable wedge signal — far tighter than the wall cap. This
# watchdog streams every attempt's combined output live AND resets a silence
# timer on each line; the instant an attempt emits nothing for
# JOURNEY_NO_OUTPUT_TIMEOUT_SECS it hard-kills the child tree (TERM then a SIGKILL
# backstop) and reports a uniform `timeout` exit (124). That (a) frees budget
# faster than the coarse wall cap so more classes reach a verdict, and (b)
# GUARANTEES the #835 STEP_TIMEOUT classifier always fires a trustworthy summary
# well before the job-level wall — the verdict is never lost to a job cancel.
#
# Default 300s of silence sits BELOW the 420s wall cap (so a silent wedge dies on
# silence, not on the coarse wall bound) yet comfortably ABOVE any legitimate
# quiet stretch (APK install + `am instrument` spin-up + the first generous
# `pocketshellCi=true` E2E wait all emit progress inside ~2 min). The window is
# always clamped to the wall cap so a tiny cap in the self-test still governs.
JOURNEY_NO_OUTPUT_TIMEOUT_SECS="${JOURNEY_NO_OUTPUT_TIMEOUT_SECS:-300}"

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

# run_bounded <wall_cap_secs> <cmd...> — issue #1056.
#
# Runs <cmd> bounded by BOTH a wall-clock cap (the existing #835 `timeout`) AND a
# NO-OUTPUT (silence) watchdog. The command's combined output is written to a FIFO
# that this function reads line-by-line with a per-read timeout equal to the
# silence window; every line is echoed live (so CI logs still stream) and resets
# the silence timer. If the command emits NOTHING for JOURNEY_NO_OUTPUT_TIMEOUT_SECS
# while still alive, the wrapping `timeout` process (and thus the wedged child) is
# hard-killed — TERM, then a SIGKILL backstop — and the function returns 124, the
# SAME uniform `timeout` exit the wall cap uses, so every downstream caller
# (LAST_RUN_CLASS_TIMEOUT_HIT, is_timeout_rc, needs_gradle_cleanup_after_class_abort)
# treats a silence-kill identically to a wall-cap timeout with no extra branching.
#
# Return codes:
#   * 124 — the silence watchdog fired (wedged, no output), OR the wall `timeout`
#     hit its ceiling (GNU timeout's own 124).
#   * 137 — the wall `timeout` SIGKILL backstop fired (child ignored TERM).
#   * <cmd rc> — the command finished on its own.
#
# The wall `timeout` still owns the total-duration ceiling; this wrapper only ADDS
# the tighter silence bound. A command that streams output steadily runs until the
# wall cap; a command that WEDGES silently dies at the silence bound, well before
# the wall cap or the job-level 95-min wall.
run_bounded() {
  local wall_cap="$1"; shift
  local no_output="$JOURNEY_NO_OUTPUT_TIMEOUT_SECS"
  # Clamp the silence window to the wall cap so a tiny cap (self-test) still
  # governs, and never let a mis-set 0/negative window disable the read timeout.
  (( no_output <= 0 || no_output > wall_cap )) && no_output="$wall_cap"

  local tmpdir fifo to_pid rc read_rc line rfd killer
  tmpdir="$(mktemp -d)"
  fifo="$tmpdir/out"
  mkfifo "$fifo"

  # Start the command under the wall-clock `timeout` (TERM + SIGKILL backstop),
  # combined output to the FIFO. Backgrounded: `$!` is the `timeout` pid, which we
  # signal on a silence breach so `timeout` forwards TERM to the wedged child.
  timeout --signal=TERM --kill-after="$JOURNEY_CLASS_KILL_AFTER_SECS" "${wall_cap}s" \
    "$@" > "$fifo" 2>&1 &
  to_pid=$!

  # Reader = the silence watchdog. `read -t <window>` returns >128 on a silence
  # timeout, 1 on EOF (command finished OR the wall `timeout` closed the pipe).
  # Capture the read's OWN exit code at the break — a bare `read_rc=$?` after the
  # `while` would capture the WHILE loop's status (0 when the condition is false
  # on the first eval, i.e. a silence timeout with no body run), silently losing
  # the >128 timeout code.
  exec {rfd}<"$fifo"
  read_rc=0
  while :; do
    if IFS= read -r -t "$no_output" -u "$rfd" line; then
      printf '%s\n' "$line"
    else
      read_rc=$?
      break
    fi
  done
  exec {rfd}<&-

  if (( read_rc > 128 )); then
    # Silence bound breached while the child is still alive: hard-kill the tree.
    echo "JOURNEY_NO_OUTPUT_WATCHDOG: no output for ${no_output}s — hard-killing wedged connectedDebugAndroidTest (issue #1056)" >&2
    kill -TERM "$to_pid" 2>/dev/null || true
    ( sleep "$JOURNEY_CLASS_KILL_AFTER_SECS"; kill -KILL "$to_pid" 2>/dev/null || true ) &
    killer=$!
    wait "$to_pid" 2>/dev/null
    kill "$killer" 2>/dev/null || true
    wait "$killer" 2>/dev/null || true
    rm -rf "$tmpdir"
    return 124
  fi

  wait "$to_pid" 2>/dev/null
  rc=$?
  rm -rf "$tmpdir"
  return "$rc"
}

# run_class <FQCN> — runs ONE journey class as its own gradle connected-test
# invocation and returns gradle's exit code (0 == that class passed). Running
# one class per invocation (rather than all classes comma-joined into a single
# invocation) is what makes the per-class retry below clean: the gradle exit
# code IS the per-class verdict, with no XML parsing or fragile result-file
# scraping required. It ALSO preserves per-class isolation — each class runs in
# its own on-device `am instrument` process, so the #1042 grace-state leak across
# test methods in one instrumentation process cannot occur.
#
# Issue #835 (REOPENED): the Gradle DAEMON is reused across invocations (no
# `--no-daemon` flag — matching the core-terminal proofs below) so the ~30-60s
# fresh-JVM config + spin-up cost is paid ~once instead of ~89×; that cold-Gradle
# tax was the dominant reason the full selection could not finish in budget.
# Per-class isolation is unchanged (still one `class=` FQCN per task). A KILLED
# invocation's lingering daemon/file-hash lock (#918) is still cleared by
# `cleanup_gradle_after_timeout` (`gradlew --stop`) before the retry, so a wedged
# class cannot poison its retry even with the daemon reused.
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
  # Issue #1056: run_bounded adds a NO-OUTPUT (silence) watchdog on top of the
  # #835 wall-clock `timeout` cap, so a wedged child that emits nothing is
  # hard-killed on silence — well before the wall cap or the job-level wall.
  run_bounded "$cap" \
    "$GRADLEW" :app:connectedDebugAndroidTest \
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

# run_ct_class <FQCN> — runs ONE core-terminal proof class as its own
# :shared:core-terminal:connectedDebugAndroidTest invocation, wrapped in the
# SAME budget-capped `timeout` discipline as run_class (issue #835 REOPENED).
#
# Before this, the six core-terminal proofs below invoked Gradle with NO
# `timeout` wrapper and NO budget cap. That is the PROXIMATE cause of the
# 95-min CANCEL in run 28307686762: the #796 output-burst-IME proof
# (CodexOutputBurstImeMainThreadProofTest) HUNG on the degraded swiftshader AVD
# at minute ~69 and, being unbounded, ran until the workflow JOB cap SIGKILLed
# the whole step — producing a "cancelled" with NO trustworthy summary.md (the
# exact reopen symptom, mis-routed to the #470/#771 branches). Bounding every
# proof at min(per-class cap, budget remaining) — identical to run_class —
# guarantees a hung proof is a CLEAN classifiable cap-hit (rc 124/137) that
# writes a summary, NEVER a job-cap cancel. A killed invocation's Gradle
# daemon/file-hash lock (#918) is cleared by cleanup_gradle_after_timeout, same
# as the journey path.
run_ct_class() {
  local fqcn="$1"
  local remaining cap rc attempt_start attempt_elapsed
  LAST_RUN_CLASS_TIMEOUT_HIT=0
  LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=0
  remaining="$(budget_remaining)"
  cap="$JOURNEY_CLASS_TIMEOUT_SECS"
  (( remaining < cap )) && cap="$remaining"
  if (( cap <= 0 )); then
    LAST_RUN_CLASS_TIMEOUT_HIT=1
    LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=1
    return 124
  fi
  attempt_start=$SECONDS
  # Issue #1056: same NO-OUTPUT (silence) watchdog as the journey path — a wedged
  # core-terminal proof (e.g. the #796 output-burst-IME proof that hung ~24 min
  # in run 28307686762) is hard-killed on silence, never a job-cap cancel.
  run_bounded "$cap" \
    "$GRADLEW" :shared:core-terminal:connectedDebugAndroidTest \
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
echo "    (per-class attempt cap: ${JOURNEY_CLASS_TIMEOUT_SECS}s; workflow job cap: 95 min)"

for fqcn in "${EFFECTIVE_JOURNEY_CLASSES[@]}"; do
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
  run_ct_class "$CORE_TERMINAL_APPEND_BURST_CLASS"
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
  run_ct_class "$CORE_TERMINAL_OUTPUT_BURST_IME_CLASS"
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
  run_ct_class "$CORE_TERMINAL_MULTICHUNK_SEED_CLASS"
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
  run_ct_class "$CORE_TERMINAL_AGENT_LINK_AFFORDANCE_CLASS"
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
  run_ct_class "$CORE_TERMINAL_REATTACH_REPAINT_CLASS"
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

# ---------------------------------------------------------------------------
# v0.4.17 RELEASE-BLOCKER: the terminal affordance-overlay UNBOUNDED-height
# measure crash (CI run 28184338389, reproduced 4/4). The draw-only overlays
# (SmartSelectionAffordanceOverlay / FilePathOverlay / AgentPaneAffordanceOverlay
# / EngineCommandOverlay) laid out at the raw `constraints.maxHeight` — fine
# under a normal bounded measure, but the overlay sits inside the terminal pane,
# itself inside the `TmuxTerminalPager` (Pager), whose lookahead/measure pass
# runs with an UNBOUNDED (`Int.MAX_VALUE`) max height. That overflowed `layout()`
# → `IllegalStateException: Size(1070 x 2147483647) is out of range`, which tore
# down the whole back-to-picker / multi-session-switch journey. This proof
# composes EACH of the four PRODUCTION overlays under EXACTLY that crash
# constraint (`Constraints(maxWidth=1070, maxHeight=Infinity)`) and HARD-asserts
# every one lays out at a FINITE height (class coverage, no self-skip). RED on
# the un-fixed overlays (the activity dies with the out-of-range crash); GREEN
# after the `layoutOverlayBounded` clamp. Uses NO Docker fixture (in-process
# Compose UI test). It lives under com.pocketshell.core.terminal.selection.
CORE_TERMINAL_OVERLAY_UNBOUNDED_CLASS="com.pocketshell.core.terminal.selection.TerminalOverlayUnboundedMeasureCrashTest"
OVERLAY_UNBOUNDED_STATUS="PASS"

run_core_terminal_overlay_unbounded() {
  run_ct_class "$CORE_TERMINAL_OVERLAY_UNBOUNDED_CLASS"
}

if budget_exhausted; then
  STEP_TIMEOUT_HIT=1
  OVERLAY_UNBOUNDED_STATUS="SKIPPED"
  echo "JOURNEY_STEP_TIMEOUT: skipping overlay-unbounded-measure proof — suite budget exhausted (issue #835 / #470 stall)"
else
  echo "=========================================================="
  echo ">>> CORE-TERMINAL OVERLAY-UNBOUNDED-MEASURE PROOF: $CORE_TERMINAL_OVERLAY_UNBOUNDED_CLASS (attempt 1)"
  echo "=========================================================="
  overlay_unbounded_start=$SECONDS
  if run_core_terminal_overlay_unbounded; then
    echo "OVERLAY_UNBOUNDED_PASS: passed on attempt 1 (elapsed $((SECONDS - overlay_unbounded_start))s)"
  else
    echo ">>> OVERLAY-UNBOUNDED-MEASURE PROOF FAILED attempt 1 — retrying once (CI-AVD infra flake / sibling-install)"
    if run_core_terminal_overlay_unbounded; then
      echo "OVERLAY_UNBOUNDED_FLAKE_RECOVERED: passed on retry (attempt 2)"
    else
      echo "OVERLAY_UNBOUNDED_FAILED: overlay-unbounded-measure proof failed twice"
      OVERLAY_UNBOUNDED_STATUS="FAIL"
    fi
  fi
fi

# Issue #1203 surface-only-black recovery proof (core-terminal androidTest).
# v0.4.23 shipped a 6th black-frame class, `surface_black_model_intact` (#1192):
# the terminal MODEL grid matches tmux (the model-vs-tmux heal oracle says
# healthy and never fires), but the on-screen SURFACE is confirmed black — the
# View's own emulator binding (mEmulator) is null while the session still holds
# a live emulator, so every onDraw takes the BLACK fallback. The manual Redraw /
# stale-render heal only RESEED the MODEL (capture-pane → appendRemoteOutput →
# forceFullRepaint), which restores NOTHING here (the model already matches
# tmux) → "Redraw doesn't work". This proof drives a REAL TerminalView through
# the REAL onDraw + the REAL FramePaintObserver seam: RED with mEmulator == null
# (the black fallback paints, observer reports paintedEmulatorContent=false),
# then forceSurfaceRepaint() re-binds the emulator and GREEN — the next real
# onDraw paints CONTENT (observer reports true). Proves the surface re-bind is
# the load-bearing recovery, not another invalidate(). Uses NO Docker fixture
# (in-process render test). Lives under com.termux.view.
CORE_TERMINAL_SURFACE_REPAINT_CLASS="com.termux.view.TerminalViewForceSurfaceRepaintInstrumentedTest"
SURFACE_REPAINT_STATUS="PASS"

run_core_terminal_surface_repaint() {
  run_ct_class "$CORE_TERMINAL_SURFACE_REPAINT_CLASS"
}

if budget_exhausted; then
  STEP_TIMEOUT_HIT=1
  SURFACE_REPAINT_STATUS="SKIPPED"
  echo "JOURNEY_STEP_TIMEOUT: skipping #1203 surface-repaint proof — suite budget exhausted (issue #835 / #470 stall)"
else
  echo "=========================================================="
  echo ">>> CORE-TERMINAL #1203 SURFACE-REPAINT PROOF: $CORE_TERMINAL_SURFACE_REPAINT_CLASS (attempt 1)"
  echo "=========================================================="
  surface_repaint_start=$SECONDS
  if run_core_terminal_surface_repaint; then
    echo "SURFACE_REPAINT_PASS: passed on attempt 1 (elapsed $((SECONDS - surface_repaint_start))s)"
  else
    echo ">>> SURFACE-REPAINT PROOF FAILED attempt 1 — retrying once (CI-AVD infra flake / sibling-install)"
    if run_core_terminal_surface_repaint; then
      echo "SURFACE_REPAINT_FLAKE_RECOVERED: passed on retry (attempt 2)"
    else
      echo "SURFACE_REPAINT_FAILED: #1203 proof failed twice"
      SURFACE_REPAINT_STATUS="FAIL"
    fi
  fi
fi

# --------------------------------------------------------------------------
# Issue #1233 shell-pane single-snapshot affordance-scan proof (core-terminal
# androidTest). A shell / non-agent pane used to run FOUR independent per-frame
# on-main full-viewport affordance scanners (URL + SmartSelection + FilePath +
# EngineCommand), each re-extracting the whole viewport AND running its regex on
# Main every frame — a milder cousin of the #796 ANR on a high-throughput
# streaming pane with the keyboard up. The fix consolidates them into ONE
# ShellPaneAffordanceOverlay that extracts the viewport ONCE per coalesced frame
# and runs the enabled passes OFF-main (the #871 single-snapshot split applied to
# all four shell scanners). This proof asserts BOTH (a) URL / file-path /
# engine-command affordances stay tappable on a real shell pane (behavior
# UNCHANGED), and (b) the LOAD-BEARING property — the scan runs OFF the main
# thread and dispatches ONE debounced single-snapshot scan per frame, NOT four
# on-main scans per tick. Because #1233 is adjacent to the #796/#803/#866/#871
# terminal-ANR class it runs alongside those proofs in this per-push gate. Uses
# NO Docker fixture (in-process Compose UI test).
CORE_TERMINAL_SHELL_SNAPSHOT_CLASS="com.pocketshell.core.terminal.ui.ShellPaneAffordanceSingleSnapshotProofTest"
SHELL_SNAPSHOT_STATUS="PASS"

run_core_terminal_shell_snapshot() {
  run_ct_class "$CORE_TERMINAL_SHELL_SNAPSHOT_CLASS"
}

if budget_exhausted; then
  STEP_TIMEOUT_HIT=1
  SHELL_SNAPSHOT_STATUS="SKIPPED"
  echo "JOURNEY_STEP_TIMEOUT: skipping #1233 shell-pane single-snapshot proof — suite budget exhausted (issue #835 / #470 stall)"
else
  echo "=========================================================="
  echo ">>> CORE-TERMINAL #1233 SHELL-PANE SINGLE-SNAPSHOT PROOF: $CORE_TERMINAL_SHELL_SNAPSHOT_CLASS (attempt 1)"
  echo "=========================================================="
  shell_snapshot_start=$SECONDS
  if run_core_terminal_shell_snapshot; then
    echo "SHELL_SNAPSHOT_PASS: passed on attempt 1 (elapsed $((SECONDS - shell_snapshot_start))s)"
  else
    echo ">>> SHELL-PANE SINGLE-SNAPSHOT PROOF FAILED attempt 1 — retrying once (CI-AVD infra flake / sibling-install)"
    if run_core_terminal_shell_snapshot; then
      echo "SHELL_SNAPSHOT_FLAKE_RECOVERED: passed on retry (attempt 2)"
    else
      echo "SHELL_SNAPSHOT_FAILED: #1233 proof failed twice"
      SHELL_SNAPSHOT_STATUS="FAIL"
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
      && "$REATTACH_REPAINT_STATUS" == "PASS" && "$OVERLAY_UNBOUNDED_STATUS" == "PASS" \
      && "$SURFACE_REPAINT_STATUS" == "PASS" && "$SHELL_SNAPSHOT_STATUS" == "PASS" ]]; then
  JOURNEY_EXIT=0
  journey_status="PASS"
elif [[ "$STEP_TIMEOUT_HIT" -eq 1 && "${#FAILED_CLASSES[@]}" -eq 0 \
        && "$APPEND_BURST_STATUS" != "FAIL" && "$OUTPUT_BURST_IME_STATUS" != "FAIL" \
        && "$MULTICHUNK_SEED_STATUS" != "FAIL" && "$AGENT_LINK_AFFORDANCE_STATUS" != "FAIL" \
        && "$REATTACH_REPAINT_STATUS" != "FAIL" && "$OVERLAY_UNBOUNDED_STATUS" != "FAIL" \
        && "$SURFACE_REPAINT_STATUS" != "FAIL" && "$SHELL_SNAPSHOT_STATUS" != "FAIL" ]]; then
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
  echo "| ${#EFFECTIVE_JOURNEY_CLASSES[@]} load-bearing journey classes (shard ${JOURNEY_CI_SHARD_INDEX}/${JOURNEY_CI_SHARD_TOTAL}; per-class retry-once) | \`pocketshellCi=true\` | $JOURNEY_EXIT | ${SUITE_ELAPSED}s | **$journey_status** |"
  echo
  echo "Classes exercised:"
  for c in "${EFFECTIVE_JOURNEY_CLASSES[@]}"; do
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
  echo
  echo "Core-terminal v0.4.17 overlay-unbounded-measure crash proof (\`shared:core-terminal\`): **$OVERLAY_UNBOUNDED_STATUS**"
  echo "- \`$CORE_TERMINAL_OVERLAY_UNBOUNDED_CLASS\`"
  echo
  echo "Core-terminal #1203 surface-only-black recovery proof (\`shared:core-terminal\`): **$SURFACE_REPAINT_STATUS**"
  echo "- \`$CORE_TERMINAL_SURFACE_REPAINT_CLASS\`"
  echo
  echo "Core-terminal #1233 shell-pane single-snapshot affordance-scan proof (\`shared:core-terminal\`): **$SHELL_SNAPSHOT_STATUS**"
  echo "- \`$CORE_TERMINAL_SHELL_SNAPSHOT_CLASS\`"
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
  # being SIGKILLed mid-loop by the workflow job cap) is the whole point: an
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
        || "$REATTACH_REPAINT_STATUS" == "FAIL" || "$OVERLAY_UNBOUNDED_STATUS" == "FAIL" ]]; then
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
      echo "- \`$CORE_TERMINAL_REATTACH_REPAINT_CLASS\` (#879 reattach-repaint proof)"
    fi
    if [[ "$OVERLAY_UNBOUNDED_STATUS" == "FAIL" ]]; then
      echo "- \`$CORE_TERMINAL_OVERLAY_UNBOUNDED_CLASS\` (v0.4.17 overlay-unbounded-measure crash proof)"
    fi
  fi
} > "$SUMMARY"

echo "----------------------------------------------------------"
cat "$SUMMARY"
echo "----------------------------------------------------------"

exit "$JOURNEY_EXIT"
