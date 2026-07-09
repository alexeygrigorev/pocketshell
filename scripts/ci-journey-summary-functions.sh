#!/usr/bin/env bash
# Result classification and markdown summary helpers for scripts/ci-journey-suite.sh.

finish_ci_journey_suite() {
  local journey_status

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
}
