#!/usr/bin/env bash
set -euo pipefail

# Repo-wide oversized-file hygiene guard.
#
# This is a ratchet, not a cleanup sweep: it accepts the current oversized
# first-party files recorded in scripts/file-size-hygiene-baseline.txt, fails if
# any of those files grow, and fails if a new non-exempt tracked file crosses the
# threshold. Lowering or deleting a baselined file is encouraged; run --update to
# lock in the smaller baseline.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BASELINE_REL="scripts/file-size-hygiene-baseline.txt"
DEFAULT_THRESHOLD_BYTES=131072 # 128 KiB
THRESHOLD_BYTES="${FILE_SIZE_HYGIENE_THRESHOLD_BYTES:-$DEFAULT_THRESHOLD_BYTES}"

# Issue #2134: a hand-authored workflow file crossing the threshold is a
# different failure from a god-object source file, and it used to arrive with no
# warning. `.github/workflows/tests.yml` sat 271 bytes under the cap, so the
# NEXT workflow addition of any realistic size failed this guard regardless of
# what it was — and the cheapest way out under time pressure was deleting the
# explanatory comments that make the workflow readable, i.e. the guard's
# practical effect became "remove the documentation".
#
# Two changes close that. (1) A workflow file that is still UNDER the cap but
# within WORKFLOW_HEADROOM_BYTES of it fails HERE, early, while the fix is still
# cheap — 1024 bytes is roughly the ~15-line job or step this margin exists to
# leave room for. (2) Every workflow failure, early or hard, prints the intended
# remedy, so the next person extracts inline shell into scripts/ instead of
# reaching for a comment, the threshold, or the baseline.
#
# This is strictly stricter than the plain cap: it introduces no new allowance
# and admits no growth the ratchet forbids, so there is nothing to ratchet down.
WORKFLOW_HEADROOM_BYTES="${FILE_SIZE_HYGIENE_WORKFLOW_HEADROOM_BYTES:-1024}"

usage() {
  cat <<'USAGE'
Usage: scripts/check-file-size-hygiene.sh [--update | --self-test]

Ratchet-style oversized-file guard over tracked, first-party files:
  - git ls-files defines the scan set.
  - vendor/generated/worktree directories and shared/core-terminal are exempt.
  - current oversized files are pinned in scripts/file-size-hygiene-baseline.txt.
  - a baselined oversized file may shrink or disappear, but may not grow.
  - a new non-exempt tracked file above the threshold fails.

  - a workflow under .github/workflows/ must also keep WORKFLOW_HEADROOM_BYTES
    of room under the threshold, and its failures name the intended remedy.

Environment:
  FILE_SIZE_HYGIENE_THRESHOLD_BYTES  override the default 131072-byte threshold.
  FILE_SIZE_HYGIENE_WORKFLOW_HEADROOM_BYTES
                                     override the default 1024-byte workflow
                                     headroom margin.

(no args)    check the real tree against the committed baseline
--update     rewrite the baseline downward only; refuses growth or new oversized files
--self-test  run a synthetic red->green proof in a temporary git repository
USAGE
}

fail_usage() {
  printf 'FAIL: %s\n' "$1" >&2
  usage >&2
  exit 2
}

validate_threshold() {
  if ! [[ "$THRESHOLD_BYTES" =~ ^[1-9][0-9]*$ ]]; then
    fail_usage "FILE_SIZE_HYGIENE_THRESHOLD_BYTES must be a positive integer (got: $THRESHOLD_BYTES)"
  fi
  if ! [[ "$WORKFLOW_HEADROOM_BYTES" =~ ^[0-9]+$ ]]; then
    fail_usage "FILE_SIZE_HYGIENE_WORKFLOW_HEADROOM_BYTES must be a non-negative integer (got: $WORKFLOW_HEADROOM_BYTES)"
  fi
}

