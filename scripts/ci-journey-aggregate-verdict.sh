#!/usr/bin/env bash
# Issue #1458: aggregate the per-shard emulator-journey verdict tokens into ONE
# overall verdict so `main`'s emulator-level health is readable at a glance and
# an all-infra flake run does NOT fire a false red-CI email.
#
# Background: per-shard infra aborts used to exit 1 (red) with no rollup —
# three independent red checks with no aggregated verdict, so the maintainer
# could not read main's health and got red-CI email spam. This helper is the
# aggregation: it reads the one-word verdict token each shard now writes and
# emits the single overall verdict.
#
# Usage:
#   ci-journey-aggregate-verdict.sh [VERDICT_DIR]
#     VERDICT_DIR  directory holding the downloaded per-shard verdict artifacts
#                  (default: artifacts/ci-journey-verdicts). Each shard artifact
#                  contributes a file whose FIRST non-empty line is the verdict
#                  token — CLEAN | INFRA | RED — followed by `key=value`
#                  provenance lines (`shard`, `run_id`, `run_attempt`,
#                  `written_at`) written by
#                  scripts/ci-journey-write-shard-verdict.sh. Files may sit
#                  directly under VERDICT_DIR or one level down
#                  (download-artifact per-shard subdirs) — any regular file
#                  named `shard-verdict*.txt` (recursively) counts.
#
# Issue #1809 (G5 re-run provenance): a re-run of ONE shard leaves the shards
# that were NOT re-run reporting their earlier attempt's token — that is
# correct, but until now it was invisible, so nobody could tell a fresh re-run
# verdict from a replay of stale tokens. Every token is therefore stamped with
# its `run_attempt`, and this script PRINTS, per shard, which attempt its
# verdict came from (and says so in the step summary). Two loud cases:
#   * some tokens carried over from an earlier attempt -> ::notice naming them,
#     so an on-call's "clean re-run" claim is backed by an artifact, not a
#     memory of which button was pressed;
#   * run_attempt > 1 and NOT ONE token is from this attempt -> ::warning: the
#     aggregation job was re-run WITHOUT re-running any shard, so this verdict
#     is a replay of unchanged data, not a re-run result. That is exactly the
#     "confusing second red" the on-call hit: because the per-shard classify
#     step is `continue-on-error`, a shard whose token is RED still finishes
#     `success`, so GitHub's "Re-run failed jobs" used to re-run ONLY this
#     aggregation job. The shard job now fails on a RED token (see tests.yml)
#     so the standard re-run button re-runs the offending shard too, and this
#     warning is the backstop that names the replay if it ever happens again.
# Provenance NEVER changes the verdict — it is reporting only. The verdict is
# still the honest rollup of the freshest token per shard.
#   EXPECTED_SHARDS (env, optional) the number of shards that should have
#                  reported. When set and fewer tokens are found, the missing
#                  shards downgrade the verdict to at least RE-RUN (a missing
#                  verdict is not a clean signal), but never to RED on their own.
#   UPSTREAM_MATRIX_RESULT (env, optional) the `needs.emulator-journey.result`
#                  value. `failure` with no RED token is a classifier/upstream
#                  mismatch and fails closed to RED (#1913). Genuine typed
#                  INFRA shards finish successfully, so their upstream result
#                  is `success` and their aggregate remains green RE-RUN.
#   CI_JOURNEY_PROGRESS_DIR (env, optional) extra-runner last-completed-class
#                  records collected by ci-journey-progress-telemetry.sh
#                  (issue #2090). When a shard is missing, the last class and
#                  job metadata are printed as an INFRA diagnostic. This never
#                  flips CLEAN/RED — attendance fail-closed stays with #2082.
#
# Verdict / exit code:
#   CLEAN   every shard reported CLEAN (and none are missing). exit 0.
#   RED     at least one shard reported RED (a genuine journey failure, or the
#           #835 budget-timeout hard-red), OR a present token file held an
#           unrecognised value (fail-closed — corruption must not silently
#           pass). exit 1 — the run goes red.
#   RE-RUN  no RED shard, but at least one INFRA shard and/or a missing shard:
#           every failing/absent shard was environmental (#470 cancel / #771
#           never-booted / retry budget denied / not reported). A re-run signal,
#           NOT a product regression, so it exits 0 (neutral) with a ::warning —
#           no false red-CI email.
#
# The verdict is printed as `AGGREGATE_VERDICT=<verdict>` and appended to
# $GITHUB_STEP_SUMMARY when that env var is set, so the rollup is readable in the
# GitHub checks UI. The load-bearing property — a genuine RED still turns the run
# red — is proven by scripts/test-ci-journey-aggregate-verdict.sh (no emulator).
set -uo pipefail

