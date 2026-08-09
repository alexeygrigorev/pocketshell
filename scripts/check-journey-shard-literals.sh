#!/usr/bin/env bash
#
# check-journey-shard-literals.sh — hardcoded emulator-journey shard-total
# ratchet (issue #2060).
#
# WHY THIS EXISTS. The emulator-journey shard count is owned by ONE place, the
# `matrix.shard` list in .github/workflows/tests.yml, and every guard that
# asserts something about the SHIPPED configuration is supposed to read it from
# there via scripts/ci-journey-shard-count.sh. In practice literals kept
# accumulating: going 3 -> 6 in #2060 uncovered FOUR guards still pinned to 3,
# found one at a time over three review rounds —
#
#   1. test-ci-journey-aggregate-verdict.sh  EXPECTED_SHARDS pin      (round 1)
#   2. test-ci-journey-budget.sh              the 125% balance cap     (round 2)
#   3. test-ci-journey-budget.sh              the (shard) ACCEPTANCE   (round 3)
#   4. test-ci-journey-warm-build.sh          the #1814 per-shard loop (round 3)
#
# Each was individually harmless-looking and each silently narrowed a guard to a
# configuration CI no longer runs. #4 is the shape that matters most: it derived
# SHARD_TOTAL correctly, printed "verified on all $SHARD_TOTAL shards", and had
# enumerated only 0/1/2 — a truthful-looking pass over a third of the legs.
# Fixing the instance you were handed does not stop the fifth, so this is a
# ratchet: it fails when a literal appears in a shape it recognises, and its
# --update is downward-only so it cannot be silenced by re-baselining.
#
# WHAT IT SCANS (see `scan` below): the journey-CI shell family — every
# scripts/*ci-journey*.sh plus scripts/test-ci-mobile-rtt-readiness.sh, which is
# a journey-matrix guard without "journey" in its name.
#
# WHAT IT LOOKS FOR — three shapes, each pinned by its own self-test case:
#   A. a shard-TOTAL noun assigned a NUMERIC LITERAL  ->  `EXPECTED_SHARDS=3`,
#      `POCKETSHELL_JOURNEY_CI_SHARD_TOTAL=3`, `shard_total=3`, `shard_count=6`,
#      `NUM_SHARDS=6`, `SOLO_SHARD_TOTAL=400`, singular `EXPECTED_SHARD=3`
#   B. the same noun pinned through a DEFAULT EXPANSION  ->
#      `SHARD_TOTAL="${SHARD_TOTAL:-3}"`. This is the nastiest of the three: it
#      READS like a derived value while hardcoding the total on exactly the
#      mis-wired job where the env var is missing.
#   C. a loop ENUMERATED over literal 0-based indices  ->  `for shard in 0 1 2`
#      (#4's shape, which carries no `total` token at all), and also
#      `for idx in 0 1 2` / `for leg in 0 1 2` / `for x in {0..5}` /
#      `for x in $(seq 0 5)` — the enumeration itself is the tell, because a
#      loop-variable-name-only pattern is one rename from blind.
# A form that DERIVES its value — `SHARD_TOTAL="$(... ci-journey-shard-count.sh
# ...)"`, `for (( i = 0; i < SHARD_TOTAL; i++ ))`, `$(seq 0 "$((TOTAL-1))")` —
# has no numeric literal and therefore never matches. Comment lines are skipped:
# prose ABOUT a literal (this file, and the #2060 notes explaining what was
# removed) is not a literal.
#
# WHAT IT DOES NOT CATCH — stated so the boundary is known rather than assumed.
# This is a grep over three named shapes, not a proof that no literal exists:
#   - a total assembled at runtime (`t=$((2+4))`, a literal read from a file or
#     a `case` arm), or one written in a language this scan does not read;
#   - a literal on a `shard`-free identifier (`EXPECTED=3`, `LEGS=6`) — nothing
#     in the text ties it to the matrix;
#   - shard INDEX literals and `=0` (see the out-of-scope list below).
# So: a partial backstop, honestly bounded. The load-bearing protection is still
# that every SHIPPED-configuration assertion derives its total from
# scripts/ci-journey-shard-count.sh; this ratchet catches the regression shapes
# that have actually occurred here, four times.
#
# DELIBERATELY OUT OF SCOPE, so the boundary is stated rather than accidental:
#   - .github/workflows/tests.yml itself. It is the SOURCE of the number; its
#     three sites are already machine-cross-checked against each other by
#     ci-journey-shard-count.sh + the #1876/#1458 guards. Baselining them here
#     would re-encode the count in a fourth place — the very drift being removed
#     — and force a baseline edit on every count change.
#   - the nightly suite's POCKETSHELL_NIGHTLY_SHARD_* mechanism
#     (.github/workflows/nightly-extensive.yml + scripts/nightly-extensive-suite.sh).
#     That is AndroidJUnitRunner numShards/shardIndex, a separate matrix that is
#     internally consistent at its own total.
#   - shard INDEX literals (`shard=0`, `SHARD_INDEX=2`, `${SHARD_INDEX:-0}`). An
#     index is bounded by the total the guards already validate; totals and
#     enumerated loops are the drift class #2060 hit.
#   - a literal ZERO (`shard_union_count=0`, `${EXPECTED_SHARDS:-0}`). Zero is
#     never a shard total: it is an accumulator initialiser or an "unset"
#     fail-safe, so matching it added noise and no protection.
#
# The accepted sites live in scripts/journey-shard-literal-baseline.txt with a
# one-line reason each. They are all self-contained sandbox fixtures: the guard
# builds N synthetic inputs and tells the code under test to expect N, so the
# property is total-invariant and 3 is just a cheap sample. A site that asserts
# something about the SHIPPED configuration does NOT belong in the baseline — it
# belongs on ci-journey-shard-count.sh.
#
# Usage:
#   scripts/check-journey-shard-literals.sh            # check against the baseline
#   scripts/check-journey-shard-literals.sh --update   # re-bake the baseline;
#       DOWNWARD-ONLY — it lowers an accepted count or drops a file, and REFUSES
#       to raise a count or admit a new file. A ratchet whose --update can
#       re-baseline upward is not a ratchet, it is a suggestion.
#   scripts/check-journey-shard-literals.sh --self-test
#
# Exit codes:
#   0  no NEW hardcoded shard total          [also: --update / --self-test ok]
#   1  a NEW hardcoded shard total appeared, the update was refused as upward,
#      or the scan itself is broken
#
# Cheap: one grep per scanned file over ~31 files, well under a second.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
BASELINE_FILE="scripts/journey-shard-literal-baseline.txt"

