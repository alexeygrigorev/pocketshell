package com.pocketshell.app.proof

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Issue #470 (blocker #1): a reusable JUnit [TestRule] that pre-grants
 * every runtime dangerous permission MainActivity (or a downstream screen)
 * may request, BEFORE the test body runs and therefore before the test
 * launches [com.pocketshell.app.MainActivity] via
 * `ActivityScenario.launch`.
 *
 * Why a rule instead of 30 copy-pasted [preGrantRuntimePermissions] calls:
 * every connected E2E in this module launches MainActivity manually with
 * `createEmptyComposeRule()` + `ActivityScenario.launch(...)`. On a freshly
 * rebooted emulator the runtime grants (`POST_NOTIFICATIONS`,
 * `RECORD_AUDIO`, `CAMERA`) reset to "ask", so the system
 * `GrantPermissionsActivity` pops over MainActivity at launch and steals
 * window focus from the Compose hierarchy — the runner then throws
 * `IllegalStateException: No compose hierarchies found in the app`. A
 * single shared rule adds the grant to a test with one `@get:Rule` line
 * and one import, keeps the grant logic in exactly one place
 * ([preGrantRuntimePermissions]), and means a newly added connected test
 * gets the hardening by declaring the rule rather than remembering to call
 * a helper before each launch.
 *
 * The rule's [apply] wraps the test [Statement] so the grant runs in the
 * rule's `before` phase. Because all connected E2E in this module launch
 * MainActivity inside the test body (not in a rule's `before`), the grant
 * is guaranteed to have completed before any `GrantPermissionsActivity`
 * could be triggered. Ordering relative to the sibling
 * `createEmptyComposeRule()` therefore does not matter: neither rule
 * launches the activity, so whichever runs first, the grant is in place by
 * the time the test body calls `ActivityScenario.launch`.
 *
 * Grants are best-effort (see [preGrantRuntimePermissions]): a permission
 * the device has already granted or does not declare is a no-op, never a
 * hard failure, so the rule is safe on every API level and device state.
 */
class PreGrantPermissionsRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                preGrantRuntimePermissions()
                base.evaluate()
            }
        }
}
