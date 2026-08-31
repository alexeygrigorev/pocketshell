package com.pocketshell.app

import com.pocketshell.app.nav.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedNavigationStateTest {
    @Test
    fun nonSessionRouteAndBackStackStayInMemoryForConfigurationRecreation() {
        val owner = RetainedNavigationState()
        val secret = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val host = AppDestination.FolderList(
            hostId = 7L, hostName = "prod", hostname = "prod.example", port = 22,
            username = "alexey", keyPath = "/keys/prod", passphrase = secret,
        )
        owner.backStack += host
        owner.current = AppDestination.Settings

        // A recreated Activity receives the same ViewModel owner. Nothing is
        // written through SavedStateHandle, Bundle, or any process-death store.
        assertEquals(AppDestination.Settings, owner.current)
        assertEquals(listOf(host), owner.backStack)
        assertSame(secret, (owner.backStack.single() as AppDestination.FolderList).passphrase)

        // A true process restart creates a fresh owner with no credentials.
        val afterProcessDeath = RetainedNavigationState()
        assertNull(afterProcessDeath.current)
        assertTrue(afterProcessDeath.backStack.isEmpty())
    }

    @Test
    fun destinationReportingAcceptsEachDistinctDestinationExactlyOnce() {
        val owner = RetainedNavigationState()

        assertTrue(owner.markReported(AppDestination.HostList))
        assertFalse(owner.markReported(AppDestination.HostList))
        assertTrue(owner.markReported(AppDestination.Settings))
        assertFalse(owner.markReported(AppDestination.Settings))
        assertTrue(owner.markReported(AppDestination.HostList))
    }
}
