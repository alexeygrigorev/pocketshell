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

INVOCATION_DIR="$PWD"
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

# Issue #1458: durable capture of the journey run's streamed gradle/ddmlib output.
# run_bounded appends every streamed line here (while still streaming live) so the
# workflow "Classify emulator-journey result" step can count the
# `Failed to start Emulator console` storm — the signature of a CPU/RAM-starved
# swiftshader AVD (~100×/shard) — AFTER this emulator step has ended and its stdout
# is gone. A degraded-emulator storm is then classified as an INFRA-ABORT (re-run),
# never a "genuine test failure" reading, so a starved emulator can no longer make
# `main`'s emulator-level health unreadable. Truncated once per suite run so the
# capture reflects THIS attempt only — mirroring how summary.md is overwritten per
# cold-boot attempt (the classifier reads the last attempt's artifacts).
JOURNEY_CONSOLE_LOG="$ARTIFACT_DIR/journey-console.log"
export JOURNEY_CONSOLE_LOG
: > "$JOURNEY_CONSOLE_LOG"

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
# Per-entry tags below are terse (issue refs + one phrase). The verbose
# per-journey rationale that used to live inline was moved out of this array to
# keep this file under the repo size-hygiene threshold (issue #1432 -- the array
# is a registry that legitimately grows with every new journey, so it must not
# be frozen by the oversized-file baseline). Recover the full rationale from git
# history or the linked issue numbers in each tag.
#
# KEEP each entry as a double-quoted FQCN literal (the FQCN_PREFIX proof form or
# a full com-package form) in this INLINE array: check-ci-journey-harness.sh,
# check-test-validity.sh (J1), and test-ci-journey-budget.sh all sed/awk the
# entries out of THIS file, so a new journey is simply one quoted line here.
JOURNEY_CLASSES=(
  "$FQCN_PREFIX.TmuxSessionScreenArtVerifyE2eTest"  # #1362 #1344 the ART dex-verification guard for the TmuxSessionScreen com…
  "$FQCN_PREFIX.DeepLinkSessionSwitchE2eTest"
  "$FQCN_PREFIX.MultiSessionSwitchJourneyE2eTest"  # #710 the CI-AVD wedge was the unbounded VM-clear park teardown; #1537 also owns parkedRuntimeFastSwitchRing...NoStormStaleLease (option-b parked-corpse gate)
  "$FQCN_PREFIX.BackThenOpenSecondSessionReusesWarmLeaseE2eTest"  # #758 the maintainer's priority- back→open-another-session reconne…
  "$FQCN_PREFIX.ColdRestoreGoneSessionNoResurrectE2eTest"
  "$FQCN_PREFIX.LifecycleReattachGoneSessionNoResurrectE2eTest"  # #666 #998 a session killed on the host must NOT be
  "$FQCN_PREFIX.ServerDeathReconnectNoResurrectE2eTest"  # #998 a remote tmux SERVER death (host reboot / OOM
  "$FQCN_PREFIX.AttachmentDropReconnectRecoversE2eTest"  # #1072 attaching a file dropped the live connection AND the
  "$FQCN_PREFIX.ReconnectRepaintE2eTest"
  "$FQCN_PREFIX.BackgroundGraceReconnectE2eTest"  # #754 #959 this class is the per-PR-CI deterministic regression catcher…
  "$FQCN_PREFIX.BoundedGraceSessionHoldJourneyE2eTest"  # #1123 #977 #1021 #215 bounded-grace D21 update — supersedes the / indefinite-hold…
  "com.pocketshell.app.share.ShareTargetE2eTest"  # #727 #657 epic Wave 1 / S1): the share-auth journey pair
  "com.pocketshell.app.share.SharePassphraseDialogE2eTest"
  "com.pocketshell.app.projects.ProfileDiscoveryPickerDockerTest"  # #732 Finding B): the host server-PROFILE discovery journey. The p…
  "com.pocketshell.app.tmux.TmuxInSessionNewSessionCollisionDockerTest"  # #898 reviewer Blocker B): the in-session + New session rich-sheet
  "com.pocketshell.app.composer.PromptComposerImeSquishProofTest"  # #736 #567 #638 #657 follow-up to the review): the composer keyboard-up SQUISH re…
  "com.pocketshell.app.composer.PromptComposerLongDraftCaretVisibleTest"  # #1619/#765 D33/G10: synthetic + real-IME long-draft caret containment above sticky controls
  "com.pocketshell.app.composer.PromptComposerImeTightScreenSquishProofTest"  # #801 #567 #780 #657 the keyboard-up squish on a REALISTIC TIGHT screen
  "com.pocketshell.app.composer.PromptComposerOfflineComposeUsableProofTest"  # #1613 #801 #780 #657 offline compose: the OFFLINE banner + keyboard up must NOT crush the draft — the user must be able to type + queue while disconnected
  "com.pocketshell.app.tmux.Issue887TerminalFixedUnderImeProofTest"  # #887 #457 #780 the terminal must stay FIXED when the soft keyboard
  "com.pocketshell.app.composer.PromptComposerRecordingTimerAndTailTest"  # #870 #880 Issues /: the Android-recognizer recording-surface dogfood p…
  "com.pocketshell.app.composer.PromptComposerLiveTranscriptTwoLineTest"  # #870 the live Android-recognizer transcript was STILL cut on devi…
  "com.pocketshell.app.composer.PromptComposerDegradedSendE2eTest"  # #745 #638 #691 composer Send feedback on a DEGRADED connection. The maintai…
  "com.pocketshell.app.composer.PromptComposerDraftLossOnFinalizeE2eTest"  # #1616/#1620: real-sheet prompt-B preservation plus one status-led queue progress owner with keyboard up
  "com.pocketshell.app.composer.PromptComposerSendPipeliningE2eTest"  # #1621 real-sheet A-active/B-queued FIFO, once-only completion, queue-empty and quiescent-close proof
  "com.pocketshell.app.composer.PromptComposerSendDismissE2eTest"  # #1108 #694 #872 #971 the composer send/dismiss + ATTACHMENT-on-failed-send journe…
  "com.pocketshell.app.composer.PromptComposerOutboundQueueTest"  # #848 #900 audit / ): the foreground outbound queue surface was
  "com.pocketshell.app.tmux.Issue1686QueueDrainWireOracleDockerTest"  # #1686 D33/G4 the composer-queue clog fix on the REAL wire: a false not-Connected label (inline enum Reconnecting + drain-gate sessionLive=false) while the -CC transport is writable — the queued prompt must DRAIN to the real tmux pane (capture-pane), and the transport-alive edge un-parks the storm-stranded backlog
  "$FQCN_PREFIX.OutboundExactlyOnceAcrossFlapE2eTest"  # #1526 S1/S6 D31: delivery-level exactly-once across a mid-send flap (server capture-pane occurrence == 1, composer + keystroke lanes)
  "com.pocketshell.app.composer.PromptComposerUndeliveredRetryTest"  # #1272 #1341 the durable couldn't deliver — retry surface wired into
  "com.pocketshell.app.composer.PromptComposerUndeliveredRetryHiltWiringTest"  # #1272 #1341 round-2, finding 1): the PRODUCTION-WIRING proof. The class…
  "com.pocketshell.app.tmux.AgentQuickReplyBandContainmentProofTest"  # #1235 F2/F3: quick-reply band stays above the IME + doesn't occlude composer/terminal controls (synthetic ime inset)
  "com.pocketshell.app.tmux.AgentApprovalQuickReplyJourneyDockerTest"  # #1235 AC2: real @ps_agent_kind claude detection -> band visible -> tap Yes -> literal y reaches pane -> agent proceeds (tmux capture-pane)
  "com.pocketshell.app.composer.ComposerResendAllImeProofTest"  # #1308 #780 #657 batch Resend all for the queued backlog. The presence/absenc…
  "com.pocketshell.app.composer.PromptComposerDiscardE2eTest"  # #746 #745 #638 #691 the composer Not sent DISCARD + draft session-scoping journe…
  "com.pocketshell.app.composer.PromptComposerRecordingRowFitProofTest"  # #1152 #780 #657 the recording-not-locked composer bottom row overflowed its…
  "com.pocketshell.app.composer.PromptComposerRecordingNoLockJourneyTest"  # #1245 the hands-free lock was REMOVED from the composer entirely
  "com.pocketshell.app.voice.ComposerLauncherHoldSwipeUpJourneyTest"  # #585 the TRUE desired behavior): the ENTRY gesture on the
  "com.pocketshell.app.composer.ComposerDraftDurabilityE2eTest"  # #832 #638 #691 durable per-session composer draft store): a draft authored…
  "$FQCN_PREFIX.SwitchStaleCaptureSessionBodyJourneyE2eTest"  # #687 #686 #658 epic Phase 1 / P1 — the device-truth gate
  "$FQCN_PREFIX.WithinGraceSocketDropForegroundJourneyE2eTest"  # #687 #635 epic Phase 2 / P2 — the device-truth gate
  "$FQCN_PREFIX.ReconnectPartialBlankReseedJourneyE2eTest"  # #687 #553 epic Phase 3 / P3 — the device-truth gate
  "$FQCN_PREFIX.RedrawFullViewportReseedJourneyE2eTest"  # #892 #553 #879 the manual Redraw kebab escape hatch): the maintainer dogfoo…
  "$FQCN_PREFIX.Issue1206PrewarmEmptyCaptureSeedRetryJourneyE2eTest"  # #1206 #780 fresh Claude pane whose FIRST prewarm capture-pane comes bac…
  "$FQCN_PREFIX.NotificationTapLivePinnedForegroundReseedJourneyE2eTest"  # #1181 #1159 #553 #892 BLACK terminal on tapping the connection notification / back…
  "$FQCN_PREFIX.RedrawNonDestructiveNearBlankCaptureE2eTest"  # #989 #892 Redraw must NEVER clear-to-black on a NEAR-BLANK remote capt…
  "$FQCN_PREFIX.ReconnectKebabInPlaceJourneyE2eTest"  # #993 #900 the manual Reconnect kebab escape hatch): the maintainer dog…
  "$FQCN_PREFIX.StaleRenderHealOnLiveTransportJourneyE2eTest"  # #966 #967 / — the DISCRIMINATING render-death-on-a-LIVE-transport jour…
  "$FQCN_PREFIX.AgentAltScreenPartialBlackHealJourneyE2eTest"  # #1138 #966 the SEMI/PARTIAL-black on a live AGENT ALT-SCREEN pane
  "$FQCN_PREFIX.PaneOutputOverflowRecoveryJourneyE2eTest"  # #1205 #780 pane delivery-backlog / seed-gate OVERFLOW must self-heal, n…
  "$FQCN_PREFIX.VoiceSendActivePaneStaysVisibleE2eTest"  # #687 #717 #658 epic slice 2, — reveal/reflow-heal absorbed from ): after
  "$FQCN_PREFIX.MostlyEmptyModelHealsAtRevealJourneyE2eTest"  # #1214 #1208 mostly-empty-model reveal-time leg of the fragments-over-bla…
  "$FQCN_PREFIX.IdleClaudeFragmentsOverBlackRecoveryJourneyE2eTest"  # #1302 #1208 #780 #1297 / — the COMPOSITE recovery journey, the campaign's acceptanc…
  "$FQCN_PREFIX.SendWithAttachmentStaysVisibleE2eTest"  # #1153 #941 send-with-attachment half-black, v0.4.21 dogfood): a compose…
  "$FQCN_PREFIX.PreExistingMultiWindowSeedE2eTest"  # #782 #662 #638 #691 D30 / D28(3)): the pre-existing multi-window `[wN]` switcher…
  "com.pocketshell.app.projects.FolderListWindowCloseAfterStopPollingDockerTest"  # #783 #657 epic / F-wiring): the project-tree torn-down prune journey…
  "com.pocketshell.app.projects.FolderListKillWindowDockerTest"  # #883 window-aware Stop session. The tree shows each tmux WINDOW
  "$FQCN_PREFIX.LongRunningSessionStabilityTest#steadyForegroundHoldDoesNotFlapTransportEveryTenSeconds"  # #795 #794 #638 #691 follow-up to ): the ~90s STEADY-HOLD no-flap proof. fixed
  "$FQCN_PREFIX.AttachmentNoReconnectE2eTest"  # #785 #687 EPIC slice 3 / D28(3)): the attachment - NO-reconnect
  "$FQCN_PREFIX.SendNoReconnectE2eTest"  # #872 the send-path sibling of AttachmentNoReconnectE2eTest. AC1's…
  "$FQCN_PREFIX.StableWifiNoSpuriousReconnectE2eTest"  # #875 #874 #879 #548 Angle C, cross-links /): the STABLE-WIFI no-spurious-reconne…
  "$FQCN_PREFIX.BareNetworkLossRestoreReconnectE2eTest"  # #997 #995 #981 #780 network sub-cluster after /): the BARE-NETWORK-LOSS proactiv…
  "$FQCN_PREFIX.MobileSpuriousReconnectE2eTest"  # #1042 #780 #548 stop SPURIOUS reconnects on mobile/cellular when the link/so…
  "$FQCN_PREFIX.Issue1078DeadSocketHandoffRedialJourneyE2eTest"  # #1082 #1078 #843 #780 the END-TO-END WIFI→CELLULAR dead-socket handoff journey — t…
  "$FQCN_PREFIX.RealisticWifiStabilityNoSpuriousReconnectE2eTest"  # #970 #964 #1081 #780 the realistic-wifi STABILITY regression gate — the durable p…
  "com.pocketshell.app.tmux.Issue796ComposerOpenTerminalScopeProofTest"  # #796 #638 #657 H3): the composer-open - terminal-relayout collision regress…
  "com.pocketshell.app.tmux.Issue1085RecompositionScopeProofTest"  # #1085 #638 #657 F3): the voice-first recomposition-jank scope proof. The mai…
  "$FQCN_PREFIX.ComposerAlwaysPresentSwitchJourneyE2eTest"  # #810 #657 #638 the composer-launcher ALWAYS-PRESENT switch journey. The mai…
  "com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#conversationTapIsHonouredBeforeDetectionLands"  # #778 #848 /): tapping Conversation while live detection is still null
  "com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#agentOpensOnDefaultViewAndIsNotYankedMidSessionShellGetsNoConversationRow"  # #818 #815 #878 #807 //): an agent pane must OPEN on the user's
  "com.pocketshell.app.tmux.AgentConversationReconnectDockerTest#reconciledPresumedAgentWithDroppedRowReseedsConversationPlaceholderOnDevice"  # #874 #878 #894 #975 residual black-screen, conversation-source area — sibling of…
  "com.pocketshell.app.tmux.ConversationToggleVisibleForLiveAgentInShellRecordedSessionDockerTest"  # #962 #975 #819 #821 /, conversation-source area — sibling of //, D31/D32): a
  "com.pocketshell.app.tmux.ConversationStaysReachableAfterDetectionDropsDockerTest"  # #1057 #975 #780 #894 the conversation stays REACHABLE after live detection drops…
  "com.pocketshell.app.tmux.ConversationTuiCommandJourneyDockerTest"  # #1207 #975 #894 reviewer BLOCKED-G4 residual, D33/G10): the composer-send IN…
  "com.pocketshell.app.tmux.TmuxComposerLauncherNarrowFontClipProofTest"  # #813 the composer-launcher NARROW / LARGE-FONT clip proof. The ma…
  "com.pocketshell.app.tmux.ComposerUnsentBadgeContainmentProofTest"  # #1531 RC1: the docked-launcher UNSENT badge is present + fully within the window (containment, not assertIsDisplayed) on both the chip-cluster and conversation launchers, pending + failed states — a stuck send is SEEN, not silently dropped
  "com.pocketshell.app.tmux.TmuxSessionVoiceSurfaceUiTest#heldTerminalHidesQuickCommandBand_keepsComposerLauncher"  # #1672 the maintainer's Reconnecting/Attaching report: while the terminal is HELD the quick-command band (git status/tmux ls/…) + primary cluster are ABSENT (hidden, not disabled); the composer launcher stays present (#810)
  "com.pocketshell.app.tmux.TmuxSessionVoiceSurfaceUiTest#liveTerminalShowsQuickCommandBand"  # #1672 the companion Live-state assertion: the full quick-command band RETURNS when the terminal is no longer held

  "com.pocketshell.app.tmux.TmuxChromeConversationTogglePresentTest"  # #1320 #962 #975 #1057 the Terminal/Conversation TOGGLE-CLIP regression guard — the…
  "com.pocketshell.app.tmux.ForwardingPillPresenceTest"  # #1487 the always-visible port-forwarding pill: present + assertNodeFullyWithinRoot contained + no overlap with the Terminal/Conversation toggle when active (single/multiple/restoring/long-title/extreme-width yield), and NO pill node (no gap) when inactive
  "com.pocketshell.app.cards.SessionCardFeedRegistryTest"  # #859 B): the typed-card RENDERER REGISTRY proof. The session feed
  "com.pocketshell.app.tmux.TmuxConnectingStatesScreenshotTest"  # #750 3rd occurrence — D31 durable-fix gate): the tmux non-Connect…
  "com.pocketshell.app.tmux.SessionSurfaceReconnectWrapperTest"  # #823 the manual-reconnect AFFORDANCE proof. The maintainer's ask…
  "com.pocketshell.app.hosts.HostResumeLastSessionE2eTest"  # #1239 the host-card one-tap Resume last session affordance proof…
  "com.pocketshell.app.hosts.FirstHostWizardE2eTest"  # #1243 the guided first-run wizard end-to-end (empty-state → host details → REAL agents:2222 test-connect → setup handoff) + the bad-port FAILURE path (actionable error + Retry/Edit recovery, no dead end)
  "com.pocketshell.app.tmux.TmuxConversationBottomComposerScreenshotTest"  # #868 #628 the previous-session-toggle REMOVED regression guard (AC4…
  "com.pocketshell.app.conversation.ConversationImageContentRenderTest"  # #842 surface transcript images): rendered-UI proof that an image…
  "com.pocketshell.app.conversation.ConversationTuiCommandNoticeRenderTest"  # #1207 black-screen audit UX leg, D33/G10): a fresh Claude session
  "com.pocketshell.app.portfwd.PortForwardDuplicateKeyCrashTest"  # #931 D33/G10 reproduce-first): the port-forward panel LazyColumn…
  "com.pocketshell.app.portfwd.PortForwardDuplicateKeyRenderTest"
  "com.pocketshell.app.portfwd.ForwardingNetworkRideThroughE2eTest"  # #1058 #843 #981 #997 audit R1, trigger T11 / coverage gap C1, D33/G10
  "com.pocketshell.app.portfwd.ForwardingNotificationE2eTest#sessionPinnedForForward_hasExactlyOneForwardNotification_andStopTearsDownTunnels"  # #1202 #1198 D31/D32 durable-fix gate, on-device regression): the port-fo…
  "$FQCN_PREFIX.SilentDropSyntheticSeamJourneyE2eTest"  # #792 #822 #823 #964 epic Slice D, /V7a + — D31 durable-fix gate
  "$FQCN_PREFIX.CleanOutageReattachResilienceE2eTest"  # #833 a CLEAN sustained outage (clean FIN/connection-refused for t…
  "$FQCN_PREFIX.ReconnectStormLivelockE2eTest"  # #1652 #1610 #1539 #1632 #1633 the LIVELOCK proof: N>=5 consecutive passive-grace cycles on the real path with a stalled tail + healthy dial; no handshaken transport killed, the counter walks, the machine terminates. Also in the pre-release confidence gate (red blocks a tag).
  "$FQCN_PREFIX.SlowClassifyKeepsSharedLeaseJourneyDockerTest"  # #1641 #1670 #1610 the storm-ENTRY-edge: a foreign-pane agents-kind classify slower than its 3.5s bound (real >3.5s host delay, #847/G10 non-happy fixture) must NOT close the shared `-CC` lease — status stays Connected, marker round-trips, cause-trail breadcrumb transportClosed=false
  "$FQCN_PREFIX.BackgroundResumeSocketDeathE2eTest"  # #1098 #173 item 3): the genuinely-UNRECOVERABLE-host counterpart of the…
  "$FQCN_PREFIX.Issue895SwitchWhileBlackBandJourneyE2eTest"  # #895 switch-while-black freeze): the R1 trigger — a transport dro…
  "com.pocketshell.app.projects.ManualKindWriterDockerTest"  # #821 Epic Slice 1: manual session classification (Option B +
  "com.pocketshell.app.projects.AgentRecordedKindReadBackDockerTest"  # #852 #848 epic ): one gated recorded-kind read-back journey replacing…
  "com.pocketshell.app.projects.AgentStateReadBackDockerTest"  # #1237/#1570 agent-state chip read-back: stamps @ps_agent_state on the agents fixture, reads via listSessionsWithFolder, asserts idle/waiting resolve + stale/absent -> Unknown + a working Codex with the REAL ISO-8601 hook timestamp -> Working, not Idle (no new port, no self-skip)
  "com.pocketshell.app.projects.AgentStateChipHostCardScreenshotTest"  # #1237 AC4: on-device HostCard chip render evidence (captureToImage)
  "com.pocketshell.app.projects.ProfileChipRelaunchDockerTest"  # #889 D31/D32 G10): the FALSE z.ai chip on a session
  "com.pocketshell.app.projects.AgentLaunchVersionMismatchHintE2eTest"  # #853 #848 #759 epic ): the OUTDATED-host agent-launch friendly-hint guard…
  "com.pocketshell.app.projects.FolderListOldCliHydrateDockerTest"  # #849 #848 #847 epic ): the OLD-CLI cold-start tree-HYDRATE connect proof
  "com.pocketshell.app.projects.FolderListBootstrapSkipTreeLoadsDockerTest"  # #849 #848 #847 #788 epic ): the OLD-CLI bootstrap-Skip → tree connect JOURNEY
  "com.pocketshell.app.projects.FolderListClientCacheInstantRenderDockerTest"  # #867 the stale-while-revalidate INSTANT-RENDER journey. A cold co…
  "com.pocketshell.app.projects.FolderListScaleAnrStrictModeDockerTest"  # #965 the SCALE ANR proof — the folder list at
  "com.pocketshell.app.projects.FolderListDurableTreeDaemonDockerTest"  # #839 #821 #837 epic workstream C — the durable-tree daemon journey): the
  "$FQCN_PREFIX.AgentSubmitAckJourneyE2eTest"  # #869 the composer-Send ACK-GATE on-device submit JOURNEY — the lo…
  "com.pocketshell.app.projects.FolderListScreenE2eTest#profiledSessionsShowProfileChipDefaultSessionsDoNot"  # #881 #858 follow-up to — D32 G9): the agent-PROFILE on-device journeys
  "com.pocketshell.app.projects.SessionKindPickerUiTest"
  "com.pocketshell.app.projects.SessionTypePickerNameFieldUiTest"  # #1184 D32 G9): the new-session picker's editable Session name fiel…
  "com.pocketshell.app.snippets.SnippetTemplateDialogButtonsTest"  # #863 D32 G9): per-criterion gate for the residual-TextButton swee…
  "com.pocketshell.app.crash.ConfirmDeleteAllDialogButtonsTest"
  "com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#goToDialogGoButtonDispatchesPath"
  "com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#goToDialogCancelDismissesWithoutNavigating"
  "com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#failedStateShowsMessageAndRetry"
  "com.pocketshell.app.fileexplorer.FileExplorerScaffoldTest#successTransferShowsDismissibleBanner"
  "com.pocketshell.app.fileviewer.FileViewerScaffoldTest#cannotPreviewStateShowsMessageAndRetry"
  "com.pocketshell.app.fileviewer.FileViewerScaffoldTest#cannotPreviewWithLocateCandidatesOffersOpenRows"
  "com.pocketshell.app.fileviewer.FileViewerScaffoldTest#markdownRenderedPipeTableShowsCellsNotRawDelimiter"  # #921 D32 G9): rendered Markdown shows GFM pipe tables as
  "com.pocketshell.app.bootstrap.HostReadyPrimaryActionTest"  # #885 #117 D32 G9): the post-update Host ready success sheet must
  "com.pocketshell.app.bootstrap.HostNotificationsReadinessTest"  # #1236 D26 / D32 G9): per-host notification readiness + the
  "$FQCN_PREFIX.StrictModeMainThreadIoDetectorE2eTest"  # #933 #928 #931 #926 ============================================================…
  "com.pocketshell.app.cards.SessionChecklistPushJourneyDockerTest"  # #949 #859 ============================================================…
  "com.pocketshell.app.projects.CliVersionMismatchBannerUpdateButtonTest"  # #947 #641 #567 #657 the host-version-mismatch banner's one-tap Update button — t…
  "com.pocketshell.app.notifications.NoNotificationPromptOnAppOpenE2eTest"  # #1509 the reported symptom: app open must NOT pop POST_NOTIFICATIONS (grant REVOKED, focus != GrantPermissionsActivity)
  "com.pocketshell.app.projects.FolderListHostOutdatedTreeVersionDaemonDockerTest"  # #1509 G10: real agents-daemon (2239) old cli_version through tree envelope -> banner once, background, after tree shown
  "$FQCN_PREFIX.LaunchNoMainThreadRoomReadE2eTest"  # #951 #928 #305 D2 — launch ANR / crash-loop). The reproduce-first end-to-en…
  "com.pocketshell.app.diagnostics.ConnectionLogHostMirrorReconnectDockerTest"  # #972 #969 D33/G4/G10: prove the host connection-log mirror's VM GLUE o…
  "com.pocketshell.app.env.EnvScreenE2eTest"  # #1094 #1092 durability for ): the env edit/add on-device journeys. The
  "com.pocketshell.app.tmux.TmuxResizeSessionE2eTest#cachedSizeReplayRestoresFullWindowAndAgentPaneIsNotCut"  # #1169 #285 Codex/agent pane rendered CUT: tmux window resized too small
  "com.pocketshell.app.usage.UsageGlancePillE2eTest"  # #1241 #418 the landing app-bar usage GLANCE PILL on-device proof. The
  "com.pocketshell.app.usage.UsageThresholdNotificationE2eTest"  # #1618 both relative countdown + absolute reset time reach the real status-bar notification
  "com.pocketshell.app.usage.Usage1318StrictSchemaRenderE2eTest"  # #1318 on-device render acceptance for the quse-v0.0.9 strict-schem…
)

