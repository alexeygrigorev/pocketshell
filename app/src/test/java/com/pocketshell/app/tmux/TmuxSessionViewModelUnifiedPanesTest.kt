package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelUnifiedPanesTest : TmuxSessionViewModelTestBase() {

    private fun unifiedTestPane(
        paneId: String,
        windowId: String = "@0",
        sessionId: String = "\$0",
        title: String = "pane",
    ): TmuxPaneState =
        TmuxPaneState(
            paneId = paneId,
            windowId = windowId,
            sessionId = sessionId,
            title = title,
            cwd = "/tmp",
            terminalState = TerminalSurfaceState(),
        )

    private fun driftedTwinRuntime(
        sessionName: String,
        hostId: Long,
        paneIds: List<String>,
    ): CachedTmuxRuntime =
        CachedTmuxRuntime(
            key = TmuxRuntimeKey(
                hostId = hostId,
                // Same host + same session name, but a DRIFTED keyPath: this is
                // the twin that activate()/deactivate() parked under a key that
                // no longer exactly matches the active session's key.
                hostname = "alpha.example",
                port = 22,
                username = "alex",
                keyPath = "/keys/a-DRIFTED",
                sessionName = sessionName,
            ),
            hostName = "alpha",
            startDirectory = null,
            session = null,
            client = FakeTmuxClient(),
            panes = paneIds.map { unifiedTestPane(it) },
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

    private fun foreignSessionRuntime(
        sessionName: String,
        hostId: Long,
        paneIds: List<String>,
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
            panes = paneIds.map { unifiedTestPane(it) },
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

    @Test
    fun unifiedPanesExcludesDriftedTwinOfActiveSessionSoNoPhantomPage() = runTest(scheduler) {
        // The maintainer's repro: ONE session "work" with TWO windows -> the
        // pager must show EXACTLY 2 pages. A key-drifted TWIN of "work"
        // survives in the cache (parked under a slightly different key, so
        // activate() never removed it). Before the fix, rebuildUnifiedPanes
        // blindly appended the twin's panes -> a phantom 3rd page that, when
        // settled on, mis-routed to a foreign session.
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)

        // Inject the drifted twin of the SAME session ("work") into the cache,
        // carrying the same two panes the active session has.
        runtimeCache.put(driftedTwinRuntime("work", hostId = 1L, paneIds = listOf("%0", "%1")))

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-w0",
                    paneIndex = 0,
                    sessionName = "work",
                ),
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%1",
                    windowId = "@1",
                    sessionId = "\$0",
                    title = "work-w1",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        // Exactly 2 pages for a 2-window session: no phantom.
        val unified = vm.unifiedPanes.value
        assertEquals(
            "2-window session must yield exactly 2 unified pages, got ${unified.map { it.paneId }}",
            2,
            unified.size,
        )
        assertEquals(listOf("%0", "%1"), unified.map { it.paneId })

        // Settling on either real page must NOT emit a switch request - both
        // belong to the active "work" session.
        vm.onUnifiedPageSettled(0)
        vm.onUnifiedPageSettled(1)
        advanceUntilIdle()
    }

    @Test
    fun unifiedPanesSettleOnRealPageRoutesToCorrectSessionNeverForeign() = runTest(scheduler) {
        // 2-window "work" session (active) PLUS a genuinely different cached
        // session "deploy" with its own window, AND a key-drifted twin of
        // "work". The pager must show exactly 3 pages (2 work + 1 deploy),
        // NOT 4, and settling on the deploy page must emit a switch to
        // "deploy" - never to a foreign/random session.
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)

        // The phantom-producing drifted twin of the active session.
        runtimeCache.put(driftedTwinRuntime("work", hostId = 1L, paneIds = listOf("%0", "%1")))
        // A real OTHER session that legitimately deserves its own page.
        runtimeCache.put(foreignSessionRuntime("deploy", hostId = 1L, paneIds = listOf("%5")))

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-w0",
                    paneIndex = 0,
                    sessionName = "work",
                ),
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%1",
                    windowId = "@1",
                    sessionId = "\$0",
                    title = "work-w1",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        val unified = vm.unifiedPanes.value
        assertEquals(
            "expected exactly 3 pages (2 work + 1 deploy), got ${unified.map { it.paneId }}",
            3,
            unified.size,
        )
        assertEquals(listOf("%0", "%1", "%5"), unified.map { it.paneId })

        // The deploy page resolves to "deploy", never the foreign/random twin.
        val deployPane = unified[2]
        assertEquals("deploy", vm.sessionNameForUnifiedPane(deployPane))

        // Capture the FIRST emitted switch. Subscribe before triggering any
        // settle (async + runCurrent reaches the SharedFlow subscription point).
        val firstSwitch = async { vm.sessionSwitchRequest.first() }
        runCurrent()

        // Settling on the active "work" pages must NOT emit a switch - if it
        // did, firstSwitch would resolve to "work" instead of "deploy".
        vm.onUnifiedPageSettled(0)
        vm.onUnifiedPageSettled(1)
        advanceUntilIdle()
        assertTrue(
            "settling on active-session pages must not switch yet",
            !firstSwitch.isCompleted,
        )

        // Settling on the deploy page switches to "deploy" - the CORRECT
        // session, never the foreign/random twin.
        vm.onUnifiedPageSettled(2)
        advanceUntilIdle()
        assertEquals("deploy", firstSwitch.await())
    }

    @Test
    fun runtimeCachePutPrunesDriftedSameSessionTwin() {
        // Cache-level guard: parking a fresh "work" runtime evicts a stale,
        // key-drifted "work" twin so a duplicate can never accumulate.
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val driftedTwin = driftedTwinRuntime("work", hostId = 1L, paneIds = listOf("%0"))
        val fresh = foreignSessionRuntime("work", hostId = 1L, paneIds = listOf("%0"))

        assertTrue(cache.put(driftedTwin).isEmpty())
        // Putting the fresh same-session runtime evicts the drifted twin.
        assertEquals(listOf(driftedTwin), cache.put(fresh))
        assertEquals(listOf(fresh.key), cache.snapshotKeys())
        assertFalse(cache.contains(driftedTwin.key))
    }

    @Test
    fun runtimeCacheActivatePrunesDriftedSameSessionTwin() {
        // Activating "work" (by its canonical key) drops a key-drifted "work"
        // twin still parked under a different key, so the now-active session
        // leaves no duplicate behind.
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val driftedTwin = driftedTwinRuntime("work", hostId = 1L, paneIds = listOf("%0"))
        val canonical = foreignSessionRuntime("work", hostId = 1L, paneIds = listOf("%0"))
        val otherSession = foreignSessionRuntime("deploy", hostId = 1L, paneIds = listOf("%5"))

        cache.put(driftedTwin)
        cache.put(canonical)
        cache.put(otherSession)
        // put already pruned the twin; re-park it to set up the activate case.
        cache.remove(canonical.key)
        cache.put(driftedTwin)

        val activation = cache.activate(canonical.key)
        // The canonical entry was removed above, so activation yields no exact
        // runtime, but the drifted twin is pruned as a same-session duplicate.
        assertEquals(listOf(driftedTwin), activation.evicted)
        assertFalse(cache.contains(driftedTwin.key))
        assertEquals(listOf(otherSession.key), cache.snapshotKeys())
    }
}
