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
# Every git-tracked file under tests/ must be REACHABLE from executable text:
#
#   1. Roots  — a file named by non-comment code outside tests/ (workflow,
#      Gradle script, shell script, Kotlin source), or by a command/code block
#      in a doc.
#   2. Closure — a file named by an already-reachable file inside tests/. This is
#      what lets tests/docker/docker-compose.yml vouch for Dockerfile.agents,
#      which in turn vouches for agent-entrypoint.sh, and so on to a fixpoint.
#
# Comments and prose do NOT count. They cannot execute a test. The guard's own
# source is also excluded as a root, so its examples and remedy text can never
# vouch for the assets it checks.
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
declare -A REFERENCE_TEXT_CACHE=()
REFERENCE_TEXT_CACHE_DIR=""
REFERENCE_TEXT_CACHE_NEXT=0

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

# Prints the executable/reference-bearing regions of a file. This is deliberately
# lexical rather than a language parser: references are literal paths, and the
# important boundary is whether they occur in code or only in comments/prose.
reference_text() {
  local file="$1"
  case "$file" in
    *.md | *.markdown)
      # In docs, only fenced or indented code can instruct a human to execute a
      # harness. A prose citation is useful documentation, but is not wiring.
      awk '
        /^[[:space:]]*(```|~~~)/ { fenced = !fenced; next }
        fenced || /^    / || /^\t/ { print }
      ' "$file"
      ;;
    *.rst | *.rest | */README*.txt | README*.txt)
      # reStructuredText literal/code blocks and plain-text README command
      # examples are indented. Ordinary paragraphs cannot make an asset reachable.
      awk '/^    / || /^\t/ { print }' "$file"
      ;;
    *.adoc | *.asciidoc)
      # AsciiDoc source/listing blocks may use ---- or .... delimiters. Also
      # accept Markdown fences and indented examples used by mixed-format docs.
      awk '
        /^[[:space:]]*(```|~~~|----|\.\.\.\.)[[:space:]]*$/ {
          fenced = !fenced
          next
        }
        fenced || /^    / || /^\t/ { print }
      ' "$file"
      ;;
    *.kt | *.kts | *.java | *.groovy | *.gradle | *.js | *.ts | *.c | *.cc | *.cpp | *.h | *.hpp)
      # Strip // and /* */ comments while retaining quoted code strings.
      awk '
        function code_without_comments(s,    i, c, n, quote, escaped, out) {
          out = ""
          quote = ""
          escaped = 0
          for (i = 1; i <= length(s); i++) {
            c = substr(s, i, 1)
            n = substr(s, i, 2)
            if (block_comment) {
              if (n == "*/") {
                block_comment = 0
                i++
              }
              continue
            }
            if (quote != "") {
              out = out c
              if (escaped) {
                escaped = 0
              } else if (c == "\\") {
                escaped = 1
              } else if (c == quote) {
                quote = ""
              }
              continue
            }
            if (c == "\"" || c == "\047") {
              quote = c
              out = out c
            } else if (n == "//") {
              break
            } else if (n == "/*") {
              block_comment = 1
              i++
            } else {
              out = out c
            }
          }
          return out
        }
        { print code_without_comments($0) }
      ' "$file"
      ;;
    *.sh | *.bash | *.zsh | *.py | *.rb | *.yml | *.yaml | *.toml | *.properties | \
      */Dockerfile | */Dockerfile.* | Dockerfile | Dockerfile.* | *.dockerfile | \
      */Containerfile | */Containerfile.* | Containerfile | Containerfile.* | \
      */Makefile | */Makefile.* | Makefile | Makefile.* | *.mk)
      # Strip # comments, but not a # inside a quoted command/string.
      awk '
        function code_without_hash_comment(s,    i, c, quote, escaped, out) {
          out = ""
          quote = ""
          escaped = 0
          for (i = 1; i <= length(s); i++) {
            c = substr(s, i, 1)
            if (quote != "") {
              out = out c
              if (escaped) {
                escaped = 0
              } else if (c == "\\") {
                escaped = 1
              } else if (c == quote) {
                quote = ""
              }
            } else if (c == "\"" || c == "\047") {
              quote = c
              out = out c
            } else if (c == "#") {
              break
            } else {
              out = out c
            }
          }
          return out
        }
        { print code_without_hash_comment($0) }
      ' "$file"
      ;;
    *.xml | *.html)
      awk '
        {
          line = $0
          while (length(line) > 0) {
            if (xml_comment) {
              end = index(line, "-->")
              if (!end) {
                line = ""
              } else {
                line = substr(line, end + 3)
                xml_comment = 0
              }
            } else {
              start = index(line, "<!--")
              if (!start) {
                print line
                line = ""
              } else {
                print substr(line, 1, start - 1)
                line = substr(line, start + 4)
                xml_comment = 1
              }
            }
          }
        }
      ' "$file"
      ;;
    *)
      # Structured manifests and fixture data are reference-bearing assets.
      # Source, build, and documentation formats with comment/prose syntax are
      # classified above instead of flowing through this raw-data arm.
      printf '%s\n' "$(cat "$file")"
      ;;
  esac
}

