package com.pocketshell.next.usage

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives [UsageFetcher] against [TestUsageStack] — a real in-memory Room
 * database and the real [com.pocketshell.next.connect.ConnectionsRegistry],
 * with only the sshj dial swapped for a fake. Everything from "which hosts
 * are connected" to "did the NDJSON parse" is production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UsageFetcherTest {

    private val stack = TestUsageStack()

    @After
    fun tearDown() {
        stack.close()
    }

    @Test
    fun `a host with no live connection is not asked and does not count as connected`() = runBlocking {
        stack.seedHost()

        val result = stack.fetcher.fetchAll()

        assertEquals(0, result.connectedHostCount)
        assertTrue(result.snapshots.isEmpty())
    }

    @Test
    fun `a connected host's records land as a Records snapshot`() = runBlocking {
        val hostId = stack.seedHost("claude-box")
        stack.scriptUsage(CLAUDE_NDJSON)
        stack.connect(hostId)

        val result = stack.fetcher.fetchAll()

        assertEquals(1, result.connectedHostCount)
        val snapshot = result.snapshots.getValue(hostId)
        check(snapshot is UsageSnapshot.Records) { "expected Records, got $snapshot" }
        assertEquals(listOf("claude"), snapshot.records.map { it.provider })
    }

    @Test
    fun `exit 127 is tool-missing, not a failure`() = runBlocking {
        val hostId = stack.seedHost()
        stack.scriptUsage(stdout = "", exitCode = 127, stderr = "sh: pocketshell: not found")
        stack.connect(hostId)

        val result = stack.fetcher.fetchAll()

        val snapshot = result.snapshots.getValue(hostId)
        assertTrue("expected ToolMissing, got $snapshot", snapshot is UsageSnapshot.ToolMissing)
    }

    @Test
    fun `a response that does not parse is a Failed snapshot, not a crash`() = runBlocking {
        val hostId = stack.seedHost()
        stack.scriptUsage("not usage json at all")
        stack.connect(hostId)

        val result = stack.fetcher.fetchAll()

        val snapshot = result.snapshots.getValue(hostId)
        assertTrue("expected Failed, got $snapshot", snapshot is UsageSnapshot.Failed)
    }

    @Test
    fun `every connected host is asked, independently`() = runBlocking {
        val hostA = stack.seedHost("a")
        val hostB = stack.seedHost("b")
        stack.scriptUsage(CLAUDE_NDJSON)
        stack.connect(hostA)
        stack.connect(hostB)

        val result = stack.fetcher.fetchAll()

        assertEquals(2, result.connectedHostCount)
        assertEquals(setOf(hostA, hostB), result.snapshots.keys)
    }

    private companion object {
        const val CLAUDE_NDJSON =
            "{\"provider\":\"claude\",\"status\":\"ok\"," +
                "\"windows\":{\"5h\":{\"percent_remaining\":80.0,\"reset_at\":null}}," +
                "\"block_reason\":null,\"error\":null,\"details\":{}}"
    }
}
