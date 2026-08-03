#!/usr/bin/env bash
# Core-terminal proof selectors and runners for scripts/ci-journey-suite.sh.
#
# Issue #1827: CORE_TERMINAL_PROOFS at the bottom of this file is the SINGLE
# registry of these proofs. scripts/ci-journey-summary-functions.sh reads it for
# BOTH the suite's red/green condition AND the summary's "Failed BOTH attempts"
# evidence section, so a proof can never turn the suite red without also writing
# the evidence the workflow classifier reads. Registering a new proof is one
# line there; there is no second list to keep in sync.

CORE_TERMINAL_APPEND_BURST_CLASS="com.pocketshell.core.terminal.ui.CodexAppendBurstMainThreadProofTest"
APPEND_BURST_STATUS="PASS"

run_core_terminal_append_burst() {
  run_ct_class "$CORE_TERMINAL_APPEND_BURST_CLASS"
}

CORE_TERMINAL_OUTPUT_BURST_IME_CLASS="com.pocketshell.core.terminal.ui.CodexOutputBurstImeMainThreadProofTest"
OUTPUT_BURST_IME_STATUS="PASS"

run_core_terminal_output_burst_ime() {
  run_ct_class "$CORE_TERMINAL_OUTPUT_BURST_IME_CLASS"
}

CORE_TERMINAL_MULTICHUNK_SEED_CLASS="com.pocketshell.core.terminal.ui.CodexMultiChunkSeedAttachMainThreadProofTest"
MULTICHUNK_SEED_STATUS="PASS"

run_core_terminal_multichunk_seed() {
  run_ct_class "$CORE_TERMINAL_MULTICHUNK_SEED_CLASS"
}

CORE_TERMINAL_AGENT_LINK_AFFORDANCE_CLASS="com.pocketshell.core.terminal.ui.AgentPaneLinkAffordanceOffMainProofTest"
AGENT_LINK_AFFORDANCE_STATUS="PASS"

run_core_terminal_agent_link_affordance() {
  run_ct_class "$CORE_TERMINAL_AGENT_LINK_AFFORDANCE_CLASS"
}

CORE_TERMINAL_REATTACH_REPAINT_CLASS="com.termux.view.TerminalViewReattachLateSubscribeRepaintInstrumentedTest"
REATTACH_REPAINT_STATUS="PASS"

run_core_terminal_reattach_repaint() {
  run_ct_class "$CORE_TERMINAL_REATTACH_REPAINT_CLASS"
}

CORE_TERMINAL_SESSION_BINDING_CLASS="com.pocketshell.core.terminal.ui.TerminalSurfaceComposeIntegrationTest#mountedViewRebindsImmediatelyFromSessionAToBAndRoutesOnlyToB"
SESSION_BINDING_STATUS="PASS"

run_core_terminal_session_binding() {
  run_ct_class "$CORE_TERMINAL_SESSION_BINDING_CLASS"
}

CORE_TERMINAL_OVERLAY_UNBOUNDED_CLASS="com.pocketshell.core.terminal.selection.TerminalOverlayUnboundedMeasureCrashTest"
OVERLAY_UNBOUNDED_STATUS="PASS"

run_core_terminal_overlay_unbounded() {
  run_ct_class "$CORE_TERMINAL_OVERLAY_UNBOUNDED_CLASS"
}

CORE_TERMINAL_SURFACE_REPAINT_CLASS="com.termux.view.TerminalViewForceSurfaceRepaintInstrumentedTest"
SURFACE_REPAINT_STATUS="PASS"

run_core_terminal_surface_repaint() {
  run_ct_class "$CORE_TERMINAL_SURFACE_REPAINT_CLASS"
}

CORE_TERMINAL_SHELL_SNAPSHOT_CLASS="com.pocketshell.core.terminal.ui.ShellPaneAffordanceSingleSnapshotProofTest"
SHELL_SNAPSHOT_STATUS="PASS"

run_core_terminal_shell_snapshot() {
  run_ct_class "$CORE_TERMINAL_SHELL_SNAPSHOT_CLASS"
}