file_has_reference() {
  local file="$1" suffix="$2"
  local cached="${REFERENCE_TEXT_CACHE[$file]:-}"
  if [[ -z "$cached" ]]; then
    REFERENCE_TEXT_CACHE_NEXT=$((REFERENCE_TEXT_CACHE_NEXT + 1))
    cached="$REFERENCE_TEXT_CACHE_DIR/$REFERENCE_TEXT_CACHE_NEXT"
    reference_text "$file" > "$cached"
    REFERENCE_TEXT_CACHE["$file"]="$cached"
  fi
  grep -F -q -- "$suffix" "$cached"
}

# Returns success when a suffix appears in executable text in at least one
# candidate. check-tests-referenced.sh is never a candidate: a guard must not
# make its own examples load-bearing.
suffix_is_referenced() {
  local suffix="$1"
  shift
  local candidate
  for candidate in "$@"; do
    [[ "$candidate" == "scripts/check-tests-referenced.sh" ]] && continue
    if file_has_reference "$candidate" "$suffix"; then
      return 0
    fi
  done
  return 1
}

# Reports the unreachable files, one per line, on stdout. Returns 0 always; the
# caller decides what an unreachable file means.
collect_unreferenced() {
  local root="$1" tests_dir="$2"
  cd "$root" || return 1
  REFERENCE_TEXT_CACHE=()
  REFERENCE_TEXT_CACHE_NEXT=0
  REFERENCE_TEXT_CACHE_DIR="$(mktemp -d)"

  # --others --exclude-standard so a NEW, not-yet-committed dead asset is caught
  # before it lands, not one merge later. Same reason the reference scan below
  # passes --untracked to git grep: a fix and the wiring that gates it usually
  # arrive uncommitted in the same worktree.
  local -a listed_files files=()
  mapfile -t listed_files < <(
    git ls-files --cached --others --exclude-standard -- "$tests_dir" | sort -u
  )
  # An implementer hard-cut deletion remains in the index until the orchestrator
  # commits it. It is no longer an asset to classify in the working tree.
  local listed_file
  for listed_file in "${listed_files[@]}"; do
    [[ -e "$listed_file" ]] && files+=("$listed_file")
  done
  if [[ "${#files[@]}" -eq 0 ]]; then
    rm -rf "$REFERENCE_TEXT_CACHE_DIR"
    return 0
  fi

  local -A reachable=()
  local file suffix

  # Round 0: named from outside tests/.
  for file in "${files[@]}"; do
    while read -r suffix; do
      local -a matching_candidates=()
      mapfile -t matching_candidates < <(
        git grep --untracked -F -l -I -- "$suffix" -- ":!$tests_dir/" 2>/dev/null
      )
      if suffix_is_referenced "$suffix" "${matching_candidates[@]}"; then
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
        if suffix_is_referenced "$suffix" "${frontier[@]}"; then
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
  rm -rf "$REFERENCE_TEXT_CACHE_DIR"
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

Adding a mention in a comment or prose is NOT wiring it. Put a real invocation
in executable code/a workflow, or a runnable command block in documentation.
See issues #1853 and #1861.
REMEDY
  return 1
}

