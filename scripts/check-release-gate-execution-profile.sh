#!/usr/bin/env bash
set -euo pipefail

# Issue #2054: keep every Gradle build in the release-validation chain on the
# shared, heap-bounded execution profile from scripts/lib/gradle-profile.sh.
#
# WHAT WENT WRONG (three failed v0.4.42 release gates in under 24h)
# -----------------------------------------------------------------
# scripts/pre-release-confidence-gate.sh carried its own flag string
# (`--no-daemon --no-build-cache --no-parallel --max-workers=2`) with NO heap
# flags, and four sibling scripts in the same chain hardcoded their own
# `./gradlew` lines with no heap flags either. The Kotlin daemon therefore ran on
# gradle.properties' inherited 2048m inside an 8G scope, and the gate died in the
# BUILD — 18m59s, then 43m58s of swap thrash into a zipflinger packaging OOM,
# then 391s to a `GC overhead limit exceeded`. Zero product assertions ran.
#
# WHAT THIS GUARD PINS
# --------------------
#   1. Every `gradlew` invocation in the release chain splices in the shared
#      resource args (or the gate's asserted GRADLE_FLAGS). A NEW hardcoded
#      build line added later fails here, at PR time, in under a second — and
#      the detection covers `"$old_worktree/gradlew"`, not just `./gradlew`.
#   2. Every one of those scripts sources the profile lib AND calls the
#      fail-fast assertion AND applies the build-scope ceiling, with each of
#      those three occurrences required to be a statement that actually RUNS —
#      not commented out, not heredoc data, not behind a `&&`/`||`/`|`/`|&`
#      short-circuit, not piped or backgrounded on its own line, not inside a
#      subshell or backticks whose export dies with them.
#      Presence-as-substring is not enough (`# ` in front of the apply call
#      would otherwise keep this green while the build ran at 8G).
#   3. The scope apply must DOMINATE every build line it protects: earlier in
#      the file AND on every execution path that reaches it, decided from the
#      script's real BLOCK STRUCTURE (see dominating_apply_exists for the exact
#      property and its named residual gaps). Placement was the entire round-1
#      defect on this issue, reachability the round-2 one, "the call is there
#      but never executes" the round-3 one, and "the call runs, in a CHILD"
#      the round-4 one; neither a presence check, nor an indentation heuristic,
#      nor block paths alone can see them.
#   3b. No script the chain INVOKES may run a build of its own from outside
#      RELEASE_CHAIN_SCRIPTS (see check_build_script_closure). Moving a
#      `gradlew` line one file out keeps the SCOPE (a child inherits the export)
#      but silently drops the HEAP flags — the other half of the same OOM.
#   4. Neither profile's floors/constants may be turned into an environment
#      override (`${VAR:-default}`), proven behaviourally by re-sourcing the lib
#      in a fresh interpreter with every name preset to a bypass value.
#   5. The LOCAL and HOSTED profiles pair their heap half with their scope half,
#      and the hosted pair still matches what
#      scripts/check-release-emulator-memory-budget.sh requires of
#      .github/workflows/release-emulator-validation.yml.
#   6. The assertion itself rejects a missing/undersized/duplicated heap bound
#      and renewed parallelism — proven by the mutation self-test below, which
#      deletes each heap flag from a real fixture and requires a RED.
#
# CI-SENSITIVITY — READ BEFORE ADDING A CHECK
# --------------------------------------------
# The >= 20G build-scope floor is deliberately CI-exempt (a 16 GiB hosted runner
# cannot honour it). That makes `CI` a MEANING-CHANGING variable for this file:
# GitHub Actions always sets `CI=true` on the required `Unit tests` job, which is
# the only lane that ever runs this guard per push. Round 1 of this issue shipped
# a regression pin that ran its child WITHOUT unsetting `CI`, so the floor was
# skipped, the child exited 0, and the pin — which requires non-zero — turned
# every PR red while staying green locally. The mirror image of the very defect
# it was written to close.
#
# Therefore: no check in this file may read ambient `CI`. Every check runs
# through exactly one of the two audited wrappers below, `as_local` or
# `as_hosted`, which state which profile's semantics that check pins. The
# `--self-test` entry point then re-runs the entire body under both `CI` shapes
# and requires byte-identical results, so a future call site that forgets is a
# hard RED rather than a silently vacuous check.
#
# Companion guard: scripts/check-release-emulator-memory-budget.sh pins the
# HOSTED profile in .github/workflows/release-emulator-validation.yml. This one
# pins the LOCAL default profile, the script wiring, and the agreement between
# the two.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SELF_PATH="$ROOT_DIR/scripts/$(basename "${BASH_SOURCE[0]}")"
HOSTED_WORKFLOW=".github/workflows/release-emulator-validation.yml"
PROFILE_LIB="scripts/lib/gradle-profile.sh"

CHECKS_RUN=0

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  return 1
}

# The two audited environment wrappers. See "CI-SENSITIVITY" above: a check that
# pins a LOCAL property must not inherit GitHub Actions' `CI=true`, and a check
# that pins the HOSTED property must not depend on the runner happening to set
# it. Both run in a subshell, so neither leaks.
as_local() { (unset CI && "$@"); }
as_hosted() { (export CI=true && "$@"); }

# Scripts in the release-validation chain. All of them must be wired to the
# shared profile; the ones that build must additionally dominate their build
# lines with a scope apply.
RELEASE_CHAIN_SCRIPTS=(
  scripts/pre-release-confidence-gate.sh
  scripts/phone-walkthrough.sh
  scripts/capture-walkthrough-screenshots.sh
  scripts/parallel-setup-detection.sh
  scripts/terminal-workbench.sh
  scripts/android-upgrade-preservation-gate.sh
  scripts/release-emulator-validation.sh
)

# A Gradle build invocation is ANY `gradlew` execution, not only `./gradlew`.
# scripts/android-upgrade-preservation-gate.sh runs a second worktree's wrapper
# as `"$old_worktree/gradlew"`, which the round-1 `grep '\./gradlew'` missed
# entirely — a hardcoded unbounded build line written that way escaped the whole
# wiring check.
GRADLEW_INVOCATION_RE='(^|[/[:space:]"'"'"'])gradlew(["'"'"']|[[:space:]]|$)'

gradlew_invocation_lines() {
  grep -nE "$GRADLEW_INVOCATION_RE" "$1" | grep -v '^[0-9]*:[[:space:]]*#' || true
}

# `gradlew --stop` starts no compiler and needs no heap profile. The exemption is
# ANCHORED to the end of the invocation: the round-1 `*"--stop"*` wildcard would
# have exempted any future build line that merely contained that substring
# anywhere (e.g. a `--stop-after` flag or a log path).
gradlew_line_is_compliant() {
  local line="$1"
  if [[ "$line" =~ gradlew[\"\']?[[:space:]]+--stop[[:space:]]*$ ]]; then
    return 0
  fi
  case "$line" in
    *"POCKETSHELL_GRADLE_RESOURCE_ARGS"*) return 0 ;;
    *'$GRADLE_FLAGS'*) return 0 ;;
    *'${GRADLE_ARGS[@]}'*) return 0 ;;
  esac
  return 1
}

# Lines whose TEXT looks like a statement-initial call to $2 in file $1, as
# `n:line`. Anchoring to the start of the statement is the point: `grep -Fq`
# matches the call inside a comment, a heredoc, or a usage block, so commenting
# a call out leaves the substring — and the guard — perfectly green.
#
# This is only the TEXT half. Text alone cannot tell an executable statement
# from heredoc data, a short-circuit continuation, or a subshell body, so every
# call site goes through eligible_call_lines below, which filters this list by
# the block scanner's per-line classification.
textual_call_lines() {
  grep -nE "^[[:space:]]*$2([[:space:]]|\$)" "$1" || true
}

# The lines from textual_call_lines that the scanner classifies as REAL,
# UNCONDITIONAL, EXPORT-SURVIVING code — flags `-`. Requires load_block_paths to
# have run for this file. Three classifications are rejected, each because the
# call it names does not do what its text says (round-3 reviewer findings):
#
#   H  heredoc DATA. `cat <<'EOS' / <apply> / EOS` contains the call as a
#      string. Nothing executes; the build runs at scope-run.sh's 8G default.
#      All THREE ways bash spells a quoted delimiter count — `'EOS'`, `"EOS"`
#      and `\EOS` — plus the plain unquoted `EOS`; the backslash spelling was
#      the round-5 escape (see heredoc_word).
#   C  a continuation of the previous line's `&&`, `||`, `|`, `|&` or `\`. An
#      apply reached only through `cond && apply || true` is conditional (round
#      2's rejected `if`-wrap in a different spelling), and one reached as a
#      later stage of a pipeline runs in a subshell, so its export never
#      escapes. This is the CROSS-LINE half of the pipeline hazard.
#   P  the line's OWN command list contains a top-level `|` (or `|&`, or a
#      backgrounding `&`). `apply "…" | tee -a "$log"` runs the apply in a
#      pipeline subshell and `apply … &` in a background one; in both the
#      `export` dies with the child. This is the SAME-LINE half of the pipeline
#      hazard, and it was open through round 4 while its cross-line twin (`C`)
#      was closed — the docstring named the hazard and shut only one of its two
#      spellings.
#   S  inside a top-level `( … )` region carried in from an earlier line — a
#      subshell (whose export dies with it), or an array/arithmetic/process
#      substitution body, which is not a statement at all.
#   Q  inside a quote, `$( )` or backticks carried in from an earlier line: the
#      text is a string, not a statement. `msg="` / `<apply>` / `"` is N3 in
#      quotes; `` MUT=` `` / `<apply>` / `` ` `` is the same hole in the legacy
#      substitution spelling.
#
# An unknown line (the scanner could not model the file) is ineligible too —
# fail closed, never assume.
eligible_call_lines() {
  local file="$1"
  local name="$2"
  local entry lineno
  while IFS= read -r entry; do
    [[ -n "$entry" ]] || continue
    lineno="${entry%%:*}"
    [[ "${BLOCK_FLAGS[$lineno]:-?}" == "-" ]] || continue
    printf '%s\n' "$entry"
  done < <(textual_call_lines "$file" "$name")
}

# " (line 356 looks like one but is classified HC…)" — so a rejection names the
# text that fooled the eye instead of claiming the call is simply absent.
ineligible_note() {
  local file="$1"
  local name="$2"
  local entry lineno flags note=""
  while IFS= read -r entry; do
    [[ -n "$entry" ]] || continue
    lineno="${entry%%:*}"
    flags="${BLOCK_FLAGS[$lineno]:-?}"
    [[ "$flags" != "-" ]] || continue
    note="$note line $lineno=$flags"
  done < <(textual_call_lines "$file" "$name")
  [[ -z "$note" ]] ||
    printf ' (text that is NOT executable code —%s; H=heredoc data, C=&&/||/|/|&/\\ continuation, P=own line has a top-level pipe or backgrounding & so the export dies in a subshell, S=inside a top-level ( … ) whose export would not escape, Q=inside a carried-over quote, $( ) or backticks, ?=unmodelled)' "$note"
}

