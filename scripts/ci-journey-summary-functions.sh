#!/usr/bin/env bash
# Result classification and markdown summary helpers for scripts/ci-journey-suite.sh.

# Issue #2143: this file is sourced standalone by guards that set only the
# buckets they exercise, so the fixture-wedge bucket declares its own empty
# default here. Under `set -u` an undeclared array would abort summary
# generation — i.e. a change meant to make a red MORE readable would instead
# destroy the summary the classifier depends on.
declare -p FIXTURE_WEDGED_CLASSES >/dev/null 2>&1 || FIXTURE_WEDGED_CLASSES=()

# ---------------------------------------------------------------------------
# Issue #1827: every reader of the core-terminal proof set derives from the ONE
# registry declared in scripts/ci-journey-core-terminal-functions.sh
# (CORE_TERMINAL_PROOFS). The red/green condition and the failed-BOTH-attempts
# evidence section used to be two hand-maintained lists; they drifted, and a
# proof that reddened the suite but had no bullet made the workflow classifier
# read `first_failure=false` and type a genuine failure as EMULATOR INFRA
# UNAVAILABLE (a green run). Deriving both from one list removes the drift by
# construction. `ci_journey_assert_red_has_evidence` below is the belt-and-braces
# backstop for any FUTURE red cause that is not a registered proof.

# ci_journey_core_terminal_all_passed — true iff every proof THIS LEG OWNS is
# PASS. Issue #2110: a proof owned by a sibling leg of the same push carries
# status OTHER_SHARD; this leg has no opinion about it, so it neither reddens
# nor greens this shard. The union property (every proof owned by exactly one
# leg) is asserted mechanically by scripts/test-ci-journey-budget.sh, not by
# hoping each shard checks all eleven.
ci_journey_core_terminal_all_passed() {
  local entry status_var
  for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
    status_var="${entry%%|*}"
    [[ "${!status_var}" == "PASS" || "${!status_var}" == "OTHER_SHARD" ]] || return 1
  done
  return 0
}

# ci_journey_core_terminal_none_failed — true iff no registered proof is FAIL.
# (A SKIPPED proof is not a FAIL; it only happens when the #835 budget was
# exhausted, which writes its own JOURNEY_STEP_TIMEOUT evidence section.)
ci_journey_core_terminal_none_failed() {
  local entry status_var
  for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
    status_var="${entry%%|*}"
    [[ "${!status_var}" != "FAIL" ]] || return 1
  done
  return 0
}

# ci_journey_core_terminal_status_lines — the per-proof status block, in
# registry order. Emitted BEFORE the classifier's arming headers, so it can
# never be mistaken for failing-class evidence.
ci_journey_core_terminal_status_lines() {
  local entry status_var class_var label
  for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
    IFS='|' read -r status_var class_var label <<<"$entry"
    echo
    echo "$label (\`shared:core-terminal\`): **${!status_var}**"
    echo "- \`${!class_var}\`"
    # Issue #2110: say plainly that OTHER_SHARD is "another leg of this push ran
    # it", so nobody reads the absent verdict as a silently dropped proof.
    if [[ "${!status_var}" == "OTHER_SHARD" ]]; then
      echo "- owned by a sibling matrix leg of this push (issue #2110 proof sharding); this shard did not run it"
    fi
  done
}

# ci_journey_core_terminal_failed_bullets — one `- \`FQCN\` (label)` bullet per
# FAILED registered proof, for the failed-BOTH-attempts section. Empty output
# means no registered proof failed.
ci_journey_core_terminal_failed_bullets() {
  local entry status_var class_var label
  for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
    IFS='|' read -r status_var class_var label <<<"$entry"
    [[ "${!status_var}" == "FAIL" ]] || continue
    echo "- \`${!class_var}\` (${label#Core-terminal })"
  done
}

# ci_journey_core_terminal_any_failed — true iff at least one registered proof
# is FAIL, i.e. iff the failed-both section must be written for the proofs.
ci_journey_core_terminal_any_failed() {
  ! ci_journey_core_terminal_none_failed
}