# CI-matrix journey class selection and banner logging live in a sourced helper.
# Keep the literal JOURNEY_CLASSES array above in this file: harness guards parse
# scripts/ci-journey-suite.sh as the per-push allowlist.
# shellcheck source=scripts/ci-journey-class-selection-functions.sh
CI_JOURNEY_CLASS_SELECTION_HELPER="$REPO_ROOT/scripts/ci-journey-class-selection-functions.sh"
if [[ ! -f "$CI_JOURNEY_CLASS_SELECTION_HELPER" && -f "$INVOCATION_DIR/scripts/ci-journey-class-selection-functions.sh" ]]; then
  CI_JOURNEY_CLASS_SELECTION_HELPER="$INVOCATION_DIR/scripts/ci-journey-class-selection-functions.sh"
fi
source "$CI_JOURNEY_CLASS_SELECTION_HELPER"

select_effective_journey_classes
print_journey_class_selection

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

# Budget/run_bounded/run_class/shard_run implementations live in a sourced helper.
# Keep the default assignments above in this file: scripts/test-ci-journey-budget.sh
# parses those literal lines as part of the #835/#1056 guard.
# shellcheck source=scripts/ci-journey-budget-functions.sh
CI_JOURNEY_BUDGET_HELPER="$REPO_ROOT/scripts/ci-journey-budget-functions.sh"
if [[ ! -f "$CI_JOURNEY_BUDGET_HELPER" && -f "$INVOCATION_DIR/scripts/ci-journey-budget-functions.sh" ]]; then
  CI_JOURNEY_BUDGET_HELPER="$INVOCATION_DIR/scripts/ci-journey-budget-functions.sh"