# ---------------------------------------------------------------------------
# Block-structure scanner (see dominating_apply_exists for why).
#
# For every line of a bash script it emits
# `<lineno> <block-path> <flags> <codelen>`.
#
# The PATH is the chain of BLOCK INSTANCES open at that line — `/`, `/b6/`,
# `/b6/b9/`. A fresh instance id is minted for every `if`/`for`/`while`/`case`/
# `{` body, for every top-level subshell `( … )`, AND for every sibling branch
# (`else`, `elif`, a new `case` pattern, `;;`), so two statements share a path
# prefix only when the earlier one really does run on every path that reaches
# the later one.
#
# The FLAGS classify the line as executable code or not — `-` for real code, or
# a combination of:
#   H  the line is heredoc BODY data, not code;
#   C  the line continues the previous line's `&&`, `||`, `|`, `|&` or trailing
#      `\`, so it runs conditionally and/or in a pipeline subshell;
#   P  the line's own command list contains a top-level `|` / `|&` / a
#      backgrounding `&`, so the FIRST command on it — the call site — runs in a
#      pipeline or background subshell and its `export` cannot escape;
#   S  the line begins inside a top-level `( … )` region, so an `export` on it
#      dies with that subshell (or the region is an array/arith body);
#   Q  the line begins inside a quote, `$( )` or backticks carried over from an
#      earlier line, so its text is string data rather than a statement.
# Statement text alone cannot see any of these (a heredoc body, an `&&`
# continuation, a piped call, a subshell body and a multi-line string all look
# like plain statements); H/C/S were live escapes found by the round-3 reviewer
# and P by the round-4 one. eligible_call_lines consumes these flags.
#
# It is a scanner, not a shell parser, so it is written to FAIL LOUDLY rather
# than silently mis-model: quotes, `$( )`, `$(( ))`, `${ }`, `<<<`, comments and
# heredoc bodies are all tracked, and if the stack does not return to empty at
# EOF the guard rejects the file instead of trusting a half-parse.
#
# PORTABILITY: plain POSIX awk — no gawk extensions. The dev box resolves `awk`
# to gawk while GitHub's ubuntu runners resolve it to mawk, so both were run
# against all seven release-chain scripts, every script the closure walk reaches,
# and busybox awk too, and their output is BYTE-identical. Keep it that way: a
# gawk-only construct here would make the reachability rule behave differently on
# the only lane that runs it per push.
#
# Two things this file does deliberately to KEEP that true, both learned by
# running the comparison rather than assuming it:
#   * no `for (k in array)` anywhere in the output path (see the END block).
#     Iteration order is unspecified and all three implementations chose
#     differently, which made the outputs undiffable even though every record
#     agreed.
#   * every invocation is pinned to `LC_ALL=C`. `length()`/`substr()` count
#     CHARACTERS under gawk in a UTF-8 locale and BYTES under mawk and busybox,
#     so the `codelen` field disagreed by 2 on any line containing an em dash —
#     and this repo's scripts print plenty of them. In the C locale all three
#     count bytes, and the shell never re-slices the line itself, so the offset
#     is only ever consumed by another `LC_ALL=C awk`.
read -r -d '' BLOCK_PATH_AWK <<'BLOCK_PATH_AWK_EOF' || true
BEGIN { hdqi = 1; hdqn = 0 }
function newid() { counter++; return "b" counter }
function curpath(   i, s) { s = "/"; for (i = 1; i <= depth; i++) s = s ids[i] "/"; return s }
function push(kind) { depth++; ids[depth] = newid(); kinds[depth] = kind; openline[ids[depth]] = NR }
function pop() {
  if (depth > 0) { delete ids[depth]; delete kinds[depth]; depth-- }
  else { broke = broke " close-without-open@" NR }
}
function branch() { if (depth > 0) { ids[depth] = newid(); openline[ids[depth]] = NR } else broke = broke " branch-outside-block@" NR }
function ctop() { return (cdepth > 0) ? ctx[cdepth] : "" }
function cpush(k) { cdepth++; ctx[cdepth] = k }
function cpop() { if (cdepth > 0) { delete ctx[cdepth]; cdepth-- } }
# The flag string for the line about to be printed. `-` means real, executable,
# unconditional, export-surviving code; anything else disqualifies the line as a
# call site (see eligible_call_lines).
function flagstr(extra,   f) {
  f = extra
  # An unterminated context carried in from a PREVIOUS line means this line does
  # not begin a statement at all. Which context decides which lie it would tell:
  # `paren` is a subshell / array / arithmetic / process-substitution body (an
  # `export` there never reaches the parent's build); anything else is an open
  # quote, `$( )` or backtick pair, i.e. string data wearing a statement's
  # clothes. The state read here is the one carried IN to this line (captured
  # before sanitize runs), not the state sanitize leaves behind.
  if (in_cdepth > 0) { if (in_ctx1 == "paren") f = f "S"; else f = f "Q" }
  if (iscont) f = f "C"
  if (ispipe) f = f "P"
  if (f == "") return "-"
  return f
}
# Does this line's OWN sanitized code put its first command into a subshell?
# A top-level `|` (or `|&`) makes every stage of the pipeline a child process,
# and a top-level `&` backgrounds the preceding command into one; in both cases
# an `export` performed there is invisible to the later build. `||` and `&&` are
# NOT this: they are conditional operators, and the FIRST command on the line —
# the only position textual_call_lines ever matches — still runs in this shell.
# Redirections that merely contain `&` (`2>&1`, `>&2`, `&>log`) are excluded.
function top_pipe_or_bg(c,   i, ch, p, nx) {
  for (i = 1; i <= length(c); i++) {
    ch = substr(c, i, 1)
    if (ch == "|") {
      if (substr(c, i + 1, 1) == "|") { i++; continue }
      return 1
    }
    if (ch == "&") {
      nx = substr(c, i + 1, 1)
      p = (i > 1) ? substr(c, i - 1, 1) : ""
      if (nx == "&") { i++; continue }
      if (p == ">" || p == "<") continue
      if (nx == ">") { i++; continue }
      return 1
    }
  }
  return 0
}
# Blank out quoted text, substitutions and comments; keep only the characters
# that are shell block syntax in this line's own context.
#
# Side channels for the caller: `hdstarts[1..nhdstart]` (where EACH heredoc word
# on this line begins — bash allows several redirections per command, e.g.
# `cmd <<EOA <<EOB`, and their bodies follow in order), `trailing_esc` (the line
# ended on an unquoted `\`, i.e. it continues), and `parenops` (the ordered
# open/close events for TOP-LEVEL parens on this line — a real subshell
# boundary, which the token splitter below cannot see because it treats `(` and
# `)` as delimiters).
function sanitize(l,   i, c, n1, n2, out, esc, p, t, popped) {
  out = ""; esc = 0; nhdstart = 0; parenops = ""; trailing_esc = 0; codelen = length(l)
  for (i = 1; i <= length(l); i++) {
    c = substr(l, i, 1)
    n1 = substr(l, i + 1, 1)
    n2 = substr(l, i + 2, 1)
    t = ctop()
    if (esc) { esc = 0; out = out " "; continue }
    if (t == "sq") { if (c == "'") cpop(); out = out " "; continue }
    # Backticks are the legacy spelling of `$( )` and are a substitution context
    # exactly like it — including INSIDE double quotes. Without this a
    # multi-line `` X=` … ` `` around a call reads as three plain statements.
    if (t == "bt") {
      if (c == "\\") { esc = 1; out = out " "; continue }
      if (c == "`") cpop()
      out = out " "; continue
    }
    if (t == "dq") {
      if (c == "\\") { esc = 1; out = out " "; continue }
      if (c == "`") { cpush("bt"); out = out " "; continue }
      if (c == "$" && n1 == "(") { cpush("sub"); i++; out = out "  "; continue }
      if (c == "$" && n1 == "{") { cpush("pexp"); i++; out = out "  "; continue }
      if (c == "\"") cpop()
      out = out " "; continue
    }
    # Unquoted: top level, or inside ( ) / $( ) / ${ } / ` `.
    if (c == "\\") { esc = 1; out = out " "; continue }
    if (c == "'") { cpush("sq"); out = out " "; continue }
    if (c == "\"") { cpush("dq"); out = out " "; continue }
    if (c == "`") { cpush("bt"); out = out " "; continue }
    if (c == "$" && n1 == "(") { cpush("sub"); i++; out = out "  "; continue }
    if (c == "$" && n1 == "{") { cpush("pexp"); i++; out = out "  "; continue }
    if (t == "pexp" && c == "}") { cpop(); out = out " "; continue }
    if (c == "#") {
      p = (i == 1) ? " " : substr(l, i - 1, 1)
      # `codelen` (emitted as the record's 4th field) is how many leading
      # characters of the RAW line are shell code. check_build_script_closure
      # uses it so a script named in a trailing comment is read as prose, not as
      # an invocation.
      if (p == " " || p == "\t" || p == ";" || p == "&" || p == "|" || p == "(") { codelen = i - 1; break }
    }
    if (t == "") {
      # A top-level `(` opens a SUBSHELL (or a grouping/array/arith paren, all
      # of which are balanced on the spot). It is recorded as a real block
      # boundary. `(` is still emitted so the case-pattern reader below keeps
      # working; the token splitter treats it as a delimiter either way.
      if (c == "(") { cpush("paren"); parenops = parenops "("; out = out "("; continue }
      # An unmatched top-level `)` is a `case` pattern terminator, not a block
      # close — leave it for the case reader.
      if (c == ")") { out = out ")"; continue }
      if (c == "<" && n1 == "<" && n2 == "<") { i += 2; out = out "   "; continue }
      # EVERY `<<` on the line, not just the first: `cmd <<EOA <<EOB` queues two
      # bodies and an apply hidden in the SECOND one was invisible while this
      # recorded a single position.
      if (c == "<" && n1 == "<") { nhdstart++; hdstarts[nhdstart] = i + 2; i++; out = out "  "; continue }
      out = out c; continue
    }
    if (c == "(") { cpush("paren"); out = out " "; continue }
    if (c == ")") {
      popped = ctop()
      cpop()
      # Only a `paren` closing back to the TOP level ends a subshell. A `)` that
      # closes `$( )` returns to top level too, which is why the popped kind is
      # checked as well.
      if (popped == "paren" && cdepth == 0) parenops = parenops ")"
      out = out " "
      continue
    }
    out = out " "
  }
  trailing_esc = esc
  return out
}
# The delimiter word introduced at position `start`, and (side channel
# `hd_dash`) whether the redirection was the `<<-` form. Only `<<-` permits an
# indented terminator, and POSIX/bash strip only leading TABS for it — never
# spaces, and never trailing whitespace in either form. Getting that wrong ends
# the body EARLY at a fake indented terminator, after which real body text is
# read as executable code.
#
# Bash treats the delimiter as QUOTED (no expansion in the body) when ANY
# character of the word is quoted — and a BACKSLASH is one of the three ways to
# spell that, alongside `'` and `"`. `<<\EOS` is exactly `<<'EOS'`; verified
# against real bash. Round 5 modelled only the two quote characters, so a
# leading `\` matched neither branch, the `[A-Za-z0-9_]` scan started ON the
# backslash and returned an EMPTY word, the heredoc was never queued, and every
# body line was then read as executable code. That errs toward ACCEPTING: an
# apply inside such a body reads as a dominating sibling while bash never runs
# it (measured `POCKETSHELL_TEST_MEM=<UNSET>` at the real build). Only a word
# whose FIRST character is a backslash needs modelling; see the delimiter table
# in KNOWN CONSERVATIVE REJECTIONS for why the other placements are already
# fail-closed.
function heredoc_word(l, start,   i, c, w, qch) {
  i = start
  hd_dash = 0
  while (i <= length(l) && (substr(l, i, 1) == "-" || substr(l, i, 1) == " " || substr(l, i, 1) == "\t")) {
    if (substr(l, i, 1) == "-") hd_dash = 1
    i++
  }
  c = substr(l, i, 1)
  # `<<\EOS` / `<<-\EOS`: consume the quoting backslash and read the delimiter
  # after it. `<<\'EOS'` and `<<\"EOS"` are bash SYNTAX ERRORS (the backslash
  # escapes the quote, leaving it unterminated), so no `bash -n` clean script
  # can reach the quote branch through here.
  if (c == "\\") { i++; c = substr(l, i, 1) }
  if (c == "'" || c == "\"") {
    qch = c; i++; w = ""
    while (i <= length(l) && substr(l, i, 1) != qch) { w = w substr(l, i, 1); i++ }
    return w
  }
  w = ""
  while (i <= length(l) && substr(l, i, 1) ~ /[A-Za-z0-9_]/) { w = w substr(l, i, 1); i++ }
  return w
}
{
  raw = $0
  iscont = pending_cont
  ispipe = 0
  # The context carried IN to this line. sanitize() mutates the context stack as
  # it scans, so flagstr must read the state as it was at the START of the line.
  in_cdepth = cdepth
  in_ctx1 = (cdepth > 0) ? ctx[1] : ""
  # A heredoc body is data, not code, but it is EMITTED where the heredoc
  # opens, so its lines inherit that line's path (pre-release-confidence-gate.sh
  # builds real `gradlew` lines inside `cat <<SCRIPT` bodies). The `H` flag is
  # what stops that data from being read as an executable call. Bodies are
  # consumed in the order their redirections appeared (hdqi walks the queue).
  if (hdqi <= hdqn) {
    print NR " " hdpath " " flagstr("H") " 0"
    t = raw
    if (hdqd[hdqi]) sub(/^\t+/, "", t)
    if (t == hdq[hdqi]) {
      hdqi++
      if (hdqi > hdqn) { hdqn = 0; hdqi = 1 }
    }
    next
  }
  code = sanitize(raw)
  ispipe = top_pipe_or_bg(code)
  print NR " " curpath() " " flagstr("") " " codelen

  ncase = 0
  while (match(code, /;;/)) { ncase++; sub(/;;/, "  ", code) }
  if (ncase > 0 && depth > 0 && kinds[depth] == "case") branch()

  if (depth > 0 && kinds[depth] == "case") {
    pidx = index(code, ")")
    if (pidx > 0 && index(substr(code, 1, pidx), "(") == 0) branch()
  }

  n = split(code, tok, /[ \t;&|()]+/)
  first = ""; last = ""
  for (i = 1; i <= n; i++) if (tok[i] != "") { if (first == "") first = tok[i]; last = tok[i] }

  if (first == "case" && last == "in") {
    push("case")
  } else {
    suppress_then = 0
    for (i = 1; i <= n; i++) {
      t = tok[i]
      if (t == "") continue
      if (t == "elif") { branch(); suppress_then = 1; continue }
      if (t == "else") { branch(); continue }
      if (t == "then") { if (suppress_then) suppress_then = 0; else push("if"); continue }
      if (t == "do") { push("loop"); continue }
      if (t == "{") { push("brace"); continue }
      if (t == "fi" || t == "done" || t == "esac" || t == "}") { pop(); continue }
    }
  }

  # Top-level paren regions, in the order they appeared on this line. These are
  # real block boundaries: a build INSIDE a subshell is still protected by an
  # apply outside it (an export propagates in), so the path must get deeper.
  np = length(parenops)
  for (pi = 1; pi <= np; pi++) {
    pc = substr(parenops, pi, 1)
    if (pc == "(") push("subshell")
    else pop()
  }

  if (nhdstart > 0) {
    hdqn = 0
    hdqi = 1
    for (hi = 1; hi <= nhdstart; hi++) {
      w = heredoc_word(raw, hdstarts[hi])
      # An EMPTY word means the scanner could not determine this heredoc's
      # terminator, so it cannot tell where the body ends. Silently NOT queuing
      # it — what rounds 1-6 did — reads every body line as executable code,
      # which is the permissive direction and is exactly how `<<\EOS` (round 5),
      # `<<\\EOS`, `<<""` and `<<$VAR` (round 7) each put a real apply at `-`
      # while bash swallowed it as data. Rejecting the whole FILE instead is the
      # class fix: it does not enumerate spellings, so a spelling nobody has
      # thought of yet fails closed by construction.
      #
      # This cannot over-reject the shipped tree, and both halves of that were
      # MEASURED on the current tree rather than assumed:
      #   * `<<<` is a here-string, and sanitize() consumes it at its own
      #     `c == "<" && n1 == "<" && n2 == "<"` branch WITHOUT recording an
      #     hdstart, so heredoc_word is never called for one. Verified by
      #     instrumenting hdstarts and finding none on any `<<<` line in the
      #     tree (228 lines across 37 files) and none in the `<<<` self-test
      #     cases.
      #   * no shipped script yields an empty delimiter word. Verified by
      #     instrumenting this exact branch and sweeping every `scripts/*.sh`,
      #     `scripts/lib/*.sh` and `tests/docker/lib/*.sh` (101 files), plus
      #     every tracked `*.sh` in the repository (125 files): zero hits, with
      #     the instrumentation proven live because it fires on all three
      #     round-7 mutants.
      # `continue`, not awk's `next`: `next` would abandon the whole input
      # record and skip the continuation-state update at the end of this rule.
      # The file is rejected either way; leaving the parser's own bookkeeping
      # intact keeps the reported status honest.
      if (w == "") { broke = broke " undeterminable-heredoc-delimiter@" NR; continue }
      hdqn++; hdq[hdqn] = w; hdqd[hdqn] = hd_dash
    }
    if (hdqn > 0) hdpath = curpath()
  }

  # Does the NEXT line continue this one? A trailing `&&`, `||`, `|`, `|&` or
  # `\` means the next statement is conditional on this one and/or lives in a
  # pipeline subshell. `|&` (bash's `2>&1 |`) is a pipe operator like any other
  # and was missed while the tail test read only `/\|$/`.
  #
  # Only a line that CARRIES CODE updates the state. A blank or comment-only
  # line carries it through instead, because bash lets a comment sit between an
  # operator and its operand — the conservative direction. The `hascode` test
  # reads the RAW line, not the sanitized one: a line consisting solely of a
  # quoted argument sanitizes to spaces and would otherwise be mistaken for a
  # comment, stranding the continuation flag on every following line.
  bare = raw
  sub(/^[ \t]+/, "", bare)
  hascode = (bare != "" && substr(bare, 1, 1) != "#")
  if (hascode) {
    tail = code
    sub(/[ \t]+$/, "", tail)
    if (trailing_esc) pending_cont = 1
    else if (tail ~ /&&$/) pending_cont = 1
    else if (tail ~ /\|$/) pending_cont = 1
    else if (tail ~ /\|&$/) pending_cont = 1
    else pending_cont = 0
  }
}
END {
  # Walk the ids in mint order, NOT `for (k in openline)`: array iteration order
  # is unspecified and gawk, mawk and busybox awk each choose differently, so the
  # `for-in` form made the scanner's output differ byte-for-byte between the dev
  # box (gawk) and the hosted runner (mawk) even though every record agreed.
  # Behaviour never depended on the order — the consumer builds a map — but an
  # instrument whose output you cannot diff across implementations is one you
  # cannot check for portability, which is the whole point of this section.
  for (i = 1; i <= counter; i++) {
    k = "b" i
    if (k in openline) print "#OPEN " k " " openline[k]
  }
  status = ""
  if (depth != 0) status = status " unbalanced-depth=" depth
  if (cdepth != 0) status = status " unbalanced-quote-or-substitution"
  if (hdqi <= hdqn) status = status " unterminated-heredoc=" hdq[hdqi]
  if (broke != "") status = status broke
  if (status == "") print "#PARSE-OK"
  else print "#PARSE-FAIL" status
}
BLOCK_PATH_AWK_EOF

declare -A BLOCK_PATHS=()
declare -A BLOCK_FLAGS=()
declare -A BLOCK_CODELEN=()
declare -A BLOCK_OPEN_LINE=()
BLOCK_PARSE_STATUS=""

# Populate BLOCK_PATHS (lineno -> block path), BLOCK_FLAGS (lineno -> `-` or an
# `H`/`C`/`P`/`S`/`Q` combination) and BLOCK_CODELEN (lineno -> how many leading
# raw characters are code rather than a trailing comment) for one script, and
# record whether the scan balanced. A file that cannot be modelled is rejected,
# never trusted.
load_block_paths() {
  local file="$1"
  local lineno path flags codelen entry rest
  BLOCK_PATHS=()
  BLOCK_FLAGS=()
  BLOCK_CODELEN=()
  BLOCK_OPEN_LINE=()
  BLOCK_PARSE_STATUS="#PARSE-OK"
  while IFS=' ' read -r lineno path flags codelen; do
    case "$lineno" in
      '#PARSE-'*)
        BLOCK_PARSE_STATUS="$lineno${path:+ $path}${flags:+ $flags}${codelen:+ $codelen}"
        continue
        ;;
      '#OPEN')
        BLOCK_OPEN_LINE["$path"]="$flags"
        continue
        ;;
    esac
    BLOCK_PATHS["$lineno"]="$path"
    BLOCK_FLAGS["$lineno"]="$flags"
    BLOCK_CODELEN["$lineno"]="$codelen"
  done < <(LC_ALL=C awk "$BLOCK_PATH_AWK" "$file")
  [[ "$BLOCK_PARSE_STATUS" == "#PARSE-OK" ]]
}