CORE_TERMINAL_HARD_WRAPPED_URL_CLASS="com.pocketshell.core.terminal.ui.WrappedUrlReassemblyInstrumentedTest"
HARD_WRAPPED_URL_STATUS="PASS"

run_core_terminal_hard_wrapped_url() {
  run_ct_class "$CORE_TERMINAL_HARD_WRAPPED_URL_CLASS"
}

# ---------------------------------------------------------------------------
# Issue #1827: THE registry of core-terminal proofs.
#
# Before this existed the suite kept the same proofs in TWO hand-maintained
# lists — the red/green condition and the summary's failed-BOTH-attempts header
# + bullets — and they drifted. `SURFACE_REPAINT_STATUS` (#1203) and
# `SHELL_SNAPSHOT_STATUS` (#1233) reddened the suite but appeared in NEITHER the
# header condition nor the bullets, so a run where only one of them failed twice
# wrote NO failed-both section at all. The workflow's classify step then read
# `first_failure=false`, fell through every RED branch, and typed the shard
# `EMULATOR INFRA UNAVAILABLE` — a genuine failure reported as a green run. That
# is the #1822 outcome reached through the WRITER instead of the parser, and
# #1822's unreadable-item fail-safe cannot catch it because there is no section
# and no item to read.
#
# The cure is structural, not another matched pair of lists: this ONE array is
# the only place a proof is declared, and scripts/ci-journey-summary-functions.sh
# derives the red condition, the per-proof status lines, and the failed-both
# bullets from it. Adding a proof to the suite means adding a line HERE; nothing
# else can fall out of step.
#
# Each entry is `STATUS_VAR|CLASS_VAR|LABEL`:
#   STATUS_VAR — the variable the suite sets to PASS / FAIL / SKIPPED.
#   CLASS_VAR  — the variable holding the proof's FQCN (or `FQCN#method`, which
#                the #1822 bullet parser reads).
#   LABEL      — the summary heading. The failed-both bullet suffix is this
#                label with its leading `Core-terminal ` stripped.
CORE_TERMINAL_PROOFS=(
  "APPEND_BURST_STATUS|CORE_TERMINAL_APPEND_BURST_CLASS|Core-terminal #803 append-burst proof"
  "OUTPUT_BURST_IME_STATUS|CORE_TERMINAL_OUTPUT_BURST_IME_CLASS|Core-terminal #796 output-burst-IME ANR proof"
  "MULTICHUNK_SEED_STATUS|CORE_TERMINAL_MULTICHUNK_SEED_CLASS|Core-terminal #866 multi-chunk seed attach ANR proof"
  "AGENT_LINK_AFFORDANCE_STATUS|CORE_TERMINAL_AGENT_LINK_AFFORDANCE_CLASS|Core-terminal #871 agent-pane link-affordance off-main proof"
  "REATTACH_REPAINT_STATUS|CORE_TERMINAL_REATTACH_REPAINT_CLASS|Core-terminal #879 beyond-grace reattach-repaint proof"
  "SESSION_BINDING_STATUS|CORE_TERMINAL_SESSION_BINDING_CLASS|Core-terminal #959 mounted session-binding proof"
  "OVERLAY_UNBOUNDED_STATUS|CORE_TERMINAL_OVERLAY_UNBOUNDED_CLASS|Core-terminal v0.4.17 overlay-unbounded-measure crash proof"
  "SURFACE_REPAINT_STATUS|CORE_TERMINAL_SURFACE_REPAINT_CLASS|Core-terminal #1203 surface-only-black recovery proof"
  "SHELL_SNAPSHOT_STATUS|CORE_TERMINAL_SHELL_SNAPSHOT_CLASS|Core-terminal #1233 shell-pane single-snapshot affordance-scan proof"
  "HARD_WRAPPED_URL_STATUS|CORE_TERMINAL_HARD_WRAPPED_URL_CLASS|Core-terminal #1955 hard-wrapped URL target proof"
)
