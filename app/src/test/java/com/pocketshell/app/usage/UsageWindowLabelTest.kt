package com.pocketshell.app.usage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #800: the Usage panel frames window spans data-driven from the
 * record's window NAME (no provider check in the Compose layer). Claude Code
 * and Codex both carry the concrete 5h/7d spans and must read "5h window" /
 * "7d window"; monthly-cadence providers (such as Copilot) read
 * "Monthly limit", NOT a 7d window; unknown spans fall back to the #522
 * humanizer.
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
        // #522 humanizer: any unknown span key reads as prose, not snake_case.
        assertEquals("Custom span", windowLabel("custom_span"))
    }

    @Test
    fun quse0014PublishedWindowKeys_allRenderLabeled() {
        // Issue #2274: published provider-owned keys must render a real label,
        // never a raw key dump. This does not assert an unreleased provider.
        assertEquals("5h window", windowLabel("5h"))
        assertEquals("7d window", windowLabel("7d"))
        assertEquals("Monthly limit", windowLabel("monthly"))
    }
}