check_script_wiring() {
  local root="$1"
  local rel script line lineno
  local rc=0

  for rel in "${RELEASE_CHAIN_SCRIPTS[@]}"; do
    script="$root/$rel"
    CHECKS_RUN=$((CHECKS_RUN + 1))
    if [[ ! -f "$script" ]]; then
      fail "release-chain script missing: $rel" || rc=1
      continue
    fi

    CHECKS_RUN=$((CHECKS_RUN + 1))
    grep -qE '^[[:space:]]*(source|\.)[[:space:]].*scripts/lib/gradle-profile\.sh' "$script" ||
      { fail "$rel does not source scripts/lib/gradle-profile.sh in an executable statement" || rc=1; }

    # Model the file's block structure FIRST; a file the scanner cannot balance
    # is REJECTED, because an unmodelled file is exactly where an unreachable
    # apply would hide. Every check below reads this model, so it has to exist
    # before the call sites are classified.
    CHECKS_RUN=$((CHECKS_RUN + 1))
    if ! load_block_paths "$script"; then
      fail "$rel could not be modelled by the block-structure scanner ($BLOCK_PARSE_STATUS); the scope-apply reachability rule cannot be evaluated, so this file is rejected rather than assumed safe" || rc=1
    fi

    CHECKS_RUN=$((CHECKS_RUN + 1))
    local -a assert_lines=()
    mapfile -t assert_lines < <(eligible_call_lines "$script" pocketshell_assert_gradle_execution_profile)
    [[ "${#assert_lines[@]}" -gt 0 ]] ||
      { fail "$rel never calls pocketshell_assert_gradle_execution_profile as an executable statement$(ineligible_note "$script" pocketshell_assert_gradle_execution_profile); an under-resourced profile would only surface as an OOM deep into the build" || rc=1; }

    CHECKS_RUN=$((CHECKS_RUN + 1))
    local -a apply_lines=()
    mapfile -t apply_lines < <(eligible_call_lines "$script" pocketshell_apply_release_gate_scope_memory)
    [[ "${#apply_lines[@]}" -gt 0 ]] ||
      { fail "$rel never applies the release build-scope MemoryMax as an executable statement$(ineligible_note "$script" pocketshell_apply_release_gate_scope_memory); heavy steps would fall back to scope-run.sh's 8G default" || rc=1; }

    while IFS= read -r entry; do
      [[ -n "$entry" ]] || continue
      lineno="${entry%%:*}"
      line="${entry#*:}"
      CHECKS_RUN=$((CHECKS_RUN + 1))
      if ! gradlew_line_is_compliant "$line"; then
        fail "$rel:$lineno has a Gradle invocation with no bounded heap profile: ${line#"${line%%[![:space:]]*}"}" || rc=1
        continue
      fi
      # `gradlew --stop` allocates nothing and needs no scope headroom.
      if [[ "$line" =~ gradlew[\"\']?[[:space:]]+--stop[[:space:]]*$ ]]; then
        continue
      fi
      CHECKS_RUN=$((CHECKS_RUN + 1))
      dominating_apply_exists "$lineno" apply_lines ||
        { fail "$rel:$lineno builds with no pocketshell_apply_release_gate_scope_memory REACHING it (needs a call earlier in the file that actually RUNS — not commented out, not heredoc data, not behind an &&/||/|/|& short-circuit or continuation, not piped or backgrounded on its own line, not inside a subshell or backticks — and that sits in this build line's own block or one enclosing it, not a sibling branch and not behind a condition of its own); the build would run inside scope-run.sh's 8G default: ${line#"${line%%[![:space:]]*}"}" || rc=1; }
    done < <(gradlew_invocation_lines "$script")
  done

  return "$rc"
}

# THE PROPERTY THIS ENFORCES, stated exactly. A build line B is accepted only
# when some apply line A satisfies ALL of:
#
#   1. A's text is a statement-initial, uncommented call to the apply;
#   2. the scanner classifies A as `-` — real executable code. That means ALL of:
#        * not heredoc BODY data (`H`), where the delimiter honoured is the
#          POSIX one: an indented terminator counts only under `<<-`, only with
#          leading TABS stripped, never with trailing whitespace; and EVERY
#          heredoc opened on a line is tracked, in order, not just the first;
#        * not a continuation of a preceding `&&`, `||`, `|`, `|&` or `\` line
#          (`C`) — the CROSS-LINE half of the pipeline/short-circuit hazard;
#        * no top-level `|`, `|&` or backgrounding `&` ON A'S OWN LINE (`P`) —
#          the SAME-LINE half. `apply … | tee -a "$log"` puts the apply in a
#          pipeline subshell; `apply … &` in a background one. `&&`, `||` and
#          redirections that merely contain `&` (`2>&1`, `>&2`, `&>f`) are NOT
#          this, and each is a required-GREEN self-test case;
#        * not inside a top-level `( … )` region (`S`);
#        * not inside a quote, `$( )` or BACKTICKS carried in from an earlier
#          line (`Q`).
#      This is the EXPORT-SURVIVES-AND-ALWAYS-RUNS half;
#   3. A is earlier in the file than B;
#   4. A's block path is a prefix of B's — every block open at A is still open
#      at B, so A is not in a sibling branch or a block B does not enter;
#   5. if B is strictly deeper than A, the first block B enters beyond A opens
#      AFTER A's line, so a function body cannot be invoked before A ran.
#
# ...and, separately, check_build_script_closure requires that no script the
# chain INVOKES runs a build of its own from outside RELEASE_CHAIN_SCRIPTS.
#
# Together: A's `export POCKETSHELL_TEST_MEM` has really happened, in the build
# process's own environment, on every path that reaches B. Items 1 and 3-5 are
# the round-1/round-2 half; item 2 is the round-3/round-4 half, and it is not
# optional — every one of its classes was a live escape that read as a plain
# dominating sibling while the runtime oracle showed POCKETSHELL_TEST_MEM UNSET
# at the build.
#
# How the rule got here, so nobody re-derives a weaker one:
#   * Round 1 shipped a PRESENCE check: `# ` in front of the apply kept it green.
#   * Round 2 used `earlier line + indentation <= the build's`. The reviewer
#     broke it three ways in one pass, all green in all four cells: an
#     `if <cond>; then` wrap (the build's CONTINUATION line shares the indent);
#     the same wrap in a second script, ruling out coincidence; and deleting
#     android-upgrade-preservation-gate.sh's SECOND apply so the survivor
#     "dominated" from inside `if BUILD_NEW_APK == 1`, a branch the documented
#     BUILD_NEW_APK=0 configuration never enters. Block paths kill all three,
#     plus a `case`-branch orphan and an UNINDENTED wrap, which no indentation
#     rule can see.
#   * Round 3 used block paths alone. The reviewer broke THAT three more ways,
#     again all green in all four cells, and proved each one leaves the build at
#     scope-run.sh's 8G default by running the real script: an apply in a
#     SUBSHELL (`export` dies with it); `cond && apply || true` (round 2's
#     rejected `if` wrap in a short-circuit spelling); and an apply that exists
#     only as HEREDOC DATA. The `-`/H/C/S/Q classification is the answer, and
#     all three are required-RED self-test cases below.
#   * Round 4 broke it FIVE more ways, all green in both plain cells, all proven
#     `<UNSET>` at the real build: `apply … | tee -a "$log"` (a top-level pipe on
#     the apply's OWN line — the `C` docstring named the pipeline hazard and had
#     closed only its cross-line spelling); a `|&` predecessor, which the
#     continuation test read only as `/\|$/`; a multi-line BACKTICK substitution,
#     the legacy spelling of the `$( )` that `Q` already covered; a heredoc body
#     containing an INDENTED copy of its own terminator, which ended the body a
#     line early for the scanner but not for bash; and TWO heredocs opened on one
#     line with the apply in the second body. `P`, the `|&` tail test, backticks
#     as a context, and the POSIX terminator + heredoc FIFO are the answers, each
#     a required-RED self-test case with a required-GREEN pin beside it.
#     Round 4 also moved the BUILD out of the list entirely; see
#     check_build_script_closure.
#   * Round 5 broke it ONCE more, and in the same place round 4's last two
#     escapes lived: `cat <<\EOS`. Bash quotes a heredoc delimiter when ANY
#     character of the word is quoted, and the BACKSLASH is the third spelling
#     of that alongside `'` and `"` — the two heredoc_word already modelled. A
#     leading `\` matched neither, the word came back empty, no body was queued,
#     and the apply inside it scored `-`: guard green, runtime oracle
#     `POCKETSHELL_TEST_MEM=<UNSET>` at the real build, and the apply's own
#     "Release build scope OK" line missing from the run because it never
#     executed. Consuming a leading `\` in heredoc_word is the answer; both
#     spellings (`<<\EOS`, `<<-\EOS`) are required-RED self-test cases, with
#     required-GREEN pins for a terminating `<<\DOC` block and for `<<<`.
#   * Round 7 broke it THREE more times, every one of them one character from
#     round 6's fix and every one the same shape: `<<\\EOS` (an escaped
#     backslash, so bash's terminator is the literal word `\EOS`), `<<""` (the
#     EMPTY delimiter, terminated by the first empty line) and `<<$VAR` (a
#     delimiter is never expanded, so the terminator is the literal text
#     `$VAR`). Each is `bash -n` clean, each is DATA to bash, each returned an
#     EMPTY word from heredoc_word, and — because the queue loop enqueued only a
#     NON-empty word — each left the body classified as executable code, the
#     apply at `-`, the guard GREEN and the runtime oracle at
#     POCKETSHELL_TEST_MEM=<UNSET> with the apply's own "Release build scope OK"
#     line absent from the run.
#     The answer this time is deliberately NOT a fourth spelling. Rounds 5 and 6
#     each modelled the exact spelling that had just been found and were broken
#     again within one round by the next one, so round 7 changes the DIRECTION
#     OF ERROR instead: an empty delimiter word means the scanner cannot know
#     where the body ends, and an unknown body boundary now REJECTS the file
#     instead of assuming the body is code. That also covers spellings nobody
#     has proposed yet, which is the property the previous two fixes lacked.
#     All three are required-RED self-test cases; the required-GREEN `<<<` pins
#     (plain, and with a command substitution in the word) are what keep the
#     rule off the here-string path. A sweep of 33 delimiter spellings then
#     measured the effect: SIXTEEN were fail-OPEN before this rule and none is
#     after it.
#     What round 7 did NOT close, and is recorded rather than glossed: the same
#     sweep found a SECOND and different family — a delimiter that yields a
#     non-empty but WRONG word, which is queued and can end the body EARLY.
#     It is pre-existing (measured live on the round-5 and round-6 scanners
#     too), it is untouched by this fix, and it is left open by an explicit
#     decision on issue #2054. See the WRONG-WORD entry under RESIDUAL GAPS.
#
# KNOWN CONSERVATIVE REJECTIONS — deliberate. Each is "we cannot prove it", and
# this guard's job is to reject what it cannot prove:
#   * an apply factored into a shell function called before the build (the call
#     site is not the definition site, so no path prefix holds);
#   * a build line that textually precedes the apply but only executes later
#     (e.g. inside a function defined above it);
#   * an apply AND its build both inside the same top-level subshell — that
#     works at runtime, but `S` rejects the apply outright rather than model
#     paths inside a region the scanner deliberately treats as opaque. (The
#     reverse, an outer apply protecting a build inside a subshell, IS accepted:
#     an export propagates in. Required-GREEN self-test case.)
#   * an apply separated from a `&&`/`||`/`|`/`|&`-terminated line by only blank
#     or comment lines — bash permits a comment between an operator and its
#     operand, so the continuation flag is carried across them;
#   * an apply on a line that also contains a pipeline somewhere AFTER it
#     (`apply …; foo | bar`). `P` is a whole-line test, so the second command's
#     pipe rejects the first command's apply. Splitting the line fixes it.
#   * a `case` PATTERN with alternatives (`--help|-h)`) also picks up `P`. It can
#     never be a call site (the pattern ends in `)`, so no call-line matcher
#     reaches it), so this costs nothing today; it is recorded because the flag
#     is visible in the scanner's output.
#   * a `( … )` inside a top-level subshell that the scanner meets as a lone
#     closing paren is reported as `#PARSE-FAIL close-without-open`, which
#     rejects the whole file. Fail-closed, but it IS a rejection: a `case`
#     statement written inside a top-level subshell hits it.
#   * a heredoc delimiter whose quoting starts anywhere but the FIRST character
#     (`<<E\OS`, `<<E"OS"`, `<<'E'OS`). Bash's terminator for all three is the
#     unquoted concatenation `EOS`; the scanner reads only the leading run
#     (`E`), never matches, and reports `#PARSE-FAIL unterminated-heredoc=E`,
#     which rejects the whole file. READ THE CONDITION ON THAT SENTENCE: it
#     holds only while the body contains no line equal to the SHORT word. If it
#     does, the scanner terminates the body there and the rest of the body is
#     read as code — that is a PERMISSIVE gap, not this conservative one, and it
#     is written up under RESIDUAL GAPS as the WRONG-WORD family. Rounds 5 and 6
#     stated this row without the condition; round 7 measured it and it is
#     false in general.
#   * ANY delimiter the scanner reads as an EMPTY word — the round-7 class
#     rule. `#PARSE-FAIL undeterminable-heredoc-delimiter@<line>` rejects the
#     whole file, because a delimiter it cannot read is a body boundary it
#     cannot find. A round-7 sweep of 33 delimiter spellings against real bash
#     found SIXTEEN that this rule catches and that were fail-OPEN before it:
#     `<<\\EOS`, `<<""`, `<<''`, `<<$VAR`, `<<${V}`, `<<$(cmd)`,
#     `` <<`cmd` ``, `<<~x`, `<<!EOS`, `<<.`, `<</tmp/x`, `<<Ω` (a delimiter
#     with no ASCII word character), `<<-""`, `<<-$V`, `<<\$V` and `<<+EOS`.
#     The reviewer reported three of them; the other thirteen are why the rule
#     is written on the EMPTY WORD rather than on a list. An unlisted spelling
#     is rejected too. This is the one entry that is deliberately not
#     enumerable — see the round-7 note above for why enumeration was tried
#     twice and failed twice.
#   * The delimiter behaviour that IS modelled, measured against real bash:
#       <<EOS  <<'EOS'  <<"EOS"  <<\EOS  <<-\EOS   modelled    -> body is `H`
#       <<E\OS  <<E"OS"  <<'E'OS                   fail-closed -> #PARSE-FAIL
#                                                  unterminated-heredoc
#       empty word (see above)                     fail-closed -> #PARSE-FAIL
#                                                  undeterminable-heredoc-
#                                                  delimiter
#       <<\'EOS'  <<\"EOS"                         bash SYNTAX ERROR; a
#                                                  `bash -n` clean tree cannot
#                                                  contain one
#     That table is NOT claimed to be complete, and it is NOT a partition. It
#     is what has been measured. Four earlier versions of this comment made a
#     completeness or exhaustiveness claim and every one was falsified within a
#     round (see RESIDUAL GAPS), so no such claim is made here. In particular
#     there IS a third outcome besides "modelled" and "fails closed": a
#     delimiter can be MIS-modelled — heredoc_word returns a non-empty but
#     WRONG word, which is queued and can end the body early. That family is
#     open and is written up under RESIDUAL GAPS; do not read this table as
#     saying it cannot happen.
#     `<<<` is a here-string, not a heredoc: sanitize consumes it at its own
#     branch without recording a heredoc start, so it opens no body and never
#     reaches heredoc_word. That is what keeps the empty-word rule off the
#     here-string path, and it was measured rather than assumed: 228 `<<<`
#     lines across 37 `scripts/` files, zero of which record a heredoc start
#     (two required-GREEN self-test cases pin it).
#   * any file the scanner cannot balance (see load_block_paths), including any
#     script REACHABLE from the chain (see check_build_script_closure).
# If a future refactor needs one of these, extend the model — do not relax the
# rule.
#
# RESIDUAL GAPS — the shapes still OUTSIDE the model, stated rather than papered
# over, because an instrument that overclaims is worse than one with a
# documented hole. A gap that errs toward rejecting is a limitation; a gap that
# errs toward ACCEPTING is a hole, and the two are labelled separately below.
#
# READ THE SCOPE OF THIS LIST EXACTLY. It is the set of permissive gaps KNOWN
# today — everything five rounds of adversarial review found and deliberately
# left open. It is NOT a proof of completeness, and this comment does not claim
# one. Four earlier versions of it did, and every one was wrong within a round:
# round 3's list omitted three live escapes, round 4's omitted five, round 5's
# said "exactly three of the second kind left, all of them one construct — an
# assembled STRING" while `<<\EOS` was a fourth and was source text rather than
# an assembled string, and round 6's said "the COMPLETE delimiter table,
# measured against real bash" while `<<\\EOS`, `<<""` and `<<$VAR` appeared in
# no row of it. Every one of those left the real build at
# POCKETSHELL_TEST_MEM=<UNSET>. A scanner over a language with bash's grammar
# cannot enumerate what it has not modelled, so the honest statement is the
# enforced property (the numbered rule above), the fail-closed rejections
# (KNOWN CONSERVATIVE REJECTIONS, and every unbalanced or unparseable file),
# and this named list of holes.
#
# THE STANDING RULE, which is what actually protects the gate: a newly found
# gap that errs toward ACCEPTING must be CLOSED, with a required-RED self-test
# case; only a gap that errs toward REJECTING may be documented and left.
#
# ONE ENTRY BELOW IS AN EXPLICIT, RECORDED EXCEPTION TO THAT RULE — the
# WRONG-WORD family. It is permissive and it is being left OPEN by a decision
# taken on issue #2054 round 7, not because it could not be closed. It is
# flagged here rather than buried so the next reader does not mistake it for an
# oversight or for something the standing rule permits. The other permissive
# entries survive the rule because closing them needs a runtime probe rather
# than a scanner — they share one root, a command that reaches the shell as an
# assembled STRING rather than as source text:
#
#   * PERMISSIVE, OPEN, and NOT an assembled string — the WRONG-WORD family,
#     found by the round-7 convergence hunt and measured live on the round-5,
#     round-6 and current scanners alike, so it is pre-existing and untouched by
#     the round-7 empty-word fix. heredoc_word reads the delimiter as the
#     LEADING RUN of `[A-Za-z0-9_]` and stops at the first character outside it.
#     When bash's real delimiter continues past that point the scanner queues a
#     SHORT, WRONG word. Usually the short word never appears in the body, so
#     the file is rejected as `unterminated-heredoc` (the conservative row
#     above). But a body line equal to the SHORT word ends the body early for
#     the scanner and not for bash, and every body line after it is read as
#     executable code. Measured fail-OPEN with a decoy line, guard `0 0` GREEN
#     and the runtime oracle at POCKETSHELL_TEST_MEM=<UNSET> at the real build:
#     `<<E\OS`, `<<E"OS"`, `<<'E'OS`, `<<E$V`, `<<EOS\X`, `<<EOS-X`,
#     `<<EOS.txt`, `<<EOS/x`, `<<EOS!`, `<<ENDÉ` — ten spellings, each with a
#     decoy line carrying the leading run. This is the THIRD outcome the
#     delimiter table above warns about: not modelled, not fail-closed, but
#     mis-modelled. It needs a two-part construction (a delimiter with a
#     non-word character after a word-character prefix, AND a body line exactly
#     equal to that prefix), which is why it is judged shippable as a documented
#     limit; it is not judged harmless. Closing it means reading the whole
#     delimiter word the way bash does — concatenating the quoted and unquoted
#     runs — rather than taking the leading run, and then keeping the empty-word
#     rule as the backstop.
#
#   * CONSERVATIVE. A heredoc BODY is attributed to the line where the heredoc
#     opens, not to wherever the resulting string is later executed. Build lines
#     inside a body are still REQUIRED to be dominated at the opening position
#     (pre-release-confidence-gate.sh builds real `gradlew` lines inside
#     `cat <<SCRIPT` bodies), and rule 5 rejects the case where that body sits
#     in a function defined before the apply. Apply lines inside a body are `H`
#     and never count.
#   * PERMISSIVE, and unavoidable statically: a build command assembled as a
#     STRING in one place and `eval`ed (or `source`d, or written out and run) in
#     another. Nothing textual can tell where such a string executes, and a rule
#     that rejected every `eval` would reject pre-release-confidence-gate.sh's
#     legitimate `cat <<SCRIPT` bodies. Nothing in the release chain does this
#     today; a future one would need a runtime probe, not a scanner.
#   * PERMISSIVE, same root, in the closure walk: a script INVOKED only from
#     inside a heredoc body or a carried-over string is not walked, and a
#     `gradlew` line that is itself heredoc data does not mark its file as a
#     build script. Both carve-outs are what keep scripts/cgroup-run.sh's
#     `cat <<'USAGE'` block — which names scripts/connected-test.sh, a real
#     build script — from making the shipped tree RED. Both are the `eval` gap
#     above wearing a different hat: to bite, someone must EXECUTE that text.
#     Note the DIRECTION of the round-6 heredoc_word fix here: a `<<\EOS` body
#     used to be misread as code, so the closure walk followed references and
#     flagged `gradlew` lines inside it. Now it is `H` like every other quoted
#     spelling, which widens THIS gap by one delimiter form while closing the
#     apply-classification hole. That is the correct trade — bash does not
#     execute heredoc data either way — but it is a widening, so it is recorded
#     rather than left implicit.
#   * PERMISSIVE: the closure walk finds a helper only when its path is written
#     literally as `scripts/<name>.sh` in code. `"$HELPERS/$name"` assembled from
#     variables is invisible to it — again, a string the scanner cannot resolve.
#   * It proves the apply is REACHED, not that it SUCCEEDED; the apply itself
#     hard-fails under `set -e`, which is what makes reaching it sufficient.
dominating_apply_exists() {
  local build_lineno="$1"
  local -n _apply_entries="$2"
  local build_path apply_lineno apply_path entry divergent open_lineno
  build_path="${BLOCK_PATHS[$build_lineno]:-}"
  [[ -n "$build_path" ]] || return 1
  for entry in "${_apply_entries[@]}"; do
    [[ -n "$entry" ]] || continue
    apply_lineno="${entry%%:*}"
    [[ "$apply_lineno" -lt "$build_lineno" ]] || continue
    apply_path="${BLOCK_PATHS[$apply_lineno]:-}"
    [[ -n "$apply_path" ]] || continue
    # Paths always end in `/`, so a prefix test cannot confuse `/b6/` with
    # `/b60/`.
    [[ "$build_path" == "$apply_path"* ]] || continue
    # The build may sit deeper than the apply. If the first block it enters
    # beyond the apply is a FUNCTION BODY, "earlier line" is not enough: the
    # function could be invoked before the apply's own line runs. Requiring the
    # apply to precede that block's opening line removes the possibility,
    # because a call cannot precede the definition. For if/for/while/case the
    # condition is already implied, so this adds no false rejection.
    divergent="${build_path#"$apply_path"}"
    divergent="${divergent%%/*}"
    if [[ -n "$divergent" ]]; then
      open_lineno="${BLOCK_OPEN_LINE[$divergent]:-}"
      [[ -n "$open_lineno" && "$apply_lineno" -lt "$open_lineno" ]] || continue
    fi
    return 0
  done
  return 1
}

# INVOCATION CLOSURE — the other half of the v0.4.42 OOM, and the one thing the
# RELEASE_CHAIN_SCRIPTS list could not see.
#
# Everything above only inspects the seven listed scripts. Move a `gradlew` line
# out of one of them into a NEW `scripts/*.sh` that it then invokes, and every
# check above stays green. That is not academic: the round-4 reviewer built it,
# and measured what leaks. The SCOPE half survives — `export POCKETSHELL_TEST_MEM`
# is inherited by the child process — but the HEAP half does NOT, because
# `-Dorg.gradle.jvmargs` / `-Pkotlin.daemon.jvmargs` live on the command line the
# child writes for itself. A Kotlin daemon back on gradle.properties' inherited
# 2048m inside a correctly-sized scope is exactly one of the two failures this
# issue exists to prevent.
#
# So the list is not a boundary you may quietly step over: from the seven roots,
# walk every `scripts/*.sh` they EXECUTE, transitively, and require that any
# reachable script which itself runs a build is IN the list — where the wiring,
# assertion, compliance and dominance checks all apply to it. That converts a
# silent maintenance obligation into a RED at PR time.
#
# Two precision rules keep this from firing on prose, and both are decided by
# the same scanner the reachability rule uses, not by a regex:
#
#   * a reference only counts when it sits in CODE. The record's `codelen`
#     field trims a trailing comment, and `H`/`Q` lines are skipped outright, so
#     scripts/cgroup-run.sh's `cat <<'USAGE'` block — which names
#     scripts/connected-test.sh, a script that really does build — is read as
#     the documentation it is.
#   * a reachable script only counts as A BUILD SCRIPT when it has a `gradlew`
#     invocation the scanner classifies as executable. cgroup-run.sh's single
#     `gradlew` line is one line of that same usage text (`H`), so cgroup-run.sh
#     is reachable but is not a build script.
#
# A reachable script the scanner cannot model is REJECTED, same as in the list.
check_build_script_closure() {
  local root="$1"
  local rc=0
  local rel cur kind rest lineno line saw_status
  local -A in_chain=()
  local -A seen=()
  local -a queue=()

  for rel in "${RELEASE_CHAIN_SCRIPTS[@]}"; do
    in_chain["$rel"]=1
    seen["$rel"]=1
    queue+=("$rel")
  done

  while [[ "${#queue[@]}" -gt 0 ]]; do
    cur="${queue[0]}"
    queue=("${queue[@]:1}")
    # A reference to a path this tree does not have is not something this guard
    # can judge; the in-list scripts are separately required to exist.
    [[ -f "$root/$cur" ]] || continue

    CHECKS_RUN=$((CHECKS_RUN + 1))
    # ONE pass per file. It scans the file, then re-reads it joined to its own
    # records and emits three record kinds: the parse status, every
    # `scripts/*.sh` named in CODE, and every executable `gradlew` line. A
    # per-line shell loop here (or a second scanner run) is what turned a
    # sub-second guard into a twelve-second one.
    saw_status=0
    while IFS=$'\t' read -r kind rest lineno; do
      case "$kind" in
        STATUS)
          saw_status=1
          [[ "$rest" == "#PARSE-OK" ]] ||
            { fail "$cur is reachable from the release chain but could not be modelled by the block-structure scanner ($rest); whether it runs an unbounded build cannot be decided, so it is rejected rather than assumed safe" || rc=1; }
          ;;
        REF)
          [[ -n "${seen[$rest]:-}" ]] && continue
          seen["$rest"]=1
          queue+=("$rest")
          ;;
        GW)
          [[ -z "${in_chain[$cur]:-}" ]] || continue
          line="$rest"
          CHECKS_RUN=$((CHECKS_RUN + 1))
          fail "$cur:$lineno runs a Gradle build but is NOT in RELEASE_CHAIN_SCRIPTS, while the release chain invokes it. The scope export is inherited by a child process but the HEAP flags are not, so this build would run its Kotlin daemon on gradle.properties' inherited 2048m — the other half of the v0.4.42 OOM. Add $cur to RELEASE_CHAIN_SCRIPTS (and wire it to scripts/lib/gradle-profile.sh) rather than moving builds outside the list: ${line#"${line%%[![:space:]]*}"}" || rc=1
          ;;
      esac
    done < <(
      LC_ALL=C awk -v gwre="$GRADLEW_INVOCATION_RE" '
        BEGIN {
          REFRE = "scripts/[A-Za-z0-9_./-]+\\.sh"
          status = "#PARSE-MISSING"
        }
        NR == FNR {
          if (substr($1, 1, 1) == "#") {
            if (substr($1, 1, 7) == "#PARSE-") { status = $0 }
            next
          }
          fl[$1] = $3; cl[$1] = $4
          next
        }
        FNR == 1 { print "STATUS\t" status }
        {
          # A gradlew invocation that really executes: not a whole-line comment,
          # not heredoc DATA, not `gradlew --stop` (which allocates nothing).
          if ($0 ~ gwre && $0 !~ /^[ \t]*#/ && fl[FNR] !~ /H/ &&
              $0 !~ /gradlew["'"'"']?[ \t]+--stop[ \t]*$/) {
            print "GW\t" $0 "\t" FNR
          }
          if (fl[FNR] ~ /[HQ]/) next
          n = cl[FNR] + 0
          if (n <= 0) next
          s = substr($0, 1, n)
          while (match(s, REFRE)) {
            print "REF\t" substr(s, RSTART, RLENGTH)
            s = substr(s, RSTART + RLENGTH)
          }
        }
        END { if (FNR == 0) print "STATUS\t" status }
      ' <(LC_ALL=C awk "$BLOCK_PATH_AWK" "$root/$cur") "$root/$cur"
    )
    # A process substitution that dies produces an EMPTY stream, and `set -e`
    # cannot see it. Without this the walk would silently examine nothing and
    # report success — the vacuous green in its purest form.
    [[ "$saw_status" -eq 1 ]] ||
      { fail "the invocation-closure scan of $cur produced no records at all; treat this as a broken scan rather than a clean one" || rc=1; }
  done

  return "$rc"
}