# ci_journey_assert_red_has_evidence <summary> <exit>
#
# THE INVARIANT, enforced after the summary is written: a red suite MUST leave
# an evidence section the workflow classifier can read. The classify step only
# reaches a RED verdict through `JOURNEY_FAILED` / `Failed BOTH attempts` or
# `JOURNEY_STEP_TIMEOUT` / `Suite step time budget exhausted`; a red summary
# carrying neither falls through every RED branch to EMULATOR INFRA UNAVAILABLE,
# which types the shard INFRA, greens the shard job, and reports the run
# successful. That is exactly how #1827 (and, by a different mechanism, #1822)
# laundered a real failure into a green.
#
# The registry above makes that unreachable for the proofs it declares. This
# check is the backstop for anything else that might redden the suite in future:
# it appends a fail-safe failed-both section naming whatever is not PASS, so the
# failure is always CLASSIFIABLE. Fail-safe direction is toward RED, never green.
ci_journey_assert_red_has_evidence() {
  local summary="$1" exit_code="$2"
  (( exit_code != 0 )) || return 0
  if grep -qE 'JOURNEY_FAILED|Failed BOTH attempts|JOURNEY_STEP_TIMEOUT|Suite step time budget exhausted' \
      "$summary" 2>/dev/null; then
    return 0
  fi
  local entry status_var class_var label wrote=0
  {
    echo
    echo "Failed BOTH attempts (\`JOURNEY_FAILED\` — job red):"
  } >> "$summary"
  for entry in "${CORE_TERMINAL_PROOFS[@]}"; do
    IFS='|' read -r status_var class_var label <<<"$entry"
    # Issue #2110: PASS is not a cause, and neither is OTHER_SHARD — a proof a
    # sibling leg of this push owns cannot be the reason THIS leg went red, and
    # naming it would point the investigation at a class this shard never ran.
    [[ "${!status_var}" == "PASS" || "${!status_var}" == "OTHER_SHARD" ]] && continue
    echo "- \`${!class_var}\` (${label#Core-terminal } — status ${!status_var})" >> "$summary"
    wrote=1
  done
  if (( wrote == 0 )); then
    # No named cause at all. Emit an entry the #1822 bullet parser cannot read,
    # which it records as missing evidence and reports `unclassified` — RED.
    echo "- unexplained-suite-red (the suite exited non-zero with no failed-both or budget-timeout evidence — issue #1827)" \
      >> "$summary"
  fi
  echo "JOURNEY_EVIDENCE_FAILSAFE: the suite was red with no classifier-readable evidence section; one was appended (issue #1827)" >&2
}

