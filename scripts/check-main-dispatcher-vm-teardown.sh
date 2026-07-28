#!/usr/bin/env bash
# Issue #1355 / #1765: every JVM test that constructs a TmuxSessionViewModel and
# later resets the process-global Dispatchers.Main must register each VM with a
# shared teardown seam. Otherwise a VM continuation can outlive resetMain() and
# fail an unrelated next class at TestMainDispatcher.kt:72.
#
# Two seams, one per fixture shape:
#   - MainDispatcherRule consumers  -> mainDispatcherRule.tmuxSessionViewModelTeardown()
#                                      + teardown.track(TmuxSessionViewModel(...))  (#1355)
#   - standalone runTest fixtures   -> StandaloneTmuxVmFixture() + fixture.track(...) (#1765)
#
# A standalone fixture must ALSO stop touching Dispatchers.setMain/resetMain
# itself: the fixture owns the install/drain/reset ordering, and a file that
# resets Main by hand can reorder the reset ahead of the drain again.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

run_guard() {
  local scan_root="${POCKETSHELL_RULE_VM_GUARD_ROOT:-$REPO_ROOT}"
  python3 - "$scan_root" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1]).resolve()
test_root = root / "app/src/test/java"
exemptions = {
    # This inherited mega-suite has its own audited multi-root teardown because
    # its terminal-feed integration deliberately needs specialized real-IO
    # pumping. Issue #1355 migrates the standalone rule consumers only.
    Path("app/src/test/java/com/pocketshell/app/tmux/TmuxSessionViewModelTestBase.kt"),
    # Issue #926's proof pins Main and the seed-IO dispatcher to two NAMED
    # single-thread executors so the off-Main hop is observable from the client's
    # recorded thread name. It cannot run on the shared virtual-clock fixture, so
    # it carries the audited #1355 drain inline (clear -> cancelOwnScopesForTest
    # -> cancelAndJoin both real-IO roots -> bounded zero-child drain -> phased
    # resetMain). Verified by hand; do not add further exemptions.
    Path("app/src/test/java/com/pocketshell/app/tmux/Issue926SeedIoOffMainTest.kt"),
}

constructor = re.compile(r"\bTmuxSessionViewModel\s*\(")
rule_tracked_constructor = re.compile(
    r"\bteardown\s*\.\s*track\s*\(\s*TmuxSessionViewModel\s*\(",
    re.DOTALL,
)
rule_registry = re.compile(
    r"\bmainDispatcherRule\s*\.\s*tmuxSessionViewModelTeardown\s*\("
)
standalone_registry = re.compile(r"\bStandaloneTmuxVmFixture\s*\(")
standalone_tracked = re.compile(r"\bfixture\s*\.\s*track\s*\(")
raw_main_swap = re.compile(r"\bDispatchers\s*\.\s*(setMain|resetMain)\s*\(|(?<![.\w])(setMain|resetMain)\s*\(")
# Comments and string literals routinely NAME the banned call while explaining
# it (the fixture's own assertion messages do). Scan executable code only.
comment_or_string = re.compile(
    r"/\*.*?\*/|//[^\n]*|\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\\n])*\"",
    re.DOTALL,
)


def executable(text: str) -> str:
    return comment_or_string.sub("", text)

failures = []
rule_consumers = []
standalone_consumers = []
exempt_found = 0
for path in sorted(test_root.rglob("*.kt")):
    text = path.read_text(encoding="utf-8")
    if not constructor.search(text):
        continue
    relative = path.relative_to(root)
    if relative in exemptions:
        exempt_found += 1
        continue
    constructors = len(constructor.findall(text))
    if "MainDispatcherRule(" in text:
        rule_consumers.append(relative)
        tracked = len(rule_tracked_constructor.findall(text))
        if not rule_registry.search(text):
            failures.append(
                f"{relative}: missing mainDispatcherRule.tmuxSessionViewModelTeardown()"
            )
        if tracked != constructors:
            failures.append(
                f"{relative}: {constructors - tracked} of {constructors} "
                "TmuxSessionViewModel constructor(s) are not wrapped in teardown.track(...)"
            )
        continue

    standalone_consumers.append(relative)
    tracked = len(standalone_tracked.findall(text))
    if not standalone_registry.search(text):
        failures.append(f"{relative}: missing StandaloneTmuxVmFixture()")
    if tracked < constructors:
        failures.append(
            f"{relative}: {constructors - tracked} of {constructors} "
            "TmuxSessionViewModel constructor(s) are not registered with fixture.track(...)"
        )
    if raw_main_swap.search(executable(text)):
        failures.append(
            f"{relative}: resets Dispatchers.Main by hand; StandaloneTmuxVmFixture "
            "owns the install/drain/reset ordering"
        )

