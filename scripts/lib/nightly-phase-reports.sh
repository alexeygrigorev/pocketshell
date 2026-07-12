#!/usr/bin/env bash
# nightly-phase-reports.sh — per-phase test-report preservation helper
# (issue #1293).
#
# WHY THIS EXISTS
# ---------------
# `scripts/nightly-extensive-suite.sh` runs the nightly-fault suite as SEVERAL
# separate `:app:connectedDebugAndroidTest` gradle invocations (phase 1 journey,
# phase 2 network-fault proofs, phase 2b expected-fail lane, phase 3 bootstrap).
# Every one of those invocations writes its JUnit XML, HTML report, and pulled
# device output to the SAME default paths under `app/build/...`:
#
#   app/build/outputs/androidTest-results/connected/
#   app/build/reports/androidTests/connected/
#   app/build/outputs/connected_android_test_additional_output/
#
# So a LATER phase CLOBBERS an earlier phase's report before the workflow's
# artifact-upload step runs. On the release line that meant the phase-2
# network-fault report (the release-GATING one) was overwritten by the phase-3
# bootstrap report, and the exact failing assertions for the two safety-critical
# proofs (DisconnectBlackholeE2eTest, NatIdleMappingSurvivalE2eTest) were
# UNRECOVERABLE from artifacts — which blocked the whole flake-vs-regression
# verdict the release gate depends on (issue #1293).
#
# This helper snapshots each phase's report into a phase-scoped, non-colliding
# directory IMMEDIATELY after that phase runs (before the next gradle
# invocation). The snapshot root lives under `artifacts/nightly-extensive/`,
# which the workflow already uploads, so every phase's report now survives to
# the artifact set.
#
# OBSERVABILITY ONLY: this helper copies reports. It NEVER runs gradle, changes
# what a phase asserts, or influences any phase's pass/fail exit code — callers
# invoke it AFTER capturing the phase's `$?`, and it always returns 0.
#
# Everything here is PURE (no gradle, no emulator, no network) so it is
# self-testable with `scripts/lib/nightly-phase-reports.sh --self-test`.

# The report subpaths a `:app:connectedDebugAndroidTest` invocation writes,
# RELATIVE to the module build dir (default `app/build`). Each is snapshotted to
# `<dest_root>/<phase_slug>/<subpath>` — the FULL relative subpath is preserved
# (not just its leaf) both so the snapshot mirrors the familiar build layout AND
# because the first two subpaths share the leaf `connected`; keying on the leaf
# alone would make them collide and clobber each other. Missing sources are
# skipped silently (a phase may not produce all three).
_PHASE_REPORT_SUBPATHS=(
  "outputs/androidTest-results/connected"
  "reports/androidTests/connected"
  "outputs/connected_android_test_additional_output"
)

# ---------------------------------------------------------------------------
# preserve_phase_reports <phase_slug> [module_build_dir] [dest_root]
#
#   phase_slug        non-empty label for the phase (e.g. "phase2-network-fault").
#                     Used as the snapshot subdirectory name.
#   module_build_dir  the module build dir the reports were written under.
#                     Default: "$REPO_ROOT/app/build" if REPO_ROOT is set, else
#                     "app/build".
#   dest_root         the snapshot root. Default:
#                     "$REPO_ROOT/artifacts/nightly-extensive/phase-reports" if
#                     REPO_ROOT is set, else
#                     "artifacts/nightly-extensive/phase-reports".
#
# Copies each existing report subpath into <dest_root>/<phase_slug>/<subpath>
# (the full relative subpath is preserved, so the two `.../connected` sources do
# not collide). Always returns 0 (never fails the caller — observability, not a
# gate).
# Prints a one-line note per copied/absent report to stdout.
# ---------------------------------------------------------------------------
preserve_phase_reports() {
  local phase_slug="${1:-}"
  local module_build_dir="${2:-${REPO_ROOT:+$REPO_ROOT/}app/build}"
  local dest_root="${3:-${REPO_ROOT:+$REPO_ROOT/}artifacts/nightly-extensive/phase-reports}"

  if [[ -z "$phase_slug" ]]; then
    echo "preserve_phase_reports: missing phase slug (skipping)" >&2
    return 0
  fi

  local phase_dir="$dest_root/$phase_slug"
  mkdir -p "$phase_dir" 2>/dev/null || {
    echo "preserve_phase_reports[$phase_slug]: could not create $phase_dir (skipping)" >&2
    return 0
  }

  local subpath src dest copied=0
  for subpath in "${_PHASE_REPORT_SUBPATHS[@]}"; do
    src="$module_build_dir/$subpath"
    # Preserve the FULL subpath under the phase dir so the two `.../connected`
    # sources do not collide (see _PHASE_REPORT_SUBPATHS note).
    dest="$phase_dir/$subpath"
    if [[ -e "$src" ]]; then
      # Fresh dest each phase: remove any stale copy, recreate its parent, copy.
      rm -rf "$dest" 2>/dev/null || true
      mkdir -p "$(dirname "$dest")" 2>/dev/null || true
      if cp -a "$src" "$dest" 2>/dev/null; then
        echo "preserve_phase_reports[$phase_slug]: saved $src -> $dest"
        copied=$((copied + 1))
      else
        echo "preserve_phase_reports[$phase_slug]: FAILED to copy $src (skipping)" >&2
      fi
    else
      echo "preserve_phase_reports[$phase_slug]: no report at $src (skipped)"
    fi
  done

  echo "preserve_phase_reports[$phase_slug]: preserved $copied report tree(s) to $phase_dir"
  return 0
}

