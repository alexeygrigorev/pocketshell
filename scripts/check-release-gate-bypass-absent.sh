#!/usr/bin/env bash
set -euo pipefail

# check-release-gate-bypass-absent.sh — locked decision D37 (issue #2379).
#
# WHY THIS EXISTS
# ---------------
# The scheduled fault-injection safety verdict (toxiproxy network-fault proofs +
# the bootstrap setup-scenario matrix) is what stands between a release tag and
# shipping a broken reconnect/transport path. It used to carry an opt-out: an
# environment variable read by scripts/check-nightly-fault-run.sh, plus a
# matching workflow_dispatch input on
# .github/workflows/release-emulator-validation.yml. Whoever cut the release
# could set it and the guard printed SKIPPED and exited 0.
#
# It was not a last-resort lever. v0.4.31-v0.4.38 (and v0.4.45) all shipped with
# the fault verdict waived; #1671 traced the #1610 reconnect storm reaching the
# maintainer to exactly that. A gate the release-owner can silently opt out of
# is not a gate.
#
# D37 removes the opt-out entirely rather than discouraging it (D22: hard cut, no
# flag for the old behaviour). A red, stale, cancelled or missing nightly fault
# verdict on the release commit unconditionally fails the release gate. The two
# sanctioned ways to unblock a release are (a) fix the failing test/journey, or
# (b) quarantine the offending class through the D36(4) flake mechanism
# (auto-filed issue, non-blocking lane, 2-week expiry) so the gate evaluates a
# genuinely smaller but still-real suite. Never a skip of the whole verdict.
#
# Deleting code is easy to undo. This guard is what makes the deletion durable.
#
# WHAT IT CHECKS
#
#   C1  STATIC — neither bypass name appears in any git-tracked file under
#       scripts/, .github/workflows/ or .github/actions/. Comments count: a
#       comment advertising the escape hatch as available is how the next round
#       reintroduces it, and a name that exists nowhere cannot be "restored" by
#       accident. Scope: git-TRACKED files only, so an untracked scratch file in
#       someone's worktree cannot redden the gate (in CI everything is tracked).
#
#   C2  BEHAVIOURAL — scripts/check-nightly-fault-run.sh, driven through its
#       real fetch+decide path with a RED fault-verdict fixture AND both bypass
#       names exported to every value that used to mean "skip", still exits
#       non-zero AND blocks for the RED-verdict reason.
#
#   C3  BEHAVIOURAL — the same, with a fixture in which the fault-verdict job
#       never ran (no signal at all): non-zero AND the missing-signal reason.
#       Missing is not permission to ship. C2/C3 pin the reason string because
#       "exited non-zero" alone is satisfied by blocking for an unrelated,
#       accidental reason — which is exactly what C3 did in round 1.
#
#   C4  ENVIRONMENT INJECTION — the class that actually survived round 1. C1/C2/
#       C3 only speak about two literal names; the bypass that remained had a
#       different name. check-nightly-fault-run.sh used to read
#       NIGHTLY_FAULT_RUN_FIXTURE from the ENVIRONMENT, and that variable does
#       not select which run is read, it SUPPLIES THE ANSWER:
#         NIGHTLY_FAULT_RUN_FIXTURE=green.json scripts/check-nightly-fault-run.sh
#         -> "PASS: nightly fault-injection safety verdict is green", exit 0
#       release-emulator-validation.sh inherited its caller's environment
#       straight into that call, so one exported variable and a three-line file
#       put a fabricated green in the release summary — worse than the deleted
#       hatch, which at least printed SKIPPED. C4 has two halves:
#         C4-static — the release path's own invocation of the guard passes no
#                     test-only flag and sets no NIGHTLY_FAULT_* variable (and
#                     still invokes the guard at all).
#         C4-probe  — the guard, invoked exactly as the release path invokes it
#                     but with a hostile environment (a fabricated-green fixture
#                     under every legacy variable name, a decoy --workflow value,
#                     and GH_REPO pointing at another repository) and a fake `gh`
#                     that serves a fabricated GREEN run to any UNPINNED query,
#                     must exit non-zero and must never print "PASS:".
#
# What this guard does NOT claim: it cannot catch an arbitrary future bypass
# spelled with a brand-new name and wired to a brand-new mechanism. C1 catches
# the two deleted names, C2/C3 catch a guard that honours them, and C4 catches
# the whole class of "something in the environment decided the verdict".
#
# `--self-test` proves each check can go RED: it scans a fixture tree that does
# contain the names (C1 must reject it), drives a stub guard that honours the
# environment variable (C2/C3/C4-probe must reject it), and feeds C4-static a
# release script that passes --fixture (it must reject that too). No network, no
# real gh, no Gradle.
#
# Runs per push in tests.yml's `guards-static` job.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SELF_REL="scripts/$(basename -- "${BASH_SOURCE[0]}")"