if failures:
    print("FAIL: TmuxSessionViewModel Main-reset teardown guard (#1355/#1765)")
    for failure in failures:
        print(f"  - {failure}")
    raise SystemExit(1)

print(
    "PASS: TmuxSessionViewModel Main-reset teardown guard (#1355/#1765) — "
    f"{len(rule_consumers)} MainDispatcherRule consumer(s) and "
    f"{len(standalone_consumers)} standalone fixture consumer(s) registered; "
    f"{exempt_found} audited exemption(s)."
)
PY
}

if [[ "${1:-}" != "--self-test" ]]; then
  run_guard
  exit 0
fi

fixture_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$fixture_root"
}
trap cleanup EXIT INT TERM
fixture_dir="$fixture_root/app/src/test/java/com/pocketshell/app/tmux"
mkdir -p "$fixture_dir"

expect_fail() {
  local label="$1"
  shift
  set +e
  local output
  output="$(POCKETSHELL_RULE_VM_GUARD_ROOT="$fixture_root" run_guard 2>&1)"
  local rc=$?
  set -e
  if [[ "$rc" -eq 0 ]]; then
    printf '%s\n' "$output"
    echo "FAIL: guard self-test '$label' expected a rejection but passed"
    exit 1
  fi
  local needle
  for needle in "$@"; do
    if [[ "$output" != *"$needle"* ]]; then
      printf '%s\n' "$output"
      echo "FAIL: guard self-test '$label' missing expected finding: $needle"
      exit 1
    fi
  done
}

cat > "$fixture_dir/GoodRuleTest.kt" <<'EOF'
class GoodRuleTest {
    val mainDispatcherRule = MainDispatcherRule()
    val teardown = mainDispatcherRule.tmuxSessionViewModelTeardown()
    fun newVm() = teardown.track(
        TmuxSessionViewModel(),
    )
}
EOF

cat > "$fixture_dir/GoodStandaloneTest.kt" <<'EOF'
class GoodStandaloneTest {
    private val fixture = StandaloneTmuxVmFixture()
    fun runVmTest(body: suspend TestScope.() -> Unit) = fixture.runVmTest(body = body)
    fun newVm() = TmuxSessionViewModel().also { fixture.track(it) }
}
EOF
POCKETSHELL_RULE_VM_GUARD_ROOT="$fixture_root" run_guard

cat > "$fixture_dir/BadRuleTest.kt" <<'EOF'
class BadRuleTest {
    val mainDispatcherRule = MainDispatcherRule()
    fun newVm() = TmuxSessionViewModel()
}
EOF
expect_fail "unregistered rule consumer" \
  "BadRuleTest.kt: missing mainDispatcherRule.tmuxSessionViewModelTeardown()" \
  "constructor(s) are not wrapped in teardown.track(...)"
rm -- "$fixture_dir/BadRuleTest.kt"

cat > "$fixture_dir/BadStandaloneTest.kt" <<'EOF'
class BadStandaloneTest {
    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun runVmTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { body() } finally { Dispatchers.resetMain() }
    }
    fun newVm() = TmuxSessionViewModel()
}
EOF
expect_fail "unmigrated standalone fixture" \
  "BadStandaloneTest.kt: missing StandaloneTmuxVmFixture()" \
  "constructor(s) are not registered with fixture.track(...)" \
  "resets Dispatchers.Main by hand"
rm -- "$fixture_dir/BadStandaloneTest.kt"

cat > "$fixture_dir/UntrackedStandaloneTest.kt" <<'EOF'
class UntrackedStandaloneTest {
    private val fixture = StandaloneTmuxVmFixture()
    fun runVmTest(body: suspend TestScope.() -> Unit) = fixture.runVmTest(body = body)
    fun newVm() = TmuxSessionViewModel()
    fun secondVm() = TmuxSessionViewModel().also { fixture.track(it) }
}
EOF
expect_fail "standalone fixture with one untracked constructor" \
  "UntrackedStandaloneTest.kt: 1 of 2 TmuxSessionViewModel constructor(s) are not registered"
rm -- "$fixture_dir/UntrackedStandaloneTest.kt"

cat > "$fixture_dir/HandRolledResetTest.kt" <<'EOF'
class HandRolledResetTest {
    private val fixture = StandaloneTmuxVmFixture()
    fun newVm() = TmuxSessionViewModel().also { fixture.track(it) }
    fun tearDown() {
        resetMain()
    }
}
EOF
expect_fail "standalone fixture that still resets Main by hand" \
  "HandRolledResetTest.kt: resets Dispatchers.Main by hand"
rm -- "$fixture_dir/HandRolledResetTest.kt"

POCKETSHELL_RULE_VM_GUARD_ROOT="$fixture_root" run_guard
echo "PASS: #1355/#1765 guard self-test rejects every omitted-migration shape"
