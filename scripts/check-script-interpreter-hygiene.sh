#!/usr/bin/env bash
# Script interpreter hygiene guard (issue #2066).
#
# THE CLASS THIS EXISTS TO KILL
#
# A file whose EXTENSION says one language and whose SHEBANG says another. The
# instance this repo shipped for months: `scripts/full-jvm-gate.sh` was a Python
# program (`#!/usr/bin/python3 -I`). Run it the way the extension invites —
# `bash scripts/full-jvm-gate.sh` — and bash mis-executes it line by line. Line
# 3 is `import os`, and `import` on this box is IMAGEMAGICK's screen-capture
# tool, which blocks forever waiting on an X display.
#
# The resulting artifact is the nastiest one in this repository's catalogue,
# because it does not look like a failure:
#
#   * the process stays "active" indefinitely — no error, no exit;
#   * it writes a ~1-line log and produces zero test XML;
#   * wrapped in the shared `flock`, it holds the gate lock the whole time and
#     silently starves every sibling lane.
#
# On 2026-07-27 that burned one lane's gate run and starved four others.
#
# WHY A GUARD AND NOT A PARAGRAPH
#
# `process.md` and `AGENTS.md` already documented this — by NAME, for exactly
# one file. Two more instances (`check-ci-unit-forced-execution.sh`,
# `check-full-jvm-gate-profile.sh`) sat undiscovered beside it, and naming one
# file actively taught readers the others were safe: during the #2066 session a
# reviewer who HAD been briefed on the trap ran
# `bash scripts/check-ci-unit-forced-execution.sh` and read its rc=0 (with a
# parse error on stderr) as a pass. Prose cannot enumerate a class. This can.
#
# WHAT IT CHECKS (over git-tracked files only)
#
#   1. EXTENSION/SHEBANG AGREEMENT. A `*.sh` file must have a shell shebang; a
#      `*.py` file must have a python shebang. Zero allowlist — the durable fix
#      for the three instances above was to rename them to `.py`, so there is
#      nothing legitimate to exempt. An exemption list would reintroduce exactly
#      the "these named files are special" lesson that failed.
#
#   2. NO SHELL-INVOCATION OF A NON-SHELL PROGRAM. No tracked command may invoke
#      a non-shell program through bash/sh/dash/zsh (bare or by executable
#      path), `source`, or `.`. Options, including the `--` separator, do not
#      exempt the target. Rule 1 makes the extension honest; rule 2 stops a
#      caller from lying about it anyway (`bash scripts/full-jvm-gate.py` is
#      still the same hang).
#
# Both rules are checked against the index (`git ls-files` / `git grep -I`), so
# binaries and untracked scratch are out of scope by construction.
#
# USAGE
#
#   scripts/check-script-interpreter-hygiene.sh [ROOT]     # check a tree
#   scripts/check-script-interpreter-hygiene.sh --self-test
#
# The self-test builds throwaway git trees, plants one violation of each rule,
# and requires the guard to go RED on each and stay GREEN on a clean tree — so a
# guard that has silently stopped detecting anything cannot pass as protection.

set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ROOT="$(cd "$SELF_DIR/.." && pwd)"

SHELL_INTERPRETERS=" sh bash dash ksh mksh zsh ash busybox "
PYTHON_INTERPRETERS=" python python2 python3 "

# Reduce a shebang line to the bare interpreter name.
#   "#!/usr/bin/python3 -I"        -> python3
#   "#!/usr/bin/env bash"          -> bash
#   "#!/usr/bin/env -S python3 -I" -> python3
shebang_interpreter() {
  local line="$1" first rest token
  line="${line#\#!}"
  # shellcheck disable=SC2206 # deliberate word split of the shebang argv.
  local parts=($line)
  first="${parts[0]:-}"
  first="${first##*/}"
  if [[ "$first" == "env" ]]; then
    rest=("${parts[@]:1}")
    for token in "${rest[@]}"; do
      [[ "$token" == -* ]] && continue
      [[ "$token" == *=* ]] && continue
      first="${token##*/}"
      break
    done
  fi
  # python3.12 / python3.12m -> python3
  if [[ "$first" =~ ^python([0-9]+)(\.[0-9]+)*m?$ ]]; then
    first="python${BASH_REMATCH[1]}"
  fi
  printf '%s\n' "$first"
}

