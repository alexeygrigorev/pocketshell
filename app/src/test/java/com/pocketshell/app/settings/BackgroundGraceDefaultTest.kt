package com.pocketshell.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1123 (bounded-grace D21 update): the background grace window defaults to 5
 * minutes, raised from the original 60 s. Pins the constant + the Settings option list so
 * a regression that drops it back to 60 s (the symptom the maintainer rejected — "not
 * after 60 seconds") fails at PR time.
 */
class BackgroundGraceDefaultTest {

    @Test
    fun `default background grace is five minutes`() {
        assertEquals(
            "background grace default must be 5 minutes (#1123)",
            5 * 60_000L,
            AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS,
        )
        assertEquals(
            AppSettings.BACKGROUND_GRACE_5_MINUTES_MS,
            AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS,
        )
    }

    @Test
    fun `grace options expose 5 minute default with correct labels and no duplicate values`() {
        val options = AppSettings.BACKGROUND_GRACE_OPTIONS
        val byMillis = options.associate { it.millis to it.label }

        assertEquals(
            "the 1-minute option must still exist and be labelled correctly",
            "1 min",
            byMillis[AppSettings.BACKGROUND_GRACE_1_MINUTE_MS],
        )
        assertEquals("5 min", byMillis[AppSettings.BACKGROUND_GRACE_5_MINUTES_MS])
        assertTrue(
            "the default (5 min) must be a selectable option",
            options.any { it.millis == AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS },
        )
        assertEquals(
            "no two options may share a millis value (the 60s default mislabel bug)",
            options.size,
            options.map { it.millis }.toSet().size,
        )
    }
}