finish_ci_journey_suite() {
  local journey_status

  SUITE_ELAPSED=$((SECONDS - SUITE_START))

  # The job is red iff at least one class failed BOTH attempts, OR any
  # registered core-terminal proof failed, OR the suite-level budget was
  # exhausted by a #470 stall (issue #835). A budget timeout is NOT green — it
  # still turns the job red — but it is labelled distinctly below so the
  # classifier reports "journey timeout / #470 stall" instead of "EMULATOR INFRA
  # UNAVAILABLE".
  #
  # Issue #1827: the proof half of both conditions is derived from the ONE
  # CORE_TERMINAL_PROOFS registry that also drives the failed-both bullets, so a
  # proof cannot redden the suite while writing no evidence.
  if [[ "${#FAILED_CLASSES[@]}" -eq 0 && "$STEP_TIMEOUT_HIT" -eq 0 ]] \
     && ci_journey_core_terminal_all_passed; then
    JOURNEY_EXIT=0
    journey_status="PASS"
  elif [[ "$STEP_TIMEOUT_HIT" -eq 1 && "${#FAILED_CLASSES[@]}" -eq 0 ]] \
       && ci_journey_core_terminal_none_failed; then
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
  echo "  of which the shared SSH/tmux fixture was wedged (issue #2143 SETUP failure): ${#FIXTURE_WEDGED_CLASSES[@]}"
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
    # Issue #1814: the cold Gradle build is paid ONCE, up front, so the first
    # class on this shard is measured on the same terms as every later class.
    echo "Warm build (issue #1814): **${JOURNEY_WARM_BUILD_STATUS:-not_run}** in ${JOURNEY_WARM_BUILD_ELAPSED:-0}s — paid before the per-class budget clock, charged to the suite budget."
    echo
    echo "Classes exercised:"
    for c in "${EFFECTIVE_JOURNEY_CLASSES[@]}"; do
      echo "- \`$c\`"
    done
    # Issue #1827: one block per registered proof, from CORE_TERMINAL_PROOFS.
    ci_journey_core_terminal_status_lines
    if [[ "${#RECOVERED_CLASSES[@]}" -gt 0 ]]; then
      echo
      echo "Recovered on retry (CI-AVD flake — \`JOURNEY_FLAKE_RECOVERED\`):"
      for c in "${RECOVERED_CLASSES[@]}"; do
        echo "- \`$c\`"
      done
    fi
    # Issue #1814: name every attempt whose outer timeout fired while Gradle was
    # still BUILDING. Such an attempt reports `exit 124 / outer_timeout /
    # raw-junit count=0` — byte-for-byte what a killed mid-journey runner
    # reports — so without this section a build-cost timeout reads exactly like a
    # genuine journey failure (that ambiguity is what made run 30323508796's
    # shard-2 first-class timeout expensive to diagnose).
    #
    # This section is deliberately emitted BEFORE the STEP_TIMEOUT / Failed-BOTH
    # sections: the workflow classifier's `awk` arms on those headers and then
    # prints every following `- ` line, so a section placed after them would be
    # mis-read as a failing class. Its wording also contains none of the
    # classifier's trigger strings, so it can never flip a verdict — it is
    # attribution only.
    if [[ "${#BUILD_PHASE_TIMEOUT_ATTEMPTS[@]}" -gt 0 ]]; then
      echo
      echo "Attempts cut short while Gradle was still BUILDING (\`JOURNEY_BUILD_PHASE_TIMEOUT\` — issue #1814):"
      echo "These attempts hit their per-class wall cap before instrumentation ever started, so they"
      echo "produced no raw JUnit XML. Read them as build-cost timeouts, NOT as journey verdicts and"
      echo "NOT as product defects. The cold build is paid up front (see \"Warm build\" above), so a"
      echo "recurrence here means the build itself outgrew its bound — investigate the build, not the"
      echo "listed journey."
      for c in "${BUILD_PHASE_TIMEOUT_ATTEMPTS[@]}"; do
        echo "- \`$c\`"
      done
    fi
    # Issue #1840: the #1814 sibling for a NON-timeout build-level death. On run
    # 30339688411 shard 0 the RETRY's Gradle build failed clearing
    # `kotlin-classes/debugAndroidTest` while an orphaned build process was still
    # writing there, so instrumentation never started and the class was reported
    # as having failed twice. Same placement discipline as the section above: it
    # is emitted BEFORE the STEP_TIMEOUT / Failed-BOTH sections (whose headers the
    # classifier's `awk` arms on) and its wording contains none of the
    # classifier's trigger strings, so it can never flip a verdict.
    if [[ "${#BUILD_PHASE_FAILURE_ATTEMPTS[@]}" -gt 0 ]]; then
      echo
      echo "Attempts that died at the Gradle BUILD level (\`JOURNEY_BUILD_PHASE_FAILURE\` — issue #1840):"
      echo "These attempts never started instrumentation, so they produced no raw JUnit XML and NO"
      echo "journey verdict at all. Read them as build-level failures, NOT as the listed journey"
      echo "failing. A recurrence here means the build could not run — investigate the build and the"
      echo "inter-attempt cleanup, not the listed journey."
      for c in "${BUILD_PHASE_FAILURE_ATTEMPTS[@]}"; do
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
    # load-bearing check failed twice — the journey classes AND/OR any registered
    # core-terminal proof. The workflow's classify step
    # (.github/workflows/tests.yml "Classify emulator-journey result") greps this
    # summary for `JOURNEY_FAILED|Failed BOTH attempts` to distinguish a genuine
    # test regression from a #771 EMULATOR INFRA UNAVAILABLE abort, and its `awk`
    # extracts the failing class names from under this exact header. If a proof
    # failed but all journey classes passed, FAILED_CLASSES is empty — so we MUST
    # still write the header (with that proof's class) here, otherwise a
    # proof-only regression falls through to the grep's else-branch and is
    # mislabeled as an infra abort, burying the real cause.
    #
    # Issue #1827: BOTH the condition and the bullets come from the same
    # CORE_TERMINAL_PROOFS registry as the red condition above, so this section
    # cannot fall out of step with what actually reddens the suite. It used to be
    # a second hand-maintained list, and #1203's SURFACE_REPAINT_STATUS and
    # #1233's SHELL_SNAPSHOT_STATUS were never added to it — a failure in either
    # reddened the suite while writing no section, and the classifier typed the
    # shard INFRA (green run) instead of RED.
    if [[ "${#FAILED_CLASSES[@]}" -gt 0 ]] || ci_journey_core_terminal_any_failed; then
      echo
      echo "Failed BOTH attempts (\`JOURNEY_FAILED\` — job red):"
      for c in "${FAILED_CLASSES[@]}"; do
        echo "- \`$c\`"
      done
      ci_journey_core_terminal_failed_bullets
    fi
    # Issue #2143: name a SETUP failure for what it is. This section is ADDITIVE
    # — every class above stays in the red list and the job stays red — but it
    # tells the reader that the shared `agents` SSH/tmux fixture was not
    # answering when these classes ran, so the failure is not evidence about the
    # code under test. On 23ed1b10 the absence of exactly this line sent a
    # release chasing an app regression on a three-line version-bump commit.
    if [[ "${#FIXTURE_WEDGED_CLASSES[@]}" -gt 0 ]]; then
      echo
      echo "Shared SSH/tmux fixture was WEDGED during these classes (\`JOURNEY_FIXTURE_SETUP_FAILURE\` — setup failure, NOT an assertion failure — issue #2143):"
      for c in "${FIXTURE_WEDGED_CLASSES[@]}"; do
        echo "- \`$c\`"
      done
      echo
      echo "A wedged shared fixture makes every later tmux-touching class time out in its own"
      echo "setup, which reads exactly like a product regression. See \`fixture-health.tsv\` in"
      echo "this shard's artifacts for the per-attempt probe latencies and the repair actions."
    fi
  } > "$SUMMARY"

  # Issue #1827 backstop: a red suite must never leave a summary the classifier
  # cannot route to RED. See ci_journey_assert_red_has_evidence.
  ci_journey_assert_red_has_evidence "$SUMMARY" "$JOURNEY_EXIT"

  echo "----------------------------------------------------------"
  cat "$SUMMARY"
  echo "----------------------------------------------------------"

  # Issue #2090: suite-completed is how classify() tells "process died mid-
  # class" from "suite finished but the artifact upload never landed".
  if [[ -n "${CI_JOURNEY_PROGRESS_HELPER:-}" && -f "$CI_JOURNEY_PROGRESS_HELPER" ]]; then
    bash "$CI_JOURNEY_PROGRESS_HELPER" suite-completed "$journey_status" || true
  elif [[ -n "${REPO_ROOT:-}" && -f "$REPO_ROOT/scripts/ci-journey-progress-telemetry.sh" ]]; then
    bash "$REPO_ROOT/scripts/ci-journey-progress-telemetry.sh" suite-completed "$journey_status" || true
  fi

  exit "$JOURNEY_EXIT"
}
