package com.pocketshell.app.tmux

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelRuntimeCachePolicyTest : TmuxSessionViewModelTestBase() {
    @Test
    fun runtimeCacheEvictsLeastRecentlyUsedRuntimePerHost() = runTest(scheduler) {
        val cache = TmuxSessionRuntimeCache(maxEntries = 2)
        val first = cachedRuntimeForTest("one")
        val second = cachedRuntimeForTest("two")
        val third = cachedRuntimeForTest("three")

        assertTrue(cache.put(first).isEmpty())
        assertTrue(cache.put(second).isEmpty())
        assertSame(first, cache.activate(first.key).runtime)
        assertTrue(cache.put(first).isEmpty())
        val evicted = cache.put(third)

        assertEquals(
            "second should be the least recently used runtime for the host",
            listOf(second),
            evicted,
        )
        assertTrue(cache.contains(first.key))
        assertTrue(cache.contains(third.key))
        assertFalse(cache.contains(second.key))
    }

    @Test
    fun runtimeCachePerHostCapDoesNotEvictOtherHosts() = runTest(scheduler) {
        val cache = TmuxSessionRuntimeCache(maxEntries = 1)
        val hostOneFirst = cachedRuntimeForTest("one", hostId = 1L)
        val hostTwoFirst = cachedRuntimeForTest("one", hostId = 2L)
        val hostOneSecond = cachedRuntimeForTest("two", hostId = 1L)

        assertTrue(cache.put(hostOneFirst).isEmpty())
        assertTrue(cache.put(hostTwoFirst).isEmpty())
        val evicted = cache.put(hostOneSecond)

        assertEquals(listOf(hostOneFirst), evicted)
        assertFalse(cache.contains(hostOneFirst.key))
        assertTrue(cache.contains(hostOneSecond.key))
        assertTrue(cache.contains(hostTwoFirst.key))
    }

    @Test
    fun runtimeCacheEvictsExpiredRuntimesDeterministically() = runTest(scheduler) {
        var nowMs = 0L
        val cache = TmuxSessionRuntimeCache(
            maxEntries = 2,
            ttlMs = 100L,
            nowMs = { nowMs },
        )
        val expired = cachedRuntimeForTest("expired")
        val fresh = cachedRuntimeForTest("fresh")

        assertTrue(cache.put(expired).isEmpty())
        nowMs = 100L

        val evicted = cache.put(fresh)

        assertEquals(listOf(expired), evicted)
        assertFalse(cache.contains(expired.key))
        assertTrue(cache.contains(fresh.key))
        assertNull(cache.activate(expired.key).runtime)
    }

    private fun cachedRuntimeForTest(
        sessionName: String,
        hostId: Long = 1L,
    ): CachedTmuxRuntime {
        val key = TmuxRuntimeKey(
            hostId = hostId,
            hostname = "alpha.example",
            port = 22,
            username = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
        )
        return CachedTmuxRuntime(
            key = key,
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
}
