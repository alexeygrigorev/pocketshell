package com.pocketshell.app.usage

import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.App
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement

/**
 * Class-fixture boundary for connected tests that render the host-list usage UI.
 *
 * AndroidJUnitRunner keeps the target application (and therefore the Hilt
 * [UsageScheduler] singleton) alive across test classes. Clearing Room in a
 * later class does not clear snapshots already published by that singleton,
 * while an owner that leaves an eligible host can repopulate the snapshots on
 * the scheduler's five-minute cadence. Reset both halves of that state before
 * and after the owning class so isolation does not depend on naming every
 * earlier test in a shard.
 */
internal class UsageSchedulerTestIsolationRule(
    private val boundaryOverride: UsageSchedulerTestBoundary? = null,
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val primaryFailure = runCatching {
                    reset(description, "before")
                    base.evaluate()
                }.exceptionOrNull()
                val afterFailure = runCatching {
                    reset(description, "after")
                }.exceptionOrNull()

                if (primaryFailure != null) {
                    afterFailure?.let(primaryFailure::addSuppressed)
                    throw primaryFailure
                }
                afterFailure?.let { throw it }
            }
        }

    private fun reset(description: Description, boundary: String) {
        (boundaryOverride ?: realBoundary()).reset(description, boundary)
    }

    private fun realBoundary(): UsageSchedulerTestBoundary {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val app = targetContext.applicationContext as App
        val database = EntryPointAccessors.fromApplication(
            targetContext,
            TestAccessEntryPoint::class.java,
        ).appDatabase()

        return UsageSchedulerTestBoundary(
            stop = { runBlocking { app.usageScheduler.stop() } },
            clear = { runBlocking { database.clearAllTables() } },
            refresh = { runBlocking { app.usageScheduler.refreshNow() } },
            snapshotKeys = { app.usageScheduler.snapshots.value.keys },
            start = app.usageScheduler::start,
            logEmpty = { test, edge ->
                println(
                    "USAGE_TEST_ISOLATION class=${test.className} " +
                        "boundary=$edge snapshots=0",
                )
            },
        )
    }
}

/** Injectable lifecycle core so failure semantics are deterministic to test. */
internal class UsageSchedulerTestBoundary(
    private val stop: () -> Unit,
    private val clear: () -> Unit,
    private val refresh: () -> Unit,
    private val snapshotKeys: () -> Set<*>,
    private val start: () -> Unit,
    private val logEmpty: (Description, String) -> Unit = { _, _ -> },
) {
    fun reset(description: Description, boundary: String) {
        val failures = mutableListOf<Throwable>()
        var stopCompleted = false
        try {
            runCatching { stop() }
                .onSuccess { stopCompleted = true }
                .exceptionOrNull()
                ?.let(failures::add)

            if (stopCompleted) {
                // Keep attempting the complete drain after an individual
                // failure so the boundary gathers all useful cleanup evidence.
                runCatching { clear() }.exceptionOrNull()?.let(failures::add)
                runCatching { refresh() }.exceptionOrNull()?.let(failures::add)

                val keys = runCatching { snapshotKeys() }
                    .onFailure(failures::add)
                    .getOrNull()
                if (keys != null) {
                    val emptyFailure = runCatching {
                        check(keys.isEmpty()) {
                            "${description.className} leaked usage snapshots at the " +
                                "$boundary class boundary: $keys"
                        }
                    }.exceptionOrNull()
                    emptyFailure?.let(failures::add)
                    if (emptyFailure == null) {
                        runCatching { logEmpty(description, boundary) }
                            .exceptionOrNull()
                            ?.let(failures::add)
                    }
                }
            }
        } finally {
            // Once stop() has completed, no drain/assertion/logging failure may
            // poison every later class by leaving the process singleton down.
            if (stopCompleted) {
                runCatching { start() }.exceptionOrNull()?.let(failures::add)
            }
        }
        MultipleFailureException.assertEmpty(failures)
    }
}
