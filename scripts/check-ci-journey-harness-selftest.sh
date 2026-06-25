#!/usr/bin/env bash
# Self-test for scripts/check-ci-journey-harness.sh.
#
# Plants synthetic journey classes in a temporary repo-shaped fixture and proves
# the guard fails on the old #743/#788 harness, fails on a launch-owned class
# missing SeedBeforeLaunchRule, passes the launch-owned + shared-seed shape, and
# honors a local inline exemption. It also proves that comment-only seed mentions,
# stale baseline entries, and an empty parsed proof allowlist are hard failures.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$SCRIPT_DIR/check-ci-journey-harness.sh"

SANDBOX="$(mktemp -d)"
cleanup() {
  rm -rf "$SANDBOX"
}
trap cleanup EXIT

mkdir -p "$SANDBOX/scripts" "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof"

cat > "$SANDBOX/scripts/ci-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
JOURNEY_CLASSES=(
  "$FQCN_PREFIX.BadManualHarnessE2eTest"
  "$FQCN_PREFIX.BadMissingSeedE2eTest"
  "$FQCN_PREFIX.BadCommentOnlySeedE2eTest"
  "$FQCN_PREFIX.GoodLaunchOwnedE2eTest"
  "$FQCN_PREFIX.ExemptManualHarnessE2eTest"
  "com.pocketshell.app.proof.DirectProofEntryE2eTest#singleMethod"
  "com.pocketshell.app.tmux.NotAProofEntryE2eTest"
)
SH

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/BadManualHarnessE2eTest.kt" <<'KT'
package com.pocketshell.app.proof
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.pocketshell.app.MainActivity
class BadManualHarnessE2eTest {
    val compose = createEmptyComposeRule()
    fun test() {
        ActivityScenario.launch(MainActivity::class.java)
    }
}
KT

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/BadMissingSeedE2eTest.kt" <<'KT'
package com.pocketshell.app.proof
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pocketshell.app.MainActivity
class BadMissingSeedE2eTest {
    val compose = createAndroidComposeRule<MainActivity>()
}
KT

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/BadCommentOnlySeedE2eTest.kt" <<'KT'
package com.pocketshell.app.proof
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pocketshell.app.MainActivity
class BadCommentOnlySeedE2eTest {
    val compose = createAndroidComposeRule<MainActivity>()
    val marker = "not a rule" // TODO migrate to SeedBeforeLaunchRule { seed() }
    // TODO migrate to SeedBeforeLaunchRule { seed() }
}
KT

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/GoodLaunchOwnedE2eTest.kt" <<'KT'
package com.pocketshell.app.proof
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pocketshell.app.MainActivity
class GoodLaunchOwnedE2eTest {
    val compose = createAndroidComposeRule<MainActivity>()
    val seed = SeedBeforeLaunchRule { }
}
KT

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/ExemptManualHarnessE2eTest.kt" <<'KT'
package com.pocketshell.app.proof
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.pocketshell.app.MainActivity
class ExemptManualHarnessE2eTest {
    // JOURNEY_HARNESS_JUSTIFIED: this fixture verifies two explicit manual relaunches.
    val compose = createEmptyComposeRule()
    fun test() {
        // JOURNEY_HARNESS_JUSTIFIED: this fixture verifies two explicit manual relaunches.
        ActivityScenario.launch(MainActivity::class.java)
    }
}
KT

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/DirectProofEntryE2eTest.kt" <<'KT'
package com.pocketshell.app.proof
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pocketshell.app.MainActivity
class DirectProofEntryE2eTest {
    val compose = createAndroidComposeRule<MainActivity>()
    val seed = SeedBeforeLaunchRule { }
}
KT

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/NotListedBadManualE2eTest.kt" <<'KT'
package com.pocketshell.app.proof
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.pocketshell.app.MainActivity
class NotListedBadManualE2eTest {
    val compose = createEmptyComposeRule()
    fun test() {
        ActivityScenario.launch(MainActivity::class.java)
    }
}
KT

PASS=0
FAIL=0
note_pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
note_fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }

