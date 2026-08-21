#!/usr/bin/env bash
# Shared area-classification engine behind the #2063 coverage-guard suite (and,
# as a by-product, its area-scoped selection). Analysis: #2059.
#
# Not a speed mechanism — measured at 1.09x on the emulator lane and
# neutral-to-slightly-negative on Unit; docs/testing.md carries the table. What
# it buys is a checkable answer to "is every test class mapped, reachable and
# actually executed", plus the host-CLI wire seam that #1509/#847 needed.
#
# WHAT THIS IS
#
# One data-driven classifier, loaded from `scripts/test-areas.txt`, that answers
# three questions about a repo-relative path:
#
#   1. Does changing it force a FULL run?  (blast-radius escapes)
#   2. If not, which test AREA does it belong to?
#   3. Which coarse emulator stage does `scripts/dev-fast-gate.sh` want for it?
#      (the `devgate` column; this engine is the promotion of that script's
#      inline `classify_path` case arms into shared, testable data)
#
# WHY A LIBRARY AND NOT INLINE CASE ARMS
#
# `scripts/dev-fast-gate.sh:108` already proved the SHAPE — ordered force-full
# arms first, a conservative allowlist second, and force-full for anything
# unmatched. What it could not do is be reused: the arms are inline, so CI
# selection, the coverage guard, and the local fast path would each have grown
# their own copy of the taxonomy and drifted apart. The taxonomy is now one
# committed file; every consumer reads it.
#
# THE FAIL-SAFE DIRECTION IS STRUCTURAL, NOT A CONVENTION
#
# `pocketshell_test_area_classify` sets POCKETSHELL_TEST_AREA_KIND to `full`
# BEFORE it looks at anything, and every non-`full` outcome requires an explicit
# manifest row to have matched. There is no code path that ends in
# "nothing matched, so run nothing": the fall-through IS `full`. A future edit
# that deletes a rule, mistypes a glob, or drops a whole record type degrades
# toward running MORE, never less. `scripts/select-test-areas-selftest.sh`
# mutates the manifest to prove that direction (unknown path, empty manifest,
# manifest with every rule removed) rather than asserting it in a comment.
#
# MANIFEST GRAMMAR (scripts/test-areas.txt)
#
#   area   <name>  <tier>  <description...>       tier = always | changed
#   full   <glob>  <reason...>                    changing this => run everything
#   noop   <glob>  <reason...>                    provably cannot affect any test
#   src    <glob>  <area>  <devgate>              production path -> area
#   test   <glob>  <area>  <devgate>              test path -> area
#   class  <fqcn>  <area>                         per-class override (beats globs)
#   couple <area>  <area,area,...>                selecting <area> also selects these
#
# Globs are matched with bash `[[ path == glob ]]`, in which `*` crosses `/`
# (the same semantics `dev-fast-gate.sh` relies on). Rows are evaluated in file
# order within their record type; first match wins.
#
# EVALUATION ORDER (single source of truth; mirrored by the docs and the tests)
#
#   1. `full` rows                      -> full
#   2. test-infrastructure code rule    -> full
#      (a path inside a test source set whose basename is not `*Test.kt` /
#      `*Test.java` is a shared fixture/helper/resource; its blast radius is
#      unknown)
#   3. `noop` rows                      -> noop
#   4. `class` overrides (test paths)   -> area
#   5. `test` rows                      -> area
#   6. `src` rows                       -> area
#   7. fall-through                     -> full (unmapped; reported loudly)
#
# Rule 2 is deliberately code, not a glob: "every file in these directories
# except the ones named *Test.kt" is not expressible as an ordered glob list,
# and enumerating today's 66 helper files would rot the moment someone adds the
# 67th.
#
# Rule 2's known bound, stated rather than hidden: it recognises test classes by
# the repo's `*Test.kt` / `*Test.java` naming convention. Four @Test-bearing
# classes do not follow it (three screenshot harnesses plus the ui-kit
# DesignRenders render target). They are force-fulled here (safe) and
# `select-test-areas.sh --verify-manifest` fails on any NEW one, so the set
# cannot grow silently.

# Guard against double-sourcing.
if [[ -n "${POCKETSHELL_TEST_AREAS_LIB_LOADED:-}" ]]; then
  return 0 2>/dev/null || true
fi
POCKETSHELL_TEST_AREAS_LIB_LOADED=1

POCKETSHELL_TEST_AREAS_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Parallel arrays, populated by pocketshell_test_areas_load.
declare -a POCKETSHELL_TA_FULL_GLOB=()
declare -a POCKETSHELL_TA_FULL_REASON=()
declare -a POCKETSHELL_TA_NOOP_GLOB=()
declare -a POCKETSHELL_TA_NOOP_REASON=()
declare -a POCKETSHELL_TA_SRC_GLOB=()
declare -a POCKETSHELL_TA_SRC_AREA=()
declare -a POCKETSHELL_TA_SRC_DEVGATE=()
declare -a POCKETSHELL_TA_TEST_GLOB=()
declare -a POCKETSHELL_TA_TEST_AREA=()
declare -a POCKETSHELL_TA_TEST_DEVGATE=()
declare -a POCKETSHELL_TA_AREAS=()
# Globs whose LAST path segment is not exactly `*`, i.e. rules that read the
# file NAME rather than its directory. The index has to classify those files
# individually instead of reusing their directory's answer; computing the set
# from the manifest (rather than hardcoding "the :app root row") means a future
# file-shaped row cannot be silently averaged away.
declare -a POCKETSHELL_TA_FILE_SHAPED_GLOB=()
declare -A POCKETSHELL_TA_AREA_TIER=()
declare -A POCKETSHELL_TA_AREA_DESC=()
declare -A POCKETSHELL_TA_CLASS_AREA=()
declare -A POCKETSHELL_TA_COUPLE=()

POCKETSHELL_TA_MANIFEST=""
declare -a POCKETSHELL_TA_LOAD_ERRORS=()

# Outputs of pocketshell_test_area_classify (set as globals, not echoed, so the
# hot loop over thousands of paths never forks a subshell).
POCKETSHELL_TEST_AREA_KIND=""     # full | noop | area
POCKETSHELL_TEST_AREA_NAME=""     # area name when KIND=area, else empty
POCKETSHELL_TEST_AREA_REASON=""   # matched glob / rule name
POCKETSHELL_TEST_AREA_DEVGATE=""  # ui | bootstrap | terminal | full

# Outputs of the FQCN resolvers, published the same way for the same reason: a
# `$(...)` call site forks, and the guards ask these questions a thousand times
# per run. Callers in a hot loop read the global; one-shot callers use the
# printed value. ONE implementation either way — a second copy of the
# resolution chain inlined at a call site is exactly the round-1 B4 defect.
POCKETSHELL_TEST_AREA_FOR_CLASS=""
POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS=""

pocketshell_test_areas_default_manifest() {
  printf '%s/../test-areas.txt\n' "$POCKETSHELL_TEST_AREAS_LIB_DIR"
}