verdict_dir="${1:-artifacts/ci-journey-verdicts}"
expected_shards="${EXPECTED_SHARDS:-0}"
upstream_matrix_result="${UPSTREAM_MATRIX_RESULT:-unknown}"

have_clean=0
have_infra=0
have_red=0
have_unknown=0
count=0
tokens=()
provenance=()
carried_over=()
cold_build_shards=()
# Issue #1833: shards that reached their verdict unable to start a cold-boot
# retry, i.e. shards whose result came from ONE attempt.
one_shot_shards=()
# Issue #2374: one-shot shards split by WHICH denial they were. `gate_capacity`
# is #1833/#1850's condition and must stay loud; `journey_failure_inflated_suite`
# is a shard whose own failing classes doubled its suite, where the budget is not
# the lever. An unstamped/unclassifiable token belongs to neither bucket and must
# never be counted as evidence that the capacity condition is absent.
capacity_one_shot_shards=()
inflated_one_shot_shards=()
unclassified_one_shot_shards=()
# #2095 external setup INFRA deliberately fails its shard job so GitHub's
# failed-job retry re-runs only that shard. Its precise token reason explains
# the otherwise-fail-closed upstream matrix failure.
external_setup_infra_shards=()
unclassified_infra=0
fresh_tokens=0
reported_shards=()

current_attempt="${GITHUB_RUN_ATTEMPT:-}"
[[ "$current_attempt" =~ ^[0-9]+$ ]] || current_attempt=""

