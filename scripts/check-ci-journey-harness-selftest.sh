#!/usr/bin/env bash
# Self-test for scripts/check-ci-journey-harness.sh.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$SCRIPT_DIR/check-ci-journey-harness.sh"

SANDBOX="$(mktemp -d)"
cleanup() {
  rm -rf "$SANDBOX"
}
trap cleanup EXIT

mkdir -p \
  "$SANDBOX/scripts" \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/composer" \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/costs" \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/notifications" \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof"

cat > "$SANDBOX/scripts/nightly-extensive-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
NETWORK_FAULT_CLASSES=(
  "$FQCN_PREFIX.OutboundAttachmentOffsetResumeJourneyE2eTest"
)
JOURNEY_EXCLUDED_CLASSES=(
  "${NETWORK_FAULT_CLASSES[@]}"
)
NETWORK_FAULT_CLASS_ARG="classes"
echo "phase 2: network-fault proofs"
gradle \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellNetworkFaultProofs=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$NETWORK_FAULT_CLASS_ARG"
echo "phase 2b: expected fail"
SH

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
  "com.pocketshell.app.composer.PromptComposerOutboundQueueTest"
  "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest#updateNotification_postsToStatusBar_andIsTappable"
  "com.pocketshell.app.tmux.NotAProofEntryE2eTest"
)
SH

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/composer/PromptComposerOutboundQueueTest.kt" <<'KT'
package com.pocketshell.app.composer
class PromptComposerOutboundQueueTest
KT

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/costs/CostsScreenE2eTest.kt" <<'KT'
package com.pocketshell.app.costs
class CostsScreenE2eTest
KT

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/notifications/UpdateAvailableNotificationE2eTest.kt" <<'KT'
package com.pocketshell.app.notifications
class UpdateAvailableNotificationE2eTest {
    @Test
    fun updateNotification_postsToStatusBar_andIsTappable() {
        check(true)
    }
}
KT

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

if printf '%s' "$out" | awk '/NEW FAIL - androidTest E2e\/Docker class not wired/{capture=1; next} /^$/{if (capture) exit} capture {print}' | grep -q 'NotListedBadManualE2eTest'; then
  note_pass "unlisted E2e/Docker fixture is reported by the per-push wiring guard"
else
  note_fail "unlisted E2e/Docker fixture should be reported as unwired"
fi

if printf '%s' "$out" | awk '/KNOWN - unwired androidTest E2e\/Docker baseline/{capture=1; next} /^$/{if (capture) exit} capture {print}' | grep -q 'CostsScreenE2eTest'; then
  note_pass "known current unwired E2e/Docker baseline is spared"
else
  note_fail "known current unwired E2e/Docker baseline should be spared"
fi

rm -f \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/BadManualHarnessE2eTest.kt" \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/BadMissingSeedE2eTest.kt" \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/BadCommentOnlySeedE2eTest.kt" \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/NotListedBadManualE2eTest.kt"
cat > "$SANDBOX/scripts/ci-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
JOURNEY_CLASSES=(
  "$FQCN_PREFIX.GoodLaunchOwnedE2eTest"
  "$FQCN_PREFIX.ExemptManualHarnessE2eTest"
  "com.pocketshell.app.proof.DirectProofEntryE2eTest#singleMethod"
  "com.pocketshell.app.composer.PromptComposerOutboundQueueTest"
  "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest#updateNotification_postsToStatusBar_andIsTappable"
)
SH

out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 0 ]]; then
  note_pass "guard passes with only compliant or justified listed fixtures"
else
  note_fail "guard should pass after removing bad fixtures (got exit $rc)"
fi

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/OutboundAttachmentOffsetResumeJourneyE2eTest.kt" <<'KT'
package com.pocketshell.app.proof
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pocketshell.app.MainActivity
class OutboundAttachmentOffsetResumeJourneyE2eTest {
    val compose = createAndroidComposeRule<MainActivity>()
    val seed = SeedBeforeLaunchRule {
        assertTrue(
            "issue #1733 requires the explicitly opted-in Toxiproxy fixture",
            true,
        )
    }
}
KT
cat > "$SANDBOX/scripts/ci-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
JOURNEY_CLASSES=(
  "$FQCN_PREFIX.OutboundAttachmentOffsetResumeJourneyE2eTest"
  "$FQCN_PREFIX.GoodLaunchOwnedE2eTest"
  "$FQCN_PREFIX.ExemptManualHarnessE2eTest"
  "com.pocketshell.app.proof.DirectProofEntryE2eTest#singleMethod"
  "com.pocketshell.app.composer.PromptComposerOutboundQueueTest"
  "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest#updateNotification_postsToStatusBar_andIsTappable"
)
SH
sed -i '/OutboundAttachmentOffsetResumeJourneyE2eTest/d' "$SANDBOX/scripts/nightly-extensive-suite.sh"