shebang_family() {
  local interp="$1"
  if [[ "$SHELL_INTERPRETERS" == *" $interp "* ]]; then
    printf 'shell\n'
  elif [[ "$PYTHON_INTERPRETERS" == *" $interp "* ]]; then
    printf 'python\n'
  else
    printf 'other\n'
  fi
}

# Populate SHEBANG_FAMILY[path]=shell|python|other for every tracked file whose
# FIRST line is a shebang. One `git grep` pass, not one fork per file.
declare -A SHEBANG_FAMILY=()
load_shebangs() {
  local root="$1" line path lineno text interp
  SHEBANG_FAMILY=()
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    path="${line%%:*}"
    line="${line#*:}"
    lineno="${line%%:*}"
    text="${line#*:}"
    [[ "$lineno" == "1" ]] || continue
    interp="$(shebang_interpreter "$text")"
    SHEBANG_FAMILY["$path"]="$(shebang_family "$interp")|$interp"
  done < <(git -C "$root" grep -I -n --no-color -e '^#!' -- . 2>/dev/null || true)
}

# Turn a token found in source ("$ROOT_DIR/scripts/x.py", './scripts/x.py',
# "${root}/scripts/x.py") into a repo-relative path, or nothing.
resolve_tracked_path() {
  local root="$1" token="$2" candidate
  token="${token%\"}"
  token="${token%\'}"
  token="${token#\"}"
  token="${token#\'}"
  candidate="$token"
  while [[ "$candidate" == *"/"* ]]; do
    if [[ -n "${TRACKED[$candidate]:-}" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
    candidate="${candidate#*/}"
  done
  if [[ -n "${TRACKED[$candidate]:-}" ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi
  return 1
}

# Return success when a regex match starts inside shell quoting or after an
# unquoted shell comment marker. The match offset comes from `grep -bo` and this
# function runs under the same byte-oriented C locale.
command_offset_is_inert() {
  local text="$1" offset="$2" quote='' escaped=0 at_word_start=1 ch i
  for ((i = 0; i < offset; i++)); do
    ch="${text:i:1}"
    if (( escaped )); then
      escaped=0
      at_word_start=0
      continue
    fi
    if [[ -n "$quote" ]]; then
      if [[ "$quote" != "'" && "$ch" == "\\" ]]; then
        escaped=1
      elif [[ "$ch" == "$quote" ]]; then
        quote=''
      fi
      continue
    fi
    case "$ch" in
      "'" | '"' | '`')
        quote="$ch"
        at_word_start=0
        ;;
      "\\")
        escaped=1
        at_word_start=0
        ;;
      '#')
        (( at_word_start )) && return 0
        at_word_start=0
        ;;
      ' ' | $'\t' | ';' | '&' | '|' | '(' | ')')
        at_word_start=1
        ;;
      *)
        at_word_start=0
        ;;
    esac
  done
  [[ -n "$quote" ]]
}

declare -A TRACKED=()
load_tracked() {
  local root="$1" path
  TRACKED=()
  while IFS= read -r path; do
    [[ -z "$path" ]] && continue
    TRACKED["$path"]=1
  done < <(git -C "$root" ls-files)
}

VIOLATIONS=0
report() {
  printf 'FAIL: %s\n' "$1" >&2
  VIOLATIONS=$((VIOLATIONS + 1))
}