if [[ -d "$verdict_dir" ]]; then
  while IFS= read -r -d '' f; do
    # The verdict is the FIRST non-empty line; everything after it is the #1809
    # `key=value` provenance stamp. Reading only line 1 is what keeps a stamped
    # token from being mangled into an UNKNOWN (which would fail closed to RED).
    raw="$(awk 'NF { print; exit }' "$f" 2>/dev/null || true)"
    # Normalise: strip whitespace, upper-case.
    token="$(printf '%s' "$raw" | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]')"
    [[ -z "$token" ]] && token="UNKNOWN"

    tok_shard="$(sed -n 's/^shard=//p' "$f" 2>/dev/null | head -n 1)"
    tok_attempt="$(sed -n 's/^run_attempt=//p' "$f" 2>/dev/null | head -n 1)"
    tok_run="$(sed -n 's/^run_id=//p' "$f" 2>/dev/null | head -n 1)"
    # Issue #1814: WHY this shard has this verdict. Absent on a token written
    # before the reason existed, which stays a plain `unspecified`.
    tok_reason="$(sed -n 's/^verdict_reason=//p' "$f" 2>/dev/null | head -n 1)"
    [[ -n "$tok_reason" ]] || tok_reason="unspecified"
    if [[ "$token" == "INFRA" ]]; then
      case "$tok_reason" in
        docker_registry_http_5xx_exhausted|docker_registry_network_exhausted)
          external_setup_infra_shards+=("shard ${tok_shard} (${tok_reason})")
          ;;
        foreign_framework_anr_focus|real_ime_precondition|retry_wall_exhausted|attempt_cancelled|emulator_never_booted)
          ;;
        *)
          unclassified_infra=$((unclassified_infra + 1))
          ;;
      esac
    fi
    if [[ -z "$tok_shard" ]]; then
      # No stamp (a token from a job that died before the pre-seed, or a foreign
      # artifact): fall back to the download-artifact per-shard subdir name.
      dir_name="$(basename "$(dirname "$f")")"
      case "$dir_name" in
        *shard-*) tok_shard="${dir_name##*shard-}" ;;
        *)        tok_shard="?" ;;
      esac
    fi
    [[ -n "$tok_attempt" ]] || tok_attempt="?"
    [[ -n "$tok_run" ]] || tok_run="?"

    origin="attempt ${tok_attempt}"
    if [[ -n "$current_attempt" && "$tok_attempt" =~ ^[0-9]+$ ]]; then
      if (( tok_attempt == current_attempt )); then
        origin="attempt ${tok_attempt} (this attempt)"
        fresh_tokens=$((fresh_tokens + 1))
      else
        origin="attempt ${tok_attempt} (carried over; this run is on attempt ${current_attempt})"
        carried_over+=("shard ${tok_shard}=${token} from attempt ${tok_attempt}")
      fi
    elif [[ "$tok_attempt" == "?" ]]; then
      origin="attempt unknown (token carries no #1809 provenance stamp)"
    fi

    # Issue #1833: did this shard still have a cold-boot retry when it reached
    # that verdict? Absent on a token written before the stamp existed, which
    # reads `unknown` and is reported as neither affordable nor one-shot.
    tok_affordable="$(sed -n 's/^retry_affordable=//p' "$f" 2>/dev/null | head -n 1)"
    [[ "$tok_affordable" == "true" || "$tok_affordable" == "false" ]] || tok_affordable="unknown"
    tok_shortfall="$(sed -n 's/^retry_shortfall_ms=//p' "$f" 2>/dev/null | head -n 1)"
    [[ "$tok_shortfall" =~ ^[0-9]+$ ]] || tok_shortfall="?"
    tok_retry_reason="$(sed -n 's/^retry_denied_reason=//p' "$f" 2>/dev/null | head -n 1)"
    [[ -n "$tok_retry_reason" ]] || tok_retry_reason="unspecified"
    # Issue #2374: WHICH denial. Absent on a token written before this stamp,
    # which reads `unknown` and is bucketed as unclassified.
    tok_denial_class="$(sed -n 's/^retry_denial_class=//p' "$f" 2>/dev/null | head -n 1)"
    case "$tok_denial_class" in
      none | gate_capacity | journey_failure_inflated_suite) ;;
      *) tok_denial_class="unknown" ;;
    esac
    # Issue #2374: the build attribution, carried independently of the verdict
    # reason. Absent on a token written before this stamp existed (and on any
    # shard with no build evidence), which reads `none`.
    tok_build_attribution="$(sed -n 's/^build_attribution=//p' "$f" 2>/dev/null | head -n 1)"
    case "$tok_build_attribution" in
      none | cold_build_timeout | build_level_failure) ;;
      *) tok_build_attribution="none" ;;
    esac

    provenance_line="shard ${tok_shard}: ${token} — run ${tok_run}, ${origin}, reason ${tok_reason}"
    if [[ "$tok_affordable" == "false" ]]; then
      provenance_line+=", ONE-SHOT (no cold-boot retry affordable: ${tok_retry_reason}, short by ${tok_shortfall}ms, class ${tok_denial_class})"
      one_shot_shards+=("shard ${tok_shard} (${token}, short by ${tok_shortfall}ms, ${tok_denial_class})")
      case "$tok_denial_class" in
        gate_capacity)
          capacity_one_shot_shards+=("shard ${tok_shard} (${token}, short by ${tok_shortfall}ms)") ;;
        journey_failure_inflated_suite)
          inflated_one_shot_shards+=("shard ${tok_shard} (${token}, short by ${tok_shortfall}ms)") ;;
        *)
          unclassified_one_shot_shards+=("shard ${tok_shard} (${token}, short by ${tok_shortfall}ms)") ;;
      esac
    fi
    provenance+=("$provenance_line")
    # Issue #1814's rollup, kept whole across #2374's precedence change: a shard
    # carries cold-build evidence when that IS its verdict reason, OR when the
    # reason went to an unrelated genuine journey failure and the build evidence
    # rode the separate `build_attribution` stamp instead. Reading only the
    # reason is what made the notice disappear from the aggregate (and therefore
    # from the step summary a release owner reads) in exactly the mixed shape
    # this issue is about.
    #
    # The stamp branch is gated on the shard's FINAL verdict being RED, and that
    # gate is load-bearing. The classify step computes `SHARD_BUILD_ATTRIBUTION`
    # before EVERY write_verdict branch, and ci-journey-build-phase-timeout.sh
    # deliberately reads the PRESERVED first-attempt tree so a retry cannot hide
    # attempt 1's evidence. Both are right — but together they mean a shard whose
    # attempt 1 was cut mid-Gradle-build and whose RETRY THEN PASSED writes
    # `CLEAN` + `build_attribution=cold_build_timeout`. Keying the rollup on the
    # stamp alone would put an "#1814 … investigate the build cost" heading on a
    # GREEN aggregate — the artifact a release owner reads before tagging
    # `validated-rc` — which is a fresh instance of the very misread this issue
    # exists to remove. A shard that self-healed via its retry SUCCEEDED: its
    # attempt-1 build hiccup is diagnostic, not a cause of any failure, and it is
    # not lost — that shard's own unconditional `::warning … (#1814)` from
    # ci-journey-build-attribution-notice.sh still names it in the job log. The
    # rollup speaks only where the build cost actually contributed to a RED.
    # The reason branch needs no such gate: `cold_build_timeout` only ever
    # reaches a token through `verdict_reason_for`, which is called exclusively
    # from RED branches. Pinned both ways by (c5)/(c8)/(c9) in
    # scripts/test-ci-journey-warm-build.sh.
    if [[ "$tok_reason" == "cold_build_timeout" ]]; then
      cold_build_shards+=("shard ${tok_shard} (${token}, verdict reason ${tok_reason})")
    elif [[ "$tok_build_attribution" == "cold_build_timeout" && "$token" == "RED" ]]; then
      cold_build_shards+=("shard ${tok_shard} (${token}, verdict reason ${tok_reason}, build attribution carried separately — issue #2374)")
    fi

    count=$((count + 1))
    tokens+=("$token")
    if [[ "$tok_shard" =~ ^[0-9]+$ ]]; then
      reported_shards+=("$tok_shard")
    fi
    case "$token" in
      CLEAN) have_clean=$((have_clean + 1)) ;;
      INFRA) have_infra=$((have_infra + 1)) ;;
      RED)   have_red=$((have_red + 1)) ;;
      *)     have_unknown=$((have_unknown + 1)) ;;
    esac
  done < <(find "$verdict_dir" -type f -name 'shard-verdict*.txt' -print0 2>/dev/null)