# Synthetic proofs on a throwaway repository. They pin comment/prose handling,
# both documented matching bounds, closure, and the exact #1861 four-harness
# driver-deletion experiment.
run_self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  local repo="$tmp/repo"
  mkdir -p \
    "$repo/.github/workflows" \
    "$repo/app/src/test/java/com/pocketshell/app/scripts" \
    "$repo/scripts/lib" \
    "$repo/tests/scripts" \
    "$repo/tests/docker"
  git -C "$repo" init -q
  git -C "$repo" config user.email selftest@example.com
  git -C "$repo" config user.name selftest

  # A live harness: named by a workflow.
  printf 'echo live\n' > "$repo/tests/scripts/live-harness-test.sh"
  printf 'jobs:\n  unit:\n    steps:\n      - run: bash tests/scripts/live-harness-test.sh\n' \
    > "$repo/.github/workflows/tests.yml"

  # Bound 1: a bare basename remains a deliberately permissive reference.
  printf 'echo basename live\n' > "$repo/tests/scripts/basename-live-test.sh"
  printf '      - run: bash basename-live-test.sh\n' >> "$repo/.github/workflows/tests.yml"

  # A live chain: the compose file is named from outside, and it names the
  # Dockerfile, which names the entrypoint. Only the closure can see the tail.
  printf 'services:\n  agents:\n    build:\n      dockerfile: Dockerfile.selftest\n' \
    > "$repo/tests/docker/docker-compose.yml"
  printf 'COPY selftest-entrypoint.sh /entrypoint.sh\n' > "$repo/tests/docker/Dockerfile.selftest"
  printf 'echo entrypoint\n' > "$repo/tests/docker/selftest-entrypoint.sh"
  printf 'docker compose -f tests/docker/docker-compose.yml up\n' > "$repo/run-agents.sh"

  # Five dead files pin distinct semantics.
  printf 'echo nobody calls me\n' > "$repo/tests/scripts/dead-harness-test.sh"
  printf 'echo comments are not callers\n' > "$repo/tests/scripts/comment-only-test.sh"
  printf 'echo a directory is not its contents\n' > "$repo/tests/scripts/directory-child-test.sh"
  printf 'echo Dockerfile comments are not callers\n' \
    > "$repo/tests/scripts/dockerfile-comment-only-test.sh"
  printf 'echo non-Markdown prose is not a caller\n' \
    > "$repo/tests/scripts/rst-prose-only-test.sh"
  printf 'echo Dockerfile command live\n' \
    > "$repo/tests/scripts/dockerfile-command-live-test.sh"
  printf 'echo reStructuredText command live\n' \
    > "$repo/tests/scripts/rst-code-live-test.sh"

  # A source comment and Markdown prose are citations, not execution.
  printf '# tests/scripts/comment-only-test.sh documents the missing wiring\n' \
    > "$repo/scripts/lib/comment-example.sh"
  # shellcheck disable=SC2016 # Markdown backticks are literal self-test data.
  printf 'The file `tests/scripts/comment-only-test.sh` needs a real caller.\n' \
    > "$repo/README.md"
  printf '%s\n' \
    '# tests/scripts/dockerfile-comment-only-test.sh is documentation only' \
    'FROM scratch' \
    'RUN sh tests/scripts/dockerfile-command-live-test.sh' \
    > "$repo/Dockerfile"
  printf '%s\n' \
    'Test assets' \
    '===========' \
    '' \
    'tests/scripts/rst-prose-only-test.sh is documentation only.' \
    '' \
    '.. code-block:: sh' \
    '' \
    '    bash tests/scripts/rst-code-live-test.sh' \
    > "$repo/README.rst"

  # The guard cannot vouch for an asset through its own source, even if remedy
  # prose lives in a heredoc and is therefore not a shell # comment.
  cat > "$repo/scripts/check-tests-referenced.sh" <<'SELF_GUARD'
