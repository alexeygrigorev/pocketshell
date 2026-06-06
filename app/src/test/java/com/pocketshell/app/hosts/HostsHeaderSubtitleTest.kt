package com.pocketshell.app.hosts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The host-list [ScreenHeader] subtitle (#479 Slice A) is built in the screen,
 * not the shared component — `ScreenHeader` is count-agnostic and does not
 * pluralise (#479 §4). These tests lock the `N hosts · M active` wording the
 * caller is responsible for, including the `1 host` singular.
 */
class HostsHeaderSubtitleTest {

    @Test
    fun pluralisesHostsAndShowsActiveCount() {
        assertEquals("4 hosts · 2 active", hostsHeaderSubtitle(hostCount = 4, activeSessionCount = 2))
    }

    @Test
    fun usesSingularForExactlyOneHost() {
        assertEquals("1 host · 0 active", hostsHeaderSubtitle(hostCount = 1, activeSessionCount = 0))
    }

    @Test
    fun zeroHostsReadsAsPlural() {
        assertEquals("0 hosts · 0 active", hostsHeaderSubtitle(hostCount = 0, activeSessionCount = 0))
    }
}