# The floors and both profiles' constants must be real constants. A
# `${VAR:-default}` form is a bypass channel for the exact failures this issue
# fixed (`POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB=0` waves the 2048m heap
# through; `POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB=0` waves the 8G scope
# through) AND it makes this guard's own mutation self-test vacuous, because a
# preset variable means the mutated default is never read.
#
# Behavioural, not a grep: preset every name to a bypass value and re-source the
# lib in a FRESH interpreter. A plain assignment overwrites the preset; any
# environment-overridable form adopts it.
check_no_env_overridable_constants() {
  local root="$1"
  local rc=0
  local observed expected

  expected='1536|3072|20|24G|8G|--no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx3072m -Pkotlin.daemon.jvmargs=-Xmx3072m|--no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.daemon.jvmargs=-Xmx3072m'

  CHECKS_RUN=$((CHECKS_RUN + 1))
  observed="$(
    env -i \
      PATH="$PATH" \
      POCKETSHELL_GRADLE_LAUNCHER_HEAP_FLOOR_MIB=0 \
      POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB=0 \
      POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB=0 \
      POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT=1G \
      POCKETSHELL_RELEASE_GATE_HOSTED_SCOPE_MEM=1G \
      bash -c '
        set -euo pipefail
        # shellcheck source=/dev/null
        source "$1"
        printf "%s|%s|%s|%s|%s|%s|%s" \
          "$POCKETSHELL_GRADLE_LAUNCHER_HEAP_FLOOR_MIB" \
          "$POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB" \
          "$POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB" \
          "$POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT" \
          "$POCKETSHELL_RELEASE_GATE_HOSTED_SCOPE_MEM" \
          "${POCKETSHELL_GRADLE_LOCAL_RESOURCE_ARGS[*]}" \
          "${POCKETSHELL_GRADLE_HOSTED_RESOURCE_ARGS[*]}"
      ' _ "$root/scripts/lib/gradle-profile.sh" 2>/dev/null
  )" || observed="<lib failed to source under preset bypass values>"

  [[ "$observed" == "$expected" ]] ||
    { fail "scripts/lib/gradle-profile.sh has an environment-overridable floor/profile constant: presetting the bypass values changed the shipped profile.
  expected: $expected
  observed: $observed" || rc=1; }

  return "$rc"
}

