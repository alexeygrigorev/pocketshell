package com.pocketshell.app.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

class UsageSchedulerTestIsolationRuleTest {
    private val description = Description.createTestDescription(javaClass, "fixture")

    @Test
    fun successfulBoundaryStopsBeforeDrainAssertsEmptyAndRestarts() {
        val events = mutableListOf<String>()
        val rule = rule(events = events)

        rule.apply(statement { events += "test" }, description).evaluate()

        assertEquals(
            listOf(
                "stop", "clear", "refresh", "assert-empty", "log-before", "start",
                "test",
                "stop", "clear", "refresh", "assert-empty", "log-after", "start",
            ),
            events,
        )
    }

    @Test
    fun clearFailureStillRestartsScheduler() {
        assertFailureStillRestarts(failingEvent = "clear")
    }

    @Test
    fun refreshFailureStillRestartsScheduler() {
        assertFailureStillRestarts(failingEvent = "refresh")
    }

    @Test
    fun nonEmptySnapshotAssertionStillRestartsScheduler() {
        assertFailureStillRestarts(failingEvent = "assert-empty")
    }

    @Test
    fun protectedTestFailureRemainsPrimaryWhenAfterBoundaryAlsoFails() {
        val primary = IllegalStateException("protected test failed")
        val cleanup = IllegalArgumentException("after refresh failed")
        var refreshCount = 0
        val events = mutableListOf<String>()
        val rule = rule(
            events = events,
            throwAt = { event ->
                if (event == "refresh" && ++refreshCount == 2) cleanup else null
            },
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            rule.apply(statement { throw primary }, description).evaluate()
        }

        assertSame(primary, thrown)
        assertEquals(listOf(cleanup), thrown.suppressed.toList())
        assertEquals(2, events.count { it == "start" })
    }

    @Test
    fun protectedTestFailureRemainsPrimaryWhenAfterBoundaryRestartFails() {
        val primary = IllegalStateException("protected test failed")
        val restart = IllegalArgumentException("after restart failed")
        var startCount = 0
        val events = mutableListOf<String>()
        val rule = rule(
            events = events,
            throwAt = { event ->
                if (event == "start" && ++startCount == 2) restart else null
            },
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            rule.apply(statement { throw primary }, description).evaluate()
        }

        assertSame(primary, thrown)
        assertEquals(listOf(restart), thrown.suppressed.toList())
        assertEquals(2, events.count { it == "start" })
    }

    private fun assertFailureStillRestarts(failingEvent: String) {
        val failure = IllegalStateException("$failingEvent failed")
        val events = mutableListOf<String>()
        val boundary = boundary(
            events = events,
            throwAt = { event ->
                if (event == failingEvent && failingEvent != "assert-empty") failure else null
            },
            snapshotKeys = if (failingEvent == "assert-empty") setOf("claude") else emptySet(),
        )

        val thrown = assertThrows(Throwable::class.java) {
            boundary.reset(description, "after")
        }

        if (failingEvent == "assert-empty") {
            check(thrown.message.orEmpty().contains("leaked usage snapshots"))
        } else {
            assertSame(failure, thrown)
        }
        assertEquals(
            "scheduler must restart after $failingEvent failure",
            1,
            events.count { it == "start" },
        )
    }

    private fun rule(
        events: MutableList<String>,
        throwAt: (String) -> Throwable? = { null },
    ): UsageSchedulerTestIsolationRule = UsageSchedulerTestIsolationRule(
        boundaryOverride = boundary(events, throwAt),
    )

    private fun boundary(
        events: MutableList<String>,
        throwAt: (String) -> Throwable? = { null },
        snapshotKeys: Set<String> = emptySet(),
    ): UsageSchedulerTestBoundary = UsageSchedulerTestBoundary(
        stop = operation("stop", events, throwAt),
        clear = operation("clear", events, throwAt),
        refresh = operation("refresh", events, throwAt),
        snapshotKeys = {
            events += "assert-empty"
            throwAt("assert-empty")?.let { throw it }
            snapshotKeys
        },
        start = operation("start", events, throwAt),
        logEmpty = { _, edge -> events += "log-$edge" },
    )

    private fun operation(
        name: String,
        events: MutableList<String>,
        throwAt: (String) -> Throwable?,
    ): () -> Unit = {
        events += name
        throwAt(name)?.let { throw it }
    }

    private fun statement(block: () -> Unit): Statement = object : Statement() {
        override fun evaluate() = block()
    }
}
