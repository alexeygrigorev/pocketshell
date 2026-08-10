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
#       is extracted from the PRODUCER ITSELF: the top-level registrations on the
#       `click` group in `tools/pocketshell/src/pocketshell/cli.py`.
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
#       ROUND-3 FINDING B9 — WHY THIS READS THE PRODUCER'S GRAMMAR, NOT ITS TEXT.
#       This extraction used to be one grep for `add_command(…, name="…")`, i.e.
#       ONE of the registration forms click offers. `cli.py` already uses a
#       second one — `daemon` is `@cli.group(name="daemon")` — so the extractor
#       under-read the producer *on the shipped tree* while the guard printed
#       "16 subcommands" as though that were the producer's list. Converting four
#       more registrations to the form `daemon` already uses dropped `usage`,
#       `tree`, `agents` and `qr-share` from the vocabulary, took
#       `com.pocketshell.app.settings` — the ONE package this end exists to
#       catch — off the seam, cut unit selection 570 -> 560, and left every floor
#       green. That is the #847 class wearing its fourth spelling, and a floor
#       cannot see it: a floor detects a SMALLER number, and under-reading
#       produces a number that looks fine.
#
#       So the reader is `python3 -c ast` over `cli.py` — the producer's own
#       grammar — and it is FAIL-CLOSED on two axes:
#
#         * it counts registration SITES (`cli.add_command(...)`, `@cli.group(…)`,
#           `@cli.command(…)`, in any shape, anywhere in the module) and the
#           NAMES it could resolve, and `--verify-manifest` check 7 asserts
#           names == sites. Equality is the instrument a floor could not be:
#           a form the reader cannot decode raises the site count without
#           raising the name count, so it is LOUD instead of silently narrowing.
#         * anything it cannot decode — a registration inside a loop / function /
#           conditional (the shape `agents.py:880` already uses one level down),
#           a `name=` that is a constant rather than a literal, an omitted name,
#           a bare `@cli.group`, a `cli.py` it cannot parse, a missing `python3` —
#           is reported as UNREADABLE and check 7 fails on it by name.
#
#       The floor on the site count stays, with a narrower job than before: it
#       is the only thing that can catch a TOTALLY dead reader, because the
#       equality check is vacuous at 0 == 0.
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
# only so the self-test can kill it, or point it at a mutated `cli.py`, and watch
# the guard redden. An absolute path is used as-is; a relative one is resolved
# against the repo root, so a self-test mutant can live in its own sandbox
# instead of inside the tree it is measuring.
POCKETSHELL_TA_HOSTCLI_CLI_SOURCE="${POCKETSHELL_TA_HOSTCLI_CLI_SOURCE:-tools/pocketshell/src/pocketshell/cli.py}"

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
POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SITES=0
POCKETSHELL_TA_INDEX_HOSTCLI_CLI_NONROOT=0
POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNCLASSIFIED=0
POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SCANNED=0
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
#   SCANNED <n>          every registrar call node in the file, ANY receiver
#   SITES <n>            of those, registrations on the TOP-LEVEL group
#   NONROOT <n>          of those, registrations PROVEN to be on something else
#   UNCLASSIFIED <n>     of those, ones whose receiver could not be resolved
#   NAME <subcommand>    one per SITE whose name the reader could resolve
#   UNREADABLE <reason>  one per site (or condition) it could NOT decode
#   END                  the reader ran to completion
#
# `SCANNED == SITES + NONROOT + UNCLASSIFIED` is an accounting identity the
# caller re-checks: no registrar call in the file can fall out of the census.
#
# The caller treats a MISSING `END` as unreadable, which is what makes "python3
# is not installed", "python3 crashed" and "the reader was killed" fail the same
# closed way as a registration form it does not understand. Nothing here ever
# returns a smaller-but-plausible vocabulary: under-reading is an ERROR, because
# a smaller number is exactly what a floor cannot distinguish from normal drift.
_pocketshell_hostcli_read_cli_vocabulary() {
  local src="$1"
  if [[ ! -f "$src" ]]; then
    printf 'UNREADABLE no such CLI source: %s\nSITES 0\nEND\n' "$src"
    return 0
  fi
  python3 - "$src" 2>/dev/null <<'PY'
import ast
import os
import re
import sys

path = sys.argv[1]
# A subcommand token the vocabulary regex can safely carry. Anything else is
# reported UNREADABLE rather than dropped: a name with regex metacharacters
# would either corrupt the seam scan or silently shrink the vocabulary, and
# "silently shrink" is the whole defect this reader exists to end.
SUBCOMMAND_TOKEN = re.compile(r"^[a-z0-9][a-z0-9-]*$")
names = []
unreadable = []
sites = 0
nonroot = 0
unclassified = 0
scanned = 0


def emit_and_exit():
    for line in unreadable:
        print("UNREADABLE " + line.replace("\n", " "))
    for n in names:
        print("NAME " + n)
    print("SITES %d" % sites)
    print("NONROOT %d" % nonroot)
    print("UNCLASSIFIED %d" % unclassified)
    print("SCANNED %d" % scanned)
    print("END")
    raise SystemExit(0)


try:
    tree = ast.parse(open(path, encoding="utf-8").read(), filename=path)
except Exception as exc:  # noqa: BLE001 - any parse problem must fail closed
    unreadable.append("cannot parse %s: %s: %s" % (path, type(exc).__name__, exc))
    emit_and_exit()


def string_literal(node):
    """The plain string a node denotes, or None when it is not a literal."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    return None


def decorator_target(dec):
    return dec.func if isinstance(dec, ast.Call) else dec


# 1. Identify the ONE top-level click group. Everything below is anchored on its
#    binding name, so a rename cannot leave the reader silently reading nothing.
roots = []
for node in tree.body:
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        for dec in node.decorator_list:
            target = decorator_target(dec)
            if (
                isinstance(target, ast.Attribute)
                and target.attr == "group"
                and isinstance(target.value, ast.Name)
                and target.value.id == "click"
            ):
                roots.append(node.name)
if len(roots) != 1:
    unreadable.append(
        "expected exactly one module-level @click.group in %s, found %d"
        % (path, len(roots))
    )
    emit_and_exit()
root = roots[0]

REGISTRARS = ("add_command", "group", "command")
accounted = set()

# ---------------------------------------------------------------------------
# 1b. Resolve every module-level BINDING in this file to one of three kinds:
#
#       "root"    — provably the top-level group object: the decorated def
#                   itself, and anything transitively assigned from it
#                   (`_g = cli`, `_h = _g`).
#       "nonroot" — provably a DIFFERENT object: an imported module or name, an
#                   undecorated def/class, or a def whose decorators are all
#                   click's own factories (which return a fresh Group/Command
#                   or the fresh function itself, never some other object).
#       "unknown" — everything else, INCLUDING every name bound inside a
#                   function (parameters, locals, loop targets, comprehension
#                   variables) and every name bound by a top-level for/if/with.
#
# This is what makes the reader receiver-AGNOSTIC, and it is the round-5 fix.
# Rounds 1-5 keyed detection on the receiver being the literal name `cli`, so a
# registration reached through ANY other binding was neither a SITE nor a NAME:
# `names == sites` held vacuously and the vocabulary shrank in silence. Five
# spellings were closed one at a time and a sixth (`_g = cli; _g.add_command(…)`,
# and a helper taking the group as a parameter) was still silent, because the
# space of ways to name an object is unbounded and cannot be enumerated.
#
# So the reader no longer recognises a list of shapes. It looks at EVERY
# registrar call in this file, whatever the receiver, and each one must either
# resolve to the root group (and then to a literal name) or be proven to be
# something else. Anything it cannot place is UNREADABLE — loud, never absent.
# ---------------------------------------------------------------------------
CLICK_FACTORY_ATTRS = ("group", "command")
env = {}
root_rebound = []
star_imports = []


def decorator_is_binding_safe(dec):
    """True when a decorator cannot make its target BE the root group object.

    click's `@click.*` decorators and `@<group>.group` / `@<group>.command`
    factories return either a fresh Group/Command or the fresh function they
    were handed. An unrecognised decorator could return anything at all —
    including `cli` itself — so it leaves the binding "unknown" rather than
    letting the reader guess.
    """
    target = decorator_target(dec)
    if not isinstance(target, ast.Attribute) or not isinstance(target.value, ast.Name):
        return False
    if target.value.id == "click":
        return True
    return target.attr in CLICK_FACTORY_ATTRS and env.get(target.value.id) in (
        "root",
        "nonroot",
    )


def bind(name, kind, lineno):
    if name == root and kind != "root":
        root_rebound.append(lineno)
    env[name] = kind


def value_kind(node):
    # Only a bare Name propagates a known kind. A call, attribute, subscript or
    # literal is "unknown": the reader will not guess that `click.Group()` or
    # `_make_group()` is not the root group, it will say so.
    if isinstance(node, ast.Name):
        return env.get(node.id, "unknown")
    return "unknown"


def bind_targets(target, kind, lineno):
    if isinstance(target, ast.Name):
        bind(target.id, kind, lineno)
    elif isinstance(target, (ast.Tuple, ast.List)):
        for elt in target.elts:
            bind_targets(elt, "unknown", lineno)
    elif isinstance(target, ast.Starred):
        bind_targets(target.value, "unknown", lineno)
    # Attribute/Subscript targets bind no module-level name.


for node in tree.body:
    if isinstance(node, ast.Import):
        for alias in node.names:
            bind(alias.asname or alias.name.split(".")[0], "nonroot", node.lineno)
    elif isinstance(node, ast.ImportFrom):
        for alias in node.names:
            if alias.name == "*":
                star_imports.append(node.lineno)
            else:
                bind(alias.asname or alias.name, "nonroot", node.lineno)
    elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
        if node.name == root:
            env[node.name] = "root"
        elif all(decorator_is_binding_safe(d) for d in node.decorator_list):
            bind(node.name, "nonroot", node.lineno)
        else:
            bind(node.name, "unknown", node.lineno)
    elif isinstance(node, ast.Assign):
        kind = value_kind(node.value)
        for target in node.targets:
            bind_targets(target, kind, node.lineno)
    elif isinstance(node, ast.AnnAssign):
        bind_targets(
            node.target,
            value_kind(node.value) if node.value is not None else "unknown",
            node.lineno,
        )
    elif isinstance(node, ast.AugAssign):
        bind_targets(node.target, "unknown", node.lineno)
    else:
        # A top-level for/if/with/try can bind names too, and the reader trusts
        # none of them: every name they store becomes "unknown", so registering
        # through one is UNREADABLE rather than invisible.
        for sub in ast.walk(node):
            if isinstance(sub, ast.Name) and isinstance(sub.ctx, ast.Store):
                bind(sub.id, "unknown", getattr(sub, "lineno", node.lineno))

# A `global` inside a function can rebind a module-level name after import time.
for sub in ast.walk(tree):
    if isinstance(sub, ast.Global):
        for nm in sub.names:
            if nm == root:
                unreadable.append(
                    "the root binding %r is declared `global` at line %d, so a "
                    "function can point it at another object" % (root, sub.lineno)
                )
            else:
                env[nm] = "unknown"
for line in sorted(set(root_rebound)):
    unreadable.append(
        "the root binding %r is reassigned at line %d, so which object the "
        "registrations below land on is not decidable from the source" % (root, line)
    )
for line in sorted(set(star_imports)):
    unreadable.append(
        "%s does a star import at line %d, which can bind arbitrary names this "
        "reader cannot resolve" % (os.path.basename(path), line)
    )


def receiver_of(node):
    """(kind, human description) of the object a registrar is invoked on."""
    recv = node.value
    if isinstance(recv, ast.Name):
        return env.get(recv.id, "unknown"), recv.id
    return "unknown", "<%s expression>" % type(recv).__name__


def accept(where, value):
    if not SUBCOMMAND_TOKEN.match(value):
        unreadable.append("%s: %r is not a plain subcommand token" % (where, value))
    else:
        names.append(value)


def take_name(where, kwargs, positional, index):
    """Resolve one site's subcommand name, or record why it cannot be resolved."""
    global sites
    sites += 1
    for kw in kwargs:
        if kw.arg == "name":
            value = string_literal(kw.value)
            if value is None:
                unreadable.append(
                    "%s: name= is not a plain string literal (%s)"
                    % (where, type(kw.value).__name__)
                )
            else:
                accept(where, value)
            return
        if kw.arg is None:
            unreadable.append("%s: **kwargs registration cannot be read" % where)
            return
    if len(positional) > index:
        value = string_literal(positional[index])
        if value is None:
            unreadable.append(
                "%s: positional name is not a plain string literal (%s)"
                % (where, type(positional[index]).__name__)
            )
        else:
            accept(where, value)
        return
    unreadable.append(
        "%s: no explicit name — click would derive it from the decorated object, "
        "which this reader deliberately does not guess" % where
    )


# 2. THE CENSUS. Every registrar call node in the file, whatever its receiver.
#    Nothing below filters by receiver name; the receiver is RESOLVED instead.
registrar_nodes = [
    n for n in ast.walk(tree) if isinstance(n, ast.Attribute) and n.attr in REGISTRARS
]
scanned = len(registrar_nodes)

# 3. The two forms the reader can fully decode, both at module top level and
#    both anchored on a receiver that RESOLVES to the root group — so an alias
#    (`_g = cli`) is read correctly rather than vanishing.
for node in tree.body:
    # (i) `<root>.add_command(cmd, name="x")`
    if (
        isinstance(node, ast.Expr)
        and isinstance(node.value, ast.Call)
        and isinstance(node.value.func, ast.Attribute)
        and node.value.func.attr == "add_command"
    ):
        kind, who = receiver_of(node.value.func)
        if kind == "root":
            accounted.add(id(node.value.func))
            take_name(
                "%s.add_command line %d" % (who, node.lineno),
                node.value.keywords,
                node.value.args,
                1,
            )
        continue
    # (ii) `@<root>.group(name="x")` / `@<root>.command("x")` on a top-level def
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        for dec in node.decorator_list:
            target = decorator_target(dec)
            if not isinstance(target, ast.Attribute) or target.attr not in (
                "group",
                "command",
            ):
                continue
            kind, who = receiver_of(target)
            if kind != "root":
                continue
            accounted.add(id(target))
            where = "@%s.%s line %d" % (who, target.attr, dec.lineno)
            if isinstance(dec, ast.Call):
                take_name(where, dec.keywords, dec.args, 0)
            else:
                sites += 1
                unreadable.append(
                    "%s: bare decorator with no name — click would derive it from "
                    "the function name, which this reader deliberately does not guess"
                    % where
                )

# 4. Every remaining node in the census gets a verdict. There are exactly three,
#    and together with step 3 they partition the census — see the identity below.
#
#      root      -> the receiver resolves to the top-level group, but the call
#                   is not in a form step 3 can place: a module-level loop or
#                   conditional, a nested def, a comprehension, or a function
#                   body that names the group through its module-level binding.
#                   Counts as a SITE and is UNREADABLE, so both the equality
#                   check and the decode check fire on it.
#                   (A function that receives the group as a PARAMETER is not
#                   this case — its receiver does not resolve at all, so it
#                   lands in `unknown` below. Round 4's comment claimed step 3
#                   caught "inside a function"; for the parameter form that was
#                   false, and the silence was the round-5 blocker.)
#      nonroot   -> it registers on a provably different object: a sub-group
#                   (`@daemon_group.command`, 4 of these ship today), or it is
#                   the `@click.group` that DEFINES the root. Neither puts a word
#                   in the TOP-LEVEL vocabulary, so there is no name to owe.
#      unknown   -> the receiver could not be resolved (a parameter, a local, a
#                   subscript, a call result). This is the round-5 blocker: such
#                   a call might be adding a top-level subcommand, and the reader
#                   says so instead of leaving it out of both counters.
for node in registrar_nodes:
    if id(node) in accounted:
        continue
    kind, who = receiver_of(node)
    line = getattr(node, "lineno", 0)
    if kind == "root":
        sites += 1
        unreadable.append(
            "%s.%s at line %d registers on the top-level group but is not a "
            "module-top-level registration this reader can decode" % (who, node.attr, line)
        )
    elif kind == "nonroot":
        nonroot += 1
    else:
        unclassified += 1
        unreadable.append(
            "%s.%s at line %d: the receiver %r resolves to neither the top-level "
            "group nor a provably different object, so this reader cannot tell "
            "whether it registers a top-level subcommand (bind the group to a "
            "module-level name so it can)" % (who, node.attr, line, who)
        )

# The partition must be exact, or a registrar node fell out of the census and
# the equality check above it would be measuring less than the whole file.
if scanned != sites + nonroot + unclassified:
    unreadable.append(
        "internal accounting error in %s: %d registrar nodes scanned but "
        "%d site + %d non-root + %d unresolved classified"
        % (path, scanned, sites, nonroot, unclassified)
    )

# 4b. The census above is total over THIS file, so the only way a registration
#     can still hide is for the group OBJECT to leave the file. Step 5 catches
#     it leaving by import; this catches it leaving as a VALUE — handed to a
#     helper, returned, stashed in a container. That is not hypothetical: this
#     package already does exactly that one level down
#     (`register_push_card_commands(push_group)` in cli.py, whose body in
#     cards.py runs `@push_group.command("checklist")`), so `…(cli)` is one
#     character away from registering a top-level subcommand out of sight.
#
#     Round 5 allowed EVERY attribute access on the root binding, on the claim
#     that "registrar attrs are already in the census". That claim was false for
#     a NON-registrar attribute, and it was the round-5 blocker (the SEVENTH
#     spelling): `click.Group.commands` is a plain dict — `add_command` is
#     literally `self.commands[name] = cmd` — so `cli.commands["x"] = cmd` and
#     `cli.commands.update({…})` register a top-level subcommand through a path
#     that is not a registrar CALL at all. They never entered the census, the
#     identity balanced over a smaller file (18 = 13 + 5 + 0), and the guard
#     printed PASS while `com.pocketshell.app.settings` fell off the seam.
#
#     So this step is INVERTED, the same way step 1b inverted receiver
#     detection: instead of naming the shapes that are forbidden, it names the
#     four attributes that are PROVEN unable to hide a registration and reports
#     every other one — including attributes nobody has thought of yet.
#
#       add_command / group / command — the census at step 2 collects EVERY
#           `ast.Attribute` node with these names, called or not, and step 3/4
#           give each one a verdict. Nothing can hide behind them; a bare
#           `_add = cli.add_command` is already a SITE with no name, so the
#           equality check fires on it.
#       main — click's invocation entry (`BaseCommand.main`). It parses argv and
#           runs the CLI; it does not mutate the command table.
#
#     The remaining two allowances are unchanged and are not attribute reads:
#     the root as the function being CALLED, and as the right-hand side of a
#     module-level assignment (the alias, which the environment tracks).
#     Anything else — attribute or not — is UNREADABLE.
#
#     Deliberately NOT the fix: adding `commands` to REGISTRARS. That is one
#     more entry on a list of recognised spellings, which is the pattern that
#     produced seven of them.
parents = {}
for n in ast.walk(tree):
    for c in ast.iter_child_nodes(n):
        parents[id(c)] = n
alias_rhs = set()
for st in tree.body:
    if isinstance(st, ast.Assign):
        alias_rhs.add(id(st.value))
    elif isinstance(st, ast.AnnAssign) and st.value is not None:
        alias_rhs.add(id(st.value))
for n in ast.walk(tree):
    if not (isinstance(n, ast.Name) and isinstance(n.ctx, ast.Load)):
        continue
    if env.get(n.id) != "root":
        continue
    par = parents.get(id(n))
    if isinstance(par, ast.Attribute):
        if par.attr in REGISTRARS or par.attr == "main":
            continue
        unreadable.append(
            "the top-level group %r is read through the non-registrar attribute "
            "%r at line %d; click's Group.commands is a plain dict, so an "
            "attribute this reader has not proven inert can add a top-level "
            "subcommand the census never sees"
            % (n.id, par.attr, getattr(n, "lineno", 0))
        )
        continue
    if isinstance(par, ast.Call) and par.func is n:
        continue
    if id(n) in alias_rhs:
        continue
    unreadable.append(
        "the top-level group %r escapes as a value at line %d (inside %s); it "
        "could be registered on anywhere, so this reader cannot claim to have "
        "seen every top-level subcommand"
        % (n.id, getattr(n, "lineno", 0), type(par).__name__ if par else "module")
    )

# 5. CROSS-MODULE. Steps 1b-4 are exhaustive over THIS file, and that is enough
#    for the whole package only because the root group object cannot be NAMED in
#    a sibling module without being imported from here. This step is what makes
#    that premise true rather than assumed: any sibling that so much as gains
#    access to this module or to the root binding trips, so no registration can
#    happen out of the census's sight. Nothing does it today — `__main__.py`
#    imports `main`, the entry function, not the group.
#
#    It is an AST scan, not a text scan, for the same reason step 4 stopped
#    matching receivers by name: a regex over import lines recognises the import
#    spellings someone thought of. `from pocketshell import cli`,
#    `from .cli import *` and `importlib.import_module("pocketshell.cli")` all
#    slipped past the round-4 regex. The AST sees every form at once.
#
#    (A module OUTSIDE this package could still import the group. That is not
#    reachable here — the package is self-contained and its entry point is
#    `cli:main` — and would need the reader to be pointed at a wider tree.)
package_dir = os.path.dirname(os.path.abspath(path))
self_name = os.path.basename(path)
module_stem = os.path.splitext(self_name)[0]


def targets_this_module(mod):
    return mod == module_stem or mod.endswith("." + module_stem)


def trip(entry, why):
    unreadable.append(
        "%s takes the top-level group object from %s (%s); a registration there "
        "would be invisible to this reader" % (entry, self_name, why)
    )


for entry in sorted(os.listdir(package_dir)):
    if not entry.endswith(".py") or entry == self_name:
        continue
    sibling = os.path.join(package_dir, entry)
    try:
        sib = ast.parse(open(sibling, encoding="utf-8").read(), filename=sibling)
    except Exception as exc:  # noqa: BLE001 - an unreadable sibling fails closed
        unreadable.append(
            "cannot read sibling module %s: %s: %s" % (entry, type(exc).__name__, exc)
        )
        continue
    reason = None
    for node in ast.walk(sib):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if targets_this_module(alias.name):
                    reason = "imports the %s module" % module_stem
                elif alias.name == "importlib" or alias.name.startswith("importlib."):
                    reason = "can import by name at run time (importlib)"
        elif isinstance(node, ast.ImportFrom):
            mod = node.module or ""
            for alias in node.names:
                if alias.name == "*" and targets_this_module(mod):
                    reason = "star-imports everything from %s" % self_name
                elif alias.name in (root, module_stem):
                    reason = "binds the name %r" % alias.name
                elif mod == "importlib" and alias.name == "import_module":
                    reason = "can import by name at run time (importlib)"
        elif isinstance(node, ast.Name) and node.id == "__import__":
            reason = "can import by name at run time (__import__)"
        elif isinstance(node, ast.Attribute) and node.attr == "import_module":
            reason = "can import by name at run time (importlib)"
        if reason is not None:
            break
    if reason is not None:
        trip(entry, reason)

emit_and_exit()
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
  #     from the producer's own registration sites, read through the producer's
  #     own grammar (finding B9 — see the block comment above for why a grep for
  #     ONE registration form was already under-reading the shipped `cli.py`).
  local cli_src="$POCKETSHELL_TA_HOSTCLI_CLI_SOURCE"
  [[ "$cli_src" == /* ]] || cli_src="$root/$cli_src"
  POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SITES=0
  POCKETSHELL_TA_INDEX_HOSTCLI_CLI_NONROOT=0
  POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNCLASSIFIED=0
  POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SCANNED=0
  POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNREADABLE=0
  POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG=""
  local -a subcommands=() vocab_lines=()
  local vline saw_end=0
  mapfile -t vocab_lines < <(_pocketshell_hostcli_read_cli_vocabulary "$cli_src")
  for vline in "${vocab_lines[@]}"; do
    case "$vline" in
      END) saw_end=1 ;;
      "SITES "*)        POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SITES="${vline#SITES }" ;;
      "NONROOT "*)      POCKETSHELL_TA_INDEX_HOSTCLI_CLI_NONROOT="${vline#NONROOT }" ;;
      "UNCLASSIFIED "*) POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNCLASSIFIED="${vline#UNCLASSIFIED }" ;;
      "SCANNED "*)      POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SCANNED="${vline#SCANNED }" ;;
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
    POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SITES=0
    POCKETSHELL_TA_INDEX_HOSTCLI_CLI_NONROOT=0
    POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNCLASSIFIED=0
    POCKETSHELL_TA_INDEX_HOSTCLI_CLI_SCANNED=0
    POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNREADABLE=$((POCKETSHELL_TA_INDEX_HOSTCLI_CLI_UNREADABLE + 1))
    POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG="${POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG:+$POCKETSHELL_TA_INDEX_HOSTCLI_CLI_DIAG; }the vocabulary reader did not finish for $cli_src (python3 missing, crashed, or killed)"
  fi
  # Counted BEFORE dedup so it can be compared with the site count: two sites
  # registering the same name is a producer bug, not a reason to under-report.
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