# The two guards in this repo must not assert opposite hosted profiles. #1724's
# scripts/check-release-emulator-memory-budget.sh hard-fails any hosted launcher
# heap but 1536m and any hosted scope but 8G; the four scripts that splice
# POCKETSHELL_GRADLE_RESOURCE_ARGS must therefore derive exactly that pair when
# the hosted workflow's environment is in force. Round 1 gave hosted the LOCAL
# 3072m heap inside the unchanged 8G scope — the heap half of the fix without
# the scope half, which is the pairing scripts/lib/gradle-profile.sh documents.
check_hosted_profile_agrees_with_workflow() {
  local root="$1"
  local rc=0
  local workflow="$root/$HOSTED_WORKFLOW"

  CHECKS_RUN=$((CHECKS_RUN + 1))
  if [[ ! -f "$workflow" ]]; then
    fail "hosted release workflow missing: $HOSTED_WORKFLOW" || rc=1
    return "$rc"
  fi

  local hosted_flags hosted_mem
  hosted_flags="$(
    awk '
      /^[[:space:]]*GRADLE_FLAGS:[[:space:]]*>-[[:space:]]*$/ { found = 1; next }
      found && /^[[:space:]]+-/ { sub(/^[[:space:]]+/, ""); printf "%s ", $0; next }
      found { exit }
    ' "$workflow"
  )"
  hosted_mem="$(
    awk '$1 == "POCKETSHELL_TEST_MEM" ":" { value = $2; gsub(/^"|"$/, "", value); print value; exit }' "$workflow"
  )"

  CHECKS_RUN=$((CHECKS_RUN + 1))
  [[ -n "$hosted_flags" && -n "$hosted_mem" ]] ||
    { fail "could not read the hosted GRADLE_FLAGS / POCKETSHELL_TEST_MEM out of $HOSTED_WORKFLOW" || rc=1; }

  # What the four splicing scripts would actually build with, under the hosted
  # workflow's exact environment.
  local derived_args derived_mem
  derived_args="$(
    as_hosted env GRADLE_FLAGS="$hosted_flags" POCKETSHELL_TEST_MEM="$hosted_mem" \
      bash -c 'source "$1"; printf "%s" "${POCKETSHELL_GRADLE_RESOURCE_ARGS[*]}"' _ \
      "$root/scripts/lib/gradle-profile.sh"
  )"
  derived_mem="$(
    as_hosted env GRADLE_FLAGS="$hosted_flags" POCKETSHELL_TEST_MEM="$hosted_mem" \
      bash -c 'source "$1"; printf "%s" "$(pocketshell_effective_release_scope_memory)"' _ \
      "$root/scripts/lib/gradle-profile.sh"
  )"

  CHECKS_RUN=$((CHECKS_RUN + 1))
  [[ " $derived_args " == *" -Dorg.gradle.jvmargs=-Xmx1536m "* ]] ||
    { fail "under the hosted workflow environment the release-chain scripts would splice '$derived_args', but scripts/check-release-emulator-memory-budget.sh requires the hosted Gradle launcher heap to stay at -Xmx1536m" || rc=1; }

  CHECKS_RUN=$((CHECKS_RUN + 1))
  [[ "$derived_mem" == "$hosted_mem" ]] ||
    { fail "under the hosted workflow environment the build scope would be '$derived_mem' instead of the workflow's pinned '$hosted_mem'" || rc=1; }

  # ...and with NO explicit hosted environment the fallback must still be the
  # hosted pair, never the local 24G/3072m on a 16 GiB runner.
  local fallback_args fallback_mem
  fallback_args="$(
    as_hosted env -u GRADLE_FLAGS -u POCKETSHELL_TEST_MEM \
      bash -c 'source "$1"; printf "%s" "${POCKETSHELL_GRADLE_RESOURCE_ARGS[*]}"' _ \
      "$root/scripts/lib/gradle-profile.sh"
  )"
  fallback_mem="$(
    as_hosted env -u GRADLE_FLAGS -u POCKETSHELL_TEST_MEM \
      bash -c 'source "$1"; printf "%s" "$(pocketshell_effective_release_scope_memory)"' _ \
      "$root/scripts/lib/gradle-profile.sh"
  )"

  CHECKS_RUN=$((CHECKS_RUN + 1))
  [[ " $fallback_args " == *" -Dorg.gradle.jvmargs=-Xmx1536m "* && "$fallback_mem" == "$hosted_mem" ]] ||
    { fail "a hosted run with no explicit profile would build with '$fallback_args' / scope '$fallback_mem'; both halves must fall back to the hosted pair (-Xmx1536m / $hosted_mem)" || rc=1; }

  # The local pair must stay local — the same fallback read the other way, so a
  # future "just make everything hosted" simplification is also a RED.
  local local_args local_mem
  local_args="$(
    as_local env -u GRADLE_FLAGS -u POCKETSHELL_TEST_MEM \
      bash -c 'source "$1"; printf "%s" "${POCKETSHELL_GRADLE_RESOURCE_ARGS[*]}"' _ \
      "$root/scripts/lib/gradle-profile.sh"
  )"
  local_mem="$(
    as_local env -u GRADLE_FLAGS -u POCKETSHELL_TEST_MEM \
      bash -c 'source "$1"; printf "%s" "$(pocketshell_effective_release_scope_memory)"' _ \
      "$root/scripts/lib/gradle-profile.sh"
  )"

  CHECKS_RUN=$((CHECKS_RUN + 1))
  [[ " $local_args " == *" -Dorg.gradle.jvmargs=-Xmx3072m "* ]] ||
    { fail "the local profile lost its raised launcher heap: '$local_args' (run 20260809-v0442 proves 2048m OOMs :app:packageDebug)" || rc=1; }

  CHECKS_RUN=$((CHECKS_RUN + 1))
  local local_gib
  local_gib="$(
    as_local bash -c 'source "$1"; pocketshell_parse_size_gib "$2"' _ \
      "$root/scripts/lib/gradle-profile.sh" "$local_mem"
  )"
  [[ "$local_gib" -ge 20 ]] ||
    { fail "the local build scope fell back to '$local_mem', below the 20G floor" || rc=1; }

  return "$rc"
}

check_default_profile() {
  local root="$1"
  local rc=0

  # Source the profile lib from the inspected root so a mutation of the lib
  # itself (e.g. a heap flag deleted from the resource args) is caught here
  # rather than only at release time.
  # shellcheck source=/dev/null
  source "$root/scripts/lib/gradle-profile.sh"

  # Both profiles are asserted explicitly, with their own environment shape, so
  # neither verdict depends on where this guard happens to run.
  CHECKS_RUN=$((CHECKS_RUN + 1))
  if ! as_local pocketshell_assert_gradle_execution_profile \
    "shipped LOCAL release-chain profile" "${POCKETSHELL_GRADLE_LOCAL_RESOURCE_ARGS[*]}" >/dev/null; then
    fail "the shipped LOCAL Gradle profile does not satisfy its own assertion" || rc=1
  fi

  CHECKS_RUN=$((CHECKS_RUN + 1))
  if ! as_hosted pocketshell_assert_gradle_execution_profile \
    "shipped HOSTED release-chain profile" "${POCKETSHELL_GRADLE_HOSTED_RESOURCE_ARGS[*]}" >/dev/null; then
    fail "the shipped HOSTED Gradle profile does not satisfy its own assertion" || rc=1
  fi

  CHECKS_RUN=$((CHECKS_RUN + 1))
  local default_flags
  default_flags="$(
    as_local env -u GRADLE_FLAGS \
      bash -c 'source "$1"; pocketshell_release_gate_gradle_flags' _ "$root/$PROFILE_LIB"
  )"
  if ! as_local pocketshell_assert_gradle_execution_profile \
    "shipped release-chain default flag string" "$default_flags" >/dev/null; then
    fail "the shipped default Gradle flag string ('$default_flags') does not satisfy its own assertion" || rc=1
  fi

  CHECKS_RUN=$((CHECKS_RUN + 1))
  if ! as_local pocketshell_assert_release_scope_memory \
    "shipped release-chain scope default" "$POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT" >/dev/null; then
    fail "the shipped POCKETSHELL_TEST_MEM default ($POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT) is below the ${POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB}G local floor" || rc=1
  fi

  # The floors and the scope default are pinned HERE, independently of the lib,
  # so "lower the floor until the profile fits" is not an available escape. A
  # guard that reads its own thresholds out of the file it is guarding proves
  # only self-consistency; the self-test's floor-lowering mutation exists to
  # keep these literals honest.
  CHECKS_RUN=$((CHECKS_RUN + 1))
  [[ "$POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB" -ge 3072 ]] ||
    { fail "Kotlin daemon heap floor must stay >= 3072 MiB (the canonical scripts/full-jvm-gate.py value), got $POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB" || rc=1; }

  CHECKS_RUN=$((CHECKS_RUN + 1))
  [[ "$POCKETSHELL_GRADLE_LAUNCHER_HEAP_FLOOR_MIB" -ge 1536 ]] ||
    { fail "Gradle launcher heap floor must stay >= 1536 MiB (the canonical scripts/full-jvm-gate.py value), got $POCKETSHELL_GRADLE_LAUNCHER_HEAP_FLOOR_MIB" || rc=1; }

  CHECKS_RUN=$((CHECKS_RUN + 1))
  [[ "$POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB" -ge 20 ]] ||
    { fail "local release build-scope floor must stay >= 20G, got ${POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB}G" || rc=1; }

  # The clean release build scope must be at least 20G (issue #2054 item 2).
  CHECKS_RUN=$((CHECKS_RUN + 1))
  local default_gib
  default_gib="$(pocketshell_parse_size_gib "$POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT")"
  [[ "$default_gib" -ge 20 ]] ||
    { fail "release build scope default must be >= 20G, got $POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT" || rc=1; }

  # The gate must take its default from the shared source of truth, not a
  # privately maintained copy that can drift again.
  CHECKS_RUN=$((CHECKS_RUN + 1))
  grep -Fq 'GRADLE_FLAGS="${GRADLE_FLAGS:-$(pocketshell_release_gate_gradle_flags)}"' \
    "$root/scripts/pre-release-confidence-gate.sh" ||
    { fail "pre-release-confidence-gate.sh no longer defaults GRADLE_FLAGS from pocketshell_release_gate_gradle_flags" || rc=1; }

  # The isolated worktree re-exec must carry the scope ceiling across.
  CHECKS_RUN=$((CHECKS_RUN + 1))
  grep -Eq '^  export .*GRADLE_FLAGS POCKETSHELL_TEST_MEM$' \
    "$root/scripts/pre-release-confidence-gate.sh" ||
    { fail "pre-release-confidence-gate.sh no longer exports POCKETSHELL_TEST_MEM into its isolated worktree copy" || rc=1; }

  return "$rc"
}

run_check() {
  local root="${1:-$ROOT_DIR}"
  local rc=0
  check_script_wiring "$root" || rc=1
  check_build_script_closure "$root" || rc=1
  check_default_profile "$root" || rc=1
  check_no_env_overridable_constants "$root" || rc=1
  check_hosted_profile_agrees_with_workflow "$root" || rc=1
  return "$rc"
}