fi

missing=0
if [[ "$expected_shards" =~ ^[0-9]+$ ]] && (( expected_shards > count )); then
  missing=$((expected_shards - count))
fi

echo "per-shard verdict tokens found: ${count} ${tokens[*]:-<none>}"
echo "  CLEAN=$have_clean  INFRA=$have_infra  RED=$have_red  UNKNOWN=$have_unknown  MISSING=$missing (expected ${expected_shards})"

# Issue #2090: a vanished shard may still have extra-runner last-completed-class
# telemetry. That is INFRA diagnostic, never a product CLEAN/RED flip — #2082
# still owns fail-closed attendance, and missing stays at least RE-RUN.
progress_dir="${CI_JOURNEY_PROGRESS_DIR:-}"
if (( missing > 0 )) && [[ -n "$progress_dir" && -d "$progress_dir" ]]; then
  progress_idx=0
  while (( progress_idx < expected_shards )); do
    shard_seen=0
    for seen in "${reported_shards[@]:-}"; do
      if [[ "$seen" == "$progress_idx" ]]; then
        shard_seen=1
        break
      fi
    done
    if (( shard_seen == 0 )); then
      progress_file=""
      for candidate in "$progress_dir"/journey-progress-shard-${progress_idx}-*.txt \
                       "$progress_dir"/*shard-${progress_idx}*.txt; do
        [[ -f "$candidate" ]] || continue
        progress_file="$candidate"
        break
      done
      if [[ -n "$progress_file" ]]; then
        last_class="$(sed -n 's/^last_completed_class=//p' "$progress_file" | head -n 1)"
        last_status="$(sed -n 's/^last_completed_status=//p' "$progress_file" | head -n 1)"
        in_progress="$(sed -n 's/^in_progress_class=//p' "$progress_file" | head -n 1)"
        job_name="$(sed -n 's/^job_name=//p' "$progress_file" | head -n 1)"
        run_id="$(sed -n 's/^run_id=//p' "$progress_file" | head -n 1)"
        run_attempt="$(sed -n 's/^run_attempt=//p' "$progress_file" | head -n 1)"
        [[ -n "$last_class" ]] || last_class="(none recorded)"
        echo "missing shard ${progress_idx} last-completed-class (infra diagnostic, issue #2090): ${last_class} status=${last_status:-unknown} in_progress=${in_progress:-} job=${job_name:-unknown} run=${run_id:-unknown} attempt=${run_attempt:-unknown}"
        echo "::notice title=Emulator journey shard ${progress_idx} last-completed-class (infra)::owner=infra signature=hosted_runner_progress last_completed_class=${last_class} — diagnostic only, not a product verdict (issue #2090)"
      fi
    fi
    progress_idx=$((progress_idx + 1))
  done
fi

# Issue #1809: per-shard provenance, so a G5 "clean re-run" claim is backed by
# the artifact rather than by which button someone remembers pressing.
echo "verdict provenance (issue #1809; this run is on attempt ${current_attempt:-unknown}):"
if (( count == 0 )); then
  echo "  <no tokens>"
else
  for line in "${provenance[@]}"; do
    echo "  $line"
  done
fi

# Issue #1814: a verdict caused (at least in part) by an attempt that was cut
# short while Gradle was still BUILDING must SAY so. Such an attempt reports
# `exit 124 / outer_timeout / raw-junit count=0` — indistinguishable from a
# killed mid-journey runner — and on run 30323508796 that ambiguity sent an
# investigation after a healthy journey class. The verdict's SEVERITY is
# untouched (naming a failure is not softening it); only its readability is.
if (( ${#cold_build_shards[@]} > 0 )); then
  echo "::notice title=Emulator journey verdict — includes a cold-BUILD-phase timeout (#1814)::${#cold_build_shards[@]} shard verdict(s) carry cold-BUILD-phase evidence, either as the verdict reason or — when the same shard ALSO failed unrelated journeys (issue #2374) — on the token's separate 'build_attribution' stamp: ${cold_build_shards[*]}. At least one attempt there hit its per-class wall cap while Gradle was still BUILDING — instrumentation never started and no JUnit XML exists, so the zero-test outer timeout is a BUILD-COST artefact, not evidence that the named journey is broken. Investigate the build cost (the cold build is paid up front by the suite's warm-build step), not the journey — but where the shard's verdict reason names a genuine journey failure, that failure is a separate, real cause and is not explained by this notice."
fi

# Issue #1833: a verdict reached WITHOUT the cold-boot retry available must say
# so. The retry is what turns a single #788-class swiftshader flake into a
# recovered run; on run 30383504733 all three shards were denied it and all three
# still reported a plain CLEAN/RED, so the batch that then went red on a blank
# viewport looked exactly like a healthy gate finding a real regression. Severity
# is untouched — this changes only what the evidence SAYS about how much
# resilience produced it.
if (( ${#one_shot_shards[@]} > 0 )); then
  echo "::notice title=Emulator journey verdict — ${#one_shot_shards[@]} shard(s) ran ONE-SHOT, no cold-boot retry (#1833)::${one_shot_shards[*]} reached their verdict unable to start a second cold boot, so their result comes from a SINGLE attempt. The cold-boot retry is what normally absorbs a #788-class environmental flake; without it such a flake reddens this aggregate instead of recovering. Read a RED from a one-shot shard with that in mind, and read a CLEAN as having had less protection than usual."
fi

# Issue #2374: WHICH denial, because the two want opposite responses and until
# now they shared one label. Run 33181062826's six one-shot shards were read as a
# recurrence of #1833/#1850's capacity loss; every one of them had already failed
# journeys, so its own suite is what ate the wall and no matrix change would have
# helped. Both branches below are reporting only — no verdict, count or exit code
# depends on them.
if (( ${#capacity_one_shot_shards[@]} > 0 )); then
  echo "::warning title=Emulator journey — ${#capacity_one_shot_shards[@]} shard(s) lost the cold-boot retry to GATE CAPACITY (#1833/#1850)::${capacity_one_shot_shards[*]} failed NO journey on their first attempt and still could not afford a second cold boot. This is the resilience loss #1833 reports and #1850 sized the matrix against: the levers are the shard count, the class distribution, and the retry-cost estimate — NOT the failing classes, because there were none. Re-check the per-leg headroom derivation in scripts/ci-journey-shard-count.sh against these shards' measured suite elapsed."
fi
if (( ${#inflated_one_shot_shards[@]} > 0 && ${#capacity_one_shot_shards[@]} == 0 \
      && ${#unclassified_one_shot_shards[@]} == 0 )); then
  echo "::notice title=Emulator journey — every ONE-SHOT shard was inflated by its OWN failing journeys, not a gate-capacity loss (#2374)::${inflated_one_shot_shards[*]} reached their verdict one-shot, but each had already written a 'Failed BOTH attempts' summary. A class that fails pays TWO full per-class attempts, so those failures are what pushed suite elapsed past what a retry could fit. This is NOT a recurrence of #1833/#1850 and no budget, shard count or cost estimate is the lever here — fix the classes each shard's own summary names. Raising a budget would buy a second hour of re-failing them and still end RED."
fi

if (( ${#carried_over[@]} > 0 )); then
  echo "::notice title=Emulator journey verdict — some shard verdicts carried over::${#carried_over[@]} shard verdict(s) come from an earlier attempt of this run (that shard was not re-run): ${carried_over[*]}. This is expected when a single shard is re-run; it is reported so the aggregate verdict's provenance is auditable (issue #1809, G5)."
fi

# The replay backstop: the aggregation job was re-run but not one shard was.
if [[ -n "$current_attempt" ]] && (( current_attempt > 1 && count > 0 && fresh_tokens == 0 )); then
  echo "::warning title=Emulator journey verdict — REPLAY, no shard was re-run::This is attempt ${current_attempt}, but every per-shard verdict token was produced by an earlier attempt. The aggregation job was re-run WITHOUT re-running any emulator-journey shard, so this verdict is a replay of unchanged data — it is NOT evidence that a re-run reached a different result. To satisfy G5, re-run the failing shard job itself (its job now fails on a RED token, so 'Re-run failed jobs' picks it up) and let this job aggregate the fresh token (issue #1809)."
fi

emit_summary() {
  local verdict="$1" detail="$2"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      echo "# Emulator journey — aggregate verdict (#1458)"
      echo
      echo "**${verdict}** — ${detail}"
      echo
      echo "| token | count |"
      echo "| --- | --- |"
      echo "| CLEAN | $have_clean |"
      echo "| INFRA | $have_infra |"
      echo "| RED | $have_red |"
      echo "| UNKNOWN | $have_unknown |"
      echo "| MISSING | $missing |"
      echo
      echo "Verdict provenance (issue #1809) — this run is on attempt ${current_attempt:-unknown}:"
      echo
      if (( count == 0 )); then
        echo "- _no per-shard verdict tokens_"
      else
        local line
        for line in "${provenance[@]}"; do
          echo "- ${line}"
        done
      fi
      if (( ${#cold_build_shards[@]} > 0 )); then
        echo
        echo "**Cold-build-phase timeout (issue #1814):** ${cold_build_shards[*]} — an attempt there was cut while Gradle was still BUILDING (no instrumentation, no JUnit XML). That is a build-cost artefact, not a defect in the named journey. Where the shard's verdict reason names a genuine journey failure instead, that failure is a separate real cause this line does not explain (issue #2374)."
      fi
      if (( ${#one_shot_shards[@]} > 0 )); then
        echo
        echo "**Ran ONE-SHOT — no cold-boot retry (issue #1833):** ${one_shot_shards[*]} — these shards reached their verdict unable to start a second cold boot, so their result comes from a SINGLE attempt and a #788-class environmental flake there reddens this aggregate instead of recovering."
      fi
      if (( ${#capacity_one_shot_shards[@]} > 0 )); then
        echo
        echo "**Denial class \`gate_capacity\` (issues #1833/#1850):** ${capacity_one_shot_shards[*]} — these failed no journey and still could not retry. The lever is the matrix/estimate."
      fi
      if (( ${#inflated_one_shot_shards[@]} > 0 )); then
        echo
        echo "**Denial class \`journey_failure_inflated_suite\` (issue #2374):** ${inflated_one_shot_shards[*]} — these had already failed journeys, so their own doubled classes consumed the wall. The lever is those classes, not the retry budget."
      fi
      if (( ${#unclassified_one_shot_shards[@]} > 0 )); then
        echo
        echo "**Denial class \`unknown\`:** ${unclassified_one_shot_shards[*]} — token predates the #2374 stamp or carried no usable reading; do not read these as either condition."
      fi
    } >> "$GITHUB_STEP_SUMMARY"
  fi
}

# Issue #1913: the matrix job result is an independent phase signal. Typed
# environmental INFRA is continue-on-error and only RED tokens trip the shard's
# final fail gate, so a genuine all-INFRA run has upstream `success`. Conversely,
# upstream `failure` with no RED token means a pre-journey/setup failure escaped
# classification (the exact run-30659266867 blind spot). Fail closed even when
# tokens are missing or all CLEAN; a deterministic workflow failure cannot be
# presented as neutral RE-RUN.
if [[ "$upstream_matrix_result" == "failure" ]] && (( have_red == 0 && have_unknown == 0 )); then
  if (( ${#external_setup_infra_shards[@]} > 0 && unclassified_infra == 0 && missing == 0 )); then
    echo "::warning title=Emulator journey external setup INFRA — failed shard is retryable (#2095)::${external_setup_infra_shards[*]} exhausted a bounded provider retry before zero journeys. Its shard job intentionally failed so 'Re-run failed jobs' re-runs only that shard; the typed aggregate remains RE-RUN, never product RED."
  else
    echo "::error title=Emulator journey verdict — RED (upstream/classifier mismatch)::The upstream emulator-journey matrix result was failure, but no shard verdict token reported RED. A commit-bound shard/setup failure escaped the classifier; refusing to downgrade the run to environmental RE-RUN (issue #1913)."
    emit_summary "RED" "upstream matrix failed without a RED shard token — classifier mismatch"
    echo "AGGREGATE_VERDICT=RED"
    exit 1
  fi
fi

# No tokens at all AND none were expected: nothing ran (e.g. the emulator job was
# skipped). Treat as RE-RUN/neutral rather than a false red — there is no
# evidence of a genuine regression.
if (( count == 0 && missing == 0 )); then
  echo "::warning title=Emulator journey verdict — RE-RUN (no shard verdicts)::No per-shard verdict tokens were found and none were expected; nothing to aggregate. Treating as neutral RE-RUN, NOT a red — there is no evidence of a genuine journey failure."
  emit_summary "RE-RUN" "no per-shard verdict tokens were found (nothing to aggregate)"
  echo "AGGREGATE_VERDICT=RE-RUN"
  exit 0
fi

# RED wins: a genuine journey failure / #835 timeout, or a corrupt (unknown)
# token which we fail closed on so a real regression can never hide behind a
# malformed artifact.
if (( have_red > 0 || have_unknown > 0 )); then
  echo "::error title=Emulator journey verdict — RED::At least one shard reported a genuine journey failure (RED=$have_red) or an unrecognised verdict (UNKNOWN=$have_unknown). This is a real regression signal — the run stays red. Per-shard annotations/logs carry the failing class(es)."
  emit_summary "RED" "a genuine journey failure (or corrupt verdict) was reported — the run stays red"
  echo "AGGREGATE_VERDICT=RED"
  exit 1
fi

# No RED; some shard was environmental (INFRA) or did not report (MISSING).
if (( have_infra > 0 || missing > 0 )); then
  echo "::warning title=Emulator journey verdict — RE-RUN (environmental)::No genuine journey failure, but ${have_infra} shard(s) hit an environmental infra abort (#470 cancel / #771 never-booted / retry budget denied) and ${missing} shard(s) did not report. This is a re-run signal, NOT a product regression — the run stays green so main-health is readable and no false red-CI email fires. Re-run the emulator-journey job."
  emit_summary "RE-RUN" "${have_infra} infra shard(s) + ${missing} missing shard(s), no genuine failure — re-run"
  echo "AGGREGATE_VERDICT=RE-RUN"
  exit 0
fi

# Every shard reported CLEAN and none are missing.
echo "All ${count} shard(s) reported CLEAN — the emulator journey gate is trustworthy this run."
emit_summary "CLEAN" "every shard passed"
echo "AGGREGATE_VERDICT=CLEAN"
exit 0