# Load the manifest. Returns non-zero (and populates POCKETSHELL_TA_LOAD_ERRORS)
# when the file is missing or a row is malformed. Callers MUST treat a load
# failure as "force full", never as "no rules, so nothing to run".
pocketshell_test_areas_load() {
  # extglob so a manifest row can express "must not cross a path segment"
  # (`+([A-Za-z0-9_])`). Plain `*` crosses `/` in `[[ ]]`, which is right for
  # almost every rule and catastrophically wrong for the :app root-level test
  # rule — without extglob that row would silently swallow every future
  # unmapped subpackage instead of failing it loudly.
  shopt -s extglob

  local manifest="${1:-$(pocketshell_test_areas_default_manifest)}"
  POCKETSHELL_TA_MANIFEST="$manifest"
  POCKETSHELL_TA_LOAD_ERRORS=()

  POCKETSHELL_TA_FULL_GLOB=(); POCKETSHELL_TA_FULL_REASON=()
  POCKETSHELL_TA_NOOP_GLOB=(); POCKETSHELL_TA_NOOP_REASON=()
  POCKETSHELL_TA_SRC_GLOB=(); POCKETSHELL_TA_SRC_AREA=(); POCKETSHELL_TA_SRC_DEVGATE=()
  POCKETSHELL_TA_TEST_GLOB=(); POCKETSHELL_TA_TEST_AREA=(); POCKETSHELL_TA_TEST_DEVGATE=()
  POCKETSHELL_TA_AREAS=()
  POCKETSHELL_TA_FILE_SHAPED_GLOB=()
  POCKETSHELL_TA_AREA_TIER=(); POCKETSHELL_TA_AREA_DESC=()
  POCKETSHELL_TA_CLASS_AREA=(); POCKETSHELL_TA_COUPLE=()
  POCKETSHELL_TA_INDEX_BUILT=0
  POCKETSHELL_TA_PROD_PKG_AREA=(); POCKETSHELL_TA_PROD_PKG_HOSTCLI=()
  POCKETSHELL_TA_PROD_PKG_SHARED=(); POCKETSHELL_TA_HOSTCLI_END=()
  POCKETSHELL_TA_CLASS_PATH=(); POCKETSHELL_TA_CLASS_RESOLVED=(); POCKETSHELL_TA_CLASS_DEPS=()
  POCKETSHELL_TA_PKG_TEST_AREA=(); POCKETSHELL_TA_PKG_TEST_DEPS=()
  POCKETSHELL_TA_CLASS_MODULE=(); POCKETSHELL_TA_CLASS_SOURCESET=()
  POCKETSHELL_TA_RESOLVE_CACHE=(); POCKETSHELL_TA_DEPS_CACHE=()
  POCKETSHELL_TA_INDEX_CLASSES=0; POCKETSHELL_TA_INDEX_IMPORT_LINES=0
  POCKETSHELL_TA_INDEX_CROSS_AREA_CLASSES=0; POCKETSHELL_TA_INDEX_PROD_PKGS=0
  POCKETSHELL_TA_INDEX_HOSTCLI_PKGS=0; POCKETSHELL_TA_INDEX_HOSTCLI_CLASSES=0
  POCKETSHELL_TA_INDEX_HOSTCLI_INVOKER_PKGS=0; POCKETSHELL_TA_INDEX_HOSTCLI_CONSUMER_PKGS=0
  POCKETSHELL_TA_INDEX_HOSTCLI_VOCAB_PKGS=0; POCKETSHELL_TA_INDEX_HOSTCLI_SHARED_PKGS=0
  POCKETSHELL_TA_INDEX_HOSTCLI_SUBCOMMANDS=0

  if [[ ! -f "$manifest" ]]; then
    POCKETSHELL_TA_LOAD_ERRORS+=("manifest not found: $manifest")
    return 1
  fi

  local lineno=0 line kind a b c rest
  while IFS= read -r line || [[ -n "$line" ]]; do
    lineno=$((lineno + 1))
    line="${line%%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#.*)?$ ]] && continue
    read -r kind a b c rest <<<"$line"
    case "$kind" in
      area)
        if [[ -z "$a" || -z "$b" ]]; then
          POCKETSHELL_TA_LOAD_ERRORS+=("$manifest:$lineno: 'area' needs <name> <tier> <desc>")
          continue
        fi
        if [[ "$b" != "always" && "$b" != "changed" ]]; then
          POCKETSHELL_TA_LOAD_ERRORS+=("$manifest:$lineno: unknown tier '$b' (want always|changed)")
          continue
        fi
        if [[ -n "${POCKETSHELL_TA_AREA_TIER[$a]:-}" ]]; then
          POCKETSHELL_TA_LOAD_ERRORS+=("$manifest:$lineno: duplicate area '$a'")
          continue
        fi
        POCKETSHELL_TA_AREAS+=("$a")
        POCKETSHELL_TA_AREA_TIER["$a"]="$b"
        POCKETSHELL_TA_AREA_DESC["$a"]="${c} ${rest}"
        ;;
      full)
        [[ -z "$a" ]] && { POCKETSHELL_TA_LOAD_ERRORS+=("$manifest:$lineno: 'full' needs <glob> <reason>"); continue; }
        POCKETSHELL_TA_FULL_GLOB+=("$a")
        POCKETSHELL_TA_FULL_REASON+=("${b} ${c} ${rest}")
        [[ "${a##*/}" == "*" ]] || POCKETSHELL_TA_FILE_SHAPED_GLOB+=("$a")
        ;;
      noop)
        [[ -z "$a" ]] && { POCKETSHELL_TA_LOAD_ERRORS+=("$manifest:$lineno: 'noop' needs <glob> <reason>"); continue; }
        POCKETSHELL_TA_NOOP_GLOB+=("$a")
        POCKETSHELL_TA_NOOP_REASON+=("${b} ${c} ${rest}")
        ;;
      src|test)
        if [[ -z "$a" || -z "$b" || -z "$c" ]]; then
          POCKETSHELL_TA_LOAD_ERRORS+=("$manifest:$lineno: '$kind' needs <glob> <area> <devgate>")
          continue
        fi
        case "$c" in
          ui|bootstrap|terminal|full) : ;;
          *) POCKETSHELL_TA_LOAD_ERRORS+=("$manifest:$lineno: unknown devgate '$c' (want ui|bootstrap|terminal|full)"); continue ;;
        esac
        if [[ "$kind" == "src" ]]; then
          POCKETSHELL_TA_SRC_GLOB+=("$a"); POCKETSHELL_TA_SRC_AREA+=("$b"); POCKETSHELL_TA_SRC_DEVGATE+=("$c")
        else
          POCKETSHELL_TA_TEST_GLOB+=("$a"); POCKETSHELL_TA_TEST_AREA+=("$b"); POCKETSHELL_TA_TEST_DEVGATE+=("$c")
          [[ "${a##*/}" == "*" ]] || POCKETSHELL_TA_FILE_SHAPED_GLOB+=("$a")
        fi
        ;;
      class)
        if [[ -z "$a" || -z "$b" ]]; then
          POCKETSHELL_TA_LOAD_ERRORS+=("$manifest:$lineno: 'class' needs <fqcn> <area>")
          continue
        fi
        POCKETSHELL_TA_CLASS_AREA["$a"]="$b"
        ;;
      couple)
        if [[ -z "$a" || -z "$b" ]]; then
          POCKETSHELL_TA_LOAD_ERRORS+=("$manifest:$lineno: 'couple' needs <area> <area,area,...>")
          continue
        fi
        POCKETSHELL_TA_COUPLE["$a"]="${POCKETSHELL_TA_COUPLE[$a]:+${POCKETSHELL_TA_COUPLE[$a]},}$b"
        ;;
      *)
        POCKETSHELL_TA_LOAD_ERRORS+=("$manifest:$lineno: unknown record type '$kind'")
        ;;
    esac
  done < "$manifest"

  # Referential integrity: every area named by a src/test/class/couple row must
  # be declared. A typo'd area name would otherwise create a silent ghost area
  # whose tests are selected by nothing.
  local i name
  for i in "${!POCKETSHELL_TA_SRC_AREA[@]}"; do
    name="${POCKETSHELL_TA_SRC_AREA[$i]}"
    [[ -n "${POCKETSHELL_TA_AREA_TIER[$name]:-}" ]] || \
      POCKETSHELL_TA_LOAD_ERRORS+=("src rule '${POCKETSHELL_TA_SRC_GLOB[$i]}' names undeclared area '$name'")
  done
  for i in "${!POCKETSHELL_TA_TEST_AREA[@]}"; do
    name="${POCKETSHELL_TA_TEST_AREA[$i]}"
    [[ -n "${POCKETSHELL_TA_AREA_TIER[$name]:-}" ]] || \
      POCKETSHELL_TA_LOAD_ERRORS+=("test rule '${POCKETSHELL_TA_TEST_GLOB[$i]}' names undeclared area '$name'")
  done
  for name in "${!POCKETSHELL_TA_CLASS_AREA[@]}"; do
    [[ -n "${POCKETSHELL_TA_AREA_TIER[${POCKETSHELL_TA_CLASS_AREA[$name]}]:-}" ]] || \
      POCKETSHELL_TA_LOAD_ERRORS+=("class override '$name' names undeclared area '${POCKETSHELL_TA_CLASS_AREA[$name]}'")
  done
  local from to
  for from in "${!POCKETSHELL_TA_COUPLE[@]}"; do
    [[ -n "${POCKETSHELL_TA_AREA_TIER[$from]:-}" ]] || \
      POCKETSHELL_TA_LOAD_ERRORS+=("couple source '$from' is not a declared area")
    local IFS=','
    for to in ${POCKETSHELL_TA_COUPLE[$from]}; do
      [[ -n "${POCKETSHELL_TA_AREA_TIER[$to]:-}" ]] || \
        POCKETSHELL_TA_LOAD_ERRORS+=("couple '$from' -> undeclared area '$to'")
    done
  done

  if [[ "${#POCKETSHELL_TA_AREAS[@]}" -eq 0 ]]; then
    POCKETSHELL_TA_LOAD_ERRORS+=("manifest declares no areas")
  fi

  [[ "${#POCKETSHELL_TA_LOAD_ERRORS[@]}" -eq 0 ]]
}

