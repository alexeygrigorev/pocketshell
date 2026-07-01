package com.pocketshell.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1159 (maintainer directive 2026-07-01): the background grace window defaults to
 * **90 seconds**, lowered from the #1123 5-minute value ("make the default like 90 seconds…
 * 5 minutes is longer than needed"). Pins the constant + the Settings option list so a
 * regression back to 5 minutes fails at PR time.
 */
class BackgroundGraceDefaultTest {

    @Test
    fun `default background grace is ninety seconds`() {
        assertEquals(
            "background grace default must be 90 seconds (#1159)",
            90_000L,
            AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS,
        )
        assertEquals(
            AppSettings.BACKGROUND_GRACE_90_SECONDS_MS,
            AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS,
        )
    }

    @Test
    fun `grace options expose the 90 second default with correct labels and no duplicate values`() {
        val options = AppSettings.BACKGROUND_GRACE_OPTIONS
        val byMillis = options.associate { it.millis to it.label }

        assertEquals(
            "the 90-second option must exist and be labelled correctly",
            "90 sec",
            byMillis[AppSettings.BACKGROUND_GRACE_90_SECONDS_MS],
        )
        assertEquals(
            "the 1-minute option must still exist and be labelled correctly",
            "1 min",
            byMillis[AppSettings.BACKGROUND_GRACE_1_MINUTE_MS],
        )
        assertEquals("5 min", byMillis[AppSettings.BACKGROUND_GRACE_5_MINUTES_MS])
        assertTrue(
            "the default (90 sec) must be a selectable option",
            options.any { it.millis == AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS },
        )
        assertEquals(
            "no two options may share a millis value (the 60s default mislabel bug)",
            options.size,
            options.map { it.millis }.toSet().size,
        )
    }
}