# ---------------------------------------------------------------------------
# Self-test: builds a temp fixture that mimics two sequential phases writing to
# the SAME module build dir (the real overwrite scenario), preserves each, and
# asserts BOTH phases' distinct reports survived to distinct non-colliding paths
# and neither was clobbered. No gradle/emulator.
# ---------------------------------------------------------------------------
_phase_reports_self_test() {
  local failures=0
  local root
  root="$(mktemp -d)"
  local build_dir="$root/app/build"
  local dest_root="$root/artifacts/nightly-extensive/phase-reports"

  _write_fake_reports() {
    # $1 = a unique marker written into every report file so we can prove which
    # phase's content survived where.
    local marker="$1"
    mkdir -p "$build_dir/outputs/androidTest-results/connected"
    mkdir -p "$build_dir/reports/androidTests/connected"
    mkdir -p "$build_dir/outputs/connected_android_test_additional_output/sub"
    printf '<testsuite marker="%s"/>' "$marker" \
      > "$build_dir/outputs/androidTest-results/connected/TEST-results.xml"
    printf '<html>%s</html>' "$marker" \
      > "$build_dir/reports/androidTests/connected/index.html"
    printf '%s' "$marker" \
      > "$build_dir/outputs/connected_android_test_additional_output/sub/viewport.png"
  }

  assert_contains() {
    local label="$1" file="$2" want="$3"
    if [[ -f "$file" ]] && grep -q "$want" "$file"; then
      printf 'ok   [%s] %s contains %s\n' "$label" "$file" "$want"
    else
      printf 'FAIL [%s] expected %s to contain %s\n' "$label" "$file" "$want"
      failures=$((failures + 1))
    fi
  }

  echo "--- phase A writes its reports, then we preserve them ---"
  _write_fake_reports "PHASE_A_MARKER"
  preserve_phase_reports "phase-a" "$build_dir" "$dest_root"

  echo
  echo "--- phase B OVERWRITES the same build dir (the real clobber), then we preserve ---"
  # Simulate the next gradle invocation: same default paths, new content. The
  # results XML dir is recreated with only phase-B content (gradle wipes it).
  rm -rf "$build_dir/outputs/androidTest-results/connected"
  rm -rf "$build_dir/reports/androidTests/connected"
  rm -rf "$build_dir/outputs/connected_android_test_additional_output"
  _write_fake_reports "PHASE_B_MARKER"
  preserve_phase_reports "phase-b" "$build_dir" "$dest_root"

  echo
  echo "--- assert BOTH phases survived to distinct, non-colliding paths ---"
  local xml_sub="outputs/androidTest-results/connected/TEST-results.xml"
  local html_sub="reports/androidTests/connected/index.html"
  local out_sub="outputs/connected_android_test_additional_output/sub/viewport.png"
  # Phase A's snapshot must still hold PHASE_A content — NOT overwritten by B,
  # and the XML + HTML (both under a `.../connected` dir) must BOTH survive
  # (the collision the earlier leaf-keyed design silently clobbered).
  assert_contains "A-xml"    "$dest_root/phase-a/$xml_sub"  "PHASE_A_MARKER"
  assert_contains "A-html"   "$dest_root/phase-a/$html_sub" "PHASE_A_MARKER"
  assert_contains "A-output" "$dest_root/phase-a/$out_sub"  "PHASE_A_MARKER"
  # Phase B's snapshot holds PHASE_B content.
  assert_contains "B-xml"    "$dest_root/phase-b/$xml_sub"  "PHASE_B_MARKER"
  assert_contains "B-html"   "$dest_root/phase-b/$html_sub" "PHASE_B_MARKER"
  assert_contains "B-output" "$dest_root/phase-b/$out_sub"  "PHASE_B_MARKER"

  # Cross-contamination guard: A's snapshot must NOT contain B's marker, ever.
  if grep -rq "PHASE_B_MARKER" "$dest_root/phase-a" 2>/dev/null; then
    echo "FAIL [isolation] phase-a snapshot was clobbered with PHASE_B content"
    failures=$((failures + 1))
  else
    echo "ok   [isolation] phase-a snapshot free of phase-b content"
  fi

  echo
  echo "--- missing-report phase is a clean no-op (no crash, returns 0) ---"
  rm -rf "$build_dir"
  if preserve_phase_reports "phase-empty" "$build_dir" "$dest_root"; then
    echo "ok   [empty] preserve_phase_reports returned 0 with no reports present"
  else
    echo "FAIL [empty] preserve_phase_reports returned non-zero for a missing-report phase"
    failures=$((failures + 1))
  fi

  rm -rf "$root"

  echo
  if [[ "$failures" -eq 0 ]]; then
    echo "PHASE-REPORTS SELF-TEST PASS: every phase's report survived to a distinct path."
    return 0
  fi
  echo "PHASE-REPORTS SELF-TEST FAIL: $failures assertion(s) wrong."
  return 1
}

# Allow running directly for the self-test; stays a pure library when sourced.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  if [[ "${1:-}" == "--self-test" ]]; then
    _phase_reports_self_test
    exit $?
  fi
  echo "usage: source this file, or run with --self-test" >&2
  exit 2
fi