# True when the path lives inside a Gradle test source set.
pocketshell_test_areas_is_test_path() {
  case "$1" in
    */src/test/*|*/src/androidTest/*|*/src/integrationTest/*|*/src/testDebug/*|*/src/testRelease/*)
      return 0 ;;
    *) return 1 ;;
  esac
}

# True when the basename follows the repo's test-class naming convention.
#
# Every registry, guard and Gradle filter in this repo keys off that
# convention, so it is the definition used here too. Kotlin AND Java: the
# vendored Termux terminal suite under shared/core-terminal is `*Test.java`,
# and treating those 19 classes as "not tests" silently force-fulled them and
# left them out of the ledger's registered set.
pocketshell_test_areas_is_test_class_file() {
  case "$1" in
    *Test.kt|*Test.java|*Tests.kt|*Tests.java) return 0 ;;
    *) return 1 ;;
  esac
}

# Repo-relative test source path -> fully-qualified class name, or empty when
# the path is not a Kotlin/Java test class file.
pocketshell_test_areas_fqcn_for_path() {
  local p="$1" rel
  pocketshell_test_areas_is_test_path "$p" || { printf ''; return 1; }
  pocketshell_test_areas_is_test_class_file "$p" || { printf ''; return 1; }
  # Strip everything up to and including the source set + source-root dir.
  case "$p" in
    */src/androidTest/*)     rel="${p##*/src/androidTest/}" ;;
    */src/integrationTest/*) rel="${p##*/src/integrationTest/}" ;;
    */src/testDebug/*)       rel="${p##*/src/testDebug/}" ;;
    */src/testRelease/*)     rel="${p##*/src/testRelease/}" ;;
    *)                       rel="${p##*/src/test/}" ;;
  esac
  rel="${rel#java/}"
  rel="${rel#kotlin/}"
  rel="${rel%.kt}"
  rel="${rel%.java}"
  printf '%s\n' "${rel//\//.}"
}

# THE classifier. Sets the POCKETSHELL_TEST_AREA_* globals.
#
# Note the very first assignment: `full`. Every later branch must actively
# prove a narrower answer. Deleting any branch below makes the classifier more
# conservative, never less.
pocketshell_test_area_classify() {
  local p="$1"
  POCKETSHELL_TEST_AREA_KIND="full"
  POCKETSHELL_TEST_AREA_NAME=""
  POCKETSHELL_TEST_AREA_REASON="unmapped-path"
  POCKETSHELL_TEST_AREA_DEVGATE="full"

  local i
  # 1. Explicit force-full rows.
  for i in "${!POCKETSHELL_TA_FULL_GLOB[@]}"; do
    # shellcheck disable=SC2053
    if [[ "$p" == ${POCKETSHELL_TA_FULL_GLOB[$i]} ]]; then
      POCKETSHELL_TEST_AREA_REASON="full-rule:${POCKETSHELL_TA_FULL_GLOB[$i]}"
      return 0
    fi
  done

  # 2. Test infrastructure: anything in a test source set that is not itself a
  #    test class is a shared fixture, helper, rule, base class or resource.
  if pocketshell_test_areas_is_test_path "$p"; then
    if ! pocketshell_test_areas_is_test_class_file "$p"; then
      POCKETSHELL_TEST_AREA_REASON="test-infrastructure(non-*Test file in a test source set)"
      return 0
    fi
  fi

  # 3. Provably inert paths.
  for i in "${!POCKETSHELL_TA_NOOP_GLOB[@]}"; do
    # shellcheck disable=SC2053
    if [[ "$p" == ${POCKETSHELL_TA_NOOP_GLOB[$i]} ]]; then
      POCKETSHELL_TEST_AREA_KIND="noop"
      POCKETSHELL_TEST_AREA_REASON="noop-rule:${POCKETSHELL_TA_NOOP_GLOB[$i]}"
      POCKETSHELL_TEST_AREA_DEVGATE="full"
      return 0
    fi
  done

  # 4. Per-class overrides (a test class pinned to an area other than its
  #    package's default, e.g. the conversation-source cluster that lives in
  #    the app.tmux package but belongs to the conversation-agents area).
  local fqcn=""
  if pocketshell_test_areas_is_test_path "$p"; then
    fqcn="$(pocketshell_test_areas_fqcn_for_path "$p")" || fqcn=""
    if [[ -n "$fqcn" && -n "${POCKETSHELL_TA_CLASS_AREA[$fqcn]:-}" ]]; then
      POCKETSHELL_TEST_AREA_KIND="area"
      POCKETSHELL_TEST_AREA_NAME="${POCKETSHELL_TA_CLASS_AREA[$fqcn]}"
      POCKETSHELL_TEST_AREA_REASON="class-override:$fqcn"
      POCKETSHELL_TEST_AREA_DEVGATE="full"
      # Fall through to the test globs only to pick up the devgate stage.
      for i in "${!POCKETSHELL_TA_TEST_GLOB[@]}"; do
        # shellcheck disable=SC2053
        if [[ "$p" == ${POCKETSHELL_TA_TEST_GLOB[$i]} ]]; then
          POCKETSHELL_TEST_AREA_DEVGATE="${POCKETSHELL_TA_TEST_DEVGATE[$i]}"
          break
        fi
      done
      return 0
    fi
    # 5. Test globs.
    for i in "${!POCKETSHELL_TA_TEST_GLOB[@]}"; do
      # shellcheck disable=SC2053
      if [[ "$p" == ${POCKETSHELL_TA_TEST_GLOB[$i]} ]]; then
        POCKETSHELL_TEST_AREA_KIND="area"
        POCKETSHELL_TEST_AREA_NAME="${POCKETSHELL_TA_TEST_AREA[$i]}"
        POCKETSHELL_TEST_AREA_REASON="test-rule:${POCKETSHELL_TA_TEST_GLOB[$i]}"
        POCKETSHELL_TEST_AREA_DEVGATE="${POCKETSHELL_TA_TEST_DEVGATE[$i]}"
        return 0
      fi
    done
    # A test file that matches no test rule stays `full` (the initial value).
    POCKETSHELL_TEST_AREA_REASON="unmapped-test-path"
    return 0
  fi

  # 6. Production globs.
  for i in "${!POCKETSHELL_TA_SRC_GLOB[@]}"; do
    # shellcheck disable=SC2053
    if [[ "$p" == ${POCKETSHELL_TA_SRC_GLOB[$i]} ]]; then
      POCKETSHELL_TEST_AREA_KIND="area"
      POCKETSHELL_TEST_AREA_NAME="${POCKETSHELL_TA_SRC_AREA[$i]}"
      POCKETSHELL_TEST_AREA_REASON="src-rule:${POCKETSHELL_TA_SRC_GLOB[$i]}"
      POCKETSHELL_TEST_AREA_DEVGATE="${POCKETSHELL_TA_SRC_DEVGATE[$i]}"
      return 0
    fi
  done

  # 7. Fall-through: full. (Already set at the top of the function.)
  return 0
}

