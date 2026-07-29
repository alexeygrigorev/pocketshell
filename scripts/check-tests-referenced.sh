#!/usr/bin/env bash
# Unreferenced-test-asset guard (issue #1853).
#
# THE CLASS THIS EXISTS TO KILL
#
# `process.md` catalogues the vacuous GREEN at length — the run that reports a
# pass over zero executed tests. This guard is for its quieter sibling: the test
# asset that never claims a colour at all, because no gate ever invokes it.
#
# `tests/scripts/` held sixteen shell harnesses. Twelve were referenced by
# nothing — no workflow, no Gradle task, no script, no doc instructing a human to
# run them. They had never executed. When #1853 finally ran them, four failed
# immediately; they had been silently rotting for months while remaining fully
# citable in a review as "coverage". One of the four, `avd-lock-sharing-test.sh`'s
# sibling property, is exactly the shape that let the Docker half of the #1657
# pool lock regress unnoticed until #1842 — two review rounds and two
# misattributed product defects (#1810/#1820).
#
# Each of the three instances of this class found so far (#1851, #1846, #1853)
# was found INCIDENTALLY, by someone looking for something else. None was found
# by a guard. This is that guard.
#
# WHAT IT CHECKS
#
# Every git-tracked file under tests/ must be REACHABLE:
#
#   1. Roots  — a file named by anything outside tests/ (workflow, Gradle script,
#      shell script, Kotlin source, doc).
#   2. Closure — a file named by an already-reachable file inside tests/. This is
#      what lets tests/docker/docker-compose.yml vouch for Dockerfile.agents,
#      which in turn vouches for agent-entrypoint.sh, and so on to a fixpoint.
#
# A file reachable by neither is dead. Wire it into a gate, or delete it (D22 —
# a deleted script is honest; a dead one is not).
#
# MATCHING, AND WHAT IT DELIBERATELY DOES NOT DO
#
# A file is "named" when any suffix of its repo-relative path appears literally
# in a candidate file: tests/docker/agent-bin/claude matches a reference written
# as the full path, as `docker/agent-bin/claude`, as `agent-bin/claude`, or as
# the bare `claude` (a COPY into an image directory). Shortening to the bare
# basename is intentionally permissive: this guard's job is to catch the file
# nobody mentions ANYWHERE, and it must not manufacture false alarms that get it
# disabled. It will therefore miss a dead file whose basename happens to be a
# common word. That is the honest bound, and it is stated here so nobody mistakes
# this guard for stronger than it is.
#
# Directory-level references are deliberately NOT treated as vouching for the
# files inside them. `tests/scripts` appears in a comment in AvdLockScriptTest.kt;
# honouring that as a directory reference would have marked all sixteen harnesses
# live and this guard would have reported green over the exact defect that
# motivated it.
#
# There is NO allowlist. An allowlist is how a guard becomes decoration.

set -uo pipefail

REPO_ROOT="${POCKETSHELL_TESTS_REFERENCED_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
TESTS_DIR="${POCKETSHELL_TESTS_REFERENCED_DIR:-tests}"

usage() {
  cat <<'USAGE'
Usage: scripts/check-tests-referenced.sh [--self-test]

Fails when a git-tracked file under tests/ is referenced by nothing: not from
outside tests/, and not from any file under tests/ that is itself reachable.

--self-test runs a synthetic red->green proof on a throwaway git repository: a
dead fixture must be REPORTED, and the same fixture must go quiet once a
workflow references it. It proves the guard can still fail.
USAGE
}