check_extension_matches_shebang() {
  local root="$1" path entry family interp
  for path in "${!SHEBANG_FAMILY[@]}"; do
    entry="${SHEBANG_FAMILY[$path]}"
    family="${entry%%|*}"
    interp="${entry##*|}"
    case "$path" in
      *.sh)
        [[ "$family" == "shell" ]] || report \
          "$path has a .sh extension but a '$interp' shebang. 'bash $path' will mis-execute it line by line (issue #2066: a python program's 'import os' runs ImageMagick's import and hangs on X forever). Rename it to the extension its shebang names."
        ;;
      *.py)
        [[ "$family" == "python" ]] || report \
          "$path has a .py extension but a '$interp' shebang. Rename it to the extension its shebang names (issue #2066)."
        ;;
    esac
  done
}

check_no_shell_invocation_of_non_shell() {
  local root="$1" line path lineno text match_record match_offset match_text
  local token resolved entry family
  local LC_ALL=C
  # Match only command positions: start of a command, a shell control/prefix
  # keyword, or a workflow `run:` field. The older "any non-word boundary"
  # admitted prose and quoted arguments such as
  # `printf '%s' 'bash scripts/x.py'`. Assignment/option prefixes cover the
  # live `PATH=... /bin/bash <script>` spelling and `bash -- <script>` without
  # turning an arbitrary mention of a shell into an invocation.
  local command_prefix='(^[[:space:]]*|[;&|()][[:space:]]*|(^|[[:space:]])(if|then|elif|while|until|do|else|exec|command|builtin|env|nohup|setsid|sudo|time|run:)[[:space:]]+)'
  local leading_words='([[:alnum:]_]+=[^[:space:]]+[[:space:]]+|[-+][[:alnum:]_-]+[[:space:]]+)*'
  local interpreter='((/[^[:space:];&|()]*/)?(bash|sh|dash|zsh)|source|\.)'
  local options='([[:space:]]+(--|--?[[:alnum:]_][[:alnum:]_-]*|\+[[:alnum:]_][[:alnum:]_-]*))*'
  local target='[[:space:]]+[^[:space:];&|)]*\.(sh|py)'
  local pattern="${command_prefix}${leading_words}${interpreter}${options}${target}"
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    path="${line%%:*}"
    line="${line#*:}"
    lineno="${line%%:*}"
    text="${line#*:}"
    # The guard's own source documents the banned form; it must not flag itself.
    [[ "$path" == "scripts/check-script-interpreter-hygiene.sh" ]] && continue
    # Documentation names the dangerous command to explain it; it is not a
    # caller. Executable examples belong in scripts/workflows, not prose files.
    case "$path" in
      *.md | *.rst | *.txt | *.adoc) continue ;;
    esac
    while IFS= read -r match_record; do
      [[ -n "$match_record" ]] || continue
      match_offset="${match_record%%:*}"
      match_text="${match_record#*:}"
      command_offset_is_inert "$text" "$match_offset" && continue
      token="$(printf '%s\n' "$match_text" \
        | grep -oE '[^[:space:]]+\.(sh|py)$' || true)"
      [[ -n "$token" ]] || continue
      resolved="$(resolve_tracked_path "$root" "$token")" || continue
      entry="${SHEBANG_FAMILY[$resolved]:-}"
      [[ -n "$entry" ]] || continue
      family="${entry%%|*}"
      [[ "$family" == "shell" ]] && continue
      report "$path:$lineno invokes '$resolved' through a shell ('$token'), but $resolved is a ${family} program. Run it directly; a shell reading a python program mis-executes it line by line (issue #2066)."
    done < <(printf '%s\n' "$text" | grep -boE "$pattern" || true)
  done < <(git -C "$root" grep -I -n -E --no-color -e "$pattern" -- . 2>/dev/null || true)
}

