package com.pocketshell.app.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmuxSessionIdentityTest {
    @Test
    fun durableIdentityRoundTripsForNavigationWithoutNameFallback() {
        val key = durableTmuxSessionKey(7, "\$12", 1_700_000_000)
        assertEquals(
            DurableTmuxSessionIdentity("\$12", 1_700_000_000),
            parseDurableTmuxSessionIdentity(7, key),
        )
        assertNull(parseDurableTmuxSessionIdentity(8, key))
        assertNull(parseDurableTmuxSessionIdentity(7, "7/project-a"))
    }

    @Test
    fun sessionCardsTargetKeyTrimsSessionName() {
        assertEquals(
            sessionCardsTargetKey(
                hostId = 42L,
                host = "example.test",
                port = 22,
                user = "alexey",
                keyPath = "/keys/id",
                sessionName = " work ",
            ),
            sessionCardsTargetKey(
                hostId = 42L,
                host = "example.test",
                port = 22,
                user = "alexey",
                keyPath = "/keys/id",
                sessionName = "work",
            ),
        )
    }

    @Test
    fun sessionCardsTargetKeyLengthPrefixesPartsToAvoidSeparatorCollisions() {
        val left = sessionCardsTargetKey(
            hostId = 1L,
            host = "a|b",
            port = 22,
            user = "c",
            keyPath = "d",
            sessionName = "e",
        )
        val right = sessionCardsTargetKey(
            hostId = 1L,
            host = "a",
            port = 22,
            user = "b|c",
            keyPath = "d",
            sessionName = "e",
        )

        assertNotEquals(left, right)
    }
}