# ===========================================================================
# THE INDEX — ONE resolver, and the import-derived dependency sets
#
# WHY THIS REPLACED TWO RESOLVERS (#2063 round-1 finding B4)
#
# Round 1 shipped two disagreeing resolvers: `--verify-manifest` resolved a test
# class from its tracked FILE PATH (correct for every module), while the ledger
# guard resolved from the FQCN by probing a hardcoded list of source roots. That
# list omitted every `shared/*/src/test/java` root, so 183 of 1047 registered
# classes — all 49 core-ssh and all 22 core-connection D28 classes among them —
# resolved to NO area and the ledger guard was red on the real tree while its
# self-test (synthetic app-style trees only) stayed green. Two resolvers is the
# defect; there is now exactly one, and it is built from `git ls-files`, so a new
# module or source set cannot be missed by a list nobody remembered to extend.
#
# WHY THE INDEX ALSO CARRIES IMPORT-DERIVED DEPENDENCIES (finding B2)
#
# Round 1 decided what to run purely from hand-written `couple` rows between
# areas. Two of those rows were CLAIMED in the write-up and absent from the
# manifest, so 29 registered journeys that compile against connection-core
# production types — `ForwardingNetworkRideThroughE2eTest` (drives the real
# `TerminalNetworkObserver`), `ConnectionLogHostMirrorReconnectDockerTest` (#969,
# constructs `ActiveTmuxClients()`), `Issue1876FolderListMobileRttDockerTest`
# (#1876, D34) — were NOT selected by a connection-core change. Under D28 that is
# the worst possible place to under-select.
#
# The cure is to stop hand-listing compile-level coupling. Every test class now
# carries a DEPENDENCY SET derived from its own `import com.pocketshell.…` lines:
# the areas of the production packages it actually compiles against. A class is
# selected when any area it depends on changed, whether or not anybody wrote a
# `couple` row. The hand rows remain for BEHAVIOURAL coupling that no import can
# express (composer-always-present across screens, ui-kit tokens reaching every
# surface) — they are additive, never the only mechanism.
#
# WHY DEPENDENCIES ARE PER-CLASS AND ONE HOP, STATED RATHER THAN HIDDEN
#
# Two stronger-looking designs were measured on this tree and both degenerate:
#
#   * area-level derived edges, applied transitively: the 79 derived edges make
#     the area graph strongly connected, so EVERY change selects 17 of 19 areas.
#   * production-to-production transitive closure (test -> prodA -> prodX): the
#     93-edge production area graph is strongly connected too (composer-voice ⇄
#     connection-core ⇄ hosts-settings ⇄ …), so it also degenerates to full.
#
# So the rule is deliberately the DIRECT compile edge, applied per test CLASS:
# "if area X changed, run every test class that imports X's production code."
# It does not chase transitive production dependencies — a test that only
# imports `fileviewer`, whose implementation imports `core-ssh`, is not selected
# by a core-ssh change through that chain. That bound is real, it is the reason
# the numbers are what they are, and the backstops are the always tier, the
# nightly full run and the release gate. Do not silently widen or narrow it;
# change it deliberately and re-measure.
#
# HOST CLI: THE ONE COUPLING NO KOTLIN IMPORT CAN EXPRESS (finding B1)
#
# `tools/pocketshell/**` is Python. No Kotlin file imports it, so the import
# graph says a host-CLI change affects nothing — which is exactly how round 1
# let a `tools/pocketshell/src/pocketshell/tree.py` change run ZERO of the
# journeys built to catch host-CLI/client mismatch, including
# `FolderListHostOutdatedTreeVersionDaemonDockerTest` (#1509, G10). That is the
# #847 / v0.4.10 class: host-CLI/client version is a RUNTIME lockstep, and a new
# client calling a new subcommand against an older host CLI hangs.
#
# A WIRE CONTRACT HAS TWO ENDS, AND ROUND 2 MARKED ONLY ONE (finding B6)
#
# Round 2's seam was "packages that INVOKE the CLI". That is the PRODUCER end of
# the request. It marked 15 :app packages and — structurally, not by accident —
# ZERO packages in `shared/*`, because no shared module ever shells out. So
# `shared/core-usage`, whose `PocketshellUsageJsonParser` is the deliberately
# STRICT / fail-loud reader of the NDJSON that `tools/pocketshell/.../usage.py`
# emits, was not on the seam and `:shared:core-usage:test` did not run on a
# host-CLI change. Same shape as #1509, moved from `tree` to `usage`: the
# instance was fixed and the class survived.
#
# The seam now marks BOTH ends, each derived mechanically:
#
#   (a) PRODUCER / INVOKER end — a production file that invokes the CLI
#       (`PocketshellCommand`, or a literal `pocketshell …` command string).
#       Its package is on the seam. Unchanged from round 2.
#
#   (b) CONSUMER / REPLY end — a production package that an INVOKING FILE
#       imports. The invoker builds the request and hands the reply to
#       something; that something is in the invoking file's own import set.
#       This is what reaches `com.pocketshell.core.usage` (UsageRemoteSource
#       imports PocketshellUsageJsonParser), `com.pocketshell.core.storage.entity`
#       (HostEntity carries the CLI lockstep version columns) and
#       `com.pocketshell.uikit.model` (HostSetupState renders the CLI
#       missing/incompatible states) — the three holes the round-2 review named,
#       plus the rest of their class.
#
#       ONE HOP, no transitive closure — the same bound rule 4 accepts and for
#       the same measured reason (the production graph is strongly connected, so
#       a closure degenerates to "run everything"). Deliberately unfiltered: the
#       hop also picks up collaborators that are not wire consumers (theme
#       tokens imported by an invoking Composable). That over-selection costs
#       test time on a `tools/pocketshell/**` change only, because `host-cli` is
#       a changed-tier area that nothing else puts in the diff. A false positive
#       there is minutes; a false negative is #847.
#
#   (c) VOCABULARY end — a production package whose source NAMES a real host-CLI
#       subcommand (`pocketshell <subcommand>`), where the subcommand vocabulary
#       is read from the live Click group in
#       `tools/pocketshell/src/pocketshell/cli.py`.
#
#       (b) cannot be the whole consumer rule because a wire contract does not
#       need an in-app invoker at all. `com.pocketshell.app.settings` parses the
#       payload that `pocketshell qr-share` produces, and that payload arrives by
#       QR scan / clipboard — no exec, so no invoking file imports the settings
#       package and no import edge exists to follow. (c) is what catches that
#       category. Taking the vocabulary from the Python side rather than from a
#       loose grep is what keeps it honest: `pocketshell-voice-secrets` (a
#       DataStore file name) and "the `pocketshell` daemon registry" (prose) are
#       not subcommands and do not match.
#
#       The reader imports `cli.py`, verifies that its exported `cli` is a live
#       `click.Group`, and asks that object for `list_commands()`. This is the
#       producer's runtime contract, so decorator aliases, sibling registration,
#       command-table writes, and dynamically synthesized commands all have one
#       source of truth. Import-time failures are UNREADABLE and fail the guard;
#       a missing terminator (including a missing interpreter or killed reader)
#       is also unreadable. There is no AST fallback: an incomplete import must
#       never become a smaller-but-plausible vocabulary.
#
# A package on the seam by ANY of the three contributes `host-cli` to the
# dependency set of every test that imports it or sits in it.
#
# Each end has its OWN substantive floor in `--verify-manifest` check 7,
# including a floor on how far the seam reaches into `shared/*`, because the
# round-2 defect was invisible to a total-only floor: the total was 15 packages
# / 569 classes while the shared reach was zero.
POCKETSHELL_TA_HOSTCLI_MARKER="${POCKETSHELL_TA_HOSTCLI_MARKER:-PocketshellCommand|\"pocketshell |pocketshell --}"
# The producer-side source of the subcommand vocabulary for end (c). Overridable
# only so the self-test can point the live reader at a mutation. An absolute path
# is used as-is; a relative one is resolved against the repo root. The package
# source root is kept separate so a self-test can import a mutated `cli.py` while
# resolving its normal `pocketshell.*` dependencies from the checkout.
POCKETSHELL_TA_HOSTCLI_CLI_SOURCE="${POCKETSHELL_TA_HOSTCLI_CLI_SOURCE:-tools/pocketshell/src/pocketshell/cli.py}"
POCKETSHELL_TA_HOSTCLI_PACKAGE_ROOT="${POCKETSHELL_TA_HOSTCLI_PACKAGE_ROOT:-}"

declare -A POCKETSHELL_TA_PROD_PKG_AREA=()      # production package -> area
declare -A POCKETSHELL_TA_PROD_PKG_HOSTCLI=()   # production package -> 1 when on the CLI wire seam
declare -A POCKETSHELL_TA_HOSTCLI_END=()        # production package -> "invoker" | "consumer" | "vocabulary" (first end that marked it)
declare -A POCKETSHELL_TA_PROD_PKG_SHARED=()    # production package -> 1 when it lives under shared/
declare -A POCKETSHELL_TA_CLASS_PATH=()         # test FQCN -> tracked path
declare -A POCKETSHELL_TA_CLASS_RESOLVED=()     # test FQCN -> area
declare -A POCKETSHELL_TA_CLASS_DEPS=()         # test FQCN -> " area area … "
declare -A POCKETSHELL_TA_PKG_TEST_AREA=()      # test package -> area (sibling fallback)
declare -A POCKETSHELL_TA_PKG_TEST_DEPS=()      # test package -> union deps (sibling fallback)
declare -A POCKETSHELL_TA_RESOLVE_CACHE=()      # test FQCN -> resolved area (memo of THE resolver)
declare -A POCKETSHELL_TA_DEPS_CACHE=()         # test FQCN -> dep set (memo of THE deps resolver)
declare -A POCKETSHELL_TA_CLASS_MODULE=()       # test FQCN -> gradle project path
declare -A POCKETSHELL_TA_CLASS_SOURCESET=()    # test FQCN -> test | androidTest | integrationTest