cat <<'REMEDY'
Run tests/scripts/comment-only-test.sh after wiring it.
REMEDY
SELF_GUARD

  # Bound 2: even an executable directory-valued string must not vouch for
  # every file below that directory.
  printf 'val testAssetsDirectory = "tests/scripts"\n' \
    > "$repo/app/src/test/java/DirectoryReference.kt"

  git -C "$repo" add -A
  git -C "$repo" commit -qm selftest

  local failures=0
  local report
  local expected

  printf '== self-test: source/build comments, doc prose, and directory mentions stay dead; basename stays live (expect FAIL) ==\n'
  if report="$(run_check "$repo" tests 2>&1)"; then
    printf '   -> UNEXPECTED PASS: the guard did not report the five dead harnesses\n\n' >&2
    failures=$((failures + 1))
  else
    if ! grep -F -q '::error title=Unreferenced test asset::5 file(s)' <<< "$report"; then
      printf '   -> UNEXPECTED: expected exactly five dead files\n%s\n\n' "$report" >&2
      failures=$((failures + 1))
    fi
    for expected in \
      tests/scripts/dead-harness-test.sh \
      tests/scripts/comment-only-test.sh \
      tests/scripts/directory-child-test.sh \
      tests/scripts/dockerfile-comment-only-test.sh \
      tests/scripts/rst-prose-only-test.sh
    do
      if ! grep -F -q "  $expected" <<< "$report"; then
        printf '   -> UNEXPECTED: guard did not report %s\n%s\n\n' "$expected" "$report" >&2
        failures=$((failures + 1))
      fi
    done
    for expected in \
      tests/scripts/live-harness-test.sh \
      tests/scripts/basename-live-test.sh \
      tests/scripts/dockerfile-command-live-test.sh \
      tests/scripts/rst-code-live-test.sh \
      tests/docker/selftest-entrypoint.sh
    do
      if grep -F -q "  $expected" <<< "$report"; then
        printf '   -> UNEXPECTED: guard reported reachable %s\n%s\n\n' "$expected" "$report" >&2
        failures=$((failures + 1))
      fi
    done
    printf '   -> FAIL as expected, with source/build comments, doc prose, and directory-only files dead\n\n'
  fi

  printf '== self-test: dead harnesses now invoked (expect PASS) ==\n'
  printf '%s\n' \
    '      - run: bash tests/scripts/dead-harness-test.sh' \
    '      - run: bash tests/scripts/comment-only-test.sh' \
    '      - run: bash tests/scripts/directory-child-test.sh' \
    '      - run: bash tests/scripts/dockerfile-comment-only-test.sh' \
    '      - run: bash tests/scripts/rst-prose-only-test.sh' \
    >> "$repo/.github/workflows/tests.yml"
  git -C "$repo" add -A
  git -C "$repo" commit -qm wire
  if run_check "$repo" tests > /dev/null 2>&1; then
    printf '   -> PASS as expected once a gate references it\n\n'
  else
    printf '   -> UNEXPECTED FAIL after wiring the harness in\n\n' >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: uncommitted D22 deletion is no longer an asset (expect PASS) ==\n'
  mv \
    "$repo/tests/scripts/dead-harness-test.sh" \
    "$repo/deleted-dead-harness-test.sh"
  if run_check "$repo" tests > /dev/null 2>&1; then
    printf '   -> PASS as expected with the tracked asset absent from the worktree\n\n'
  else
    printf '   -> UNEXPECTED FAIL: the guard classified a deleted working-tree asset\n\n' >&2
    failures=$((failures + 1))
  fi
  mv \
    "$repo/deleted-dead-harness-test.sh" \
    "$repo/tests/scripts/dead-harness-test.sh"

  # The exact production shape from #1861: four shell harnesses have one real
  # Kotlin driver. Two also appear in comments/prose, including this guard's own
  # synthetic source. Removing the driver must report all four, never two.
  local -a avd_harnesses=(
    tests/scripts/avd-lock-sharing-test.sh
    tests/scripts/avd-lock-test.sh
    tests/scripts/avd-pool-test.sh
    tests/scripts/connected-test-serial-ownership-test.sh
  )
  for expected in "${avd_harnesses[@]}"; do
    printf 'echo avd harness\n' > "$repo/$expected"
  done
  cat > "$repo/app/src/test/java/com/pocketshell/app/scripts/AvdLockScriptTest.kt" <<'AVD_DRIVER'