# ---------------------------------------------------------------------------
# Self-test: prove the guard and the runtime assertion are load-bearing by
# mutating a real fixture and requiring RED. An assertion whose negative case
# was never exercised is decorative (AGENTS.md, G6).
# ---------------------------------------------------------------------------
self_test() {
  # shellcheck source=/dev/null
  source "$ROOT_DIR/scripts/lib/gradle-profile.sh"

  local good
  good="$(
    as_local env -u GRADLE_FLAGS \
      bash -c 'source "$1"; pocketshell_release_gate_gradle_flags' _ "$ROOT_DIR/$PROFILE_LIB"
  )"
  local checks=0

  expect_profile_pass() {
    checks=$((checks + 1))
    if ! as_local pocketshell_assert_gradle_execution_profile "self-test" "$1" >/dev/null 2>&1; then
      fail "self-test: assertion REJECTED a valid profile: $1"
      exit 1
    fi
  }
  expect_profile_reject() {
    local what="$1"
    local flags="$2"
    checks=$((checks + 1))
    if as_local pocketshell_assert_gradle_execution_profile "self-test" "$flags" >/dev/null 2>&1; then
      fail "self-test: assertion ACCEPTED $what: '$flags'"
      exit 1
    fi
  }

  expect_profile_pass "$good"
  # The hosted 16 GiB-runner profile sits exactly on the canonical floors and
  # must keep passing, or every hosted release validation would break.
  expect_profile_pass "--no-daemon --no-build-cache --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.daemon.jvmargs=-Xmx3072m"

  # Issue #2054 item 4 — delete either heap flag, require RED.
  expect_profile_reject "a profile with the Kotlin daemon heap flag deleted" \
    "${good/ -Pkotlin.daemon.jvmargs=-Xmx3072m/}"
  expect_profile_reject "a profile with the Gradle heap flag deleted" \
    "${good/ -Dorg.gradle.jvmargs=-Xmx3072m/}"
  # The exact stale values that produced the three failed release runs.
  expect_profile_reject "the inherited 2048m Kotlin daemon heap" \
    "${good/-Pkotlin.daemon.jvmargs=-Xmx3072m/-Pkotlin.daemon.jvmargs=-Xmx2048m}"
  expect_profile_reject "an undersized Gradle heap" \
    "${good/-Dorg.gradle.jvmargs=-Xmx3072m/-Dorg.gradle.jvmargs=-Xmx1024m}"
  expect_profile_reject "the stale two-worker gate profile" \
    "${good/--max-workers=1/--max-workers=2}"
  expect_profile_reject "a profile with no worker bound at all" \
    "${good/--max-workers=1/}"
  expect_profile_reject "renewed parallel project execution" "$good --parallel"
  expect_profile_reject "a duplicated Kotlin heap flag" \
    "$good -Pkotlin.daemon.jvmargs=-Xmx2048m"
  expect_profile_reject "a duplicated Gradle heap flag" \
    "$good -Dorg.gradle.jvmargs=-Xmx1024m"
  expect_profile_reject "an unparseable Kotlin heap bound" \
    "${good/-Pkotlin.daemon.jvmargs=-Xmx3072m/-Pkotlin.daemon.jvmargs=-XX:+UseG1GC}"
  expect_profile_reject "the original stale release-gate flag string" \
    "--no-daemon --no-build-cache --no-parallel --max-workers=2"
  expect_profile_reject "an empty flag string" ""

  # A caller's GRADLE_FLAGS must reach the resource args a splicing script uses,
  # or the hosted chain silently builds on a profile nobody asserted.
  checks=$((checks + 1))
  local caller_derived
  caller_derived="$(
    as_local env GRADLE_FLAGS="--no-daemon --offline --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.daemon.jvmargs=-Xmx3072m" \
      bash -c 'source "$1"; printf "%s" "${POCKETSHELL_GRADLE_RESOURCE_ARGS[*]}"' _ \
      "$ROOT_DIR/scripts/lib/gradle-profile.sh"
  )"
  if [[ "$caller_derived" != "--no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.daemon.jvmargs=-Xmx3072m" ]]; then
    fail "self-test: an explicit caller GRADLE_FLAGS did not reach POCKETSHELL_GRADLE_RESOURCE_ARGS (got '$caller_derived')"
    exit 1
  fi

  # ...and a caller who re-enables parallelism must be REJECTED, not silently
  # filtered into compliance by the token extraction.
  checks=$((checks + 1))
  local hostile_derived
  hostile_derived="$(
    as_local env GRADLE_FLAGS="--no-daemon --parallel --max-workers=4" \
      bash -c 'source "$1"; printf "%s" "${POCKETSHELL_GRADLE_RESOURCE_ARGS[*]}"' _ \
      "$ROOT_DIR/scripts/lib/gradle-profile.sh"
  )"
  if as_local pocketshell_assert_gradle_execution_profile "self-test" "$hostile_derived" >/dev/null 2>&1; then
    fail "self-test: a hostile caller GRADLE_FLAGS ('--parallel --max-workers=4') was accepted after extraction: '$hostile_derived'"
    exit 1
  fi

  # Scope-ceiling floor.
  checks=$((checks + 1))
  as_local pocketshell_assert_release_scope_memory "self-test" "24G" >/dev/null ||
    { fail "self-test: 24G scope was rejected"; exit 1; }
  checks=$((checks + 1))
  as_local pocketshell_assert_release_scope_memory "self-test" "20G" >/dev/null ||
    { fail "self-test: 20G scope (the documented floor) was rejected"; exit 1; }
  checks=$((checks + 1))
  if as_local pocketshell_assert_release_scope_memory "self-test" "8G" >/dev/null 2>&1; then
    fail "self-test: the 8G scope that OOMed three release runs was accepted locally"
    exit 1
  fi
  checks=$((checks + 1))
  if as_local pocketshell_assert_release_scope_memory "self-test" "19G" >/dev/null 2>&1; then
    fail "self-test: a below-floor 19G scope was accepted locally"
    exit 1
  fi
  # Hosted runners legitimately pin 8G; the local floor must not break them.
  checks=$((checks + 1))
  as_hosted pocketshell_assert_release_scope_memory "self-test" "8G" >/dev/null ||
    { fail "self-test: the hosted 8G budget was rejected under CI=true"; exit 1; }

  # ---- non-building invocations must survive a SMALL ambient scope ----------
  #
  # Regression pin for a real defect this guard's author shipped and the
  # canonical gate caught: the build-scope floor was originally asserted at
  # script LOAD time, which hard-failed
  # `AvdLockScriptTest.avdLockHelperOwnershipHarnessPasses` and
  # `ReleaseGateScriptTest.phoneWalkthroughDispatchesEveryScenario`.
  # `scripts/full-jvm-gate.py` exports POCKETSHELL_TEST_MEM=8G into every unit
  # test, and those tests drive these scripts in modes that build nothing.
  #
  # Every probe runs through `as_local`: the floor is CI-exempt, so on GitHub
  # Actions (CI=true) a load-time floor assertion would be harmless and all
  # seven probes would pass WITH THE BUG PRESENT. Pinning the local shape is what
  # makes them mean the same thing in both lanes.
  local probe
  probe="$(mktemp -d)"
  run_probe_local() {
    as_local env \
      POCKETSHELL_TEST_MEM=8G \
      POCKETSHELL_AVD_LOCK_FILE="$probe/avd.lock" \
      LOG_ROOT="$probe/logs" \
      RUN_ID="profile-guard-probe" \
      timeout 60 "$@"
  }
  expect_help_ok() {
    local label="$1"
    shift
    checks=$((checks + 1))
    if ! run_probe_local "$@" >/dev/null 2>&1; then
      fail "self-test: $label must exit 0 under an 8G ambient scope (it builds nothing), but it failed"
      rm -rf "$probe"
      exit 1
    fi
  }

  expect_help_ok "phone-walkthrough.sh terminal-lab --help" \
    "$ROOT_DIR/scripts/phone-walkthrough.sh" terminal-lab --help
  expect_help_ok "phone-walkthrough.sh dispatch-only" \
    env PHONE_WALKTHROUGH_VERIFY_DISPATCH_ONLY=1 "$ROOT_DIR/scripts/phone-walkthrough.sh" all
  expect_help_ok "pre-release-confidence-gate.sh --help" \
    "$ROOT_DIR/scripts/pre-release-confidence-gate.sh" --help
  expect_help_ok "release-emulator-validation.sh --help" \
    "$ROOT_DIR/scripts/release-emulator-validation.sh" --help
  expect_help_ok "capture-walkthrough-screenshots.sh --help" \
    "$ROOT_DIR/scripts/capture-walkthrough-screenshots.sh" --help
  expect_help_ok "parallel-setup-detection.sh --help" \
    "$ROOT_DIR/scripts/parallel-setup-detection.sh" --help
  expect_help_ok "android-upgrade-preservation-gate.sh --help" \
    "$ROOT_DIR/scripts/android-upgrade-preservation-gate.sh" --help

  # ...but the REAL check must still bite under the same 8G ambient scope, or
  # the fix above would have been "delete the floor" wearing a green hat.
  checks=$((checks + 1))
  if run_probe_local "$ROOT_DIR/scripts/pre-release-confidence-gate.sh" --check-profile >/dev/null 2>&1; then
    fail "self-test: --check-profile ACCEPTED an 8G build scope; the floor is no longer load-bearing"
    rm -rf "$probe"
    exit 1
  fi

  # ...and it must ACCEPT the shipped local default, or the pin above would be
  # satisfied by a --check-profile that simply always fails.
  checks=$((checks + 1))
  if ! as_local env -u POCKETSHELL_TEST_MEM -u GRADLE_FLAGS \
    timeout 60 "$ROOT_DIR/scripts/pre-release-confidence-gate.sh" --check-profile >/dev/null 2>&1; then
    fail "self-test: --check-profile REJECTED the shipped local default profile"
    rm -rf "$probe"
    exit 1
  fi

  # ...and it must accept the hosted pair under the hosted environment shape.
  checks=$((checks + 1))
  if ! as_hosted env POCKETSHELL_TEST_MEM=8G \
    GRADLE_FLAGS="--no-daemon --no-build-cache --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.daemon.jvmargs=-Xmx3072m" \
    timeout 60 "$ROOT_DIR/scripts/pre-release-confidence-gate.sh" --check-profile >/dev/null 2>&1; then
    fail "self-test: --check-profile REJECTED the pinned hosted profile"
    rm -rf "$probe"
    exit 1
  fi
  rm -rf "$probe"

  # ---- static wiring guard: mutate REAL script copies, require RED ----------
  local tmp
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2064 # expand tmp now, not at trap time.
  trap "rm -rf '$tmp'" RETURN
  cp -a "$ROOT_DIR/scripts" "$tmp/scripts"
  mkdir -p "$tmp/$(dirname "$HOSTED_WORKFLOW")"
  cp -a "$ROOT_DIR/$HOSTED_WORKFLOW" "$tmp/$HOSTED_WORKFLOW"

  checks=$((checks + 1))
  if ! (run_check "$tmp" >/dev/null); then
    fail "self-test: the guard rejected an UNmutated copy of the real scripts"
    exit 1
  fi

  mutate_and_expect_red() {
    local what="$1"
    local file="$2"
    local sed_expr="$3"
    local mutant="$tmp/mutant-$RANDOM"
    mkdir -p "$mutant"
    cp -a "$ROOT_DIR/scripts" "$mutant/scripts"
    mkdir -p "$mutant/$(dirname "$HOSTED_WORKFLOW")"
    cp -a "$ROOT_DIR/$HOSTED_WORKFLOW" "$mutant/$HOSTED_WORKFLOW"
    local before after
    before="$(md5sum "$mutant/$file" | cut -d' ' -f1)"
    sed -i "$sed_expr" "$mutant/$file"
    after="$(md5sum "$mutant/$file" | cut -d' ' -f1)"
    if [[ "$before" == "$after" ]]; then
      # A mutation that never happened is not a passing mutation test.
      fail "self-test: mutation '$what' did not change $file (stale anchor)"
      exit 1
    fi
    checks=$((checks + 1))
    if (run_check "$mutant" >/dev/null 2>&1); then
      fail "self-test: the guard ACCEPTED a tree where $what"
      exit 1
    fi
    rm -rf "$mutant"
  }

  # Structural placement mutations (wrap in a conditional, move to a sibling
  # branch, hoist out a block) cannot be written as a `sed` expression. awk is
  # already a hard dependency here (the block scanner), so those mutants use an
  # awk program — with the same md5 liveness proof, and with an EXPECTED verdict
  # so the required-GREEN cases below can prove the rule is not "reject
  # everything".
  mutate_awk_and_expect() {
    local expect="$1"
    local what="$2"
    local file="$3"
    local program="$4"
    local mutant="$tmp/mutant-$RANDOM"
    mkdir -p "$mutant"
    cp -a "$ROOT_DIR/scripts" "$mutant/scripts"
    mkdir -p "$mutant/$(dirname "$HOSTED_WORKFLOW")"
    cp -a "$ROOT_DIR/$HOSTED_WORKFLOW" "$mutant/$HOSTED_WORKFLOW"
    local before after
    before="$(md5sum "$mutant/$file" | cut -d' ' -f1)"
    awk "$program" "$mutant/$file" > "$mutant/.mutation.tmp"
    cat "$mutant/.mutation.tmp" > "$mutant/$file"
    rm -f "$mutant/.mutation.tmp"
    after="$(md5sum "$mutant/$file" | cut -d' ' -f1)"
    if [[ "$before" == "$after" ]]; then
      # A mutation that never happened is not a passing mutation test.
      fail "self-test: mutation '$what' did not change $file (stale anchor)"
      exit 1
    fi
    checks=$((checks + 1))
    if [[ "$expect" == "red" ]]; then
      if (run_check "$mutant" >/dev/null 2>&1); then
        fail "self-test: the guard ACCEPTED a tree where $what"
        exit 1
      fi
    else
      if ! (run_check "$mutant" >/dev/null 2>&1); then
        fail "self-test: the guard REJECTED a LEGITIMATE tree where $what"
        (run_check "$mutant" >&2 || true)
        exit 1
      fi
    fi
    rm -rf "$mutant"
  }

  # Same, but the mutant root also gains a NEW helper script — the only way to
  # exercise the invocation-closure rule, whose whole subject is a build that
  # moved into a file the list does not name.
  mutate_awk_with_helper_and_expect() {
    local expect="$1"
    local what="$2"
    local file="$3"
    local program="$4"
    local helper_rel="$5"
    local helper_body="$6"
    local mutant="$tmp/mutant-$RANDOM"
    mkdir -p "$mutant"
    cp -a "$ROOT_DIR/scripts" "$mutant/scripts"
    mkdir -p "$mutant/$(dirname "$HOSTED_WORKFLOW")"
    cp -a "$ROOT_DIR/$HOSTED_WORKFLOW" "$mutant/$HOSTED_WORKFLOW"
    printf '%s\n' "$helper_body" > "$mutant/$helper_rel"
    chmod +x "$mutant/$helper_rel"
    local before after
    before="$(md5sum "$mutant/$file" | cut -d' ' -f1)"
    awk "$program" "$mutant/$file" > "$mutant/.mutation.tmp"
    cat "$mutant/.mutation.tmp" > "$mutant/$file"
    rm -f "$mutant/.mutation.tmp"
    after="$(md5sum "$mutant/$file" | cut -d' ' -f1)"
    if [[ "$before" == "$after" ]]; then
      fail "self-test: mutation '$what' did not change $file (stale anchor)"
      exit 1
    fi
    checks=$((checks + 1))
    if [[ "$expect" == "red" ]]; then
      if (run_check "$mutant" >/dev/null 2>&1); then
        fail "self-test: the guard ACCEPTED a tree where $what"
        exit 1
      fi
    else
      if ! (run_check "$mutant" >/dev/null 2>&1); then
        fail "self-test: the guard REJECTED a LEGITIMATE tree where $what"
        (run_check "$mutant" >&2 || true)
        exit 1
      fi
    fi
    rm -rf "$mutant"
  }

  mutate_and_expect_red \
    "the phone walkthrough's APK build lost its resource args" \
    scripts/phone-walkthrough.sh \
    's| "\${POCKETSHELL_GRADLE_RESOURCE_ARGS\[@\]}" :app:assembleDebug| :app:assembleDebug|'
  mutate_and_expect_red \
    "the visual-audit APK build lost its resource args" \
    scripts/capture-walkthrough-screenshots.sh \
    's| "\${POCKETSHELL_GRADLE_RESOURCE_ARGS\[@\]}" :app:assembleDebug| :app:assembleDebug|'
  mutate_and_expect_red \
    "the terminal workbench APK build lost its resource args" \
    scripts/terminal-workbench.sh \
    's| "\${POCKETSHELL_GRADLE_RESOURCE_ARGS\[@\]}" :app:assembleDebug| :app:assembleDebug|'
  mutate_and_expect_red \
    "the parallel setup-detection APK build lost its resource args" \
    scripts/parallel-setup-detection.sh \
    's| "\${POCKETSHELL_GRADLE_RESOURCE_ARGS\[@\]}" \\| \\|'
  mutate_and_expect_red \
    "the confidence gate went back to its stale private flag string" \
    scripts/pre-release-confidence-gate.sh \
    's|^GRADLE_FLAGS=.*$|GRADLE_FLAGS="${GRADLE_FLAGS:---no-daemon --no-build-cache --no-parallel --max-workers=2}"|'
  mutate_and_expect_red \
    "the confidence gate stopped exporting the scope ceiling into its isolated copy" \
    scripts/pre-release-confidence-gate.sh \
    's|^  export LOG_ROOT RUN_ID GRADLE_USER_HOME GRADLE_FLAGS POCKETSHELL_TEST_MEM$|  export LOG_ROOT RUN_ID GRADLE_USER_HOME GRADLE_FLAGS|'
  mutate_and_expect_red \
    "the phone walkthrough dropped its fail-fast profile assertion" \
    scripts/phone-walkthrough.sh \
    's|^pocketshell_assert_gradle_execution_profile|: assertion_removed_by_mutation|'
  mutate_and_expect_red \
    "the shared Kotlin daemon heap flag was deleted from the profile lib" \
    scripts/lib/gradle-profile.sh \
    's|^  -Pkotlin.daemon.jvmargs=-Xmx3072m$||'
  mutate_and_expect_red \
    "the shared Gradle heap flag was deleted from the local profile" \
    scripts/lib/gradle-profile.sh \
    's|^  -Dorg.gradle.jvmargs=-Xmx3072m$||'
  mutate_and_expect_red \
    "the release build scope default was dropped back below 20G" \
    scripts/lib/gradle-profile.sh \
    's|^POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT=24G$|POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT=8G|'
  mutate_and_expect_red \
    "the Kotlin daemon heap floor was lowered to accept the stale 2048m heap" \
    scripts/lib/gradle-profile.sh \
    's|^POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB=3072$|POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB=0|;s|^  -Pkotlin.daemon.jvmargs=-Xmx3072m$|  -Pkotlin.daemon.jvmargs=-Xmx2048m|'
  mutate_and_expect_red \
    "the local build-scope floor was lowered back to the 8G that OOMed" \
    scripts/lib/gradle-profile.sh \
    's|^POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB=20$|POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB=8|;s|^POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT=24G$|POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT=8G|'

  # ---- reviewer-found survivors (round 1), each now a required RED ----------

  # (a) An overridable floor passes every value check while re-opening the exact
  #     bypass the plain assignments exist to close.
  mutate_and_expect_red \
    "the local scope floor became environment-overridable (\${VAR:-20})" \
    scripts/lib/gradle-profile.sh \
    's|^POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB=20$|POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB=${POCKETSHELL_RELEASE_GATE_SCOPE_MEM_FLOOR_GIB:-20}|'
  mutate_and_expect_red \
    "the Kotlin daemon heap floor became environment-overridable (\${VAR:-3072})" \
    scripts/lib/gradle-profile.sh \
    's|^POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB=3072$|POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB=${POCKETSHELL_KOTLIN_DAEMON_HEAP_FLOOR_MIB:-3072}|'
  mutate_and_expect_red \
    "the release scope default became environment-overridable (\${VAR:-24G})" \
    scripts/lib/gradle-profile.sh \
    's|^POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT=24G$|POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT=${POCKETSHELL_RELEASE_GATE_SCOPE_MEM_DEFAULT:-24G}|'

  # (b) Commenting a call out leaves the substring. Placement, not presence.
  mutate_and_expect_red \
    "the phone walkthrough's point-of-use scope apply was COMMENTED OUT" \
    scripts/phone-walkthrough.sh \
    's|^\([[:space:]]*\)pocketshell_apply_release_gate_scope_memory|\1# pocketshell_apply_release_gate_scope_memory|'
  mutate_and_expect_red \
    "the visual-audit profile assertion was COMMENTED OUT" \
    scripts/capture-walkthrough-screenshots.sh \
    's|^pocketshell_assert_gradle_execution_profile|# pocketshell_assert_gradle_execution_profile|'
  mutate_and_expect_red \
    "the terminal workbench stopped sourcing the profile lib (line COMMENTED OUT)" \
    scripts/terminal-workbench.sh \
    's|^source "\$ROOT_DIR/scripts/lib/gradle-profile.sh"$|# source "$ROOT_DIR/scripts/lib/gradle-profile.sh"|'
  # Dominance specifically: the upgrade gate keeps its SECOND apply, so the
  # presence checks all stay green and only the placement rule can catch this.
  mutate_and_expect_red \
    "the upgrade gate's FIRST assemble lost the apply that dominated it (the second one survives, so presence alone stays green)" \
    scripts/android-upgrade-preservation-gate.sh \
    '0,/^  pocketshell_apply_release_gate_scope_memory/{s|^  pocketshell_apply_release_gate_scope_memory.*$|  : apply_removed_by_mutation|}'

  # ---- reviewer-found survivors (round 2): REACHABILITY, not just order ----
  #
  # Every one of these passed round 2's `earlier line + indent <=` rule fully
  # green in all four cells. They are the required-RED set for the block-path
  # rule in dominating_apply_exists; two of them (WRAP_FLAT, CASE_ORPHAN) also
  # defeat the intervening-terminator refinement, because both hide the branch
  # boundary from ANY indentation-based reading.
  local WRAP_CONDITIONAL CASE_ORPHAN WRAP_FLAT DROP_SECOND_APPLY MOVE_TO_ELSE
  local HOIST_TO_ENCLOSING NEST_BUILD_DEEPER BREAK_BLOCK_STRUCTURE

  # Wrap the point-of-use apply in a condition that can be false.
  WRAP_CONDITIONAL='
    !did && /^[[:space:]]*pocketshell_apply_release_gate_scope_memory/ {
      match($0, /^[[:space:]]*/); ind = substr($0, 1, RLENGTH)
      print ind "if [[ \"${MUTANT_APPLY_SCOPE:-0}\" == \"1\" ]]; then"
      print "  " $0
      print ind "fi"
      did = 1
      next
    }
    { print }'

  # Same, but written with NO indentation at all — the shape that makes any
  # indentation-derived rule (including "no intervening block terminator at a
  # smaller indent") read the apply as a plain sibling of the build.
  WRAP_FLAT='
    !did && /^[[:space:]]*pocketshell_apply_release_gate_scope_memory/ {
      sub(/^[[:space:]]+/, "", $0)
      print "if [[ \"${MUTANT_APPLY_SCOPE:-0}\" == \"1\" ]]; then"
      print $0
      print "fi"
      did = 1
      next
    }
    { print }'

  # Apply in one `case` branch, build in the SIBLING branch. The only boundary
  # between them is `;;`, which sits at the apply own indent, so an
  # indent-`<` terminator test cannot see it.
  CASE_ORPHAN='
    /^pocketshell_apply_release_gate_scope_memory/ && !did {
      print "case \"${MUTANT_AUDIT_MODE:-a}\" in"
      print "  a)"
      print "    " $0
      print "    ;;"
      print "  b)"
      did = 1
      inbranch = 1
      next
    }
    inbranch { print "    " $0 }
    inbranch && /\.\/gradlew --no-daemon/ { print "    ;;"; print "esac"; inbranch = 0; next }
    !inbranch { print }'

  # Delete the SECOND of two sibling-branch applies. This is the shape the
  # shipped android-upgrade-preservation-gate.sh already has, and the surviving
  # first apply lives inside `if BUILD_NEW_APK == 1` — a branch the documented
  # BUILD_NEW_APK=0 configuration never enters.
  DROP_SECOND_APPLY='
    /^[[:space:]]*pocketshell_apply_release_gate_scope_memory/ { n++; if (n == 2) next }
    { print }'

  # Relocate the apply into the sibling `else` branch of the build's own `if`.
  MOVE_TO_ELSE='
    { L[NR] = $0 }
    END {
      for (i = 1; i <= NR; i++) if (L[i] ~ /^[[:space:]]*pocketshell_apply_release_gate_scope_memory/) { a = i; break }
      for (i = a; i <= NR; i++) if (L[i] ~ /^[[:space:]]*else[[:space:]]*$/) { e = i; break }
      for (i = 1; i <= NR; i++) {
        if (i == a) continue
        print L[i]
        if (i == e) print L[a]
      }
    }'

  # REQUIRED GREEN: hoisting the apply OUT to the block that ENCLOSES the build
  # keeps it reachable, so the rule must accept it. Without this case, a rule
  # that simply rejected everything would pass every RED above.
  HOIST_TO_ENCLOSING='
    { L[NR] = $0 }
    END {
      for (i = 1; i <= NR; i++) if (L[i] ~ /^[[:space:]]*pocketshell_apply_release_gate_scope_memory/) { a = i; break }
      for (i = 1; i <= NR; i++) if (L[i] ~ /BUILD_APKS/ && L[i] ~ /then[[:space:]]*$/) { g = i; break }
      for (i = 1; i <= NR; i++) {
        if (i == g) { s = L[a]; sub(/^[[:space:]]+/, "  ", s); print s }
        if (i == a) continue
        print L[i]
      }
    }'

  # REQUIRED GREEN: pushing the build one block DEEPER after the apply keeps the
  # apply on every path that reaches it.
  NEST_BUILD_DEEPER='
    { L[NR] = $0 }
    END {
      for (i = 1; i <= NR; i++) if (L[i] ~ /^run_logged "10-build-walkthrough-visual-apks"/) { s = i }
      for (i = 1; i <= NR; i++) if (L[i] ~ /\.\/gradlew --no-daemon/) { e = i }
      for (i = 1; i <= NR; i++) {
        if (i == s) print "if [[ \"${MUTANT_ALWAYS:-1}\" == \"1\" ]]; then"
        if (i >= s && i <= e) print "  " L[i]; else print L[i]
        if (i == e) print "fi"
      }
    }'

  # A file the scanner cannot model must be REJECTED, not assumed safe.
  BREAK_BLOCK_STRUCTURE='
    !did && /^[[:space:]]*fi[[:space:]]*$/ { did = 1; next }
    { print }'

  mutate_awk_and_expect red \
    "the phone walkthrough point-of-use apply was WRAPPED IN A CONDITIONAL that can be false" \
    scripts/phone-walkthrough.sh "$WRAP_CONDITIONAL"
  mutate_awk_and_expect red \
    "the visual-audit apply was WRAPPED IN A CONDITIONAL in a second script, at different indents, so this is not an indentation coincidence" \
    scripts/capture-walkthrough-screenshots.sh "$WRAP_CONDITIONAL"
  mutate_awk_and_expect red \
    "the visual-audit apply was wrapped in an UNINDENTED conditional, invisible to any indentation rule" \
    scripts/capture-walkthrough-screenshots.sh "$WRAP_FLAT"
  mutate_awk_and_expect red \
    "the visual-audit apply and its build were split into SIBLING case branches, with only a ;; between them at the apply own indent" \
    scripts/capture-walkthrough-screenshots.sh "$CASE_ORPHAN"
  mutate_awk_and_expect red \
    "the upgrade gate SECOND apply was deleted, leaving only the one inside if BUILD_NEW_APK == 1 to cover a BUILD_NEW_APK=0 old-worktree assemble" \
    scripts/android-upgrade-preservation-gate.sh "$DROP_SECOND_APPLY"
  mutate_awk_and_expect red \
    "the phone walkthrough apply moved into the SIBLING else branch of the build own if" \
    scripts/phone-walkthrough.sh "$MOVE_TO_ELSE"
  mutate_awk_and_expect red \
    "a release-chain script's block structure stopped balancing, so reachability could not be decided" \
    scripts/phone-walkthrough.sh "$BREAK_BLOCK_STRUCTURE"

  mutate_awk_and_expect green \
    "the phone walkthrough apply was hoisted to the block ENCLOSING the build, so it still reaches it" \
    scripts/phone-walkthrough.sh "$HOIST_TO_ENCLOSING"
  mutate_awk_and_expect green \
    "the visual-audit build was nested one block DEEPER after the apply, so it is still reached by it" \
    scripts/capture-walkthrough-screenshots.sh "$NEST_BUILD_DEEPER"

  # ---- reviewer-found survivors (round 3): the apply EXISTS but does not RUN --
  #
  # Three shapes that the block-path rule alone read as a plain, dominating,
  # unconditional sibling. All three were fully green in all four cells, and a
  # runtime probe of the real script showed POCKETSHELL_TEST_MEM UNSET at the
  # build in every one — i.e. scope-run.sh's 8G default, the v0.4.42 OOM.
  # eligible_call_lines' H/C/S classification is what rejects them.
  local SUBSHELL_APPLY SHORTCIRCUIT_APPLY HEREDOC_APPLY HEREDOC_APPLY_BARE
  local APPLY_THEN_AND DOC_HEREDOC_PLUS_APPLY BUILD_IN_SUBSHELL

  # An `export` inside a subshell never reaches the parent's build.
  SUBSHELL_APPLY='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "("
      print $0
      print ")"
      did = 1
      next
    }
    { print }'

  # `cond && apply || true` — round 2 required the `if` wrap closed; this is the
  # same defect spelled with a short-circuit, and a far more likely future edit
  # ("only apply the scope when we are actually going to build").
  SHORTCIRCUIT_APPLY='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "[[ \"${MUTANT_APPLY_SCOPE:-0}\" == \"1\" ]] &&"
      print "  " $0 " ||"
      print "  true"
      did = 1
      next
    }
    { print }'

  # The apply exists only as heredoc DATA. Nothing executes.
  HEREDOC_APPLY='
    BEGIN { q = sprintf("%c", 39) }
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat > /dev/null <<" q "MUTANT_EOS" q
      print $0
      print "MUTANT_EOS"
      did = 1
      next
    }
    { print }'

  # ...and with an UNQUOTED delimiter, the other spelling of the same hole.
  HEREDOC_APPLY_BARE='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat > /dev/null <<MUTANT_EOS"
      print $0
      print "MUTANT_EOS"
      did = 1
      next
    }
    { print }'

  # REQUIRED GREEN: the three rules above must reject those shapes and NOTHING
  # ELSE. An over-broad "any line near an operator / heredoc / paren is
  # suspect" rule would pass every RED above while breaking real refactors.

  # The apply line itself CONTINUES with `&& …`: the apply still runs first.
  APPLY_THEN_AND='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print $0 " && printf \"scope applied\\n\" >/dev/null"
      did = 1
      next
    }
    { print }'

  # A documentation heredoc that quotes the apply, with the REAL apply still
  # present: the H rule must drop the quoted copy, not the real call.
  DOC_HEREDOC_PLUS_APPLY='
    BEGIN { q = sprintf("%c", 39) }
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat > /dev/null <<" q "MUTANT_DOC" q
      print "Example: " $0
      print $0
      print "MUTANT_DOC"
      print $0
      did = 1
      next
    }
    { print }'

  # The BUILD moved into a subshell AFTER the apply. An export propagates INTO
  # a subshell, so this direction is legitimate and must stay green.
  BUILD_IN_SUBSHELL='
    { L[NR] = $0 }
    END {
      for (i = 1; i <= NR; i++) if (L[i] ~ /^pocketshell_apply_release_gate_scope_memory/) { a = i; break }
      for (i = a; i <= NR; i++) if (L[i] ~ /--stacktrace/) { e = i; break }
      for (i = 1; i <= NR; i++) {
        if (i == a + 1) print "("
        if (i > a && i <= e) print "  " L[i]; else print L[i]
        if (i == e) print ")"
      }
    }'

  mutate_awk_and_expect red \
    "the visual-audit apply was wrapped in a SUBSHELL, so its export dies before the build runs" \
    scripts/capture-walkthrough-screenshots.sh "$SUBSHELL_APPLY"
  mutate_awk_and_expect red \
    "the visual-audit apply became a cond && apply || true SHORT-CIRCUIT, conditional exactly like the rejected if-wrap" \
    scripts/capture-walkthrough-screenshots.sh "$SHORTCIRCUIT_APPLY"
  mutate_awk_and_expect red \
    "the visual-audit apply exists only as HEREDOC DATA (quoted delimiter), so nothing applies the scope" \
    scripts/capture-walkthrough-screenshots.sh "$HEREDOC_APPLY"
  mutate_awk_and_expect red \
    "the visual-audit apply exists only as HEREDOC DATA (unquoted delimiter)" \
    scripts/capture-walkthrough-screenshots.sh "$HEREDOC_APPLY_BARE"

  mutate_awk_and_expect green \
    "the visual-audit apply line continues with && …, so the apply itself still runs unconditionally" \
    scripts/capture-walkthrough-screenshots.sh "$APPLY_THEN_AND"
  mutate_awk_and_expect green \
    "a documentation heredoc quotes the apply while the real executable apply is still there" \
    scripts/capture-walkthrough-screenshots.sh "$DOC_HEREDOC_PLUS_APPLY"
  mutate_awk_and_expect green \
    "the visual-audit BUILD moved into a subshell after the apply, which an export propagates into" \
    scripts/capture-walkthrough-screenshots.sh "$BUILD_IN_SUBSHELL"

  # ---- reviewer-found survivors (round 4): the apply RUNS, in a CHILD -------
  #
  # Five more shapes that read as a plain dominating sibling. Every one was
  # `bash -n` clean, fully GREEN in both plain cells, and left
  # POCKETSHELL_TEST_MEM UNSET at the real build under the round-5 runtime
  # oracle. Three are the pipeline hazard the `C` flag's own docstring already
  # named while closing only its cross-line spelling; two are heredoc-model
  # depth.
  local PIPED_APPLY PIPEAMP_CONT_APPLY BACKTICK_APPLY
  local HEREDOC_FAKE_TERMINATOR TWO_HEREDOCS_APPLY
  local APPLY_THEN_OR APPLY_WITH_REDIRECT PRIOR_PIPELINE DASH_HEREDOC_TERMINATOR

  # `apply "…" | tee -a "$log"` — the ordinary way anyone adds logging, and the
  # single most plausible future edit of this code. The pipeline puts the apply
  # in a child process and the export dies with it.
  PIPED_APPLY='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print $0 " | tee -a \"$RUN_DIR/scope.log\""
      did = 1
      next
    }
    { print }'

  # The predecessor ends in `|&` (bash'"'"'s `2>&1 |`), which the continuation
  # test read only as `/\|$/`.
  PIPEAMP_CONT_APPLY='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "printf \"\" |&"
      print "  " $0
      did = 1
      next
    }
    { print }'

  # A multi-line BACKTICK substitution around the apply — the legacy spelling of
  # the `$( )` the Q rule already covered, and a subshell just the same.
  BACKTICK_APPLY='
    BEGIN { bt = sprintf("%c", 96) }
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "MUT_OUT=" bt
      print $0
      print bt
      did = 1
      next
    }
    { print }'

  # A heredoc body containing an INDENTED copy of its own terminator. Bash keeps
  # reading the body (only `<<-` permits an indented terminator, and only tabs);
  # a scanner that strips leading whitespace ends it a line early and then reads
  # real body text as executable code.
  HEREDOC_FAKE_TERMINATOR='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat > /dev/null <<MUTANT_EOS"
      print "documentation line"
      print "  MUTANT_EOS"
      print $0
      print "MUTANT_EOS"
      did = 1
      next
    }
    { print }'

  # TWO heredocs opened on ONE line, apply in the SECOND body.
  TWO_HEREDOCS_APPLY='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat /dev/null > /dev/null <<MUTANT_EOA <<MUTANT_EOB"
      print "first body"
      print "MUTANT_EOA"
      print $0
      print "MUTANT_EOB"
      did = 1
      next
    }
    { print }'

  # REQUIRED GREEN: the `P` rule must reject a PIPE and nothing else. `||` is a
  # conditional operator, not a subshell; a redirection that merely contains `&`
  # is not backgrounding; and an unrelated pipeline on an EARLIER line leaves
  # the apply a plain statement of its own.
  APPLY_THEN_OR='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print $0 " || true"
      did = 1
      next
    }
    { print }'
  APPLY_WITH_REDIRECT='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print $0 " 2>&1"
      did = 1
      next
    }
    { print }'
  PRIOR_PIPELINE='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "printf \"pre\\n\" | cat >/dev/null"
      print $0
      did = 1
      next
    }
    { print }'
  # ...and the heredoc tightening must not break the form bash DOES honour: a
  # `<<-` body whose terminator is indented with TABS.
  DASH_HEREDOC_TERMINATOR='
    BEGIN { t = sprintf("%c", 9) }
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat > /dev/null <<-MUTANT_EOS"
      print t "indented body"
      print t "MUTANT_EOS"
      print $0
      did = 1
      next
    }
    { print }'

  mutate_awk_and_expect red \
    "the visual-audit apply gained a top-level PIPE on its own line (| tee -a), so it runs in a pipeline subshell whose export never reaches the build" \
    scripts/capture-walkthrough-screenshots.sh "$PIPED_APPLY"
  mutate_awk_and_expect red \
    "the visual-audit apply became the second stage of a |& pipeline" \
    scripts/capture-walkthrough-screenshots.sh "$PIPEAMP_CONT_APPLY"
  mutate_awk_and_expect red \
    "the visual-audit apply was wrapped in a multi-line BACKTICK substitution" \
    scripts/capture-walkthrough-screenshots.sh "$BACKTICK_APPLY"
  mutate_awk_and_expect red \
    "the visual-audit apply hides after a FAKE indented heredoc terminator, so bash keeps it as body data while the text reads as code" \
    scripts/capture-walkthrough-screenshots.sh "$HEREDOC_FAKE_TERMINATOR"
  mutate_awk_and_expect red \
    "the visual-audit apply lives in the SECOND of two heredocs opened on one line" \
    scripts/capture-walkthrough-screenshots.sh "$TWO_HEREDOCS_APPLY"

  mutate_awk_and_expect green \
    "the visual-audit apply line continues with || true, which is a conditional operator and not a subshell" \
    scripts/capture-walkthrough-screenshots.sh "$APPLY_THEN_OR"
  mutate_awk_and_expect green \
    "the visual-audit apply redirects with 2>&1, which contains an & but backgrounds nothing" \
    scripts/capture-walkthrough-screenshots.sh "$APPLY_WITH_REDIRECT"
  mutate_awk_and_expect green \
    "an unrelated PIPELINE sits on the line before the visual-audit apply, which is still its own statement" \
    scripts/capture-walkthrough-screenshots.sh "$PRIOR_PIPELINE"
  mutate_awk_and_expect green \
    "a <<- heredoc with a TAB-indented terminator still terminates, so the real apply after it counts" \
    scripts/capture-walkthrough-screenshots.sh "$DASH_HEREDOC_TERMINATOR"

  # ---- reviewer-found survivor (round 5): the BACKSLASH-quoted delimiter ----
  #
  # `cat <<\EOS` is `cat <<'EOS'` — bash quotes the delimiter when ANY character
  # of the word is quoted, and a backslash is one of the three spellings.
  # heredoc_word modelled `'` and `"` and not `\`, so the word came back EMPTY,
  # the heredoc was never queued, and the body was read as executable code: the
  # apply scored `-` and the guard passed a tree where the runtime oracle
  # measured POCKETSHELL_TEST_MEM=<UNSET> at the real build, with the apply's
  # own "Release build scope OK" line absent from the run. Unlike the other
  # residual gaps this one errs toward ACCEPTING, so it could not be documented
  # — it had to be closed.
  local BACKSLASH_HEREDOC_APPLY DASH_BACKSLASH_HEREDOC_APPLY
  local BACKSLASH_HEREDOC_DOC HERE_STRING_BEFORE_APPLY

  BACKSLASH_HEREDOC_APPLY='
    BEGIN { b = sprintf("%c", 92) }
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat > /dev/null <<" b "MUTANT_EOS"
      print $0
      print "MUTANT_EOS"
      did = 1
      next
    }
    { print }'

  # The `<<-` spelling of the same thing, tab-indented body and terminator.
  DASH_BACKSLASH_HEREDOC_APPLY='
    BEGIN { b = sprintf("%c", 92); t = sprintf("%c", 9) }
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat > /dev/null <<-" b "MUTANT_EOS"
      print t $0
      print t "MUTANT_EOS"
      did = 1
      next
    }
    { print }'

  # REQUIRED GREEN: the backslash form must still TERMINATE. A documentation
  # `<<\DOC` block that quotes the apply, with the real executable apply after
  # it, is the shape that catches a fix which queues a body it never dequeues.
  BACKSLASH_HEREDOC_DOC='
    BEGIN { b = sprintf("%c", 92) }
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat > /dev/null <<" b "MUTANT_DOC"
      print "Example: " $0
      print "MUTANT_DOC"
      print $0
      did = 1
      next
    }
    { print }'

  # REQUIRED GREEN: `<<<` is a HERE-STRING, not a heredoc, and four scripts the
  # closure walk reaches use one. heredoc_word must keep returning an empty word
  # for it; a fix that treated every unmodellable `<<` word as a heredoc would
  # strand those files mid-body and reject the shipped tree.
  HERE_STRING_BEFORE_APPLY='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat >/dev/null <<<\"mutant here string\""
      print $0
      did = 1
      next
    }
    { print }'

  mutate_awk_and_expect red \
    "the visual-audit apply lives in a heredoc whose delimiter is BACKSLASH-quoted (<<\\EOS), which bash reads as data exactly like <<'EOS'" \
    scripts/capture-walkthrough-screenshots.sh "$BACKSLASH_HEREDOC_APPLY"
  mutate_awk_and_expect red \
    "the visual-audit apply lives in a <<-\\EOS heredoc body, the tab-stripping spelling of the backslash-quoted delimiter" \
    scripts/capture-walkthrough-screenshots.sh "$DASH_BACKSLASH_HEREDOC_APPLY"

  mutate_awk_and_expect green \
    "a <<\\DOC documentation heredoc quotes the apply and TERMINATES, so the real executable apply after it still counts" \
    scripts/capture-walkthrough-screenshots.sh "$BACKSLASH_HEREDOC_DOC"
  mutate_awk_and_expect green \
    "a <<< HERE-STRING sits before the visual-audit apply, and a here-string opens no heredoc body at all" \
    scripts/capture-walkthrough-screenshots.sh "$HERE_STRING_BEFORE_APPLY"

  # ---- reviewer-found survivors (round 7): the UNDETERMINABLE delimiter -----
  #
  # Round 6 closed `<<\EOS` by teaching heredoc_word one more spelling. Round 7
  # found three more one character away — `<<\\EOS` (an escaped backslash, so
  # bash's terminator is the literal word `\EOS`), `<<""` (the EMPTY delimiter,
  # whose terminator is the first empty line) and `<<$VAR` (bash does not expand
  # a delimiter, so the terminator is the literal text `$VAR`). All three are
  # `bash -n` clean, all three are DATA to bash, all three scored the apply `-`
  # on the round-6 guard with the runtime oracle measuring
  # POCKETSHELL_TEST_MEM=<UNSET> at the real build.
  #
  # These cases are here to pin the CLASS fix, not the three spellings: an
  # empty delimiter word now rejects the file at load_block_paths rather than
  # silently reading the body as code. A fix that enumerated exactly these three
  # would leave the fourth spelling open again, which is how rounds 5 and 6 both
  # went. If a future change re-introduces "unmodellable -> treat the body as
  # code", every one of these reddens.
  local BSBS_HEREDOC_APPLY EMPTY_DELIM_HEREDOC_APPLY DOLLAR_DELIM_HEREDOC_APPLY
  local HERE_STRING_SUBST_BEFORE_APPLY

  BSBS_HEREDOC_APPLY='
    BEGIN { b = sprintf("%c", 92) }
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat > /dev/null <<" b b "MUTANT_EOS"
      print $0
      print b "MUTANT_EOS"
      did = 1
      next
    }
    { print }'

  EMPTY_DELIM_HEREDOC_APPLY='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat > /dev/null <<\"\""
      print $0
      print ""
      did = 1
      next
    }
    { print }'

  DOLLAR_DELIM_HEREDOC_APPLY='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat > /dev/null <<$MUTANT_DELIM"
      print $0
      print "$MUTANT_DELIM"
      did = 1
      next
    }
    { print }'

  # REQUIRED GREEN, and the pin that makes the class fix safe: the fail-closed
  # rule fires on an empty DELIMITER word, and `<<<` must never produce one
  # because sanitize consumes a here-string before heredoc_word is reached.
  # This spelling puts a command substitution inside the here-string word, so it
  # also exercises the substitution context on the same line.
  HERE_STRING_SUBST_BEFORE_APPLY='
    !did && /^pocketshell_apply_release_gate_scope_memory/ {
      print "cat >/dev/null <<< \"$(printf %s mutant)\""
      print $0
      did = 1
      next
    }
    { print }'

  mutate_awk_and_expect red \
    "the visual-audit apply lives in a <<\\\\EOS heredoc, whose bash terminator is the literal word \\EOS and whose body is data" \
    scripts/capture-walkthrough-screenshots.sh "$BSBS_HEREDOC_APPLY"
  mutate_awk_and_expect red \
    "the visual-audit apply lives in a heredoc with an EMPTY delimiter (<<\"\"), terminated by the first empty line" \
    scripts/capture-walkthrough-screenshots.sh "$EMPTY_DELIM_HEREDOC_APPLY"
  mutate_awk_and_expect red \
    "the visual-audit apply lives in a <<\$VAR heredoc; bash does not expand a delimiter, so the body is data" \
    scripts/capture-walkthrough-screenshots.sh "$DOLLAR_DELIM_HEREDOC_APPLY"

  mutate_awk_and_expect green \
    "a <<< here-string whose word is a COMMAND SUBSTITUTION sits before the apply; a here-string still opens no heredoc body" \
    scripts/capture-walkthrough-screenshots.sh "$HERE_STRING_SUBST_BEFORE_APPLY"

  # ---- reviewer-found survivor (round 4): the build left the LIST -----------
  #
  # G-LIST. The chain script keeps its source, assertion and apply; only the
  # `gradlew` line moves into a helper it invokes. Round 4 measured this GREEN
  # and measured exactly what leaks: the runtime oracle shows the child at
  # POCKETSHELL_TEST_MEM=24G (the scope export IS inherited) executing
  # `./gradlew --no-daemon --no-build-cache --max-workers=2 …` — no heap flags
  # at all, i.e. the Kotlin daemon back on the inherited 2048m. That is the
  # other half of the v0.4.42 OOM, so the closure rule must make it RED.
  local OFFLIST_BUILD OFFLIST_NONBUILD OFFLIST_HELPER_BUILDS OFFLIST_HELPER_INERT

  OFFLIST_BUILD='
    /^run_logged "10-build-walkthrough-visual-apks"/ {
      print "run_logged \"10-build-walkthrough-visual-apks\" \\"
      print "  \"$ROOT_DIR/scripts/mut-offlist-build.sh\" \"$RUN_ID\""
      skip = 2
      next
    }
    skip > 0 { skip--; next }
    { print }'

  # REQUIRED GREEN: invoking an off-list helper is perfectly normal — the rule
  # must bite only when that helper itself runs a build.
  OFFLIST_NONBUILD='
    /^run_logged "10-build-walkthrough-visual-apks"/ && !did {
      print "run_logged \"09b-offlist-helper\" \"$ROOT_DIR/scripts/mut-offlist-build.sh\""
      did = 1
    }
    { print }'

  OFFLIST_HELPER_BUILDS='#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