# A "shard TOTAL" identifier. Deliberately noun-shaped rather than "any
# identifier containing shard", so shard INDEX spellings (`shard`, `shard_idx`,
# `SHARD_INDEX`) — an out-of-scope class, see above — do not match:
#   shard...total / shard...count   SHARD_TOTAL, JOURNEY_CI_SHARD_TOTAL,
#                                   SHARD_TOTALS, shard_total_probe, SHARD_COUNT,
#                                   shard_count  (the spelling a future author is
#                                   most likely to pick: the helper is named
#                                   ci-journey-shard-count.sh)
#   ...shards                       EXPECTED_SHARDS, NUM_SHARDS, TOTAL_SHARDS
#   expected|num|total|count|max + shard   also in the SINGULAR: EXPECTED_SHARD=3
RE_SHARD_NOUN='(([a-z0-9_]*shard[a-z0-9_]*(total|count)[a-z0-9_]*)|([a-z0-9_]*shards)|((expected|num|total|count|max)[a-z0-9_]*shard[a-z0-9_]*))'
# The literal must be >= 1. Zero is never a shard total: `=0` is an accumulator
# initialiser (`shard_union_count=0`) or an "unset" fail-safe (`${VAR:-0}`), and
# matching it produced only noise.
RE_NUM='[1-9][0-9]*'
# A. the noun assigned a numeric literal.
RE_TOTAL="${RE_SHARD_NOUN}[[:space:]]*=[[:space:]]*${RE_NUM}"
# B. the noun pinned through a DEFAULT EXPANSION — `SHARD_TOTAL="${SHARD_TOTAL:-3}"`.
#    This shape *looks* derived (it reads an env var) while hardcoding the total
#    whenever the env var is absent, which on a mis-wired job is exactly when it
#    matters. Matched on either side, so `TOTAL="${JOURNEY_CI_SHARD_TOTAL:-3}"`
#    and `SHARD_COUNT="${FOO:-3}"` both trip.
RE_DEFAULT="(${RE_SHARD_NOUN}[[:space:]]*=[[:space:]]*\"?\\\$\\{[a-z0-9_]*:-${RE_NUM}\\})|(=[[:space:]]*\"?\\\$\\{$RE_SHARD_NOUN:-${RE_NUM}\\})"
# C. a shard loop ENUMERATED over literal indices, in all four spellings. The
#    first keys on the loop VARIABLE (`for shard in 0`, `for shard_idx in 0`);
#    the rest key on the 0-BASED LITERAL ENUMERATION itself, because #4's shape
#    happened to use `shard` and a future `for leg in 0 1 2` would walk straight
#    through a variable-name-only pattern.
#      for x in 0 1 2      for x in {0..5}      for x in $(seq 0 5)
#    `$(seq 0 "$((N-1)))"` and `for (( i = 0; i < N; i++ ))` are DERIVED and
#    deliberately do not match — the upper bound must be a literal.
RE_LOOP='(for[[:space:]]+[a-z0-9_]*shard[a-z0-9_]*[[:space:]]+in[[:space:]]+[0-9]+)|(for[[:space:]]+[a-z0-9_]+[[:space:]]+in[[:space:]]+0[[:space:]]+1([[:space:]]|;|$))|(for[[:space:]]+[a-z0-9_]+[[:space:]]+in[[:space:]]+\{0\.\.[0-9]+\})|(for[[:space:]]+[a-z0-9_]+[[:space:]]+in[[:space:]]+\$\(seq[[:space:]]+0[[:space:]]+[0-9]+)'
# The one expression every scan uses, so `scan` and `scan_verbose` can never drift.
RE_ANY="($RE_TOTAL)|($RE_DEFAULT)|($RE_LOOP)"

