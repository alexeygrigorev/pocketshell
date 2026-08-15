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

CORE_TERMINAL_PIXEL_PROBE_ABANDON_CLASS="com.termux.view.TerminalViewPixelProbeAbandonedCopyInstrumentedTest"
PIXEL_PROBE_ABANDON_STATUS="PASS"

run_core_terminal_pixel_probe_abandon() {
  run_ct_class "$CORE_TERMINAL_PIXEL_PROBE_ABANDON_CLASS"
}

CORE_TERMINAL_SELECTION_VIEWPORT_CLASS="com.termux.view.TerminalSelectionViewportStabilityInstrumentedTest"
SELECTION_VIEWPORT_STATUS="PASS"

run_core_terminal_selection_viewport() {
  run_ct_class "$CORE_TERMINAL_SELECTION_VIEWPORT_CLASS"
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
  "PIXEL_PROBE_ABANDON_STATUS|CORE_TERMINAL_PIXEL_PROBE_ABANDON_CLASS|Core-terminal #2003 abandoned-PixelCopy RenderThread abort proof"
  "SELECTION_VIEWPORT_STATUS|CORE_TERMINAL_SELECTION_VIEWPORT_CLASS|Core-terminal #2154 selection-viewport-stability proof"
)

# ---------------------------------------------------------------------------
# Issue #2110: the proofs are SHARDED across the CI matrix, exactly like the
# journey classes.
#
# THE WASTE THIS REMOVES
# ----------------------
# Every one of these proofs used to run on EVERY matrix leg. The stated reason
# was "a regression in any of them is caught on every leg" — but all six legs
# run the SAME COMMIT, and these are device-independent in-process Compose /
# TerminalView tests with no Docker fixture and no cross-shard state. Six
# identical verdicts on one commit is one verdict plus five copies: ~20-35
# runner-minutes and 4-7 minutes of wall clock on five legs, buying zero
# additional signal. (The measured suite is 206 of ~269 runner-min per push and
# roughly half of that is work repeated six times — see _docs/2026-08-13-test-suite-audit.md §4.)
#
# THE MECHANISM AND WHY THIS SHAPE
# --------------------------------
# The partition reuses the #1862 class-name hash verbatim (journey_class_shard_hash
# over the proof's SELECTOR, i.e. the FQCN or `FQCN#method` the runner is given).
# So a proof's leg depends only on its own name: registering, removing or
# reordering a proof cannot re-roll where the others land, and a red leg right
# after a registration is signal about the new proof rather than noise from
# relocated siblings. The same reasons the index-based partition was rejected for
# journey classes apply here unchanged.
#
# Deliberately NOT chosen:
#   * pinning the proofs to one nominated shard — that leg becomes strictly the
#     slowest by construction and the imbalance grows with every new proof;
#   * a second, proof-specific hash/salt — a second partition to reason about for
#     no benefit; the #1862 mechanism already has the property we need.
#
# WHAT A DEFERRED PROOF IS
# ------------------------
# A proof this leg does not own gets status OTHER_SHARD. That is NOT a pass and
# NOT a skip: it means another leg of the SAME push owns it, so this shard has no
# opinion. `ci_journey_core_terminal_all_passed` therefore treats it as
# "nothing to say" and the red-evidence fail-safe never names it as a cause.
# The property that actually matters — every registered proof runs on exactly one
# leg, and the union over the shipped matrix is the whole registry — is not left
# to inspection: scripts/test-ci-journey-budget.sh drives the REAL suite once per
# shipped shard and asserts disjointness + completeness mechanically, the same way
# it already does for JOURNEY_CLASSES.
#
# Unsharded (total <= 1, i.e. every local run and every self-test that does not
# set the matrix vars) selects ALL proofs, unchanged.
CORE_TERMINAL_OTHER_SHARD_STATUS="OTHER_SHARD"

# select_effective_core_terminal_proofs — partition CORE_TERMINAL_PROOFS by the
# #1862 name hash and mark the proofs this leg does not own as OTHER_SHARD.
# Populates EFFECTIVE_CORE_TERMINAL_PROOFS (registry entries this leg runs).
select_effective_core_terminal_proofs() {
  # Prefer the ALREADY-NORMALISED values select_effective_journey_classes wrote
  # (it clamps a malformed/out-of-range matrix var), so the proofs and the
  # classes can never be partitioned over two different totals.
  local total="${JOURNEY_CI_SHARD_TOTAL:-${POCKETSHELL_JOURNEY_CI_SHARD_TOTAL:-1}}"
  local index="${JOURNEY_CI_SHARD_INDEX:-${POCKETSHELL_JOURNEY_CI_SHARD_INDEX:-0}}"
  [[ "$total" =~ ^[0-9]+$ ]] || total=1
  [[ "$index" =~ ^[0-9]+$ ]] || index=0
  (( total < 1 )) && total=1
  (( index < 0 || index >= total )) && index=0

  EFFECTIVE_CORE_TERMINAL_PROOFS=()
  local entry status_var class_var label owner deferred=0
  for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
    IFS='|' read -r status_var class_var label <<<"$entry"
    if (( total <= 1 )); then
      owner="$index"
    else
      owner="$(journey_class_shard_index "${!class_var}" "$total")"
    fi
    if [[ "$owner" == "$index" ]]; then
      EFFECTIVE_CORE_TERMINAL_PROOFS+=("$entry")
      echo "CORE_TERMINAL_SHARD_SELECTED: ${!class_var} (shard ${owner}/${total}) — ${label}"
    else
      printf -v "$status_var" '%s' "$CORE_TERMINAL_OTHER_SHARD_STATUS"
      deferred=$((deferred + 1))
      echo "CORE_TERMINAL_SHARD_DEFERRED: ${!class_var} (runs on shard ${owner}/${total} this push) — ${label}"
    fi
  done
  echo ">>> CI core-terminal proof shard ${index}/${total} (issue #2110): running ${#EFFECTIVE_CORE_TERMINAL_PROOFS[@]} of ${#CORE_TERMINAL_PROOFS[@]} proofs on this leg, ${deferred} owned by sibling legs of the SAME push (partitioned by the #1862 proof-name hash)"
}

# core_terminal_proof_deferred <STATUS_VAR> — true when this leg does not own the
# proof. The suite gates each proof block on it; the summary treats it as
# "no opinion", never as a pass and never as a failure cause.
core_terminal_proof_deferred() {
  [[ "${!1:-}" == "$CORE_TERMINAL_OTHER_SHARD_STATUS" ]]
}

# announce_core_terminal_deferred <STATUS_VAR> — one greppable line at the point
# in the run where the proof WOULD have executed, so a leg's log reads honestly
# top-to-bottom instead of silently omitting it.
announce_core_terminal_deferred() {
  local wanted="$1" entry status_var class_var label
  for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
    IFS='|' read -r status_var class_var label <<<"$entry"
    [[ "$status_var" == "$wanted" ]] || continue
    echo "CORE_TERMINAL_OTHER_SHARD: skipping ${label} (\`${!class_var}\`) — a sibling leg of this push owns it (issue #2110)"
    return 0
  done
  echo "CORE_TERMINAL_OTHER_SHARD: skipping $wanted — a sibling leg of this push owns it (issue #2110)"
}
