package com.pocketshell.app.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxSessionRuntimeCacheTest {

    @Test
    fun removeEvictsOnlyMatchingRuntimeKey() {
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val sessionA = cachedRuntime("work")
        val sessionB = cachedRuntime("other")
        val otherHost = cachedRuntime("work", hostId = 2L)

        assertTrue(cache.put(sessionA).isEmpty())
        assertTrue(cache.put(sessionB).isEmpty())
        assertTrue(cache.put(otherHost).isEmpty())

        assertSame(sessionB, cache.remove(sessionB.key))

        assertEquals(listOf(sessionA.key, otherHost.key), cache.snapshotKeys())
        assertTrue(cache.contains(sessionA.key))
        assertFalse(cache.contains(sessionB.key))
        assertTrue(cache.contains(otherHost.key))
    }

    @Test
    fun removeHostExplicitlyClearsEveryRuntimeForHost() {
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val sessionA = cachedRuntime("work")
        val sessionB = cachedRuntime("other")
        val otherHost = cachedRuntime("work", hostId = 2L)

        cache.put(sessionA)
        cache.put(sessionB)
        cache.put(otherHost)

        assertEquals(listOf(sessionA, sessionB), cache.removeHost(1L))

        assertEquals(listOf(otherHost.key), cache.snapshotKeys())
    }

    private fun cachedRuntime(
        sessionName: String,
        hostId: Long = 1L,
    ): CachedTmuxRuntime =
        CachedTmuxRuntime(
            key = TmuxRuntimeKey(
                hostId = hostId,
                hostname = "alpha.example",
                port = 22,
                username = "alex",
                keyPath = "/keys/a",
                sessionName = sessionName,
            ),
            hostName = "alpha",
            startDirectory = null,
            session = null,
            client = FakeTmuxClient(),
            panes = emptyList(),
            paneRows = emptyMap(),
            paneProducerJobs = emptyMap(),
            paneInputQueues = emptyMap(),
            paneInputJobs = emptyMap(),
            paneAgentJobs = emptyMap(),
            paneAgentInputs = emptyMap(),
            agentConversations = emptyMap(),
            remoteColumns = 0,
            remoteRows = 0,
            lease = null,
        )
}
