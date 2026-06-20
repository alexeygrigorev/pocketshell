package com.pocketshell.app.usage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #800: the Usage panel frames window spans data-driven from the
 * record's window NAME (no provider check in the Compose layer). Claude Code
 * and Codex both carry the concrete 5h/7d spans and must read "5h window" /
 * "7d window"; monthly-cadence providers (Copilot) read "Monthly limit", NOT
 * a 7d window; unknown spans fall back to the #522 humanizer.
 */
class UsageWindowLabelTest {

    @Test
    fun fiveHourSpanRendersConcreteLabel() {
        assertEquals("5h window", windowLabel("5h"))
    }

    @Test
    fun sevenDaySpanRendersConcreteLabel() {
        assertEquals("7d window", windowLabel("7d"))
    }

    @Test
    fun monthlySpanRendersMonthlyLabelNot7d() {
        assertEquals("Monthly limit", windowLabel("monthly"))
    }

    @Test
    fun weeklySpanUnchanged() {
        assertEquals("Weekly limit", windowLabel("weekly"))
    }

    @Test
    fun unknownSnakeCaseSpansFallBackToHumanizer() {
        // #522 humanizer: keep "Short term" / "Long term" for unknown spans.
        assertEquals("Short term", windowLabel("short_term"))
        assertEquals("Long term", windowLabel("long_term"))
    }
}