out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'is not in nightly NETWORK_FAULT_CLASSES'; then
  note_pass "#1733 class selected without nightly Toxiproxy routing is a hard failure"
else
  note_fail "missing #1733 nightly fixture routing should fail (got exit $rc)"
fi

sed -i '/NETWORK_FAULT_CLASSES=(/a\  "$FQCN_PREFIX.OutboundAttachmentOffsetResumeJourneyE2eTest"' \
  "$SANDBOX/scripts/nightly-extensive-suite.sh"
out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 0 ]]; then
  note_pass "#1733 class passes when routed to the fixture-backed gating phase"
else
  note_fail "fixture-backed #1733 nightly routing should pass (got exit $rc)"
fi
rm -f "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/OutboundAttachmentOffsetResumeJourneyE2eTest.kt"

cat > "$SANDBOX/scripts/ci-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
JOURNEY_CLASSES=(
  "$FQCN_PREFIX.DeepLinkSessionSwitchE2eTest"
  "com.pocketshell.app.composer.PromptComposerOutboundQueueTest"
  "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest#updateNotification_postsToStatusBar_andIsTappable"
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
rm -f "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/DeepLinkSessionSwitchE2eTest.kt"

cat > "$SANDBOX/scripts/ci-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
JOURNEY_CLASSES=(
  "com.pocketshell.app.tmux.NotAProofEntryE2eTest"
  "com.pocketshell.app.composer.PromptComposerOutboundQueueTest"
  "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest#updateNotification_postsToStatusBar_andIsTappable"
)
SH

out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'NO_PROOF_CLASSES_PARSED'; then
  note_pass "empty parsed proof class allowlist is a hard failure"
else
  note_fail "empty parsed proof class allowlist should fail (got exit $rc)"
fi

cat > "$SANDBOX/scripts/ci-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
JOURNEY_CLASSES=(
  "$FQCN_PREFIX.GoodLaunchOwnedE2eTest"
  "$FQCN_PREFIX.ExemptManualHarnessE2eTest"
  "com.pocketshell.app.proof.DirectProofEntryE2eTest#singleMethod"
  "com.pocketshell.app.composer.PromptComposerOutboundQueueTest"
  "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest#updateNotification_postsToStatusBar_andIsTappable"
)
SH

out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 0 ]]; then
  note_pass "the exact update-notification method is accepted as the required per-push selector"
else
  note_fail "the exact update-notification selector should satisfy the required per-push contract (got exit $rc)"
fi

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/notifications/UpdateAvailableNotificationE2eTest.kt" <<'KT'
package com.pocketshell.app.notifications
class UpdateAvailableNotificationE2eTest {
    // @Test
    fun updateNotification_postsToStatusBar_andIsTappable() {
        check(true)
    }
}
KT
out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'missing @Test method.*UpdateAvailableNotificationE2eTest'; then
  note_pass "a line-commented @Test cannot make the required method executable"
else
  note_fail "a line-commented @Test should fail the exact-method guard specifically (got exit $rc)"
fi

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/notifications/UpdateAvailableNotificationE2eTest.kt" <<'KT'
package com.pocketshell.app.notifications
class UpdateAvailableNotificationE2eTest {
    /*
     * @Test
     */
    fun updateNotification_postsToStatusBar_andIsTappable() {
        check(true)
    }
}
KT
out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'missing @Test method.*UpdateAvailableNotificationE2eTest'; then
  note_pass "a block-commented @Test cannot make the required method executable"
else
  note_fail "a block-commented @Test should fail the exact-method guard specifically (got exit $rc)"
fi

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/notifications/UpdateAvailableNotificationE2eTest.kt" <<'KT'
package com.pocketshell.app.notifications
class UpdateAvailableNotificationE2eTest {
    private val decoration = """
        @Test
    """
    fun updateNotification_postsToStatusBar_andIsTappable() {
        check(decoration.isNotEmpty())
    }
}
KT
out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'missing @Test method.*UpdateAvailableNotificationE2eTest'; then
  note_pass "an @Test string literal cannot make the required method executable"