# Emits every suffix of a repo-relative path, longest first:
#   tests/docker/agent-bin/claude
#   docker/agent-bin/claude
#   agent-bin/claude
#   claude
path_suffixes() {
  local cur="$1"
  while :; do
    printf '%s\n' "$cur"
    [[ "$cur" == */* ]] || break
    cur="${cur#*/}"
  done
}

# Reports the unreachable files, one per line, on stdout. Returns 0 always; the
# caller decides what an unreachable file means.
collect_unreferenced() {
  local root="$1" tests_dir="$2"
  cd "$root" || return 1

  # --others --exclude-standard so a NEW, not-yet-committed dead asset is caught
  # before it lands, not one merge later. Same reason the reference scan below
  # passes --untracked to git grep: a fix and the wiring that gates it usually
  # arrive uncommitted in the same worktree.
  local -a files
  mapfile -t files < <(git ls-files --cached --others --exclude-standard -- "$tests_dir" | sort -u)
  if [[ "${#files[@]}" -eq 0 ]]; then
    return 0
  fi

  local -A reachable=()
  local file suffix

  # Round 0: named from outside tests/.
  for file in "${files[@]}"; do
    while read -r suffix; do
      if git grep --untracked -F -q -I -- "$suffix" -- ":!$tests_dir/" 2>/dev/null; then
        reachable["$file"]=1
        break
      fi
    done < <(path_suffixes "$file")
  done

  # Closure: named from an already-reachable file under tests/. Iterate to a
  # fixpoint so a chain (compose -> Dockerfile -> entrypoint -> fixture) resolves
  # regardless of the order `git ls-files` happens to return.
  local added=1
  local -a frontier
  while [[ "$added" -eq 1 ]]; do
    added=0
    frontier=()
    for file in "${files[@]}"; do
      [[ -n "${reachable[$file]:-}" ]] && frontier+=("$file")
    done
    [[ "${#frontier[@]}" -eq 0 ]] && break
    for file in "${files[@]}"; do
      [[ -n "${reachable[$file]:-}" ]] && continue
      while read -r suffix; do
        if grep -F -q -I -- "$suffix" "${frontier[@]}" 2>/dev/null; then
          reachable["$file"]=1
          added=1
          break
        fi
      done < <(path_suffixes "$file")
    done
  done

  for file in "${files[@]}"; do
    [[ -z "${reachable[$file]:-}" ]] && printf '%s\n' "$file"
  done
  return 0
}

run_check() {
  local root="$1" tests_dir="$2"
  local -a dead
  mapfile -t dead < <(collect_unreferenced "$root" "$tests_dir")

  # mapfile on empty input still yields a single empty element in some bash
  # builds; normalise before counting.
  local -a filtered=()
  local entry
  for entry in "${dead[@]:-}"; do
    [[ -n "$entry" ]] && filtered+=("$entry")
  done

  if [[ "${#filtered[@]}" -eq 0 ]]; then
    printf 'OK: every tracked file under %s/ is reachable from a gate, a script, a doc, or another reachable test asset.\n' \
      "$tests_dir"
    return 0
  fi

  printf '::error title=Unreferenced test asset::%d file(s) under %s/ are referenced by nothing.\n' \
    "${#filtered[@]}" "$tests_dir" >&2
  for entry in "${filtered[@]}"; do
    printf '  %s\n' "$entry" >&2
  done
  cat >&2 <<'REMEDY'

Nothing invokes these, so they have never run and cannot fail. Pick one:
  * wire it into a gate that executes it (prefer the Unit job unless it needs an
    emulator or a Docker daemon), or
  * delete it (D22 - a deleted script is honest; a dead one is not).

Adding a mention in a comment is NOT wiring it. See issue #1853.
REMEDY
  return 1
}

# Synthetic red->green proof on a throwaway repository. If this guard ever stops
# being able to FAIL, this is what says so.
run_self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  local repo="$tmp/repo"
  mkdir -p "$repo/.github/workflows" "$repo/tests/scripts" "$repo/tests/docker"
  git -C "$repo" init -q
  git -C "$repo" config user.email selftest@example.com
  git -C "$repo" config user.name selftest

  # A live harness: named by a workflow.
  printf 'echo live\n' > "$repo/tests/scripts/live-harness-test.sh"
  printf 'jobs:\n  unit:\n    steps:\n      - run: bash tests/scripts/live-harness-test.sh\n' \
    > "$repo/.github/workflows/tests.yml"

  # A live chain: the compose file is named from outside, and it names the
  # Dockerfile, which names the entrypoint. Only the closure can see the tail.
  printf 'services:\n  agents:\n    build:\n      dockerfile: Dockerfile.selftest\n' \
    > "$repo/tests/docker/docker-compose.yml"
  printf 'COPY selftest-entrypoint.sh /entrypoint.sh\n' > "$repo/tests/docker/Dockerfile.selftest"
  printf 'echo entrypoint\n' > "$repo/tests/docker/selftest-entrypoint.sh"
  printf 'docker compose -f tests/docker/docker-compose.yml up\n' > "$repo/run-agents.sh"

  # The dead one.
  printf 'echo nobody calls me\n' > "$repo/tests/scripts/dead-harness-test.sh"

  git -C "$repo" add -A
  git -C "$repo" commit -qm selftest

  local failures=0
  local report

  printf '== self-test: dead harness present (expect FAIL) ==\n'
  report="$(run_check "$repo" tests 2>&1)"
  if [[ $? -eq 0 ]]; then
    printf '   -> UNEXPECTED PASS: the guard did not report the dead harness\n\n' >&2
    failures=$((failures + 1))
  elif ! grep -q 'tests/scripts/dead-harness-test.sh' <<< "$report"; then
    printf '   -> UNEXPECTED: guard failed but did not name the dead harness\n%s\n\n' "$report" >&2
    failures=$((failures + 1))
  elif grep -q 'tests/scripts/live-harness-test.sh' <<< "$report"; then
    printf '   -> UNEXPECTED: guard reported the workflow-referenced harness as dead\n%s\n\n' "$report" >&2
    failures=$((failures + 1))
  elif grep -q 'selftest-entrypoint.sh' <<< "$report"; then
    printf '   -> UNEXPECTED: closure failed; a transitively-referenced file was called dead\n%s\n\n' "$report" >&2
    failures=$((failures + 1))
  else
    printf '   -> FAIL as expected, naming only tests/scripts/dead-harness-test.sh\n\n'
  fi

  printf '== self-test: same harness now referenced (expect PASS) ==\n'
  printf '      - run: bash tests/scripts/dead-harness-test.sh\n' \
    >> "$repo/.github/workflows/tests.yml"
  git -C "$repo" add -A
  git -C "$repo" commit -qm wire
  if run_check "$repo" tests > /dev/null 2>&1; then
    printf '   -> PASS as expected once a gate references it\n\n'
  else
    printf '   -> UNEXPECTED FAIL after wiring the harness in\n\n' >&2
    failures=$((failures + 1))
  fi

  if [[ "$failures" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: %d case(s) behaved incorrectly.\n' "$failures" >&2
    return 1
  fi
  printf 'SELF-TEST OK: a dead asset is reported, a referenced asset is not, and the closure holds.\n'
  return 0
}

main() {
  case "${1:-}" in
    -h | --help)
      usage
      exit 0
      ;;
    --self-test)
      run_self_test
      exit $?
      ;;
    "")
      run_check "$REPO_ROOT" "$TESTS_DIR"
      exit $?
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
}

main "$@"