"$ROOT_DIR/scripts/cgroup-run.sh" --unit "pocketshell-offlist-${1:-x}-build-apks" -- \
  ./gradlew --no-daemon --no-build-cache --max-workers=2 \
  :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace'

  OFFLIST_HELPER_INERT='#!/usr/bin/env bash
set -euo pipefail
printf "offlist helper that builds nothing\n"'

  mutate_awk_with_helper_and_expect red \
    "the visual-audit BUILD moved into scripts/mut-offlist-build.sh, outside RELEASE_CHAIN_SCRIPTS, where the inherited scope export hides the fact that the heap flags are gone" \
    scripts/capture-walkthrough-screenshots.sh "$OFFLIST_BUILD" \
    scripts/mut-offlist-build.sh "$OFFLIST_HELPER_BUILDS"

  mutate_awk_with_helper_and_expect green \
    "the visual audit invokes an off-list helper that runs NO build, which is not a reason to reject anything" \
    scripts/capture-walkthrough-screenshots.sh "$OFFLIST_NONBUILD" \
    scripts/mut-offlist-build.sh "$OFFLIST_HELPER_INERT"

  # REQUIRED GREEN: naming a real build script in a COMMENT is documentation.
  # scripts/cgroup-run.sh's `cat <<'USAGE'` block names
  # scripts/connected-test.sh, which really does build; if prose pulled scripts
  # into the closure the shipped tree itself would be RED.
  local OFFLIST_DOC_MENTION
  OFFLIST_DOC_MENTION='
    /^run_logged "10-build-walkthrough-visual-apks"/ && !did {
      print "# See scripts/connected-test.sh for the connected-test equivalent."
      did = 1
    }
    { print }'
  mutate_awk_and_expect green \
    "a real build script (scripts/connected-test.sh) is named only in a COMMENT, which is prose and not an invocation" \
    scripts/capture-walkthrough-screenshots.sh "$OFFLIST_DOC_MENTION"

  # (c) `grep '\./gradlew'` never saw the second worktree's wrapper.
  mutate_and_expect_red \
    "the upgrade gate's old-worktree \"\$old_worktree/gradlew\" build dropped \$GRADLE_FLAGS" \
    scripts/android-upgrade-preservation-gate.sh \
    's|"\$old_worktree/gradlew" \$GRADLE_FLAGS |"$old_worktree/gradlew" |'

  # (d) Hosted/local pairing: giving hosted the local launcher heap is the
  #     heap-without-scope mismatch the sibling budget guard forbids.
  mutate_and_expect_red \
    "the hosted profile took the local 3072m launcher heap inside the hosted 8G scope" \
    scripts/lib/gradle-profile.sh \
    's|^  -Dorg.gradle.jvmargs=-Xmx1536m$|  -Dorg.gradle.jvmargs=-Xmx3072m|'
  mutate_and_expect_red \
    "the hosted scope constant drifted away from the workflow's pinned 8G" \
    scripts/lib/gradle-profile.sh \
    's|^POCKETSHELL_RELEASE_GATE_HOSTED_SCOPE_MEM=8G$|POCKETSHELL_RELEASE_GATE_HOSTED_SCOPE_MEM=24G|'

  printf 'PASS: release-gate execution-profile guard self-test (%s checks)\n' "$checks"
}

