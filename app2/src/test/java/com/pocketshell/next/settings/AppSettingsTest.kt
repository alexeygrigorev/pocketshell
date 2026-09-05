package com.pocketshell.next.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the five fields' defaults and the fixed option lists — plain data, no
 * Android dependency.
 */
class AppSettingsTest {

    @Test
    fun `a fresh AppSettings is the fresh-install snapshot`() {
        val settings = AppSettings()

        assertEquals(28, settings.terminalTextSizePx)
        assertEquals("auto", settings.voiceLanguage)
        assertEquals(80, settings.usageWarnThresholdPercent)
        assertEquals(90_000L, settings.backgroundGraceMillis)
        assertEquals(150, settings.agentSubmitEnterDelayMs)
    }

    /**
     * Must equal `com.pocketshell.next.terminal.TERMINAL_TEXT_SIZE_RAW_PX`
     * (28) — pinned as a literal here rather than a cross-module reference
     * because that constant is `internal` to the terminal package. A drift
     * between the two numbers is exactly the "default flip" this class doc
     * warns about: the terminal would render one size while a freshly opened
     * Settings screen showed a different one as "current".
     */
    @Test
    fun `the default terminal text size matches the terminal's own raw-pixel default`() {
        assertEquals(28, AppSettings.DEFAULT_TERMINAL_TEXT_SIZE_PX)
    }

    @Test
    fun `background grace defaults to 90 seconds, the maintainer's #1159 directive`() {
        assertEquals(
            AppSettings.BACKGROUND_GRACE_90_SECONDS_MS,
            AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS,
        )
        assertEquals(90_000L, AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS)
    }

    @Test
    fun `every background grace option is unique and ascending`() {
        val millis = AppSettings.BACKGROUND_GRACE_OPTIONS.map { it.millis }
        assertEquals(millis.sorted(), millis)
        assertEquals(millis.size, millis.toSet().size)
    }

    @Test
    fun `every voice language option has a unique code`() {
        val codes = AppSettings.VOICE_LANGUAGE_OPTIONS.map { it.code }
        assertTrue(codes.contains(AppSettings.VOICE_LANGUAGE_AUTO))
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `the usage warn range brackets the fixed critical threshold`() {
        assertTrue(AppSettings.MIN_USAGE_WARN_PERCENT < AppSettings.MAX_USAGE_WARN_PERCENT)
        assertEquals(95, AppSettings.MAX_USAGE_WARN_PERCENT)
    }

    @Test
    fun `the terminal text size range brackets the default`() {
        assertTrue(AppSettings.MIN_TERMINAL_TEXT_SIZE_PX < AppSettings.DEFAULT_TERMINAL_TEXT_SIZE_PX)
        assertTrue(AppSettings.DEFAULT_TERMINAL_TEXT_SIZE_PX < AppSettings.MAX_TERMINAL_TEXT_SIZE_PX)
    }

    @Test
    fun `agent submit delay defaults to 150ms and the range brackets it`() {
        assertEquals(150, AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS)
        assertEquals(0, AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS)
        assertEquals(1000, AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS)
        assertEquals(50, AppSettings.AGENT_SUBMIT_ENTER_DELAY_STEP_MS)
        assertTrue(AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS < AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS)
        assertTrue(AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS < AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS)
    }
}