# The two names D37 deletes. Kept as data so C1 and C2/C3 cannot drift apart:
# whatever C1 forbids in the tree is exactly what C2/C3 export at the guard.
BYPASS_NAMES=(
  "NIGHTLY_FAULT_GATE_DISABLED"
  "disable_nightly_fault_gate"
)

# Roots scanned by C1 — everything the release path can execute. docs/ is
# deliberately NOT scanned, because docs/decisions.md's D37 entry and this file's
# own header must be able to name what was removed in order to explain it.
SCAN_ROOTS=(
  "scripts"
  ".github/workflows"
  ".github/actions"
)

# The release entry point whose invocation of the guard C4-static inspects.
RELEASE_SCRIPT_REL="scripts/release-emulator-validation.sh"

# Test-only flags of check-nightly-fault-run.sh. None of them may appear in the
# release path's invocation: they replace the environment variables that used to
# be injectable, and passing one from the release script would hand the bypass
# straight back.
TEST_ONLY_FLAGS=(
  "--fixture"
  "--workflow"
  "--job-needle"
)

FAILURES=0

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  FAILURES=$((FAILURES + 1))
}

# The behavioural checks below all assert "the guard did NOT produce a green".
# Without jq the guard cannot parse a run at all and exits non-zero for that
# reason, which would satisfy every one of those assertions vacuously. Fail
# closed instead of collecting a free pass.
require_jq() {
  local label="$1"
  command -v jq >/dev/null 2>&1 && return 0
  fail "[$label] jq is not installed, so the guard cannot reach its decide path
      at all. \"It exited non-zero\" would then be evidence of nothing. Install jq."
  return 1
}

# ---------------------------------------------------------------------------
# C1: static scan.
#
# scan_tree <listing-mode> <root> [exclude-rel-path]
#   listing-mode "git"  — git-tracked files under <root> (the real check)
#   listing-mode "find" — every file under <root> (self-test fixture trees)
#
# Prints one "<file>:<line>:<text>" per hit; returns 0 when clean, 1 on a hit.
# ---------------------------------------------------------------------------
scan_tree() {
  local mode="$1" root="$2" exclude="${3:-}"
  local files=() f hits=0 name

  case "$mode" in
    git)
      # `git ls-files` keeps the scan on tracked content only, so a scratch file
      # or an untracked worktree artifact cannot turn the guard red.
      while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        [[ -n "$exclude" && "$f" == "$exclude" ]] && continue
        files+=("$f")
      done < <(git -C "$root" ls-files -- "${SCAN_ROOTS[@]}" 2>/dev/null)
      ;;
    find)
      while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        f="${f#"$root"/}"
        [[ -n "$exclude" && "$f" == "$exclude" ]] && continue
        files+=("$f")
      done < <(find "$root" -type f 2>/dev/null)
      ;;
    *)
      printf 'scan_tree: unknown listing mode %s\n' "$mode" >&2
      return 2
      ;;
  esac

  for f in "${files[@]}"; do
    [[ -f "$root/$f" ]] || continue
    for name in "${BYPASS_NAMES[@]}"; do
      if grep -Fn -- "$name" "$root/$f" >/dev/null 2>&1; then
        grep -Fn -- "$name" "$root/$f" | while IFS= read -r line; do
          printf '%s:%s\n' "$f" "$line"
        done
        hits=1
      fi
    done
  done

  [[ "$hits" -eq 0 ]]
}