# ---------------------------------------------------------------------------
# CI-invariance: the guard must mean the SAME thing in both lanes.
#
# Round 1's pin was RED on GitHub and GREEN locally; the reviewer then found the
# converse — with the round-1 defect restored, `CI=true` produced byte-identical
# output to a correct tree, i.e. constant red, zero discrimination, on the only
# lane that runs this guard per push. Running the whole body under both shapes
# and requiring identical results makes "this check silently changed meaning
# under CI" a hard failure instead of an invisible one.
# ---------------------------------------------------------------------------
ci_invariance_check() {
  local mode label out_local out_hosted rc_local rc_hosted rc=0

  for mode in --self-test ""; do
    label="${mode:-plain check}"
    rc_local=0
    rc_hosted=0
    out_local="$(as_local env POCKETSHELL_PROFILE_GUARD_NO_RECURSE=1 "$SELF_PATH" ${mode:+"$mode"} 2>&1)" || rc_local=$?
    out_hosted="$(as_hosted env POCKETSHELL_PROFILE_GUARD_NO_RECURSE=1 "$SELF_PATH" ${mode:+"$mode"} 2>&1)" || rc_hosted=$?

    if [[ "$rc_local" -ne "$rc_hosted" || "$out_local" != "$out_hosted" ]]; then
      fail "CI-invariance: '$label' behaves differently with and without CI=true.
  CI unset -> exit $rc_local
$out_local
  CI=true  -> exit $rc_hosted
$out_hosted" || rc=1
    fi
    if [[ "$rc_local" -ne 0 ]]; then
      fail "CI-invariance: '$label' failed (exit $rc_local) with CI unset" || rc=1
    fi
  done

  [[ "$rc" -eq 0 ]] || return 1
  printf 'PASS: guard verdict is identical with CI unset and CI=true (2 modes)\n'
}

case "${1:-}" in
  --self-test)
    self_test
    # Recursion guard: the invariance sub-runs re-enter this same mode.
    if [[ -z "${POCKETSHELL_PROFILE_GUARD_NO_RECURSE:-}" ]]; then
      ci_invariance_check
    fi
    ;;
  --ci-invariance)
    ci_invariance_check
    ;;
  --root)
    [[ $# -ge 2 ]] || {
      fail "--root needs a directory"
      exit 2
    }
    run_check "$2"
    printf 'PASS: release-chain Gradle execution profile is heap-bounded (%s checks, root %s)\n' "$CHECKS_RUN" "$2"
    ;;
  --help | -h)
    printf 'Usage: %s [--self-test | --ci-invariance | --root <dir>]\n' "$0"
    ;;
  "")
    run_check "$ROOT_DIR"
    printf 'PASS: release-chain Gradle execution profile is heap-bounded (%s checks)\n' "$CHECKS_RUN"
    ;;
  *)
    fail "unknown argument: $1"
    exit 2
    ;;
esac
