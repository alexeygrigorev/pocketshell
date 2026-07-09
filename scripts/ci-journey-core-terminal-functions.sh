#!/usr/bin/env bash
# Core-terminal proof selectors and runners for scripts/ci-journey-suite.sh.

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