check_tree() {
  local root="$1" path
  VIOLATIONS=0
  load_tracked "$root"
  for path in "${!TRACKED[@]}"; do
    if [[ "$path" == *:* ]]; then
      report "tracked path '$path' contains a colon; this guard parses 'path:line:text' output and cannot reason about it"
    fi
  done
  load_shebangs "$root"
  if (( ${#SHEBANG_FAMILY[@]} == 0 )); then
    report "found zero shebang-bearing tracked files under $root; the guard would pass vacuously"
  fi
  check_extension_matches_shebang "$root"
  check_no_shell_invocation_of_non_shell "$root"
  if (( VIOLATIONS > 0 )); then
    printf '\n%s: %d violation(s). See issue #2066.\n' "$root" "$VIOLATIONS" >&2
    return 1
  fi
  printf 'OK: %d tracked shebang files agree with their extension, and no tracked caller shells a non-shell program (%s).\n' \
    "${#SHEBANG_FAMILY[@]}" "$root"
  return 0
}

# ---------------------------------------------------------------------------
# Self-test
# ---------------------------------------------------------------------------

SELF_TEST_CASES=0
SELF_TEST_FAILURES=0

self_pass() {
  SELF_TEST_CASES=$((SELF_TEST_CASES + 1))
  printf 'self-test PASS: %s\n' "$1"
}

self_fail() {
  SELF_TEST_CASES=$((SELF_TEST_CASES + 1))
  SELF_TEST_FAILURES=$((SELF_TEST_FAILURES + 1))
  printf 'self-test FAIL: %s\n' "$1" >&2
}

make_fixture_tree() {
  local dir="$1"
  mkdir -p "$dir/scripts" "$dir/docs"
  printf '#!/usr/bin/env bash\necho ok\n' > "$dir/scripts/real-shell.sh"
  printf '#!/usr/bin/python3 -I\nprint("ok")\n' > "$dir/scripts/real-python.py"
  printf '#!/bin/sh\n. scripts/lib.sh\n' > "$dir/scripts/sourcer.sh"
  printf '#!/usr/bin/env bash\n:\n' > "$dir/scripts/lib.sh"
  printf 'Run scripts/real-python.py directly.\n' > "$dir/docs/readme.md"
  git -C "$dir" init -q
  git -C "$dir" add -A
}

add_selectivity_controls() {
  local dir="$1"
  # shellcheck disable=SC2016 # literal variable reference belongs in fixture.
  printf '#!/usr/bin/env bash\nPATH="$minimal_bin" /bin/bash scripts/real-shell.sh\n' \
    > "$dir/scripts/absolute-shell-caller.sh"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    "printf '%s\\n' 'bash scripts/real-python.py'" \
    "printf '%s\\n' 'not a command; bash scripts/real-python.py'" \
    '# not a command; bash scripts/real-python.py' \
    > "$dir/scripts/quoted-command-example.sh"
  # A legitimate shell invocation of a shell script, prose naming the banned
  # command, and a quoted string containing it. None may be flagged.
  printf 'This prose has a semicolon; bash scripts/real-python.py is still not a caller.\n' \
    > "$dir/docs/readme.md"
  git -C "$dir" add -A
}

run_guard_on() {
  local dir="$1" out rc=0
  out="$("$SELF_DIR/check-script-interpreter-hygiene.sh" "$dir" 2>&1)" || rc=$?
  printf '%s\n' "$out"
  return "$rc"
}

self_test_rule2_invocation() {
  local tmp="$1" fixture_name="$2" invocation="$3" description="$4"
  local dir out rc=0
  dir="$tmp/$fixture_name"
  mkdir -p "$dir"
  make_fixture_tree "$dir"
  printf '#!/usr/bin/env bash\n%s\n' "$invocation" > "$dir/scripts/caller.sh"
  git -C "$dir" add -A
  out="$(run_guard_on "$dir")" || rc=$?
  if (( rc != 0 )) && \
      [[ "$out" == *"scripts/caller.sh:2 invokes 'scripts/real-python.py' through a shell"* ]] && \
      [[ "$out" == *": 1 violation(s)"* ]]; then
    self_pass "$description"
  else
    self_fail "$description (rc=$rc): $out"
  fi
}

self_test() {
  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/ps-2066-selftest-XXXXXX")"
  # shellcheck disable=SC2064 # expand tmp now, at trap-installation time.
  trap "rm -rf '$tmp'" EXIT

  local dir out rc

  # Case 1: a clean tree passes, and the legitimate absolute-path
  # `/bin/bash scripts/real-shell.sh` caller is NOT flagged. Prose and a quoted
  # string that name the banned form are also NOT callers (selectivity).
  dir="$tmp/clean"
  mkdir -p "$dir"
  make_fixture_tree "$dir"
  add_selectivity_controls "$dir"
  rc=0
  out="$(run_guard_on "$dir")" || rc=$?
  if (( rc == 0 )) && [[ "$out" == OK:* ]]; then
    self_pass "clean real-shell, prose, and quoted-string controls are not flagged"
  else
    self_fail "a clean tree should pass (rc=$rc): $out"
  fi

  # Case 2: rule 1 — a .sh file carrying a python shebang must go RED.
  dir="$tmp/sh-is-python"
  mkdir -p "$dir"
  make_fixture_tree "$dir"
  printf '#!/usr/bin/python3 -I\nimport os\n' > "$dir/scripts/liar.sh"
  git -C "$dir" add -A
  rc=0
  out="$(run_guard_on "$dir")" || rc=$?
  if (( rc != 0 )) && [[ "$out" == *"scripts/liar.sh has a .sh extension but a 'python3' shebang"* ]]; then
    self_pass "rule 1 reddens on a .sh file with a python shebang"
  else
    self_fail "rule 1 did not redden on scripts/liar.sh (rc=$rc): $out"
  fi
  if [[ "$out" == *": 1 violation(s)"* ]]; then
    self_pass "rule 1 reports exactly the planted violation (selectivity)"
  else
    self_fail "rule 1 reported more than the planted violation: $out"
  fi

  # Case 3: rule 1, the mirror — a .py file carrying a shell shebang.
  dir="$tmp/py-is-shell"
  mkdir -p "$dir"
  make_fixture_tree "$dir"
  printf '#!/usr/bin/env bash\necho nope\n' > "$dir/scripts/liar.py"
  git -C "$dir" add -A
  rc=0
  out="$(run_guard_on "$dir")" || rc=$?
  if (( rc != 0 )) && [[ "$out" == *"scripts/liar.py has a .py extension but a 'bash' shebang"* ]]; then
    self_pass "rule 1 reddens on a .py file with a shell shebang"
  else
    self_fail "rule 1 did not redden on scripts/liar.py (rc=$rc): $out"
  fi

  # Case 4: rule 2 — a caller that shells an (honestly named) python program.
  # This is the residual trap after the rename: `bash scripts/x.py` hangs too.
  dir="$tmp/bash-invokes-python"
  mkdir -p "$dir"
  make_fixture_tree "$dir"
  printf '#!/usr/bin/env bash\nbash scripts/real-python.py\n' > "$dir/scripts/caller.sh"
  git -C "$dir" add -A
  rc=0
  out="$(run_guard_on "$dir")" || rc=$?
  if (( rc != 0 )) && [[ "$out" == *"scripts/caller.sh:2 invokes 'scripts/real-python.py' through a shell"* ]]; then
    self_pass "rule 2 reddens when a tracked caller runs a python program through bash"
  else
    self_fail "rule 2 did not redden on 'bash scripts/real-python.py' (rc=$rc): $out"
  fi

  # Case 5: rule 2 also catches the variable-prefixed and flagged forms the
  # repository actually writes ("$ROOT_DIR/scripts/x.py", `bash -n x.py`).
  dir="$tmp/bash-invokes-python-varpath"
  mkdir -p "$dir"
  make_fixture_tree "$dir"
  # shellcheck disable=SC2016 # literal variable reference belongs in fixture.
  printf '#!/usr/bin/env bash\nbash -n "$ROOT_DIR/scripts/real-python.py"\n' \
    > "$dir/scripts/caller.sh"
  git -C "$dir" add -A
  rc=0
  out="$(run_guard_on "$dir")" || rc=$?
  if (( rc != 0 )) && [[ "$out" == *"invokes 'scripts/real-python.py' through a shell"* ]]; then
    self_pass "rule 2 reddens on 'bash -n \"\$ROOT_DIR/scripts/x.py\"'"
  else
    self_fail "rule 2 missed the variable-prefixed flagged form (rc=$rc): $out"
  fi

  # Case 6: a tree with no shebang-bearing file must NOT read as a pass.
  dir="$tmp/empty"
  mkdir -p "$dir/docs"
  printf 'nothing executable here\n' > "$dir/docs/readme.md"
  git -C "$dir" init -q
  git -C "$dir" add -A
  rc=0
  out="$(run_guard_on "$dir")" || rc=$?
  if (( rc != 0 )) && [[ "$out" == *"would pass vacuously"* ]]; then
    self_pass "a tree with zero shebang files is refused, not silently passed"
  else
    self_fail "a shebang-free tree passed vacuously (rc=$rc): $out"
  fi

  # Rule 2's interpreter spelling is a class: every supported shell must be
  # caught both through an absolute executable path and with `--` separating
  # interpreter options from the tracked non-shell program.
  self_test_rule2_invocation "$tmp" "absolute-bash" \
    "/bin/bash scripts/real-python.py" \
    "rule 2 reddens on '/bin/bash scripts/real-python.py'"
  # shellcheck disable=SC2016 # literal variable reference belongs in fixture.
  self_test_rule2_invocation "$tmp" "live-prefix-absolute-bash" \
    'PATH="$minimal_bin" /bin/bash scripts/real-python.py' \
    "rule 2 reddens on the live 'PATH=... /bin/bash scripts/real-python.py' route"
  self_test_rule2_invocation "$tmp" "option-bash" \
    "bash -- scripts/real-python.py" \
    "rule 2 reddens on 'bash -- scripts/real-python.py'"
  self_test_rule2_invocation "$tmp" "absolute-option-bash" \
    "/usr/local/bin/bash -n -- scripts/real-python.py" \
    "rule 2 reddens on an arbitrary absolute bash path with options and '--'"
  self_test_rule2_invocation "$tmp" "absolute-sh" \
    "/bin/sh scripts/real-python.py" \
    "rule 2 reddens on '/bin/sh scripts/real-python.py'"
  self_test_rule2_invocation "$tmp" "option-sh" \
    "sh -- scripts/real-python.py" \
    "rule 2 reddens on 'sh -- scripts/real-python.py'"
  self_test_rule2_invocation "$tmp" "absolute-dash" \
    "/bin/dash scripts/real-python.py" \
    "rule 2 reddens on '/bin/dash scripts/real-python.py'"
  self_test_rule2_invocation "$tmp" "option-dash" \
    "dash -- scripts/real-python.py" \
    "rule 2 reddens on 'dash -- scripts/real-python.py'"
  self_test_rule2_invocation "$tmp" "absolute-zsh" \
    "/usr/bin/zsh scripts/real-python.py" \
    "rule 2 reddens on '/usr/bin/zsh scripts/real-python.py'"
  self_test_rule2_invocation "$tmp" "option-zsh" \
    "zsh -- scripts/real-python.py" \
    "rule 2 reddens on 'zsh -- scripts/real-python.py'"
  self_test_rule2_invocation "$tmp" "source-python" \
    "source scripts/real-python.py" \
    "rule 2 still reddens on 'source scripts/real-python.py'"
  self_test_rule2_invocation "$tmp" "dot-python" \
    ". scripts/real-python.py" \
    "rule 2 still reddens on '. scripts/real-python.py'"

  printf '\nself-test: %d case(s), %d failure(s)\n' \
    "$SELF_TEST_CASES" "$SELF_TEST_FAILURES"
  if (( SELF_TEST_CASES == 0 )); then
    printf 'self-test executed zero cases; that is not a pass.\n' >&2
    return 1
  fi
  (( SELF_TEST_FAILURES == 0 ))
}

main() {
  case "${1:-}" in
    --self-test)
      self_test
      ;;
    -h | --help)
      sed -n '/^# USAGE/,/^$/p' "${BASH_SOURCE[0]}"
      ;;
    "")
      check_tree "$DEFAULT_ROOT"
      ;;
    *)
      check_tree "$1"
      ;;
  esac
}

main "$@"
