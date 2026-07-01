package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1159 (maintainer directive 2026-07-01): the controller grace window — the period a
 * backgrounded connection is held before it tears down — is 90 seconds, aligned with
 * `AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS`. Pins the constant so a regression back to
 * the #1123 5-minute value ("5 minutes is longer than needed") fails at PR time.
 */
class ControllerGraceDefaultTest {

    @Test
    fun `controller grace default is ninety seconds`() {
        assertEquals(
            "controller grace default must be 90 seconds (#1159)",
            90_000L,
            ConnectionController.DEFAULT_GRACE_MS,
        )
    }
}
