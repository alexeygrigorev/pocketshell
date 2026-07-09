package com.pocketshell.app.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TmuxSessionIdentityTest {
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