POCKETSHELL_TA_INDEX_BUILT=0
POCKETSHELL_TA_INDEX_CLASSES=0
POCKETSHELL_TA_INDEX_IMPORT_LINES=0
POCKETSHELL_TA_INDEX_CROSS_AREA_CLASSES=0
POCKETSHELL_TA_INDEX_PROD_PKGS=0
POCKETSHELL_TA_INDEX_HOSTCLI_PKGS=0
POCKETSHELL_TA_INDEX_HOSTCLI_CLASSES=0
POCKETSHELL_TA_INDEX_HOSTCLI_INVOKER_PKGS=0
POCKETSHELL_TA_INDEX_HOSTCLI_CONSUMER_PKGS=0
POCKETSHELL_TA_INDEX_HOSTCLI_VOCAB_PKGS=0
POCKETSHELL_TA_INDEX_HOSTCLI_SHARED_PKGS=0
POCKETSHELL_TA_INDEX_HOSTCLI_SUBCOMMANDS=0
POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNREADABLE=0
POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG=""

# Mark ONE production package as on the host-CLI wire seam, attributed to the
# end that found it first, and keep that end's counter. Attribution exists so
# `--verify-manifest` check 7 can floor each end separately: the round-2 defect
# (finding B6) was a seam whose TOTAL looked healthy at 15 packages while one
# whole end — and with it all of `shared/*` — contributed zero.
_pocketshell_hostcli_mark() {
  local p="$1" end="$2"
  [[ -n "${POCKETSHELL_TA_PROD_PKG_AREA[$p]:-}" ]] || return 0
  POCKETSHELL_TA_PROD_PKG_HOSTCLI["$p"]=1
  # Ends ACCUMULATE rather than first-wins: each end needs its own honest count,
  # or a floor on end X silently passes because end Y happened to mark the same
  # package first. (That shadowing is a miniature of the B6 defect itself.)
  [[ " ${POCKETSHELL_TA_HOSTCLI_END[$p]:-} " == *" $end "* ]] || \
    POCKETSHELL_TA_HOSTCLI_END["$p"]="${POCKETSHELL_TA_HOSTCLI_END[$p]:+${POCKETSHELL_TA_HOSTCLI_END[$p]} }$end"
  return 0
}

# Recount the per-end and shared-reach seam totals from the accumulated ends.
_pocketshell_hostcli_recount() {
  local p e
  POCKETSHELL_TA_INDEX_HOSTCLI_INVOKER_PKGS=0
  POCKETSHELL_TA_INDEX_HOSTCLI_CONSUMER_PKGS=0
  POCKETSHELL_TA_INDEX_HOSTCLI_VOCAB_PKGS=0
  POCKETSHELL_TA_INDEX_HOSTCLI_SHARED_PKGS=0
  for p in "${!POCKETSHELL_TA_PROD_PKG_HOSTCLI[@]}"; do
    for e in ${POCKETSHELL_TA_HOSTCLI_END[$p]:-}; do
      case "$e" in
        invoker)    POCKETSHELL_TA_INDEX_HOSTCLI_INVOKER_PKGS=$((POCKETSHELL_TA_INDEX_HOSTCLI_INVOKER_PKGS + 1)) ;;
        consumer)   POCKETSHELL_TA_INDEX_HOSTCLI_CONSUMER_PKGS=$((POCKETSHELL_TA_INDEX_HOSTCLI_CONSUMER_PKGS + 1)) ;;
        vocabulary) POCKETSHELL_TA_INDEX_HOSTCLI_VOCAB_PKGS=$((POCKETSHELL_TA_INDEX_HOSTCLI_VOCAB_PKGS + 1)) ;;
      esac
    done
    [[ -n "${POCKETSHELL_TA_PROD_PKG_SHARED[$p]:-}" ]] && \
      POCKETSHELL_TA_INDEX_HOSTCLI_SHARED_PKGS=$((POCKETSHELL_TA_INDEX_HOSTCLI_SHARED_PKGS + 1))
  done
  POCKETSHELL_TA_INDEX_HOSTCLI_PKGS="${#POCKETSHELL_TA_PROD_PKG_HOSTCLI[@]}"
  return 0
}

