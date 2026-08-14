#!/usr/bin/env bash
# Journey class retry loop and result buckets for scripts/ci-journey-suite.sh.

# run_journey_classes_with_retry - issue #712.
#
# The per-push job runs on the GitHub `android-emulator-runner` AVD - a 2-core
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
# CONSISTENTLY - it fails the original run AND the retry - so it still turns the
# job red and is still caught. Only a true infra flake (passes on the very next
# attempt of the same class) recovers to green. When a recovery happens we print
# a LOUD, greppable `JOURNEY_FLAKE_RECOVERED:` line so a degrading flake trend
# stays visible in the logs and is never silently hidden.
#
# Note on `Process crashed` / signal-9 (sibling-install SIGKILL): on the CI
# emulator-runner this job is the only installer, so that collision class does
# not arise here. If it ever did it would surface as a non-zero exit and the
# retry-once below would recover it exactly as for any other transient failure.
run_journey_classes_with_retry() {
  RECOVERED_CLASSES=()  # classes that failed first then PASSED on retry
  FAILED_CLASSES=()     # classes that failed BOTH attempts (real failures)
  PASSED_FIRST_TRY=()   # classes that passed on the first attempt
  FIXTURE_WEDGED_CLASSES=()  # issue #2143: classes whose failure was observed while
                             # the SHARED SSH/tmux fixture was not answering. That is
                             # a SETUP failure of the fixture, not an assertion failure
                             # of the code under test, and the two must not read alike
                             # in the verdict — on 23ed1b10 three storm-test setup
                             # timeouts and one ProfileChipRelaunch timeout were
                             # reported as a "genuine test failure (app-code
                             # regression)" on a commit whose whole diff was three
                             # version strings.
  BUDGET_TIMEOUT_CLASSES=()  # issue #835: classes not run / cut short because the
                             # suite-level budget was exhausted (the #470 stall
                             # ate the time). A DISTINCT bucket from a real failure
                             # so the classifier labels it "journey timeout / #470
                             # stall", NOT "EMULATOR INFRA UNAVAILABLE".
  STEP_TIMEOUT_HIT=0    # issue #835: set to 1 once the suite-level budget is spent.

  SUITE_START=$SECONDS

  echo ">>> Suite-level time budget (issue #835): ${JOURNEY_STEP_BUDGET_SECS}s"
  echo "    (per-class attempt cap: ${JOURNEY_CLASS_TIMEOUT_SECS}s; workflow job cap: 95 min)"

  # Issue #1814: pay the cold Gradle build ONCE, here, BEFORE the first class's
  # per-class clock starts. The suite budget clock (SUITE_START, just above) is
  # already running, so this does NOT move build time out of the suite budget —
  # it only stops the first class from being charged for shared, once-per-shard
  # work no later class pays. Non-fatal by design (see the helper's FAIL-SAFE
  # note): a failed warm build leaves the class loop exactly as it was.
  warm_journey_build

  # Issue #2143: one baseline probe of the shared SSH/tmux fixture BEFORE any
  # class runs. It is the reference point for the per-attempt series that
  # follows — without a known-good first row there is nothing to compare a later
  # slow/absent probe against, which is why the previous occurrences had to be
  # re-derived from scratch each time.
  journey_fixture_health_gate preflight suite_start

  local fqcn class_start rc retry_start class_wedged
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
    class_wedged=0

    run_class "$fqcn"
    rc=$?
    [[ "${LAST_RUN_CLASS_FIXTURE_HEALTH:-ok}" == "ok" || "${LAST_RUN_CLASS_FIXTURE_HEALTH:-ok}" == "skipped" ]] || class_wedged=1
    if [[ "${JOURNEY_ABORT_ISOLATION_FAILED:-0}" -eq 1 ]]; then
      echo "JOURNEY_ISOLATION_FAILURE: $fqcn primary_rc=${LAST_RUN_CLASS_PRIMARY_RC:-unknown} cleanup_failed=${LAST_RUN_CLASS_ABORT_CLEANUP_FAILED:-0} artifact_failed=${LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED:-0} — refusing every retry/next class because isolation is unproven"
      FAILED_CLASSES+=("$fqcn")
      break
    fi
    if [[ $rc -eq 0 ]]; then
      echo "JOURNEY_PASS: $fqcn passed on attempt 1 (elapsed $((SECONDS - class_start))s)"
      PASSED_FIRST_TRY+=("$fqcn")
      continue
    fi

    # Issue #835: if the budget is now spent (this attempt was cut by `timeout`, or
    # an earlier attempt drained the clock), do NOT burn the remaining-class retry
    # on a stalled AVD - bucket this class as a BUDGET-timeout and move on so the
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

    # Attempt 1 failed. Re-run ONLY this class once - a CI-AVD infra flake
    # (e.g. the #470 enumeration stall) typically clears on the next attempt;
    # a real regression fails again and keeps the job red.
    echo "=========================================================="
    echo ">>> JOURNEY CLASS: $fqcn FAILED attempt 1 — retrying once (attempt 2) [budget remaining: $(budget_remaining)s]"
    echo "=========================================================="
    retry_start=$SECONDS

    run_class "$fqcn"
    rc=$?
    [[ "${LAST_RUN_CLASS_FIXTURE_HEALTH:-ok}" == "ok" || "${LAST_RUN_CLASS_FIXTURE_HEALTH:-ok}" == "skipped" ]] || class_wedged=1
    if [[ "${JOURNEY_ABORT_ISOLATION_FAILED:-0}" -eq 1 ]]; then
      echo "JOURNEY_ISOLATION_FAILURE: $fqcn retry primary_rc=${LAST_RUN_CLASS_PRIMARY_RC:-unknown} cleanup_failed=${LAST_RUN_CLASS_ABORT_CLEANUP_FAILED:-0} artifact_failed=${LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED:-0} — refusing every next class because isolation is unproven"
      FAILED_CLASSES+=("$fqcn")
      break
    fi
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

    # Issue #2143: a class that failed while the SHARED fixture was not answering
    # is recorded in a SECOND, additive bucket. It stays in FAILED_CLASSES — the
    # job is still red, nothing is masked — but the verdict now says WHICH kind
    # of failure it was, so the next occurrence is not read as an app regression.
    if [[ $class_wedged -eq 1 ]]; then
      echo "JOURNEY_FIXTURE_SETUP_FAILURE: $fqcn failed while the shared SSH/tmux fixture was ${LAST_RUN_CLASS_FIXTURE_HEALTH:-unknown} — classified as a fixture SETUP failure, not an assertion failure (issue #2143)"
      FIXTURE_WEDGED_CLASSES+=("$fqcn")
    fi
  done
}