check_static() {
  local out
  if out="$(scan_tree git "$ROOT_DIR" "$SELF_REL")"; then
    printf 'ok   [C1 static] neither bypass name appears under %s\n' "${SCAN_ROOTS[*]}"
    return 0
  fi
  printf '%s\n' "$out" >&2
  fail "D37 bypass name reintroduced in the release path (hits above).
      The nightly fault-injection verdict is not opt-out-able. To unblock a
      release: fix the failing test/journey, or quarantine that class through
      the D36(4) flake mechanism (auto-filed issue, non-blocking lane, 2-week
      expiry). See docs/decisions.md D37 and docs/release.md."
  return 1
}

# ---------------------------------------------------------------------------
# C2/C3: behavioural.
#
# assert_guard_blocks <label> <guard-script> <job-conclusion> <reason-needle>
#   Drives the guard through its real fetch+decide path on a fixture run whose
#   fault-verdict job concluded <job-conclusion> ("" = the job never ran), with
#   every bypass name exported to "1". The guard must exit non-zero AND block
#   for <reason-needle>. Pinning the reason is not pedantry: in round 1 the
#   no-verdict case blocked as STALE because an empty field collapsed in an
#   `IFS=$'\t' read`, so the branch this check exists to cover never ran and the
#   exit-code-only assertion reported `ok`.
# ---------------------------------------------------------------------------
assert_guard_blocks() {
  local label="$1" guard="$2" job_conclusion="$3" reason_needle="$4"
  local sha="dddddddddddddddddddddddddddddddddddddddd"
  local tmp rc out env_args=() name

  tmp="$(mktemp)"
  printf '{"status":"completed","jobConclusion":"%s","headSha":"%s","databaseId":379379}\n' \
    "$job_conclusion" "$sha" > "$tmp"

  for name in "${BYPASS_NAMES[@]}"; do
    env_args+=("$name=1")
  done

  # The fixture arrives as a FLAG (the guard reads no environment variable any
  # more, C4 below asserts that); the deleted bypass names are still exported,
  # because that is what C2/C3 are here to prove is ignored.
  # `|| rc=$?` keeps this errexit-safe without ever toggling `set -e`: a helper
  # that re-enables errexit on behalf of its caller kills the self-test the first
  # time a check legitimately returns non-zero.
  rc=0
  out="$(env "${env_args[@]}" \
    bash "$guard" --fixture "$tmp" --release-head "$sha" 2>&1)" || rc=$?
  rm -f "$tmp"

  if [[ "$rc" -eq 0 ]]; then
    printf '%s\n' "$out" >&2
    fail "[$label] $guard exited 0 on a non-green fault verdict while the D37
      bypass names were set. The release gate is opt-out-able again."
    return 1
  fi

  if ! printf '%s' "$out" | grep -qF -- "$reason_needle"; then
    printf '%s\n' "$out" >&2
    fail "[$label] $guard exited $rc, but not for the expected reason
      (\"$reason_needle\"). A guard that blocks by accident is not a guard: this
      check would keep passing after the branch it covers stopped being reached."
    return 1
  fi

  printf 'ok   [%s] guard exited %s for the right reason, every bypass name set\n' "$label" "$rc"
  return 0
}

check_behavioural() {
  local guard="$ROOT_DIR/scripts/check-nightly-fault-run.sh"
  if [[ ! -f "$guard" ]]; then
    fail "scripts/check-nightly-fault-run.sh is missing — the release gate has no
      nightly fault guard to enforce."
    return 1
  fi
  local rc=0
  require_jq "C2/C3" || return 1
  assert_guard_blocks "C2 red-verdict" "$guard" "failure" \
    "safety verdict is RED" || rc=1
  assert_guard_blocks "C3 no-verdict"  "$guard" "" \
    "did not run the journey job" || rc=1
  return "$rc"
}

# ---------------------------------------------------------------------------
# C4-static: the release path invokes the guard, and invokes it clean.
#
# assert_release_invocation_clean <label> <release-script>
#   Extracts check_nightly_fault_run()'s body, drops comments, and requires that
#   it (a) still runs scripts/check-nightly-fault-run.sh, (b) passes no test-only
#   flag, and (c) sets no NIGHTLY_FAULT_* variable for that call.
# ---------------------------------------------------------------------------
assert_release_invocation_clean() {
  local label="$1" release_script="$2"
  local body flag

  if [[ ! -f "$release_script" ]]; then
    fail "[$label] $release_script is missing — cannot verify how the release
      path invokes the nightly fault guard."
    return 1
  fi

  body="$(awk '/^check_nightly_fault_run\(\)[[:space:]]*\{/{f=1} f{print} f&&/^\}/{exit}' \
    "$release_script" | sed 's/#.*$//')"

  if [[ -z "$body" ]]; then
    fail "[$label] $release_script has no check_nightly_fault_run() function —
      the release gate's nightly fault check is gone, not just bypassable."
    return 1
  fi

  if ! grep -qF -- "check-nightly-fault-run.sh" <<<"$body"; then
    fail "[$label] check_nightly_fault_run() no longer invokes
      scripts/check-nightly-fault-run.sh. The release path lost its fault gate."
    return 1
  fi

  for flag in "${TEST_ONLY_FLAGS[@]}"; do
    if grep -qF -- "$flag" <<<"$body"; then
      printf '%s\n' "$body" >&2
      fail "[$label] the release path passes the test-only flag '$flag' to
        check-nightly-fault-run.sh. Those flags fabricate/redirect the verdict;
        the release invocation may pass only --release-head."
      return 1
    fi
  done

  if grep -qE 'NIGHTLY_FAULT_[A-Z_]+=' <<<"$body"; then
    printf '%s\n' "$body" >&2
    fail "[$label] the release path sets a NIGHTLY_FAULT_* variable around the
      guard call. The guard reads no environment variable by design (D37); an
      env prefix here is either dead code or a reintroduced injection point."
    return 1
  fi

  printf 'ok   [%s] the release path invokes the guard with no test-only flag or NIGHTLY_FAULT_* env\n' "$label"
  return 0
}

# ---------------------------------------------------------------------------
# C4-probe: a hostile environment cannot fabricate a green verdict.
#
# assert_env_cannot_fabricate_green <label> <guard-script>
#   Invokes <guard-script> the way release-emulator-validation.sh invokes it
#   (only --release-head), with:
#     * a fabricated GREEN run file exported under every legacy variable name,
#     * NIGHTLY_FAULT_WORKFLOW/_JOB_NEEDLE decoys,
#     * GH_REPO pointing at another repository,
#     * a fake `gh` first on PATH that answers an UNPINNED query with a
#       fabricated GREEN run covering the release HEAD, and a --repo-pinned one
#       with an empty list.
#   The guard must exit non-zero, must not print "PASS:", and must not have used
#   the decoy workflow name. Hermetic: no network, no real gh, no credentials.
# ---------------------------------------------------------------------------
assert_env_cannot_fabricate_green() {
  local label="$1" guard="$2"
  local sha="eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
  local tmpdir rc out
  tmpdir="$(mktemp -d)"

  printf '{"status":"completed","jobConclusion":"success","headSha":"%s","databaseId":379379}\n' \
    "$sha" > "$tmpdir/fabricated-green.json"

  mkdir -p "$tmpdir/bin"
  cat > "$tmpdir/bin/gh" <<GHEOF
#!/usr/bin/env bash
# Fake gh for the D37 C4 probe. An UNPINNED query (one that trusts \$GH_REPO)
# gets a fabricated GREEN nightly run; a --repo-pinned query gets nothing.
pinned=0
for a in "\$@"; do
  case "\$a" in --repo|-R|--repo=*) pinned=1 ;; esac
done
if [[ "\${1:-}" == "run" && "\${2:-}" == "list" ]]; then
  if [[ "\$pinned" == 1 ]]; then
    echo '[]'
  else
    printf '[{"databaseId":911,"headSha":"%s","status":"completed","conclusion":"success","createdAt":"2026-01-01T00:00:00Z"}]\n' "$sha"
  fi
  exit 0
fi
if [[ "\${1:-}" == "run" && "\${2:-}" == "view" ]]; then
  echo '{"jobs":[{"name":"Fault-injection safety verdict","conclusion":"success"}]}'
  exit 0
fi
echo "fake gh: unexpected invocation: \$*" >&2
exit 1
GHEOF
  chmod +x "$tmpdir/bin/gh"

  rc=0
  # NIGHTLY_FAULT_RELEASE_HEAD is exported too, so a guard of the ROUND-1 shape
  # (which read the release head from the environment) would happily agree that
  # the fabricated run covers the release commit and print its green. Without it
  # such a guard would block as STALE and this probe would pass vacuously.
  out="$(PATH="$tmpdir/bin:$PATH" env \
    NIGHTLY_FAULT_RUN_FIXTURE="$tmpdir/fabricated-green.json" \
    NIGHTLY_FAULT_RELEASE_HEAD="$sha" \
    NIGHTLY_FAULT_WORKFLOW="attacker-decoy.yml" \
    NIGHTLY_FAULT_JOB_NEEDLE="Guard" \
    GH_REPO="attacker/green-fork" \
    bash "$guard" --release-head "$sha" 2>&1)" || rc=$?

  local verdict=0
  if [[ "$rc" -eq 0 ]]; then
    printf '%s\n' "$out" >&2
    fail "[$label] $guard exited 0 on a fabricated verdict supplied purely
      through the environment. This is the round-1 bypass: one exported variable
      plus a three-line JSON file writes a green into the release summary."
    verdict=1
  elif printf '%s' "$out" | grep -qF -- "PASS:"; then
    printf '%s\n' "$out" >&2
    fail "[$label] $guard printed a PASS line while being fed a fabricated
      environment. Even with a non-zero exit, a 'PASS: ... verdict is green'
      line in the release summary is an artifact that lies."
    verdict=1
  elif printf '%s' "$out" | grep -qF -- "attacker-decoy.yml"; then
    printf '%s\n' "$out" >&2
    fail "[$label] $guard read its target workflow from the environment
      (NIGHTLY_FAULT_WORKFLOW). Redirecting the guard at a different, green
      workflow is the same bypass wearing a different name."
    verdict=1
  fi

  rm -rf "$tmpdir"
  if [[ "$verdict" -eq 0 ]]; then
    printf 'ok   [%s] a hostile environment (fixture + workflow decoy + GH_REPO) produced no green\n' "$label"
  fi
  return "$verdict"
}

