package com.pocketshell.app.hosts

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Process-wide ownership boundary for tests that install or reset
 * `Dispatchers.Main`.
 *
 * Coroutine test dispatchers can read the current Main dispatcher while they
 * are constructed, so every custom Main owner must share this lock with
 * [MainDispatcherRule].
 */
internal object MainDispatcherTestIsolation {
    private val ownershipMonitor = Any()

    fun <T> withOwnership(block: () -> T): T =
        synchronized(ownershipMonitor) {
            block()
        }
}

/**
 * Holds [MainDispatcherTestIsolation] ownership across a complete JUnit
 * statement, including its `@Before` and `@After` methods.
 *
 * Custom fixtures that must manage their own Main dispatcher use this rule;
 * ordinary tests should use [MainDispatcherRule].
 */
class MainDispatcherOwnershipRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                MainDispatcherTestIsolation.withOwnership(base::evaluate)
            }
        }
}