run_guard() {
  POCKETSHELL_JOURNEY_HARNESS_REPO_ROOT="$SANDBOX" "$GUARD" "$@" 2>&1
}

echo "=============================================================="
echo " Self-test: scripts/check-ci-journey-harness.sh"
echo "=============================================================="

out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]]; then
  note_pass "guard fails when bad listed proof fixtures are present"
else
  note_fail "guard should fail with bad listed proof fixtures (got exit $rc)"
fi

if printf '%s' "$out" | grep -q 'BadManualHarnessE2eTest'; then
  note_pass "manual ActivityScenario/createEmptyComposeRule fixture is reported"
else
  note_fail "manual old-harness fixture was not reported"
fi

if printf '%s' "$out" | grep -q 'BadMissingSeedE2eTest'; then
  note_pass "launch-owned fixture missing SeedBeforeLaunchRule is reported"
else
  note_fail "missing SeedBeforeLaunchRule fixture was not reported"
fi

if printf '%s' "$out" | awk '/NEW FAIL - createAndroidComposeRule without SeedBeforeLaunchRule/{capture=1; next} /^$/{if (capture) exit} capture {print}' | grep -q 'BadCommentOnlySeedE2eTest'; then
  note_pass "comment-only SeedBeforeLaunchRule mention is not accepted"
else
  note_fail "comment-only SeedBeforeLaunchRule mention should be reported as missing shared seed"
fi

if ! printf '%s' "$out" | awk '/NEW FAIL - manual/{capture=1; next} /^NEW FAIL - createAndroid/{capture=0} capture {print}' | grep -q 'ExemptManualHarnessE2eTest'; then
  note_pass "inline JOURNEY_HARNESS_JUSTIFIED manual exemption is spared"
else
  note_fail "inline manual exemption was incorrectly reported as a new failure"
fi

if ! printf '%s' "$out" | grep -q 'NotListedBadManualE2eTest'; then
  note_pass "unlisted proof fixture is ignored"
else
  note_fail "unlisted proof fixture should not be scanned"
fi

rm -f \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/BadManualHarnessE2eTest.kt" \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/BadMissingSeedE2eTest.kt" \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/BadCommentOnlySeedE2eTest.kt"
cat > "$SANDBOX/scripts/ci-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
JOURNEY_CLASSES=(
  "$FQCN_PREFIX.GoodLaunchOwnedE2eTest"
  "$FQCN_PREFIX.ExemptManualHarnessE2eTest"
  "com.pocketshell.app.proof.DirectProofEntryE2eTest#singleMethod"
)
SH

out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 0 ]]; then
  note_pass "guard passes with only compliant or justified listed fixtures"
else
  note_fail "guard should pass after removing bad fixtures (got exit $rc)"
fi

cat > "$SANDBOX/scripts/ci-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
JOURNEY_CLASSES=(
  "$FQCN_PREFIX.DeepLinkSessionSwitchE2eTest"
)
SH
cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/DeepLinkSessionSwitchE2eTest.kt" <<'KT'
package com.pocketshell.app.proof
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pocketshell.app.MainActivity
class DeepLinkSessionSwitchE2eTest {
    val compose = createAndroidComposeRule<MainActivity>()
    val seed = SeedBeforeLaunchRule { }
}
KT

out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'KNOWN_MANUAL_HARNESS:DeepLinkSessionSwitchE2eTest'; then
  note_pass "stale known manual-harness baseline is a hard failure"
else
  note_fail "stale known manual-harness baseline should fail (got exit $rc)"
fi

cat > "$SANDBOX/scripts/ci-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
JOURNEY_CLASSES=(
  "com.pocketshell.app.tmux.NotAProofEntryE2eTest"
)
SH

out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'NO_PROOF_CLASSES_PARSED'; then
  note_pass "empty parsed proof class allowlist is a hard failure"
else
  note_fail "empty parsed proof class allowlist should fail (got exit $rc)"
fi

echo
echo "=============================================================="
echo " Self-test result: $PASS passed, $FAIL failed"
echo "=============================================================="

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
