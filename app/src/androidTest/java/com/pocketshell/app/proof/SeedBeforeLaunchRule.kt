package com.pocketshell.app.proof

import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Issue #788 — the seed-before-launch half of the
 * `createAndroidComposeRule<MainActivity>()` harness migration.
 *
 * ## Why this rule exists
 *
 * The load-bearing connection/session journeys used to launch MainActivity by
 * hand inside the `@Test` body:
 *
 * ```kotlin
 * @get:Rule val compose = createEmptyComposeRule()
 * ...
 * seedTmuxSessions(key)                              // seed remote + DB
 * launchedActivity = ActivityScenario.launch(...)    // then launch
 * ```
 *
 * Under the GitHub-hosted swiftshader emulator (and intermittently on the dev
 * box) `createEmptyComposeRule()` + a hand-rolled `ActivityScenario.launch`
 * leaves the Termux `TerminalView` `AndroidView` interop child **never placed
 * into the window** — `waitForTerminalViewAttached` polls null for the full
 * budget and the in-emulator `tmux list-sessions` enumeration stalls (the #470
 * blocker the on-call repeatedly traced to this exact harness shape).
 *
 * The fix ([journey-test-interop-placement-stall]) is to let
 * `createAndroidComposeRule<MainActivity>()` own the activity lifecycle so the
 * Compose test clock drives the SAME foreground activity the interop child is
 * placed into. But that rule launches MainActivity in its own `before()` phase,
 * BEFORE the test body runs — so the DB host row + remote tmux session must
 * already exist when MainActivity first reads `hostDao.getAll()`. The app DB
 * has no `enableMultiInstanceInvalidation()`, so a host written through a
 * separate Room instance AFTER launch is not guaranteed to reach the
 * already-running Flow.
 *
 * This rule runs the per-test seed lambda in its `before()` phase. Wired as the
 * MIDDLE link of a [org.junit.rules.RuleChain]:
 *
 * ```kotlin
 * @get:Rule val chain = RuleChain
 *     .outerRule(PreGrantPermissionsRule())   // grant first (no focus theft)
 *     .around(SeedBeforeLaunchRule { seed() }) // THEN seed remote + DB
 *     .around(compose)                         // THEN launch MainActivity
 * ```
 *
 * `RuleChain` evaluates outer `before()` first, so the order is deterministic:
 * grant permissions, seed the remote tmux sessions + DB host row, and only then
 * does the compose rule launch MainActivity — which now reads a populated DB and
 * places the interop child reliably.
 *
 * The seed lambda is supplied per test class (it varies — plain three-session
 * seed vs. distinct-project seed vs. a single warm-lease host) and may suspend;
 * it runs under [runBlocking] so the rule's synchronous `before()` waits for the
 * remote SSH seeding to complete before the activity launches.
 *
 * The grant rule stays OUTERMOST so the runtime grants are in place before
 * MainActivity's launch could pop the system `GrantPermissionsActivity` and
 * steal window focus from the Compose hierarchy (#470 blocker #1).
 */
class SeedBeforeLaunchRule(
    private val seed: suspend (Description) -> Unit,
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                runBlocking { seed(description) }
                base.evaluate()
            }
        }
}