# Read the host-CLI subcommand vocabulary out of the producer (finding B9).
#
# $1 = absolute path to `cli.py`. Emits a line-oriented protocol on stdout:
#
#   NAME <subcommand>    one name returned by the live Click Group
#   UNREADABLE <reason>  an import, type, command-name, or list_commands failure
#   END                  the reader ran to completion
#
# The caller treats a MISSING `END` as unreadable, which is what makes "python3
# is not installed", "python3 crashed" and "the reader was killed" fail closed.
# Nothing here ever returns a smaller-but-plausible vocabulary: an import or
# runtime failure is an ERROR, because a smaller number is exactly what a floor
# cannot distinguish from normal drift.
_pocketshell_hostcli_read_cli_vocabulary() {
  local src="$1"
  local package_root="${POCKETSHELL_TA_HOSTCLI_PACKAGE_ROOT:-}"
  local repo_root="${POCKETSHELL_TA_REPO_ROOT:-}"

  if [[ ! -f "$src" ]]; then
    printf 'UNREADABLE no such CLI source: %s\nEND\n' "$src"
    return 0
  fi
  if [[ -z "$package_root" ]]; then
    if [[ -n "$repo_root" ]]; then
      package_root="$repo_root/tools/pocketshell/src"
    elif [[ "$src" == */pocketshell/cli.py ]]; then
      package_root="${src%/pocketshell/cli.py}"
    else
      package_root="$(pwd)"
    fi
  fi
  if [[ "$package_root" != /* ]]; then
    package_root="${repo_root:-.}/$package_root"
  fi
  if [[ ! -d "$package_root" ]]; then
    printf 'UNREADABLE cannot import %s: package source root does not exist: %s\nEND\n' \
      "$src" "$package_root"
    return 0
  fi

  POCKETSHELL_TA_HOSTCLI_PACKAGE_ROOT="$package_root" \
    python3 - "$src" "$package_root" 2>/dev/null <<'PY'
import importlib.util
import os
import re
import sys

path = os.path.abspath(sys.argv[1])
package_root = os.path.abspath(sys.argv[2])
subcommand_token = re.compile(r"^[a-z0-9][a-z0-9-]*$")


def unreadable(reason):
    print("UNREADABLE " + str(reason).replace("\n", " "))
    print("END")
    raise SystemExit(0)


try:
    import click
except BaseException as exc:  # noqa: BLE001 - missing import-time dependency is a guard error
    unreadable(
        "cannot import %s: %s: %s"
        % (path, type(exc).__name__, exc)
    )

try:
    sys.path.insert(0, package_root)
    spec = importlib.util.spec_from_file_location("_pocketshell_live_cli", path)
    if spec is None or spec.loader is None:
        raise ImportError("no import loader for %s" % path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
except BaseException as exc:  # noqa: BLE001 - cli.py must be safe to import
    unreadable(
        "cannot import %s: %s: %s"
        % (path, type(exc).__name__, exc)
    )

group = getattr(module, "cli", None)
if not isinstance(group, click.Group):
    unreadable(
        "%s does not export cli as click.Group (got %s)"
        % (path, type(group).__name__)
    )

try:
    context = click.Context(group)
    live_names = list(group.list_commands(context))
except BaseException as exc:  # noqa: BLE001 - list_commands is the live oracle
    unreadable(
        "cannot read live commands from %s: %s: %s"
        % (path, type(exc).__name__, exc)
    )

seen = set()
for name in live_names:
    if not isinstance(name, str):
        unreadable(
            "live command name from %s is not a string (%s)"
            % (path, type(name).__name__)
        )
    if not subcommand_token.fullmatch(name):
        unreadable(
            "live command name from %s is not a plain subcommand token: %r"
            % (path, name)
        )
    if name in seen:
        unreadable("live command list from %s contains duplicate %r" % (path, name))
    seen.add(name)

for name in sorted(live_names):
    print("NAME " + name)
print("END")
PY
}
# Build (once) the whole index. Idempotent; call it before any FQCN lookup.
pocketshell_test_areas_build_index() {
  [[ "$POCKETSHELL_TA_INDEX_BUILT" -eq 1 ]] && return 0
  POCKETSHELL_TA_INDEX_BUILT=1

  local root="${POCKETSHELL_TA_REPO_ROOT:-.}"
  local f d pkg fqcn area

  # 1. production package -> area, by classifying a synthetic probe file in each
  #    production directory. A directory whose probe classifies force-full (di/,
  #    nav/, …) is intentionally absent: importing from it cannot narrow a run.
  local -a prod_dirs=()
  mapfile -t prod_dirs < <(
    git -C "$root" ls-files 2>/dev/null |
      grep -E '/src/main/(java|kotlin)/.*\.(kt|java)$' |
      sed 's|/[^/]*$||' | LC_ALL=C sort -u
  )
  for d in "${prod_dirs[@]}"; do
    [[ -z "$d" ]] && continue
    pkg="$d"; pkg="${pkg#*/src/main/java/}"; pkg="${pkg#*/src/main/kotlin/}"
    pocketshell_test_area_classify "$d/PocketshellAreaProbe.kt"
    [[ "$POCKETSHELL_TEST_AREA_KIND" == "area" ]] || continue
    POCKETSHELL_TA_PROD_PKG_AREA["${pkg//\//.}"]="$POCKETSHELL_TEST_AREA_NAME"
    [[ "$d" == shared/* ]] && POCKETSHELL_TA_PROD_PKG_SHARED["${pkg//\//.}"]=1
  done
  POCKETSHELL_TA_INDEX_PROD_PKGS="${#POCKETSHELL_TA_PROD_PKG_AREA[@]}"

  # 2. the host-CLI wire seam — BOTH ENDS of the contract (see the block comment
  #    above for why one end is not enough). Each end is derived mechanically and
  #    counted separately so its own floor can catch its own erosion.
  local -a prod_files=()
  mapfile -t prod_files < <(
    git -C "$root" ls-files 2>/dev/null | grep -E '/src/main/(java|kotlin)/.*\.(kt|java)$'
  )

  # (a) PRODUCER end: files that invoke the CLI. Their packages are the seam,
  #     and the file list is also the anchor for end (b).
  local -a invoker_files=()
  if [[ "${#prod_files[@]}" -gt 0 ]]; then
    mapfile -t invoker_files < <(
      printf '%s\n' "${prod_files[@]}" |
        (cd "$root" && xargs -d '\n' -r grep -lE "$POCKETSHELL_TA_HOSTCLI_MARKER" 2>/dev/null) || true
    )
  fi
  for f in "${invoker_files[@]}"; do
    [[ -z "$f" ]] && continue
    d="${f%/*}"
    pkg="$d"; pkg="${pkg#*/src/main/java/}"; pkg="${pkg#*/src/main/kotlin/}"
    _pocketshell_hostcli_mark "${pkg//\//.}" invoker
  done

  # (b) CONSUMER end: the production packages an INVOKING FILE imports. One hop.
  if [[ "${#invoker_files[@]}" -gt 0 ]]; then
    local impline imp
    while IFS= read -r impline; do
      imp="${impline#*:}"
      imp="${imp#"${imp%%[![:space:]]*}"}"
      imp="${imp#import }"
      imp="${imp#"${imp%%[![:space:]]*}"}"
      imp="${imp%%[!A-Za-z0-9_.]*}"
      [[ "$imp" == *.* ]] || continue
      _pocketshell_hostcli_mark "${imp%.*}" consumer
    done < <(
      printf '%s\n' "${invoker_files[@]}" |
        (cd "$root" && xargs -d '\n' -r grep -hE '^[[:space:]]*import[[:space:]]+com\.pocketshell\.' 2>/dev/null) || true
    )
  fi

  # (c) VOCABULARY end: packages naming a real subcommand, where "real" comes
  #     from the live Click Group exported by the producer's `cli.py`.
  local cli_src="$POCKETSHELL_TA_HOSTCLI_CLI_SOURCE"
  [[ "$cli_src" == /* ]] || cli_src="$root/$cli_src"
  POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNREADABLE=0
  POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG=""
  local -a subcommands=() vocab_lines=()
  local vline saw_end=0
  mapfile -t vocab_lines < <(_pocketshell_hostcli_read_cli_vocabulary "$cli_src")
  for vline in "${vocab_lines[@]}"; do
    case "$vline" in
      END) saw_end=1 ;;
      "NAME "*)       subcommands+=("${vline#NAME }") ;;
      "UNREADABLE "*)
        POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNREADABLE=$((POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNREADABLE + 1))
        POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG="${POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG:+$POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG; }${vline#UNREADABLE }"
        ;;
    esac
  done
  if [[ "$saw_end" -ne 1 ]]; then
    # No terminator => the reader never finished (no python3, a crash, a kill).
    # Discard whatever partial output arrived rather than seeding the seam from
    # half a vocabulary: a partial read is the exact failure this end exists to
    # make loud.
    subcommands=()
    POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNREADABLE=$((POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNREADABLE + 1))
    POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG="${POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG:+$POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG; }the vocabulary reader did not finish for $cli_src (python3 missing, crashed, or killed)"
  fi
  # Count the live Group's returned names before the grep regex deduplicates
  # them. Duplicate names are rejected by the reader, so this is the complete
  # runtime vocabulary rather than a static registration-site estimate.
  POCKETSHELL_TA_INDEX_HOSTCLI_SUBCOMMANDS="${#subcommands[@]}"
  if [[ "${#subcommands[@]}" -gt 0 && "${#prod_files[@]}" -gt 0 ]]; then
    # Longest-first so `agent-log` cannot be shadowed by `agent`.
    local vocab_re="" sc
    while IFS= read -r sc; do
      [[ -z "$sc" ]] && continue
      vocab_re="${vocab_re:+$vocab_re|}$sc"
    done < <(printf '%s\n' "${subcommands[@]}" | LC_ALL=C sort -u | awk '{print length"\t"$0}' | LC_ALL=C sort -rn | cut -f2-)
    while IFS= read -r f; do
      [[ -z "$f" ]] && continue
      d="${f%/*}"
      pkg="$d"; pkg="${pkg#*/src/main/java/}"; pkg="${pkg#*/src/main/kotlin/}"
      _pocketshell_hostcli_mark "${pkg//\//.}" vocabulary
    done < <(
      printf '%s\n' "${prod_files[@]}" |
        (cd "$root" && xargs -d '\n' -r grep -lE "pocketshell +($vocab_re)([^A-Za-z0-9_-]|\$)" 2>/dev/null) || true
    )
  fi
  _pocketshell_hostcli_recount

  # 3. test class -> area.
  #
  #    Classified per DIRECTORY (111 of them, not 1042 files) and only per FILE
  #    where a file-name-shaped rule could actually change the answer — the :app
  #    root `+([A-Za-z0-9_])Test.kt` row is the one such rule today, and
  #    POCKETSHELL_TA_FILE_SHAPED_GLOB is computed from the manifest rather than
  #    hardcoded so a future file-shaped row cannot be silently averaged away by
  #    its directory's answer. Everything here is fork-free on purpose: a
  #    command substitution per file is 3000 forks and dominates the runtime.
  local -a test_files=()
  mapfile -t test_files < <(
    git -C "$root" ls-files 2>/dev/null |
      grep -E '/src/(test|androidTest|integrationTest|testDebug|testRelease)/(java|kotlin)/.*(Test|Tests)\.(kt|java)$'
  )
  local -A dir_area=() dir_exact=()
  local g rel pre srcset pa
  for f in "${test_files[@]}"; do
    [[ -z "$f" ]] && continue
    d="${f%/*}"
    if [[ -z "${dir_area[$d]+x}" ]]; then
      pocketshell_test_area_classify "$d/PocketshellAreaProbeTest.kt"
      if [[ "$POCKETSHELL_TEST_AREA_KIND" == "area" ]]; then
        dir_area["$d"]="$POCKETSHELL_TEST_AREA_NAME"
      else
        dir_area["$d"]=""
      fi
      # If the directory probe itself was decided by a file-shaped rule, no
      # directory-wide answer exists here; every file must be classified.
      dir_exact["$d"]=0
      for g in "${POCKETSHELL_TA_FILE_SHAPED_GLOB[@]:-}"; do
        [[ -z "$g" ]] && continue
        # shellcheck disable=SC2053
        if [[ "$d/PocketshellAreaProbeTest.kt" == $g ]]; then dir_exact["$d"]=1; break; fi
      done
    fi
    area="${dir_area[$d]}"
    if [[ "${dir_exact[$d]}" -eq 1 ]]; then
      area=""
      pocketshell_test_area_classify "$f"
      [[ "$POCKETSHELL_TEST_AREA_KIND" == "area" ]] && area="$POCKETSHELL_TEST_AREA_NAME"
    else
      for g in "${POCKETSHELL_TA_FILE_SHAPED_GLOB[@]:-}"; do
        [[ -z "$g" ]] && continue
        # shellcheck disable=SC2053
        if [[ "$f" == $g ]]; then
          area=""
          pocketshell_test_area_classify "$f"
          [[ "$POCKETSHELL_TEST_AREA_KIND" == "area" ]] && area="$POCKETSHELL_TEST_AREA_NAME"
          break
        fi
      done
    fi

    # inline fqcn / module / source-set derivation (no forks)
    case "$f" in
      */src/androidTest/*)     rel="${f##*/src/androidTest/}";     srcset="androidTest" ;;
      */src/integrationTest/*) rel="${f##*/src/integrationTest/}"; srcset="integrationTest" ;;
      */src/testDebug/*)       rel="${f##*/src/testDebug/}";       srcset="testDebug" ;;
      */src/testRelease/*)     rel="${f##*/src/testRelease/}";     srcset="testRelease" ;;
      *)                       rel="${f##*/src/test/}";            srcset="test" ;;
    esac
    rel="${rel#java/}"; rel="${rel#kotlin/}"; rel="${rel%.kt}"; rel="${rel%.java}"
    fqcn="${rel//\//.}"
    [[ -z "$fqcn" ]] && continue
    # `class` overrides beat everything, including the directory answer.
    [[ -n "${POCKETSHELL_TA_CLASS_AREA[$fqcn]:-}" ]] && area="${POCKETSHELL_TA_CLASS_AREA[$fqcn]}"
    pre="${f%%/src/*}"

    POCKETSHELL_TA_CLASS_PATH["$fqcn"]="$f"
    POCKETSHELL_TA_CLASS_RESOLVED["$fqcn"]="$area"
    POCKETSHELL_TA_CLASS_DEPS["$fqcn"]="${area:+ $area }"
    POCKETSHELL_TA_CLASS_MODULE["$fqcn"]=":${pre//\//:}"
    POCKETSHELL_TA_CLASS_SOURCESET["$fqcn"]="$srcset"
    pkg="${fqcn%.*}"
    [[ -n "$area" ]] && POCKETSHELL_TA_PKG_TEST_AREA["$pkg"]="$area"

    # SAME-PACKAGE production access needs no `import` line, so the import scan
    # alone cannot see it. That is not a corner case: it is exactly why round 1
    # missed `FolderListHostOutdatedTreeVersionDaemonDockerTest` (#1509) — the
    # test sits in `com.pocketshell.app.projects` and reaches `TreeRemoteSource`
    # in the same package, so no import line mentions the host-CLI wire seam.
    if [[ -n "${POCKETSHELL_TA_PROD_PKG_AREA[$pkg]:-}" ]]; then
      pa="${POCKETSHELL_TA_PROD_PKG_AREA[$pkg]}"
      [[ "${POCKETSHELL_TA_CLASS_DEPS[$fqcn]}" == *" $pa "* ]] || \
        POCKETSHELL_TA_CLASS_DEPS["$fqcn"]="${POCKETSHELL_TA_CLASS_DEPS[$fqcn]:- }$pa "
    fi
    if [[ -n "${POCKETSHELL_TA_PROD_PKG_HOSTCLI[$pkg]:-}" ]]; then
      [[ "${POCKETSHELL_TA_CLASS_DEPS[$fqcn]}" == *" host-cli "* ]] || \
        POCKETSHELL_TA_CLASS_DEPS["$fqcn"]="${POCKETSHELL_TA_CLASS_DEPS[$fqcn]:- }host-cli "
    fi
  done
  POCKETSHELL_TA_INDEX_CLASSES="${#POCKETSHELL_TA_CLASS_PATH[@]}"

  # 4. import-derived dependency sets. ONE grep over every test class file.
  if [[ "${#test_files[@]}" -gt 0 ]]; then
    local line imp p pa path_to_fqcn
    local -A file_fqcn=()
    for fqcn in "${!POCKETSHELL_TA_CLASS_PATH[@]}"; do
      file_fqcn["${POCKETSHELL_TA_CLASS_PATH[$fqcn]}"]="$fqcn"
    done
    # THE HOTTEST LOOP IN THE INDEX, and why it is awk.
    #
    # 5709 import lines x (peel the dotted name, look up the area, dedupe into
    # the class's dep set) is ~1.0s of the index's ~1.9s in pure bash, and the
    # guards rebuild the index in ~20 subprocesses per Unit variant. Folding the
    # per-LINE work into one awk pass that emits one deduped line per FILE takes
    # the bash side from 5709 iterations to <=1046 — same answer, and the guard's
    # cost on the Unit job is what the round-2 review (rightly) pushed back on.
    #
    # awk gets the production-package map as its FIRST input and the grep stream
    # as its second. It emits:  <path> TAB <import-count> TAB <area> <area> …
    local awk_map
    awk_map=""
    for p in "${!POCKETSHELL_TA_PROD_PKG_AREA[@]}"; do
      awk_map+="M	$p	${POCKETSHELL_TA_PROD_PKG_AREA[$p]}	${POCKETSHELL_TA_PROD_PKG_HOSTCLI[$p]:-}"$'\n'
    done
    local areas_for_file count_for_file
    while IFS=$'\t' read -r f count_for_file areas_for_file; do
      [[ -z "$f" ]] && continue
      fqcn="${file_fqcn[$f]:-}"
      [[ -z "$fqcn" ]] && continue
      POCKETSHELL_TA_INDEX_IMPORT_LINES=$((POCKETSHELL_TA_INDEX_IMPORT_LINES + count_for_file))
      for pa in $areas_for_file; do
        [[ "${POCKETSHELL_TA_CLASS_DEPS[$fqcn]}" == *" $pa "* ]] || \
          POCKETSHELL_TA_CLASS_DEPS["$fqcn"]="${POCKETSHELL_TA_CLASS_DEPS[$fqcn]}$pa "
      done
    done < <(
      {
        printf '%s' "$awk_map"
        printf '%s\n' "${test_files[@]}" |
          (cd "$root" && xargs -d '\n' -r grep -HE '^[[:space:]]*import[[:space:]]+com\.pocketshell\.' 2>/dev/null) || true
      } | awk -F'\t' '
        NR == FNR && $1 == "M" { area[$2] = $3; if ($4 != "") hc[$2] = 1; next }
        {
          line = $0
          ci = index(line, ":")
          if (ci == 0) next
          f = substr(line, 1, ci - 1)
          imp = substr(line, ci + 1)
          sub(/^[ \t]*import[ \t]+/, "", imp)
          gsub(/[^A-Za-z0-9_.].*$/, "", imp)
          if (imp == "") next
          n[f]++
          if (!(imp in memo)) {
            p = imp; a = ""; h = ""
            while ((d = match(p, /\.[^.]*$/)) > 0) {
              p = substr(p, 1, d - 1)
              if (p in area) { a = area[p]; if (p in hc) h = "host-cli"; break }
            }
            memo[imp] = a; memoh[imp] = h
          }
          a = memo[imp]
          if (a == "") next
          key = f SUBSEP a
          if (!(key in seen)) { seen[key] = 1; out[f] = out[f] " " a }
          if (memoh[imp] != "") {
            key = f SUBSEP "host-cli"
            if (!(key in seen)) { seen[key] = 1; out[f] = out[f] " host-cli" }
          }
        }
        END { for (f in n) printf "%s\t%d\t%s\n", f, n[f], (f in out ? out[f] : "") }
      '
    )
  fi

  # 5. per-test-package union deps, for a registered class that has no file of
  #    its own (Kotlin allows several public classes per file, and
  #    `PortForwardDuplicateKeyRenderTest` lives inside
  #    `PortForwardDuplicateKeyCrashTest.kt`).
  local dep
  for fqcn in "${!POCKETSHELL_TA_CLASS_DEPS[@]}"; do
    pkg="${fqcn%.*}"
    for dep in ${POCKETSHELL_TA_CLASS_DEPS[$fqcn]}; do
      [[ "${POCKETSHELL_TA_PKG_TEST_DEPS[$pkg]:-}" == *" $dep "* ]] || \
        POCKETSHELL_TA_PKG_TEST_DEPS["$pkg"]="${POCKETSHELL_TA_PKG_TEST_DEPS[$pkg]:- }$dep "
    done
    # A class whose deps name an area other than its own is the evidence that
    # the import scan actually ran; the guard asserts this count is large.
    local own="${POCKETSHELL_TA_CLASS_RESOLVED[$fqcn]:-}"
    for dep in ${POCKETSHELL_TA_CLASS_DEPS[$fqcn]}; do
      if [[ "$dep" != "$own" ]]; then
        POCKETSHELL_TA_INDEX_CROSS_AREA_CLASSES=$((POCKETSHELL_TA_INDEX_CROSS_AREA_CLASSES + 1))
        break
      fi
    done
    [[ "${POCKETSHELL_TA_CLASS_DEPS[$fqcn]}" == *" host-cli "* ]] && \
      POCKETSHELL_TA_INDEX_HOSTCLI_CLASSES=$((POCKETSHELL_TA_INDEX_HOSTCLI_CLASSES + 1))
  done
  return 0
}

# FQCN -> area. THE resolver (see the index comment above). Order:
#   1. the explicit `class` override table;
#   2. the index built from tracked files;
#   3. the class's test package, when the class shares a file with a sibling.
# Fails (non-zero, empty output) when none of the three answers, so a genuinely
# unmapped class is loud rather than silently unselectable.
pocketshell_test_area_for_class() {
  local fqcn="$1" pkg
  fqcn="${fqcn%%#*}"   # `Class#method` registry entries
  POCKETSHELL_TEST_AREA_FOR_CLASS=""
  pocketshell_test_areas_build_index
  # MEMOISED, not duplicated. The invariants ask this question ~20k times per
  # run (19 areas x 1046 classes), so the answer is cached — but it is still
  # computed by THIS function and nowhere else, which is the property finding B4
  # (and its round-2 echo B7b) is about. A cache in front of one implementation
  # is not a second implementation.
  if [[ -n "${POCKETSHELL_TA_RESOLVE_CACHE[$fqcn]+x}" ]]; then
    POCKETSHELL_TEST_AREA_FOR_CLASS="${POCKETSHELL_TA_RESOLVE_CACHE[$fqcn]}"
    [[ -z "$POCKETSHELL_TEST_AREA_FOR_CLASS" ]] && return 1
    printf '%s\n' "$POCKETSHELL_TEST_AREA_FOR_CLASS"
    return 0
  fi
  pkg="${fqcn%.*}"
  POCKETSHELL_TEST_AREA_FOR_CLASS="${POCKETSHELL_TA_CLASS_AREA[$fqcn]:-}"
  [[ -z "$POCKETSHELL_TEST_AREA_FOR_CLASS" ]] && POCKETSHELL_TEST_AREA_FOR_CLASS="${POCKETSHELL_TA_CLASS_RESOLVED[$fqcn]:-}"
  [[ -z "$POCKETSHELL_TEST_AREA_FOR_CLASS" ]] && POCKETSHELL_TEST_AREA_FOR_CLASS="${POCKETSHELL_TA_PKG_TEST_AREA[$pkg]:-}"
  POCKETSHELL_TA_RESOLVE_CACHE["$fqcn"]="$POCKETSHELL_TEST_AREA_FOR_CLASS"
  [[ -z "$POCKETSHELL_TEST_AREA_FOR_CLASS" ]] && return 1
  printf '%s\n' "$POCKETSHELL_TEST_AREA_FOR_CLASS"
  return 0
}

# FQCN -> " area area … ": every area whose PRODUCTION code this test class
# compiles against, plus its own area, plus `host-cli` when it imports a package
# on the CLI wire seam. Falls back to the union over its test package for a
# class that shares a file with a sibling, and to the class's own area when even
# that is unknown.
pocketshell_test_area_deps_for_class() {
  local fqcn="$1" pkg
  fqcn="${fqcn%%#*}"
  pocketshell_test_areas_build_index
  if [[ -n "${POCKETSHELL_TA_DEPS_CACHE[$fqcn]+x}" ]]; then
    POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS="${POCKETSHELL_TA_DEPS_CACHE[$fqcn]}"
    printf '%s\n' "$POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS"
    return 0
  fi
  pkg="${fqcn%.*}"
  POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS="${POCKETSHELL_TA_CLASS_DEPS[$fqcn]:-}"
  if [[ -z "$POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS" ]]; then
    POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS="${POCKETSHELL_TA_PKG_TEST_DEPS[$pkg]:-}"
  fi
  if [[ -z "$POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS" ]]; then
    pocketshell_test_area_for_class "$fqcn" >/dev/null
    POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS="${POCKETSHELL_TEST_AREA_FOR_CLASS:+ $POCKETSHELL_TEST_AREA_FOR_CLASS }"
  fi
  POCKETSHELL_TA_DEPS_CACHE["$fqcn"]="$POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS"
  printf '%s\n' "$POCKETSHELL_TEST_AREA_DEPS_FOR_CLASS"
  return 0
}

# Expand a set of CHANGED areas over the `couple` edges to a fixpoint, then
# union in every `always`-tier area.
#
# ORDER MATTERS, in both directions:
#
#   * The always-tier union happens LAST and unconditionally, which is what
#     makes "select nothing" unreachable — any selection, from any input,
#     contains at least the always tier.
#   * The coupling closure runs over the SEED areas only, NOT over the always
#     tier. A `couple` edge means "a CHANGE here has broken there"; an area
#     that is present merely because it is always-on did not change, so it has
#     nothing to fan out. Seeding the closure with the always tier instead
#     dragged tmux-session and composer-voice (the two largest suites) into
#     every run via the conversation-agents coupling and silently erased most
#     of the saving while proving nothing extra.
#
# Reads: the space-separated seed areas in $1. Prints the sorted result.
pocketshell_test_areas_expand() {
  local -A seen=()
  local a
  for a in $(pocketshell_test_areas_expand_seeds "$1"); do seen["$a"]=1; done
  for a in "${POCKETSHELL_TA_AREAS[@]}"; do
    [[ "${POCKETSHELL_TA_AREA_TIER[$a]}" == "always" ]] && seen["$a"]=1
  done
  printf '%s\n' "${!seen[@]}" | LC_ALL=C sort
}

# The CHANGED half of the same expansion: the seeds plus their `couple` closure,
# WITHOUT the always tier unioned in. Callers need this separately because the
# per-class import-dependency test must ask "did an area this class depends on
# actually CHANGE?" — an always-tier area sitting in the selection merely as the
# floor did not change, and matching against it would drag every class that
# imports connection-core into every push.
pocketshell_test_areas_expand_seeds() {
  local -A seen=()
  local -a queue=()
  local a to
  for a in $1; do
    [[ -z "$a" ]] && continue
    if [[ -z "${seen[$a]:-}" ]]; then seen["$a"]=1; queue+=("$a"); fi
  done
  local idx=0
  while (( idx < ${#queue[@]} )); do
    a="${queue[$idx]}"; idx=$((idx + 1))
    [[ -z "${POCKETSHELL_TA_COUPLE[$a]:-}" ]] && continue
    local IFS=','
    for to in ${POCKETSHELL_TA_COUPLE[$a]}; do
      [[ -z "$to" ]] && continue
      if [[ -z "${seen[$to]:-}" ]]; then seen["$to"]=1; queue+=("$to"); fi
    done
  done
  [[ "${#seen[@]}" -eq 0 ]] && return 0
  printf '%s\n' "${!seen[@]}" | LC_ALL=C sort
}