check_env_injection() {
  local guard="$ROOT_DIR/scripts/check-nightly-fault-run.sh"
  local rc=0
  assert_release_invocation_clean "C4-static" "$ROOT_DIR/$RELEASE_SCRIPT_REL" || rc=1
  if [[ ! -f "$guard" ]]; then
    return 1  # already reported by check_behavioural
  fi
  require_jq "C4-probe" || return 1
  assert_env_cannot_fabricate_green "C4-probe" "$guard" || rc=1
  return "$rc"
}

# ---------------------------------------------------------------------------
# --self-test: prove both checks can go RED.
# ---------------------------------------------------------------------------
self_test() {
  local failures=0 tmpdir
  tmpdir="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '$tmpdir'" RETURN

  # --- C1 can go red: a fixture tree that reintroduces each name. ---
  local name i=0
  for name in "${BYPASS_NAMES[@]}"; do
    i=$((i + 1))
    local tree="$tmpdir/tree$i"
    mkdir -p "$tree/scripts" "$tree/.github/workflows"
    printf '#!/usr/bin/env bash\necho hello\n' > "$tree/scripts/clean.sh"
    printf 'name: x\n' > "$tree/.github/workflows/clean.yml"
    printf 'if [[ "${%s:-0}" == "1" ]]; then exit 0; fi\n' "$name" \
      > "$tree/scripts/reintroduced.sh"
    if scan_tree find "$tree" >/dev/null 2>&1; then
      printf 'FAIL [self-test C1/%s]: static scan stayed GREEN over a tree that reintroduces the name\n' "$name"
      failures=$((failures + 1))
    else
      printf 'ok   [self-test C1/%s] static scan reddens on a reintroduced name\n' "$name"
    fi
  done

  # --- C1 does not false-alarm on a clean tree. ---
  local clean="$tmpdir/clean"
  mkdir -p "$clean/scripts" "$clean/.github/workflows"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$clean/scripts/ok.sh"
  printf 'name: ok\n' > "$clean/.github/workflows/ok.yml"
  if scan_tree find "$clean" >/dev/null 2>&1; then
    printf 'ok   [self-test C1/clean] static scan stays green on a clean tree\n'
  else
    printf 'FAIL [self-test C1/clean]: static scan reddened on a tree with neither name\n'
    failures=$((failures + 1))
  fi

  # --- C1 excludes only the named path, and does so by exact path. ---
  local excl="$tmpdir/excl"
  mkdir -p "$excl/scripts"
  printf '%s\n' "${BYPASS_NAMES[0]}" > "$excl/scripts/self.sh"
  if scan_tree find "$excl" "scripts/self.sh" >/dev/null 2>&1; then
    printf 'ok   [self-test C1/exclude] the guard can exempt its own source\n'
  else
    printf 'FAIL [self-test C1/exclude]: exclusion of the guard'"'"'s own source did not apply\n'
    failures=$((failures + 1))
  fi

  # --- C2/C3 can go red: a stub guard that DOES honour the bypass variable. ---
  local stub="$tmpdir/bypassing-guard.sh"
  cat > "$stub" <<STUB
#!/usr/bin/env bash
set -euo pipefail
if [[ "\${${BYPASS_NAMES[0]}:-0}" == "1" ]]; then
  echo "SKIPPED: guard disabled (escape hatch)."
  exit 0
fi
echo "BLOCK: fault verdict is not green."
exit 1
STUB

  # `expect_red <label> <description> <command...>` runs one assertion helper and
  # requires it to have reported a failure; `expect_green` the opposite. Both
  # restore $FAILURES so a deliberate self-test red never leaks into the verdict.
  expect_red() {
    local label="$1" what="$2"; shift 2
    local before="$FAILURES"
    "$@" >/dev/null 2>&1 || true
    if [[ "$FAILURES" -gt "$before" ]]; then
      printf 'ok   [self-test %s] %s\n' "$label" "$what"
    else
      printf 'FAIL [self-test %s]: stayed GREEN — %s\n' "$label" "$what"
      failures=$((failures + 1))
    fi
    FAILURES="$before"
  }
  expect_green() {
    local label="$1" what="$2"; shift 2
    local before="$FAILURES"
    "$@" >/dev/null 2>&1 || true
    if [[ "$FAILURES" -eq "$before" ]]; then
      printf 'ok   [self-test %s] %s\n' "$label" "$what"
    else
      printf 'FAIL [self-test %s]: reddened — %s\n' "$label" "$what"
      failures=$((failures + 1))
    fi
    FAILURES="$before"
  }

  expect_red "C2/stub" "behavioural check reddens on a guard that honours the bypass" \
    assert_guard_blocks "self-test C2/stub" "$stub" "failure" "BLOCK: fault verdict"

  # --- and stays green on a guard that ignores it entirely. ---
  local honest="$tmpdir/honest-guard.sh"
  cat > "$honest" <<'HONEST'
#!/usr/bin/env bash
set -euo pipefail
echo "BLOCK: fault verdict is not green."
exit 1
HONEST
  expect_green "C2/honest" "behavioural check stays green on a non-bypassable guard" \
    assert_guard_blocks "self-test C2/honest" "$honest" "failure" "BLOCK: fault verdict"

  # --- C2/C3 reject a guard that blocks for the WRONG reason (the round-1 hole:
  #     the no-verdict case exited 1 through the STALE branch and nobody noticed).
  local wrongreason="$tmpdir/wrong-reason-guard.sh"
  cat > "$wrongreason" <<'WRONG'
#!/usr/bin/env bash
set -euo pipefail
echo "BLOCK: something unrelated went wrong."
exit 1
WRONG
  expect_red "C2/wrong-reason" "behavioural check reddens when the block reason is not the expected branch" \
    assert_guard_blocks "self-test C2/wrong-reason" "$wrongreason" "failure" "BLOCK: fault verdict"

  # --- C4-probe can go red: the ROUND-1 guard shape, which took its answer from
  #     NIGHTLY_FAULT_RUN_FIXTURE in the environment. ---
  local envfixture="$tmpdir/env-fixture-guard.sh"
  cat > "$envfixture" <<'ENVFIX'
#!/usr/bin/env bash
set -euo pipefail
# Round-1 shape: the fixture arrives through the ENVIRONMENT and supplies the answer.
if [[ -n "${NIGHTLY_FAULT_RUN_FIXTURE:-}" ]]; then
  echo "PASS: nightly fault-injection safety verdict is green (fault-verdict job conclusion=success)."
  exit 0
fi
echo "BLOCK: no fault signal."
exit 1
ENVFIX
  expect_red "C4-probe/env-fixture" "injection probe reddens on a guard that takes its verdict from the environment" \
    assert_env_cannot_fabricate_green "self-test C4-probe/env-fixture" "$envfixture"

  # --- and on a guard that trusts GH_REPO (queries `gh` unpinned). ---
  local unpinned="$tmpdir/unpinned-guard.sh"
  cat > "$unpinned" <<'UNPINNED'
#!/usr/bin/env bash
set -euo pipefail
runs="$(gh run list --workflow=app2.yml --limit 1 --json databaseId,headSha,status)"
if [[ "$(jq 'length' <<<"$runs")" -gt 0 ]]; then
  echo "PASS: nightly fault-injection safety verdict is green."
  exit 0
fi
echo "BLOCK: no nightly fault run found."
exit 1
UNPINNED
  expect_red "C4-probe/gh-repo" "injection probe reddens on a guard that queries gh unpinned (trusts \$GH_REPO)" \
    assert_env_cannot_fabricate_green "self-test C4-probe/gh-repo" "$unpinned"

  expect_green "C4-probe/honest" "injection probe stays green on a guard that reads no environment" \
    assert_env_cannot_fabricate_green "self-test C4-probe/honest" "$honest"

  # --- C4-static can go red: a release script that passes a test-only flag, one
  #     that re-adds an env prefix, and one that dropped the guard call. ---
  local rel_flag="$tmpdir/release-with-flag.sh" rel_env="$tmpdir/release-with-env.sh"
  local rel_gone="$tmpdir/release-without-guard.sh" rel_ok="$tmpdir/release-clean.sh"
  cat > "$rel_flag" <<'RELF'
check_nightly_fault_run() {
  scripts/check-nightly-fault-run.sh --fixture /tmp/green.json --release-head "$(git rev-parse HEAD)"
}
RELF
  cat > "$rel_env" <<'RELE'
check_nightly_fault_run() {
  env NIGHTLY_FAULT_RUN_FIXTURE=/tmp/green.json scripts/check-nightly-fault-run.sh
}
RELE
  cat > "$rel_gone" <<'RELG'
check_nightly_fault_run() {
  echo "nothing to see here"
}
RELG
  cat > "$rel_ok" <<'RELOK'
check_nightly_fault_run() {
  # --fixture must never appear outside a comment; this line proves comments are stripped.
  scripts/check-nightly-fault-run.sh --release-head "$(git rev-parse HEAD)"
}
RELOK
  expect_red "C4-static/flag" "release-invocation check reddens on a test-only flag in the release path" \
    assert_release_invocation_clean "self-test C4-static/flag" "$rel_flag"
  expect_red "C4-static/env" "release-invocation check reddens on a NIGHTLY_FAULT_* env prefix" \
    assert_release_invocation_clean "self-test C4-static/env" "$rel_env"
  expect_red "C4-static/gone" "release-invocation check reddens when the release path stops calling the guard" \
    assert_release_invocation_clean "self-test C4-static/gone" "$rel_gone"
  expect_green "C4-static/clean" "release-invocation check stays green on a clean --release-head-only call" \
    assert_release_invocation_clean "self-test C4-static/clean" "$rel_ok"

  echo
  if [[ "$failures" -eq 0 ]]; then
    echo "SELF-TEST PASS: every check in check-release-gate-bypass-absent.sh can go red."
    return 0
  fi
  echo "SELF-TEST FAIL: $failures case(s) wrong."
  return 1
}

main() {
  if [[ "${1:-}" == "--self-test" ]]; then
    self_test
    exit $?
  fi

  echo "D37 release-gate bypass guard (issue #2379)"
  check_static || true
  check_behavioural || true
  check_env_injection || true

  if [[ "$FAILURES" -eq 0 ]]; then
    # Deliberately specific. The round-1 banner claimed "no bypass path" while a
    # live environment-injected bypass survived; a guard must claim exactly what
    # it asserted and nothing more.
    echo "PASS: the deleted D37 names are absent (C1), the nightly fault guard ignores them (C2/C3), and no environment variable can fabricate its verdict on the release path (C4)."
    exit 0
  fi
  printf 'FAILED: %s check(s) — see above.\n' "$FAILURES" >&2
  exit 1
}

main "$@"