fi
source "$CI_JOURNEY_BUDGET_HELPER"

if [[ "${POCKETSHELL_JOURNEY_SHARD:-0}" == "1" ]]; then
  if shard_run; then
    echo "Sharded journey run: PASS"
    exit 0
  else
    echo "Sharded journey run: FAIL (see shard-lane-*.log under $ARTIFACT_DIR)"
    exit 1
  fi
fi

# Journey class retry loop and result buckets live in a sourced helper. The
# helper preserves the existing budget log phrase "workflow job cap: 95 min",
# which scripts/test-ci-journey-budget.sh pins as part of the #835 guard.
# shellcheck source=scripts/ci-journey-class-loop-functions.sh
CI_JOURNEY_CLASS_LOOP_HELPER="$REPO_ROOT/scripts/ci-journey-class-loop-functions.sh"
if [[ ! -f "$CI_JOURNEY_CLASS_LOOP_HELPER" && -f "$INVOCATION_DIR/scripts/ci-journey-class-loop-functions.sh" ]]; then
  CI_JOURNEY_CLASS_LOOP_HELPER="$INVOCATION_DIR/scripts/ci-journey-class-loop-functions.sh"
fi
source "$CI_JOURNEY_CLASS_LOOP_HELPER"

run_journey_classes_with_retry