path_is_workflow() {
  case "$1" in
    .github/workflows/*.yml|.github/workflows/*.yaml) return 0 ;;
  esac
  return 1
}

# Issue #2134: the remedy, printed on EVERY workflow-file failure so the next
# person does not reflexively delete comments to buy bytes.
print_workflow_remedy() {
  printf '  Remedy for a workflow file: extract its inline `run: |` shell into a scripts/*.sh\n' >&2
  printf '  file and call that from the step (comments move with the code they explain), or\n' >&2
  printf '  collapse duplicated job/step boilerplate. Keep the required check NAMES unchanged.\n' >&2
  printf '  Do NOT delete explanatory comments, raise the threshold, or baseline the workflow.\n' >&2
}

human_bytes() {
  awk -v bytes="$1" 'BEGIN {
    if (bytes < 1024) {
      printf "%d B", bytes
    } else {
      printf "%.1f KiB", bytes / 1024
    }
  }'
}

path_is_exempt() {
  local path="$1"
  case "$path" in
    shared/core-terminal|shared/core-terminal/*)
      return 0
      ;;
    .worktrees/*|.claude/worktrees/*|.codex/worktrees/*|*/worktree/*|*/worktrees/*)
      return 0
      ;;
    vendor/*|*/vendor/*|third_party/*|*/third_party/*|external/*|*/external/*|node_modules/*|*/node_modules/*)
      return 0
      ;;
    generated/*|*/generated/*|*/build/generated/*|build/*|*/build/*|.gradle/*|*/.gradle/*)
      return 0
      ;;
  esac
  return 1
}

# Emits "<bytes> <path>" for every tracked, non-exempt file.
collect_sizes() {
  local root="$1"
  (
    cd "$root"
    local path size
    while IFS= read -r -d '' path; do
      [[ -f "$path" ]] || continue
      if path_is_exempt "$path"; then
        continue
      fi
      size="$(wc -c < "$path" | tr -d ' ')"
      printf '%s %s\n' "$size" "$path"
    done < <(git ls-files -z)
  ) | LC_ALL=C sort -k2,2
}

collect_oversized_sizes() {
  local root="$1"
  local size path
  while read -r size path; do
    [[ -z "${size:-}" ]] && continue
    if (( size > THRESHOLD_BYTES )); then
      printf '%s %s\n' "$size" "$path"
    fi
  done < <(collect_sizes "$root")
}

load_baseline() {
  local file="$1"
  local -n out_ref="$2"
  out_ref=()

  if [[ ! -f "$file" ]]; then
    printf 'FAIL: missing baseline %s\n' "$file" >&2
    printf '  run once on the current tree: scripts/check-file-size-hygiene.sh --update\n' >&2
    return 2
  fi

  local line size path
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    read -r size path <<< "$line"
    if [[ -z "${size:-}" || -z "${path:-}" ]]; then
      printf 'FAIL: malformed baseline line in %s: %s\n' "$file" "$line" >&2
      return 2
    fi
    if ! [[ "$size" =~ ^[0-9]+$ ]]; then
      printf 'FAIL: malformed byte count in %s: %s\n' "$file" "$line" >&2
      return 2
    fi
    out_ref["$path"]="$size"
  done < "$file"
}

write_baseline() {
  local root="$1" file="$2"
  {
    echo "# oversized first-party file-size hygiene baseline"
    echo "# format: <accepted-byte-count> <tracked-path>"
    echo "# threshold_bytes $THRESHOLD_BYTES"
    echo "# Generated by: scripts/check-file-size-hygiene.sh --update"
    echo "# Ratchet rule: baselined files may shrink or disappear, but may not grow."
    echo "# New non-exempt tracked files above threshold_bytes fail the guard."
    echo "# Exemptions: vendor/generated/worktree dirs and shared/core-terminal."
    collect_oversized_sizes "$root"
  } > "$file"
}

check_tree() {
  local root="$1" baseline_file="$2"
  declare -A baseline=()
  declare -A current=()
  load_baseline "$baseline_file" baseline || return $?

  local size path
  while read -r size path; do
    [[ -z "${size:-}" ]] && continue
    current["$path"]="$size"
  done < <(collect_sizes "$root")

  local regressions=0
  local improvements=0
  local workflow_regressions=0
  printf '== file-size-hygiene (threshold %s bytes / %s) ==\n' \
    "$THRESHOLD_BYTES" "$(human_bytes "$THRESHOLD_BYTES")"

  for path in "${!baseline[@]}"; do
    local base="${baseline[$path]}"
    local now="${current[$path]:-}"
    if [[ -z "$now" ]]; then
      printf 'better %s: gone or exempted (baseline %s bytes / %s)\n' \
        "$path" "$base" "$(human_bytes "$base")"
      improvements=$((improvements + 1))
    elif (( now > base )); then
      printf 'GROWTH %s: %s bytes (%s) > baseline %s bytes (%s), +%s bytes\n' \
        "$path" "$now" "$(human_bytes "$now")" "$base" "$(human_bytes "$base")" "$((now - base))" >&2
      regressions=$((regressions + 1))
    elif (( now < base )); then
      printf 'better %s: %s bytes (%s), baseline %s bytes (%s)\n' \
        "$path" "$now" "$(human_bytes "$now")" "$base" "$(human_bytes "$base")"
      improvements=$((improvements + 1))
    fi
  done

  for path in "${!current[@]}"; do
    size="${current[$path]}"
    if (( size > THRESHOLD_BYTES )) && [[ -z "${baseline[$path]:-}" ]]; then
      printf 'NEW oversized %s: %s bytes (%s) exceeds threshold %s bytes (%s)\n' \
        "$path" "$size" "$(human_bytes "$size")" "$THRESHOLD_BYTES" "$(human_bytes "$THRESHOLD_BYTES")" >&2
      regressions=$((regressions + 1))
      if path_is_workflow "$path"; then
        workflow_regressions=$((workflow_regressions + 1))
      fi
    fi
  done

  # Issue #2134: workflow headroom. A workflow still under the cap but within
  # WORKFLOW_HEADROOM_BYTES of it fails here, while the fix is cheap, instead of
  # ambushing the next unrelated CI change with a size failure.
  for path in "${!current[@]}"; do
    size="${current[$path]}"
    path_is_workflow "$path" || continue
    (( size <= THRESHOLD_BYTES )) || continue
    local headroom=$((THRESHOLD_BYTES - size))
    if (( headroom < WORKFLOW_HEADROOM_BYTES )); then
      printf 'WORKFLOW HEADROOM %s: %s bytes (%s) leaves only %s bytes under the %s-byte threshold (need %s)\n' \
        "$path" "$size" "$(human_bytes "$size")" "$headroom" "$THRESHOLD_BYTES" "$WORKFLOW_HEADROOM_BYTES" >&2
      regressions=$((regressions + 1))
      workflow_regressions=$((workflow_regressions + 1))
    fi
  done

  echo
  if (( regressions > 0 )); then
    printf 'check-file-size-hygiene: FAIL — %d oversized-file regression(s).\n' "$regressions" >&2
    printf '  Split/shrink the file, move generated/vendor artifacts out of git, or keep generated/vendor/worktree output under an exempt directory.\n' >&2
    printf '  Do not raise the baseline to accept growth; --update only ratchets downward.\n' >&2
    if (( workflow_regressions > 0 )); then
      print_workflow_remedy
    fi
    return 1
  fi

  if (( improvements > 0 )); then
    printf 'check-file-size-hygiene: OK — no oversized-file growth. %d file(s) improved;\n' "$improvements"
    printf "  run 'scripts/check-file-size-hygiene.sh --update' to lock in the smaller baseline.\n"
  else
    printf 'check-file-size-hygiene: OK — no oversized-file growth.\n'
  fi
}

run_update() {
  local root="$1" baseline_file="$2"
  declare -A baseline=()
  declare -A current=()

  if [[ -f "$baseline_file" ]]; then
    load_baseline "$baseline_file" baseline || return $?
  fi

  local size path
  while read -r size path; do
    [[ -z "${size:-}" ]] && continue
    current["$path"]="$size"
  done < <(collect_sizes "$root")

  local refused=0
  if [[ -f "$baseline_file" ]]; then
    for path in "${!baseline[@]}"; do
      local base="${baseline[$path]}"
      local now="${current[$path]:-}"
      if [[ -n "$now" ]] && (( now > base )); then
        printf 'REFUSE: %s is %s bytes (%s) > baseline %s bytes (%s).\n' \
          "$path" "$now" "$(human_bytes "$now")" "$base" "$(human_bytes "$base")" >&2
        refused=1
      fi
    done

    for path in "${!current[@]}"; do
      size="${current[$path]}"
      if (( size > THRESHOLD_BYTES )) && [[ -z "${baseline[$path]:-}" ]]; then
        printf 'REFUSE: new oversized file %s is %s bytes (%s) > threshold %s bytes (%s).\n' \
          "$path" "$size" "$(human_bytes "$size")" "$THRESHOLD_BYTES" "$(human_bytes "$THRESHOLD_BYTES")" >&2
        refused=1
      fi
    done
  fi

  if (( refused != 0 )); then
    printf 'check-file-size-hygiene: update refused — the ratchet never admits growth or new oversized files.\n' >&2
    return 1
  fi

  write_baseline "$root" "$baseline_file"
  printf 'check-file-size-hygiene: baseline rewritten -> %s\n' "$baseline_file"
  printf '  threshold=%s bytes (%s)\n' "$THRESHOLD_BYTES" "$(human_bytes "$THRESHOLD_BYTES")"
}

write_bytes() {
  local count="$1" file="$2"
  : > "$file"
  local i
  for ((i = 0; i < count; i++)); do
    printf 'x' >> "$file"
  done
}

run_self_test() {
  local tmp repo baseline
  tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp:-}"' RETURN
  repo="$tmp/repo"
  baseline="$tmp/baseline.txt"
  mkdir -p "$repo"
  git -C "$repo" init -q

  local old_threshold="$THRESHOLD_BYTES"
  local old_headroom="$WORKFLOW_HEADROOM_BYTES"
  THRESHOLD_BYTES=16
  # Scaled to the synthetic 16-byte threshold: 4 bytes here plays the role the
  # production 1024 bytes plays against 131072 (issue #2134).
  WORKFLOW_HEADROOM_BYTES=4

  write_bytes 24 "$repo/large.txt"
  write_bytes 8 "$repo/small.txt"
  mkdir -p "$repo/shared/core-terminal/src" "$repo/vendor/blob" "$repo/generated/out" "$repo/.worktrees/issue-1"
  write_bytes 64 "$repo/shared/core-terminal/src/Terminal.java"
  write_bytes 64 "$repo/vendor/blob/cache.bin"
  write_bytes 64 "$repo/generated/out/generated.bin"
  write_bytes 64 "$repo/.worktrees/issue-1/output.bin"
  git -C "$repo" add -A

  local failures=0

  printf '== self-test: initial baseline creation ==\n'
  if run_update "$repo" "$baseline" && grep -q '^24 large.txt$' "$baseline"; then
    printf '   -> PASS: only first-party oversized file was baselined\n\n'
  else
    printf '   -> UNEXPECTED FAIL: initial baseline did not match expected oversized file\n\n' >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: clean baseline check ==\n'
  if check_tree "$repo" "$baseline"; then
    printf '   -> PASS as expected\n\n'
  else
    printf '   -> UNEXPECTED FAIL on clean baseline\n\n' >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: baselined file growth fails ==\n'
  write_bytes 25 "$repo/large.txt"
  if check_tree "$repo" "$baseline"; then
    printf '   -> UNEXPECTED PASS on growth\n\n' >&2
    failures=$((failures + 1))
  else
    printf '   -> FAIL as expected\n\n'
  fi

  printf '== self-test: new oversized file fails ==\n'
  write_bytes 24 "$repo/large.txt"
  write_bytes 17 "$repo/new-large.txt"
  git -C "$repo" add new-large.txt
  if check_tree "$repo" "$baseline"; then
    printf '   -> UNEXPECTED PASS on new oversized file\n\n' >&2
    failures=$((failures + 1))
  else
    printf '   -> FAIL as expected\n\n'
  fi

  printf '== self-test: exempt oversized file stays ignored ==\n'
  git -C "$repo" rm -q --cached new-large.txt
  rm -f "$repo/new-large.txt"
  write_bytes 128 "$repo/shared/core-terminal/src/AnotherTerminal.java"
  git -C "$repo" add shared/core-terminal/src/AnotherTerminal.java
  if check_tree "$repo" "$baseline"; then
    printf '   -> PASS as expected\n\n'
  else
    printf '   -> UNEXPECTED FAIL on exempt path\n\n' >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: downward update lowers the cap ==\n'
  write_bytes 20 "$repo/large.txt"
  if run_update "$repo" "$baseline" && grep -q '^20 large.txt$' "$baseline"; then
    printf '   -> PASS: baseline lowered\n\n'
  else
    printf '   -> UNEXPECTED FAIL lowering baseline\n\n' >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: growth after downward update fails ==\n'
  write_bytes 21 "$repo/large.txt"
  if check_tree "$repo" "$baseline"; then
    printf '   -> UNEXPECTED PASS after lowered cap growth\n\n' >&2
    failures=$((failures + 1))
  else
    printf '   -> FAIL as expected\n\n'
  fi

  # --- issue #2134: workflow headroom + remedy message -----------------------
  # Restore the tree the earlier cases left over-baseline, so every verdict
  # below is caused by the workflow file under test and nothing else.
  write_bytes 20 "$repo/large.txt"
  mkdir -p "$repo/.github/workflows"

  printf '== self-test: a workflow with headroom passes ==\n'
  write_bytes 11 "$repo/.github/workflows/roomy.yml" # headroom 5 >= 4
  git -C "$repo" add .github/workflows/roomy.yml
  if check_tree "$repo" "$baseline"; then
    printf '   -> PASS as expected\n\n'
  else
    printf '   -> UNEXPECTED FAIL: a workflow with headroom was rejected\n\n' >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: a workflow inside the headroom margin fails with the remedy ==\n'
  # 13 bytes under a 16-byte threshold: still UNDER the cap (the plain ratchet
  # is happy) but only 3 bytes of room, below the 4-byte margin. This is the
  # #2134 state tests.yml was in at 271 bytes.
  write_bytes 13 "$repo/.github/workflows/roomy.yml"
  local headroom_out
  headroom_out="$(check_tree "$repo" "$baseline" 2>&1)" && headroom_out=""
  if [[ -z "$headroom_out" ]]; then
    printf '   -> UNEXPECTED PASS: a workflow 3 bytes under the cap was accepted\n\n' >&2
    failures=$((failures + 1))
  elif ! grep -q 'WORKFLOW HEADROOM .*roomy.yml' <<<"$headroom_out"; then
    printf '   -> UNEXPECTED: failed, but not with the headroom diagnosis\n%s\n\n' "$headroom_out" >&2
    failures=$((failures + 1))
  elif ! grep -q 'extract its inline `run: |` shell into a scripts/\*\.sh' <<<"$headroom_out"; then
    printf '   -> UNEXPECTED: headroom failure did not print the remedy\n%s\n\n' "$headroom_out" >&2
    failures=$((failures + 1))
  elif ! grep -q 'Do NOT delete explanatory comments' <<<"$headroom_out"; then
    printf '   -> UNEXPECTED: remedy did not warn against deleting comments\n%s\n\n' "$headroom_out" >&2
    failures=$((failures + 1))
  else
    printf '   -> FAIL as expected, with the headroom diagnosis + remedy\n\n'
  fi

  printf '== self-test: an oversized workflow also gets the remedy ==\n'
  write_bytes 24 "$repo/.github/workflows/roomy.yml"
  local oversized_out
  oversized_out="$(check_tree "$repo" "$baseline" 2>&1)" && oversized_out=""
  if [[ -z "$oversized_out" ]]; then
    printf '   -> UNEXPECTED PASS on an oversized workflow\n\n' >&2
    failures=$((failures + 1))
  elif ! grep -q 'NEW oversized .*roomy.yml' <<<"$oversized_out"; then
    printf '   -> UNEXPECTED: failed, but not as a NEW oversized workflow\n%s\n\n' "$oversized_out" >&2
    failures=$((failures + 1))
  elif ! grep -q 'extract its inline `run: |` shell into a scripts/\*\.sh' <<<"$oversized_out"; then
    printf '   -> UNEXPECTED: oversized workflow failure did not print the remedy\n%s\n\n' "$oversized_out" >&2
    failures=$((failures + 1))
  else
    printf '   -> FAIL as expected, with the remedy\n\n'
  fi

  printf '== self-test: a non-workflow file inside the margin is unaffected ==\n'
  git -C "$repo" rm -q -f --cached .github/workflows/roomy.yml
  rm -f "$repo/.github/workflows/roomy.yml"
  write_bytes 13 "$repo/near-cap.txt" # 3 bytes of room, but not a workflow
  git -C "$repo" add near-cap.txt
  if check_tree "$repo" "$baseline"; then
    printf '   -> PASS as expected (the margin is workflow-only)\n\n'
  else
    printf '   -> UNEXPECTED FAIL: the headroom margin leaked to a source file\n\n' >&2
    failures=$((failures + 1))
  fi

  THRESHOLD_BYTES="$old_threshold"
  WORKFLOW_HEADROOM_BYTES="$old_headroom"

  if (( failures > 0 )); then
    printf 'SELF-TEST FAILED: %d case(s) behaved incorrectly.\n' "$failures" >&2
    return 1
  fi
  printf 'SELF-TEST OK: oversized-file ratchet catches growth/new files and ignores exemptions.\n'
}

main() {
  validate_threshold
  case "${1:-}" in
    -h|--help)
      usage
      ;;
    --update)
      run_update "$REPO_ROOT" "$REPO_ROOT/$BASELINE_REL"
      ;;
    --self-test)
      run_self_test
      ;;
    "")
      check_tree "$REPO_ROOT" "$REPO_ROOT/$BASELINE_REL"
      ;;
    *)
      fail_usage "unknown arg: $1"
      ;;
  esac
}

main "$@"