class AvdLockScriptTest {
  fun sharing() = ProcessBuilder("bash", "tests/scripts/avd-lock-sharing-test.sh")
  fun ownership() = ProcessBuilder("bash", "tests/scripts/avd-lock-test.sh")
  fun pool() = ProcessBuilder("bash", "tests/scripts/avd-pool-test.sh")
  fun serialOwnership() =
    ProcessBuilder("bash", "tests/scripts/connected-test-serial-ownership-test.sh")
}
AVD_DRIVER
  printf '%s\n' \
    '# tests/scripts/avd-lock-sharing-test.sh is documented here.' \
    '# tests/scripts/avd-lock-test.sh is documented here.' \
    >> "$repo/scripts/lib/comment-example.sh"
  cat >> "$repo/scripts/check-tests-referenced.sh" <<'SELF_GUARD_AVD'
cat <<'AVD_EXAMPLE'
tests/scripts/avd-lock-sharing-test.sh
AVD_EXAMPLE
SELF_GUARD_AVD
  git -C "$repo" add -A
  git -C "$repo" commit -qm avd-driver

  printf '== self-test: four AVD harnesses with their real driver (expect PASS) ==\n'
  if run_check "$repo" tests > /dev/null 2>&1; then
    printf '   -> PASS as expected while AvdLockScriptTest.kt drives all four\n\n'
  else
    printf '   -> UNEXPECTED FAIL with the real driver present\n\n' >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: delete AvdLockScriptTest.kt (expect all four DEAD) ==\n'
  git -C "$repo" rm -q app/src/test/java/com/pocketshell/app/scripts/AvdLockScriptTest.kt
  if report="$(run_check "$repo" tests 2>&1)"; then
    printf '   -> UNEXPECTED PASS after deleting the only real driver\n\n' >&2
    failures=$((failures + 1))
  else
    if ! grep -F -q '::error title=Unreferenced test asset::4 file(s)' <<< "$report"; then
      printf '   -> UNEXPECTED: expected exactly four dead AVD harnesses\n%s\n\n' \
        "$report" >&2
      failures=$((failures + 1))
    fi
    for expected in "${avd_harnesses[@]}"; do
      if ! grep -F -q "  $expected" <<< "$report"; then
        printf '   -> UNEXPECTED: driver deletion did not report %s\n%s\n\n' \
          "$expected" "$report" >&2
        failures=$((failures + 1))
      fi
    done
    printf '   -> FAIL as expected, naming all four AVD harnesses\n\n'
  fi

  if [[ "$failures" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: %d case(s) behaved incorrectly.\n' "$failures" >&2
    return 1
  fi
  printf 'SELF-TEST OK: comments/prose do not wire tests, bounds 1/2 and closure hold, and deleting AvdLockScriptTest.kt reports all four AVD harnesses.\n'
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