# Emit "<file>\t<count>" for every scanned file holding at least one literal,
# sorted. Comment lines are dropped first.
scan() {
  local root="$1" f
  local -a files=()
  # shellcheck disable=SC2231
  for f in "$root"/scripts/*ci-journey*.sh "$root"/scripts/test-ci-mobile-rtt-readiness.sh; do
    [[ -f "$f" ]] && files+=("$f")
  done
  (( ${#files[@]} > 0 )) || { echo "check-journey-shard-literals: scanned ZERO files under $root" >&2; return 2; }
  for f in "${files[@]}"; do
    local n
    n="$(
      grep -vE '^[[:space:]]*#' "$f" \
        | grep -coiE "$RE_ANY"
    )"
    (( n > 0 )) && printf '%s\t%s\n' "${f#"$root"/}" "$n"
  done | sort
  return 0
}

# Same, but with file:line:text so a failure can name the offending site.
scan_verbose() {
  local root="$1" f
  for f in "$root"/scripts/*ci-journey*.sh "$root"/scripts/test-ci-mobile-rtt-readiness.sh; do
    [[ -f "$f" ]] || continue
    grep -nvE '^[[:space:]]*#' "$f" \
      | grep -iE "$RE_ANY" \
      | sed "s|^|${f#"$root"/}:|"
  done
}

do_check() {
  local root="${1:-$REPO_ROOT}" quiet="${2:-}"
  local current
  current="$(scan "$root")" || return 2
  [[ -f "$root/$BASELINE_FILE" ]] || { echo "missing baseline $BASELINE_FILE" >&2; return 1; }

  local -A base=()
  load_baseline "$root/$BASELINE_FILE" base

  local file count
  local bad=0 improved=0
  while IFS=$'\t' read -r file count; do
    [[ -n "$file" ]] || continue
    local allowed="${base[$file]:-0}"
    if (( count > allowed )); then
      echo "NEW hardcoded shard total in $file: $count literal(s), baseline $allowed" >&2
      bad=1
    elif (( count < allowed )); then
      improved=$((improved + 1))
    fi
  done <<<"$current"

  if (( bad )); then
    echo >&2
    echo "Every shard-total literal below must either be derived from" >&2
    echo "scripts/ci-journey-shard-count.sh (the matrix is the single source of" >&2
    echo "truth) or, if it is a self-contained sandbox fixture, be added to" >&2
    echo "$BASELINE_FILE with a one-line reason:" >&2
    scan_verbose "$root" >&2
    echo >&2
    echo "check-journey-shard-literals: FAIL" >&2
    return 1
  fi
  if [[ -z "$quiet" ]]; then
    if (( improved )); then
      echo "check-journey-shard-literals: OK — no new hardcoded shard totals ($improved file(s) improved; re-baseline with --update)."
    else
      echo "check-journey-shard-literals: OK — no new hardcoded shard totals."
    fi
  fi
  return 0
}

# Read "<file>\t<count>" baseline rows into the caller's associative array.
load_baseline() {  # $1 = baseline path, $2 = name of an associative array
  local path="$1" file count
  local -n _out="$2"
  _out=()
  [[ -f "$path" ]] || return 0
  while IFS=$'\t' read -r file count; do
    [[ -n "$file" ]] && _out["$file"]="$count"
  done < <(grep -vE '^[[:space:]]*(#|$)' "$path" | sort)
}

# DOWNWARD-ONLY, in the idiom of the two ratchets this script models itself on:
# scripts/check-connection-vm-ratchet.sh ("REFUSE: ... downward-only ratchet")
# and scripts/check-file-size-hygiene.sh ("--update only ratchets downward").
# --update may LOWER an accepted count or DROP a file; it may NOT raise a count
# or admit a new file. Without this, a fifth literal is one documented command
# away from green — `--update` would silently re-baseline the very drift the
# ratchet exists to stop, and the header's claim would be false.
do_update() {
  local root="${1:-$REPO_ROOT}"
  local baseline="$root/$BASELINE_FILE"
  local current
  current="$(scan "$root")" || return 2

  local -A base=()
  load_baseline "$baseline" base

  local refused=0 file count allowed
  while IFS=$'\t' read -r file count; do
    [[ -n "$file" ]] || continue
    if [[ -z "${base[$file]+set}" ]]; then
      echo "REFUSE: $file is not in the baseline and holds $count literal(s) — downward-only ratchet." >&2
      refused=1
      continue
    fi
    allowed="${base[$file]}"
    if (( count > allowed )); then
      echo "REFUSE: $file holds $count literal(s), baseline $allowed — downward-only ratchet." >&2
      refused=1
    fi
  done <<<"$current"

  if (( refused )); then
    echo >&2
    echo "update refused: a current count EXCEEDS the baseline (or a new file appeared)." >&2
    echo "Derive the total from scripts/ci-journey-shard-count.sh, then re-run --update;" >&2
    echo "the ratchet never raises. Admitting a genuinely new self-contained sandbox" >&2
    echo "fixture — or absorbing a WIDENED detector — is a deliberate hand edit of" >&2
    echo "$BASELINE_FILE with its one-line reason, never this command:" >&2
    scan_verbose "$root" >&2
    return 1
  fi

  {
    sed -n '1,/^# regenerate with:/p' "$baseline"
    printf '%s\n' "$current"
  } > "$baseline.tmp"
  mv "$baseline.tmp" "$baseline"
  echo "rewrote $BASELINE_FILE (downward-only)"
}

# ---------------------------------------------------------------------------
# Self-test. A ratchet that cannot see a new literal is worse than none: it
# reports OK forever. Each case plants a state in a throwaway copy of the tree
# and asserts the verdict, so the detector is proven live rather than assumed.
self_test() {
  local sandbox checks=0
  sandbox="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '$sandbox'" EXIT

  mkdir -p "$sandbox/scripts"
  cp "$REPO_ROOT/scripts"/*ci-journey*.sh "$sandbox/scripts/"
  cp "$REPO_ROOT/scripts/test-ci-mobile-rtt-readiness.sh" "$sandbox/scripts/"
  cp "$REPO_ROOT/$BASELINE_FILE" "$sandbox/$BASELINE_FILE"

  local victim="$sandbox/scripts/ci-journey-suite.sh"
  local pristine="$sandbox/pristine-victim"
  cp "$victim" "$pristine"

  expect() {  # $1 = expected rc, $2 = label
    local want="$1" label="$2" rc=0
    do_check "$sandbox" quiet >/dev/null 2>&1 || rc=$?
    [[ "$rc" == "$want" ]] \
      || { echo "FAIL self-test [$label]: rc=$rc, expected $want" >&2; exit 1; }
    checks=$((checks + 1))
  }

  expect 0 "clean tree matches its baseline"

  # A NEW total literal must redden.
  printf 'EXPECTED_SHARDS=3\n' >> "$victim"
  expect 1 "a new EXPECTED_SHARDS=<n> literal"
  cp "$pristine" "$victim"
  expect 0 "restored"

  # ...in either spelling.
  printf 'POCKETSHELL_JOURNEY_CI_SHARD_TOTAL=4\n' >> "$victim"
  expect 1 "a new _SHARD_TOTAL=<n> literal"
  cp "$pristine" "$victim"

  printf 'shard_total=6\n' >> "$victim"
  expect 1 "a new lowercase shard_total=<n> literal"
  cp "$pristine" "$victim"

  # `shard_count` is the spelling a future author is MOST likely to reach for,
  # because the helper that owns the number is named ci-journey-shard-count.sh —
  # and the round-3 pattern (`shard` + `total`|`s`) could not match it.
  printf 'SHARD_COUNT=6\n' >> "$victim"
  expect 1 "a new SHARD_COUNT=<n> literal"
  cp "$pristine" "$victim"
  printf 'shard_count=6\n' >> "$victim"
  expect 1 "a new lowercase shard_count=<n> literal"
  cp "$pristine" "$victim"
  printf 'NUM_SHARDS=6\n' >> "$victim"
  expect 1 "a new NUM_SHARDS=<n> literal"
  cp "$pristine" "$victim"
  # ...including the SINGULAR noun, which no plural/total pattern reaches.
  printf 'EXPECTED_SHARD=3\n' >> "$victim"
  expect 1 "a new singular EXPECTED_SHARD=<n> literal"
  cp "$pristine" "$victim"

  # THE DEFAULT-EXPANSION SHAPE: reads as derived, pins a literal whenever the
  # env var is absent — i.e. on exactly the mis-wired job where it matters. This
  # is the "the guard presets the variable it exists to exercise" trap that has
  # bitten this repo repeatedly.
  printf 'SHARD_TOTAL="${SHARD_TOTAL:-3}"\n' >> "$victim"
  expect 1 "a new \${SHARD_TOTAL:-3} default-expansion pin"
  cp "$pristine" "$victim"
  printf 'total="${JOURNEY_CI_SHARD_TOTAL:-3}"\n' >> "$victim"
  expect 1 "a new default-expansion pin named only on the inner var"
  cp "$pristine" "$victim"
  printf 'SHARD_COUNT="${SOME_OTHER_VAR:-3}"\n' >> "$victim"
  expect 1 "a new default-expansion pin named only on the outer var"
  cp "$pristine" "$victim"
  expect 0 "restored"

  # THE #4 SHAPE: an enumerated shard loop, which carries no `total` token.
  # A total-only pattern passes this and it is the one that produced a
  # truthful-looking "verified on all N shards" over three of them.
  printf 'for shard in 0 1 2; do :; done\n' >> "$victim"
  expect 1 "a new enumerated 'for shard in 0 1 2' loop"
  cp "$pristine" "$victim"
  printf 'for shard_idx in 0 1 2 3; do :; done\n' >> "$victim"
  expect 1 "a new enumerated 'for shard_idx in ...' loop"
  cp "$pristine" "$victim"
  # ...and the same enumeration under a loop variable that says nothing about
  # shards. #4 happened to use `shard`; `for leg in 0 1 2` is one rename away and
  # a variable-name-only pattern is blind to it.
  printf 'for idx in 0 1 2; do :; done\n' >> "$victim"
  expect 1 "a new enumerated 'for idx in 0 1 2' loop"
  cp "$pristine" "$victim"
  printf 'for leg in 0 1 2; do :; done\n' >> "$victim"
  expect 1 "a new enumerated 'for leg in 0 1 2' loop"
  cp "$pristine" "$victim"
  # ...and the two compact spellings of the same enumeration.
  printf 'for shard in {0..5}; do :; done\n' >> "$victim"
  expect 1 "a new brace-expansion 'for shard in {0..5}' loop"
  cp "$pristine" "$victim"
  printf 'for shard in $(seq 0 5); do :; done\n' >> "$victim"
  expect 1 "a new 'for shard in \$(seq 0 5)' loop"
  cp "$pristine" "$victim"
  expect 0 "restored"

  # SELECTIVITY — the derived forms this guard exists to encourage must NOT trip
  # it, or the ratchet would push authors back toward literals.
  printf 'SHARD_TOTAL="$(bash "$SCRIPT_DIR/ci-journey-shard-count.sh")"\n' >> "$victim"
  printf 'for (( shard = 0; shard < SHARD_TOTAL; shard++ )); do :; done\n' >> "$victim"
  expect 0 "derived total + derived loop bound are not literals"
  cp "$pristine" "$victim"
  # A `seq`/brace loop whose UPPER BOUND is derived is the encouraged form of the
  # shape just banned; only a literal bound may match.
  printf 'for idx in $(seq 0 "$((SHARD_TOTAL - 1))"); do :; done\n' >> "$victim"
  expect 0 "a derived-bound seq loop is not a literal enumeration"
  cp "$pristine" "$victim"
  # Index spellings and zero are deliberately out of scope; matching them added
  # noise (a `_MS` variable, an accumulator init) and no protection.
  printf 'SHARD_INDEX="${SHARD_INDEX:-0}"\nshard_union_count=0\n' >> "$victim"
  expect 0 "a shard INDEX default and a zero-valued counter are not totals"
  cp "$pristine" "$victim"

  # Prose about a literal is not a literal, or every explanatory comment (this
  # file included) would be a violation.
  printf '# this block used to pin shard_total=3 / for shard_idx in 0 1 2\n' >> "$victim"
  expect 0 "a comment mentioning the old literal"
  cp "$pristine" "$victim"

  # A baselined site may go DOWN, never up.
  local baselined="$sandbox/scripts/test-ci-journey-retry-budget.sh"
  local before_n
  before_n="$(grep -vE '^[[:space:]]*#' "$baselined" | grep -coiE "$RE_ANY")"
  (( before_n > 0 )) \
    || { echo "FAIL self-test: the baselined fixture file has no literals to lower" >&2; exit 1; }
  printf 'EXPECTED_SHARDS=3\n' >> "$baselined"
  expect 1 "a baselined file exceeding its accepted count"
  # (removing the added line restores it; lowering below the baseline is fine)
  sed -i '$d' "$baselined"
  expect 0 "back at the accepted count"
  checks=$((checks + 1))

  # ---- --update is DOWNWARD-ONLY ------------------------------------------
  # Without these three cases the ratchet is a suggestion: plant a literal, run
  # the documented --update, and the violation is absorbed into the baseline —
  # rc back to 0 with the offending file silently accepted.
  local sb_baseline="$sandbox/$BASELINE_FILE"
  local baseline_md5 rc

  # (a) a NEW file must be refused, and the baseline left byte-identical.
  baseline_md5="$(md5sum < "$sb_baseline")"
  printf 'EXPECTED_SHARDS=3\n' >> "$victim"
  rc=0; do_update "$sandbox" >/dev/null 2>&1 || rc=$?
  (( rc != 0 )) \
    || { echo "FAIL self-test: --update ACCEPTED a new file into the baseline" >&2; exit 1; }
  [[ "$(md5sum < "$sb_baseline")" == "$baseline_md5" ]] \
    || { echo "FAIL self-test: --update rewrote the baseline while refusing" >&2; exit 1; }
  checks=$((checks + 1))
  cp "$pristine" "$victim"

  # (b) a RAISED count on an ALREADY-baselined file must be refused too — the
  #     likelier drift, since every baselined file is a place literals live.
  printf 'EXPECTED_SHARDS=3\n' >> "$baselined"
  rc=0; do_update "$sandbox" >/dev/null 2>&1 || rc=$?
  (( rc != 0 )) \
    || { echo "FAIL self-test: --update RAISED a baselined file's accepted count" >&2; exit 1; }
  [[ "$(md5sum < "$sb_baseline")" == "$baseline_md5" ]] \
    || { echo "FAIL self-test: --update rewrote the baseline while refusing a raise" >&2; exit 1; }
  checks=$((checks + 1))
  sed -i '$d' "$baselined"

  # (c) it must still RATCHET: removing a literal lowers the accepted count, so
  #     the refusal above is downward-only rather than a dead command.
  local lowered_from lowered_to
  lowered_from="$(awk -F'\t' '$1=="scripts/test-ci-journey-retry-budget.sh"{print $2}' "$sb_baseline")"
  sed -i '0,/^for shard in 0 1 2; do$/s//for shard in $(all_shards); do/' "$baselined"
  rc=0; do_update "$sandbox" >/dev/null 2>&1 || rc=$?
  (( rc == 0 )) \
    || { echo "FAIL self-test: --update refused a genuine DOWNWARD move (rc=$rc)" >&2; exit 1; }
  lowered_to="$(awk -F'\t' '$1=="scripts/test-ci-journey-retry-budget.sh"{print $2}' "$sb_baseline")"
  (( lowered_to < lowered_from )) \
    || { echo "FAIL self-test: --update did not lower the count ($lowered_from -> $lowered_to)" >&2; exit 1; }
  checks=$((checks + 1))
  # Restore the sandbox baseline so the real-tree checks below are unaffected.
  cp "$REPO_ROOT/$BASELINE_FILE" "$sb_baseline"
  cp "$REPO_ROOT/scripts/test-ci-journey-retry-budget.sh" "$baselined"
  expect 0 "sandbox restored after the --update cases"

  # A scan that silently matches nothing would make the guard vacuous forever.
  local empty="$sandbox/empty-root"
  mkdir -p "$empty/scripts"
  local rc=0
  scan "$empty" >/dev/null 2>&1 || rc=$?
  [[ "$rc" == 2 ]] \
    || { echo "FAIL self-test: an empty scan set did not fail closed (rc=$rc)" >&2; exit 1; }
  checks=$((checks + 1))

  # The REAL tree must be clean, and must actually have found something to scan.
  do_check "$REPO_ROOT" quiet >/dev/null \
    || { echo "FAIL self-test: the real tree does not match its baseline" >&2; exit 1; }
  checks=$((checks + 1))
  local real_files
  real_files="$(scan "$REPO_ROOT" | wc -l)"
  (( real_files >= 5 )) \
    || { echo "FAIL self-test: only $real_files file(s) carry literals — the scan looks broken" >&2; exit 1; }
  checks=$((checks + 1))

  (( checks == 34 )) \
    || { echo "FAIL: self-test ran $checks checks, expected 34" >&2; exit 1; }
  echo "PASS: check-journey-shard-literals self-test ($checks checks; $real_files baselined file(s))"
}

case "${1:-}" in
  --self-test) self_test ;;
  --update)    do_update ;;
  --*)         echo "usage: check-journey-shard-literals.sh [--update | --self-test]" >&2; exit 1 ;;
  *)           do_check "$REPO_ROOT" || exit 1 ;;
esac
