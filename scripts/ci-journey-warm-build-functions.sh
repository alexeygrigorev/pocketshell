#!/usr/bin/env bash
# Warm-build helper for scripts/ci-journey-suite.sh — issue #1814.
#
# THE DEFECT THIS REMOVES
# -----------------------
# Every journey class attempt is hard-capped at JOURNEY_CLASS_TIMEOUT_SECS by
# `run_bounded`. That cap is meant to bound ONE class's on-device work. But the
# very first `:app:connectedDebugAndroidTest` invocation on a freshly-booted
# shard also has to do the whole COLD Gradle build (`:app:compileDebugKotlin`,
# dexing, packaging, `:app:createDebugApkListingFileRedirect`, …) before a
# single assertion runs — so the FIRST class on a shard was measured against a
# budget that included work no later class pays.
#
# Observed on run 30323508796, shard 2: `MultiSessionSwitchJourneyE2eTest` was
# first on the shard and its attempt-1 verdict was `exit 124 / outer_timeout /
# raw-junit count=0`, with the log ending at
# `:app:createDebugApkListingFileRedirect` — still BUILDING. It was the only one
# of that shard's 47 classes whose attempt 1 ran a cold `:app:compileDebugKotlin`
# and it passes locally. The class was fine; the accounting was not.
#
# Two reasons that mattered more than one odd-looking class:
#   * a first-class timeout looks EXACTLY like a genuine journey failure
#     (`raw-junit count=0` is also what a killed runner produces), so it burns
#     investigation time and pollutes attribution;
#   * shard membership used to be round-robin by ARRAY INDEX, so it reshuffled
#     whenever the class list changed and WHICH class absorbed the cold build
#     rotated — one mechanical contributor to the (now-disproven) "shard 0 is
#     pathological" theory. (Issue #1862 removed that reshuffle: membership is
#     now derived from the class NAME, so the class that absorbs the cold build
#     only changes when that class itself is added or removed. The accounting fix
#     below is still the real cure — it stops any class paying for it at all.)
#
# THE FIX AND WHY THIS SHAPE
# --------------------------
# The per-class budget exists to bound ONE class's work, so the honest fix is to
# stop putting shared, once-per-shard work inside it: assemble the APKs ONCE,
# up front, and let the class loop measure every class — first and last — on
# equal terms. Deliberately NOT chosen:
#   * raising JOURNEY_CLASS_TIMEOUT_SECS — that makes the symptom rarer without
#     making the measurement correct, and slackens the bound for all ~47 classes
#     to fix an artefact affecting one (G6);
#   * granting the first class a special larger allowance — that encodes the
#     problem instead of removing it, and leaves the first class still measured
#     differently from its siblings.
#
# BUDGET ACCOUNTING (why the warm build is INSIDE the suite budget)
# ----------------------------------------------------------------
# The cold build is real work the shard must do either way; today it is already
# spent inside the #835 suite budget (during class 1). Warming it AFTER
# SUITE_START keeps that total unchanged, so the documented job-cap arithmetic
# (5700s cap - 900s worst-case boot - 4200s suite budget = 600s slack) is
# untouched. Only the PER-CLASS clock changes: it no longer starts before the
# build. The warm build is itself bounded (JOURNEY_WARM_BUILD_TIMEOUT_SECS,
# clamped to the remaining suite budget) and streams through `run_bounded`, so
# the #1056 silence watchdog covers it exactly like a class attempt.
#
# FAIL-SAFE: a failed or timed-out warm build is NON-fatal. It is a
# pre-payment, not a gate: on failure the class loop simply builds as it did
# before, and a genuinely broken build still surfaces as failing classes. Making
# it fatal would add a brand-new way for the gate to go red, which is the
# opposite of this issue's intent.

# warm_journey_build — assemble the APKs the class loop needs, before the
# per-class budget clock starts. Always returns 0 (see FAIL-SAFE above).
# shellcheck disable=SC2034  # JOURNEY_WARM_BUILD_STATUS / _ELAPSED are read by
# scripts/ci-journey-summary-functions.sh (a separately sourced helper).
warm_journey_build() {
  local cap remaining rc start elapsed
  local app_id_suffix="${POCKETSHELL_APP_ID_SUFFIX:-}"
  local -a app_id_args=()

  JOURNEY_WARM_BUILD_STATUS="skipped"
  JOURNEY_WARM_BUILD_ELAPSED=0

  if [[ -n "$app_id_suffix" ]]; then
    if [[ ! "$app_id_suffix" =~ ^[A-Za-z0-9._]+$ ]]; then
      echo "JOURNEY_WARM_BUILD_SKIPPED: invalid application id suffix '$app_id_suffix' — leaving the build to the class loop (issue #1814)" >&2
      return 0
    fi
    # Bind the SAME applicationId the class loop will use, or the warm outputs
    # would belong to a different variant and every class would rebuild anyway.
    app_id_args+=("-PpocketshellAppIdSuffix=$app_id_suffix")
  fi

  remaining="$(budget_remaining)"
  cap="$JOURNEY_WARM_BUILD_TIMEOUT_SECS"
  (( remaining < cap )) && cap="$remaining"
  if (( cap <= 0 )); then
    echo "JOURNEY_WARM_BUILD_SKIPPED: no suite budget remains for the warm build (issue #1814)"
    return 0
  fi

  echo "=========================================================="
  echo ">>> WARM BUILD (issue #1814): assembling the debug + androidTest APKs"
  echo "    BEFORE the per-class budget clock starts, so the FIRST class on this"
  echo "    shard is measured on the same terms as every later class."
  echo "    (cap ${cap}s; charged to the suite budget, NOT to any class)"
  echo "=========================================================="
  start=$SECONDS
  # Daemon reuse is preserved (no --no-daemon) and --max-workers=2 matches the
  # class-loop invocations, so this is the very same build the first class used
  # to trigger — just paid outside anyone's per-class cap.
  run_bounded "$cap" \
    "$GRADLEW" \
    :app:assembleDebug \
    :app:assembleDebugAndroidTest \
    :shared:core-terminal:assembleDebugAndroidTest \
    "${app_id_args[@]}" \
    --max-workers=2 \
    --stacktrace
  rc=$?
  elapsed=$((SECONDS - start))
  JOURNEY_WARM_BUILD_ELAPSED="$elapsed"

  if [[ $rc -eq 0 ]]; then
    JOURNEY_WARM_BUILD_STATUS="ok"
    echo "JOURNEY_WARM_BUILD: cold build paid up front in ${elapsed}s (rc=0) — every class on this shard now starts from a warm build (issue #1814)"
    return 0
  fi

  JOURNEY_WARM_BUILD_STATUS="failed"
  echo "JOURNEY_WARM_BUILD_FAILED: warm build exited ${rc} after ${elapsed}s. This is NOT fatal — the class loop will build as before; a genuinely broken build still surfaces as failing classes (issue #1814)." >&2
  if needs_gradle_cleanup_after_class_abort "$rc"; then
    # Same #918 discipline as an aborted class: a killed Gradle invocation can
    # leave a poisoned file-hash lock that would break every following class.
    cleanup_gradle_after_timeout "warm-build"
  fi
  return 0
}
