#!/usr/bin/env bash
# Issue #1355: every standalone JVM test that combines MainDispatcherRule with
# TmuxSessionViewModel must register each VM constructor with the rule-owned
# teardown registry. Otherwise a VM continuation can outlive resetMain() and
# fail an unrelated next class at TestMainDispatcher.kt:72.

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
}

constructor = re.compile(r"\bTmuxSessionViewModel\s*\(")
tracked_constructor = re.compile(
    r"\bteardown\s*\.\s*track\s*\(\s*TmuxSessionViewModel\s*\(",
    re.DOTALL,
)
registry = re.compile(
    r"\bmainDispatcherRule\s*\.\s*tmuxSessionViewModelTeardown\s*\("
)

failures = []
consumers = []
exempt_found = 0
for path in sorted(test_root.rglob("*.kt")):
    text = path.read_text(encoding="utf-8")
    if "MainDispatcherRule(" not in text or not constructor.search(text):
        continue
    relative = path.relative_to(root)
    consumers.append(relative)
    if relative in exemptions:
        exempt_found += 1
        continue
    constructors = len(constructor.findall(text))
    tracked = len(tracked_constructor.findall(text))
    if not registry.search(text):
        failures.append(f"{relative}: missing mainDispatcherRule.tmuxSessionViewModelTeardown()")
    if tracked != constructors:
        failures.append(
            f"{relative}: {constructors - tracked} of {constructors} "
            "TmuxSessionViewModel constructor(s) are not wrapped in teardown.track(...)"
        )

if failures:
    print("FAIL: MainDispatcherRule/TmuxSessionViewModel teardown guard (#1355)")
    for failure in failures:
        print(f"  - {failure}")
    raise SystemExit(1)

print(
    "PASS: MainDispatcherRule/TmuxSessionViewModel teardown guard "
    f"(#1355) — {len(consumers) - exempt_found} standalone consumer(s) registered; "
    f"{exempt_found} audited inherited-suite exemption(s)."
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

cat > "$fixture_dir/GoodTest.kt" <<'EOF'
class GoodTest {
    val mainDispatcherRule = MainDispatcherRule()
    val teardown = mainDispatcherRule.tmuxSessionViewModelTeardown()
    fun newVm() = teardown.track(
        TmuxSessionViewModel(),
    )
}
EOF
POCKETSHELL_RULE_VM_GUARD_ROOT="$fixture_root" run_guard

cat > "$fixture_dir/BadTest.kt" <<'EOF'
class BadTest {
    val mainDispatcherRule = MainDispatcherRule()
    fun newVm() = TmuxSessionViewModel()
}
EOF
set +e
bad_output="$(POCKETSHELL_RULE_VM_GUARD_ROOT="$fixture_root" run_guard 2>&1)"
bad_rc=$?
set -e
if [[ "$bad_rc" -eq 0 ]] ||
   [[ "$bad_output" != *"BadTest.kt: missing mainDispatcherRule.tmuxSessionViewModelTeardown()"* ]] ||
   [[ "$bad_output" != *"constructor(s) are not wrapped in teardown.track(...)"* ]]; then
  printf '%s\n' "$bad_output"
  echo "FAIL: #1355 guard self-test did not reject an unregistered constructor"
  exit 1
fi

rm -- "$fixture_dir/BadTest.kt"
POCKETSHELL_RULE_VM_GUARD_ROOT="$fixture_root" run_guard
echo "PASS: #1355 guard self-test rejects an omitted consumer migration"
