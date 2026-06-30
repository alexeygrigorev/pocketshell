package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1123 (bounded-grace D21 update): the controller grace window — the period a
 * backgrounded connection is held before it tears down — is 5 minutes, aligned with
 * `AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS`. Pins the constant so a regression back to
 * the old 60 s value (which the maintainer rejected) fails at PR time.
 */
class ControllerGraceDefaultTest {

    @Test
    fun `controller grace default is five minutes`() {
        assertEquals(
            "controller grace default must be 5 minutes (#1123)",
            5 * 60_000L,
            ConnectionController.DEFAULT_GRACE_MS,
        )
    }
}