# Core-terminal proof selectors/runners live in a sourced helper so this driver
# keeps the proof execution and summary wiring local while the class cluster is
# isolated.
# shellcheck source=scripts/ci-journey-core-terminal-functions.sh
CI_JOURNEY_CORE_TERMINAL_HELPER="$REPO_ROOT/scripts/ci-journey-core-terminal-functions.sh"
if [[ ! -f "$CI_JOURNEY_CORE_TERMINAL_HELPER" && -f "$INVOCATION_DIR/scripts/ci-journey-core-terminal-functions.sh" ]]; then
  CI_JOURNEY_CORE_TERMINAL_HELPER="$INVOCATION_DIR/scripts/ci-journey-core-terminal-functions.sh"
fi
source "$CI_JOURNEY_CORE_TERMINAL_HELPER"

# Result classification and markdown summary generation live in a sourced helper.
# shellcheck source=scripts/ci-journey-summary-functions.sh
CI_JOURNEY_SUMMARY_HELPER="$REPO_ROOT/scripts/ci-journey-summary-functions.sh"
if [[ ! -f "$CI_JOURNEY_SUMMARY_HELPER" && -f "$INVOCATION_DIR/scripts/ci-journey-summary-functions.sh" ]]; then
  CI_JOURNEY_SUMMARY_HELPER="$INVOCATION_DIR/scripts/ci-journey-summary-functions.sh"
fi
source "$CI_JOURNEY_SUMMARY_HELPER"

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

finish_ci_journey_suite