else
  note_fail "an @Test string literal should fail the exact-method guard specifically (got exit $rc)"
fi

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/notifications/UpdateAvailableNotificationE2eTest.kt" <<'KT'
package com.pocketshell.app.notifications
class UpdateAvailableNotificationE2eTest {
    @Test
    fun helper() = Unit
    fun updateNotification_postsToStatusBar_andIsTappable() {
        check(true)
    }
}
KT
out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'missing @Test method.*UpdateAvailableNotificationE2eTest'; then
  note_pass "an active @Test on another method cannot annotate the required method"
else
  note_fail "an @Test on another method should fail the exact-method guard specifically (got exit $rc)"
fi

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/notifications/UpdateAvailableNotificationE2eTest.kt" <<'KT'
package com.pocketshell.app.notifications
class UpdateAvailableNotificationE2eTest {
    @Test
    // fun updateNotification_postsToStatusBar_andIsTappable() = Unit
}
KT
out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'missing @Test method.*UpdateAvailableNotificationE2eTest'; then
  note_pass "a commented required method cannot satisfy the selector"
else
  note_fail "a commented required method should fail the exact-method guard specifically (got exit $rc)"
fi

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/notifications/UpdateAvailableNotificationE2eTest.kt" <<'KT'
package com.pocketshell.app.notifications
class UpdateAvailableNotificationE2eTest {
    @org.junit.Test
    /* A real block comment and blank line may separate annotation and method. */

    public fun updateNotification_postsToStatusBar_andIsTappable() {
        check(true)
    }
}
KT
out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 0 ]]; then
  note_pass "an active fully-qualified @Test remains related across whitespace and block comments"
else
  note_fail "a real @Test separated by whitespace/comments should remain executable (got exit $rc)"
fi

cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/notifications/UpdateAvailableNotificationE2eTest.kt" <<'KT'
package com.pocketshell.app.notifications
class UpdateAvailableNotificationE2eTest {
    @Test
    fun updateNotification_postsToStatusBar_andIsTappable() {
        check(true)
    }
}
KT

sed -i \
  's/^  "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest#/  # "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest#/' \
  "$SANDBOX/scripts/ci-journey-suite.sh"
out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'UpdateAvailableNotificationE2eTest#updateNotification_postsToStatusBar_andIsTappable'; then
  note_pass "a commented-out update selector cannot satisfy the required per-push wiring"
else
  note_fail "commenting out the required update selector should fail specifically (got exit $rc)"
fi
sed -i \
  's/^  # "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest#/  "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest#/' \
  "$SANDBOX/scripts/ci-journey-suite.sh"

sed -i \
  's/UpdateAvailableNotificationE2eTest#updateNotification_postsToStatusBar_andIsTappable/UpdateAvailableNotificationE2eTest/' \
  "$SANDBOX/scripts/ci-journey-suite.sh"
out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'UpdateAvailableNotificationE2eTest#updateNotification_postsToStatusBar_andIsTappable'; then
  note_pass "a bare-class update selector cannot stand in for the required exact method"
else
  note_fail "replacing the required update method with a bare-class selector should fail specifically (got exit $rc)"
fi

sed -i \
  's/UpdateAvailableNotificationE2eTest/UpdateAvailableNotificationE2eTest#updateNotification_postsToStatusBar_andIsTappable/' \
  "$SANDBOX/scripts/ci-journey-suite.sh"
cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/notifications/UpdateAvailableNotificationE2eTest.kt" <<'KT'
package com.pocketshell.app.notifications
class UpdateAvailableNotificationE2eTest {
    @Test
    fun updateNotification_postsToStatusBar_andIsTappable() {
        Assume.assumeFalse(TerminalTestTimeouts.isRunningOnCi())
        check(true)
    }
}
KT
out="$(run_guard)"
rc=$?
if [[ "$rc" -eq 1 ]] && printf '%s' "$out" | grep -q 'CI self-skip'; then
  note_pass "a required update-notification method cannot retain its CI self-skip"
else
  note_fail "a required update-notification method with a CI self-skip should fail specifically (got exit $rc)"
fi

echo
echo "=============================================================="
echo " Self-test result: $PASS passed, $FAIL failed"
echo "=============================================================="

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
