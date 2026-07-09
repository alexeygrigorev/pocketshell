package com.pocketshell.app.tmux

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelPrewarmSeedTest : TmuxSessionViewModelTestBase() {
    @Test
    fun sessionSwitcherPrewarmCachesOnlyBoundedLikelyTargets() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession(), FakeSshSession(), FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val activeSession = FakeSshSession()
        val prewarmClients = listOf(
            FakeTmuxClient().withSinglePane("recent-a", "%1"),
            FakeTmuxClient().withSinglePane("recent-b", "%2"),
            FakeTmuxClient().withSinglePane("recent-c", "%3"),
        )
        val clients = ArrayDeque(prewarmClients)
        vm.setTmuxClientFactoryForTest { _, _, _ -> clients.removeFirst() }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = activeSession,
        )

        vm.prewarmLikelySwitchTargets(listOf("work", "recent-a", "recent-b", "recent-c"))
        advanceUntilIdle()

        assertTrue(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent-a")))
        assertTrue(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent-b")))
        assertFalse(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent-c")))
        assertEquals(
            "prewarm must stay capped to likely switch targets",
            TMUX_SESSION_PREWARM_MAX_TARGETS,
            prewarmClients.count { it.connectCalled },
        )
        assertEquals(
            "prewarm should reuse the warm SSH lease when possible",
            1,
            connector.connectCount,
        )
    }

    @Test
    fun sessionPrewarmSeedsCaptureWithCursorRestore() = runTest(scheduler) {
        // Issue #640: the prewarm seed shares the capture+cursor exchange via
        // [TmuxClient.captureWithCursor] (single-flight in production), pairing
        // the capture with the cursor restore, and still caches the runtime +
        // keeps the client open.
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val prewarmClient = FakeTmuxClient().withSinglePane("recent", "%4")
        vm.setTmuxClientFactoryForTest { _, _, _ -> prewarmClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )

        vm.prewarmLikelySwitchTargets(listOf("recent"))
        advanceUntilIdle()

        assertTrue(
            "expected prewarm seed to capture the pane, got ${prewarmClient.sentCommands}",
            prewarmClient.sentCommands.contains(seedCaptureCommand("%4")),
        )
        assertTrue(
            "expected prewarm seed to pair the capture with a cursor restore query, " +
                "got ${prewarmClient.sentCommands}",
            prewarmClient.sentCommands.contains(seedCursorCommand("%4")),
        )
        assertTrue(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent")))
        assertFalse("prewarm seed timeout must not close tmux client", prewarmClient.closed)
        assertFalse("prewarm seed timeout must not mark tmux disconnected", prewarmClient.disconnected.value)
    }

    @Test
    fun sessionPrewarmBestEffortCaptureFailureCachesRuntimeAndKeepsClientOpen() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val prewarmClient = FakeTmuxClient().withSinglePane("recent", "%4").apply {
            failBestEffortOnCommandPrefix = "capture-pane"
            bestEffortException = TmuxClientException("tmux command `capture-pane` timed out")
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> prewarmClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )

        vm.prewarmLikelySwitchTargets(listOf("recent"))
        advanceUntilIdle()

        assertTrue(
            "expected prewarm seed to attempt capture best-effort, got ${prewarmClient.sentCommands}",
            prewarmClient.sentCommands.contains(seedCaptureCommand("%4")),
        )
        assertTrue(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent")))
        assertFalse("prewarm capture timeout must not close tmux client", prewarmClient.closed)
        assertFalse("prewarm capture timeout must not mark tmux disconnected", prewarmClient.disconnected.value)
    }

    // ---------------------------------------------------------------------
    // Issue #1206 - fresh-pane seed: retry an empty/error/wedged first
    // capture-pane instead of leaving the model grid empty (fragments-over-
    // black on a fresh Claude session). Reproduce-first (D33/G10): each fixture
    // makes the FIRST capture come back the non-happy way (empty / error /
    // wedged-throw) while the pane HAS content the retry can seed - a happy
    // fixture proves nothing (#847). The prewarmed pane is inspected DIRECTLY
    // from the cached runtime (no switch/reveal reseed masking the RED), so the
    // ONLY seeding path under test is the prewarm seed + #1206 retry.
    //
    // RED on base (no retry): the pane stays blank - the second (content)
    // capture response is never consumed. GREEN with the fix: the background
    // retry consumes it and seeds the full grid.
    // ---------------------------------------------------------------------

    /** Empty first capture, content on retry (G2 - empty-capture class). */
    @Test
    fun prewarmSeedRetriesEmptyFirstCaptureUntilContentSeedsTheGrid() = runTest(scheduler) {
        val pane = prewarmSeedRetryPane(
            firstCapture = CommandResponse(number = 2L, output = emptyList(), isError = false),
        )
        assertFalse(
            "empty first capture must NOT leave the fresh pane blank - the #1206 retry " +
                "must seed the full grid (transcript='${renderedTranscriptFrom(pane.terminalState)}')",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            "the retry's captured content must be on the pane grid",
            renderedTranscriptFrom(pane.terminalState).contains(ISSUE_1206_SEED_MARKER),
        )
    }

    /** Error first capture, content on retry (G2 - error-capture class). */
    @Test
    fun prewarmSeedRetriesErrorFirstCaptureUntilContentSeedsTheGrid() = runTest(scheduler) {
        val pane = prewarmSeedRetryPane(
            firstCapture = CommandResponse(
                number = 2L,
                output = listOf("capture-pane: no such pane"),
                isError = true,
            ),
        )
        assertFalse(
            "error first capture must NOT leave the fresh pane blank - the #1206 retry must seed it",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            renderedTranscriptFrom(pane.terminalState).contains(ISSUE_1206_SEED_MARKER),
        )
    }

    /**
     * Wedged/thrown first capture (the busy -CC acquire timeout), content on
     * retry (G2 - timeout/wedged-acquire class). Modeled by making the first
     * `capture-pane` THROW (as `captureWithCursor` does on a wedged acquire),
     * then succeed.
     */
    @Test
    fun prewarmSeedRetriesWedgedFirstCaptureUntilContentSeedsTheGrid() = runTest(scheduler) {
        val pane = prewarmSeedRetryPane(
            firstCapture = null,
            wedgeFirstCapture = true,
        )
        assertFalse(
            "a wedged/thrown first capture must NOT leave the fresh pane blank - #1206 retry seeds it",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            renderedTranscriptFrom(pane.terminalState).contains(ISSUE_1206_SEED_MARKER),
        )
    }

    /**
     * Issue #1206 - deferred reseed on the first live %output. When the capture
     * stays empty/wedged for the WHOLE bounded retry window, the pane opens its
     * gate blank; the moment the pane emits its first live %output, ONE fresh
     * capture reseeds the full grid. RED on base: no retry AND no deferred
     * reseed, so an idle Claude pane whose non-visible first frame arrives after
     * the seed window stays fragments-over-black. GREEN: the deferred reseed
     * seeds the full grid on that first output.
     */
    @Test
    fun prewarmDeferredReseedOnFirstOutputSeedsTheGridAfterExhaustedRetries() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
        vm.setPrewarmSeedRetryBackoffForTest(50L)
        // Every capture across the inline attempt + all retries returns empty.
        val prewarmClient = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf("%4\t@0\t\$0\trecent\trecent\t0"),
                    isError = false,
                ),
            )
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> prewarmClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )

        vm.prewarmLikelySwitchTargets(listOf("recent"))
        advanceUntilIdle()

        val pane = runtimeCache.cachedRuntimesForHost(1L).single().panes.single { it.paneId == "%4" }
        // The retry window is exhausted (all captures empty) - pane still blank,
        // deferred reseed parked on the first live %output.
        assertTrue(
            "after an all-empty retry window the pane is blank until it produces output",
            pane.terminalState.visibleScreenIsBlank(),
        )

        // The pane produces its first live %output - non-visible bytes (a cursor
        // home escape) so the OUTPUT itself paints nothing: the deferred reseed
        // is the only thing that can fill the grid. Queue the content the reseed
        // capture will pick up, then emit.
        prewarmClient.capturePaneResponses.addLast(
            CommandResponse(number = 9L, output = listOf(ISSUE_1206_SEED_MARKER), isError = false),
        )
        prewarmClient.tryEmitPaneOutput("%4", "\u001b[H".toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()

        assertFalse(
            "the first live %output must trigger ONE deferred reseed that fills the grid " +
                "(transcript='${renderedTranscriptFrom(pane.terminalState)}')",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            "the deferred reseed's captured content must be on the pane grid",
            renderedTranscriptFrom(pane.terminalState).contains(ISSUE_1206_SEED_MARKER),
        )
    }

    /**
     * Drives a prewarm whose FIRST seed capture is [firstCapture] (or throws
     * when [wedgeFirstCapture]) and whose RETRY capture returns the
     * [ISSUE_1206_SEED_MARKER] content, then returns the cached prewarmed pane
     * for the caller to assert on. Shared by the #1206 empty/error/wedged tests.
     */
    private fun TestScope.prewarmSeedRetryPane(
        firstCapture: CommandResponse?,
        wedgeFirstCapture: Boolean = false,
    ): TmuxPaneState {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
        vm.setPrewarmSeedRetryBackoffForTest(50L)
        val prewarmClient = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf("%4\t@0\t\$0\trecent\trecent\t0"),
                    isError = false,
                ),
            )
            if (wedgeFirstCapture) {
                // The first capture-pane THROWS (a wedged -CC acquire), then the
                // retry succeeds - throwOnCommandPrefix does NOT consume a queued
                // capture response, so only the content response is queued.
                throwOnCommandPrefix = "capture-pane"
                throwOnCommandException = TmuxClientException("capture-pane acquire wedged")
                throwOnCommandRemaining = 1
            } else {
                capturePaneResponses.addLast(requireNotNull(firstCapture))
            }
            // The retry capture returns real content that must seed the grid.
            capturePaneResponses.addLast(
                CommandResponse(number = 8L, output = listOf(ISSUE_1206_SEED_MARKER), isError = false),
            )
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> prewarmClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )

        vm.prewarmLikelySwitchTargets(listOf("recent"))
        advanceUntilIdle()

        return runtimeCache.cachedRuntimesForHost(1L).single().panes.single { it.paneId == "%4" }
    }

    /**
     * Issue #1206 (recovery-job cancellation - the reviewer's #2 residual). A
     * prewarmed pane whose seed capture stays empty for the WHOLE bounded retry
     * window PARKS a recovery job on the first-live-%output wait. That job is
     * promoted with the runtime into the cache entry; it MUST be cancelled when
     * the cached runtime is evicted/closed (`closeCachedRuntime`) - not leaked
     * as a parked coroutine until whole-VM `bridgeScope` teardown.
     *
     * Load-bearing assertion: after [closeCachedRuntime] the parked recovery job
     * is CANCELLED (`isCancelled` / not `isActive`). Before the fix the cache
     * entry did not carry the recovery job and `closeCachedRuntime` had nothing
     * to cancel, so the job leaked - this test would fail (the job stays active).
     */
    @Test
    fun prewarmSeedRecoveryJobIsCancelledOnCachedRuntimeClose() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
        vm.setPrewarmSeedRetryBackoffForTest(50L)
        // Every capture (inline + all retries) returns empty -> the recovery job
        // exhausts its retries and PARKS on the first-live-%output wait.
        val prewarmClient = FakeTmuxClient().apply {
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf("%4\t@0\t\$0\trecent\trecent\t0"),
                    isError = false,
                ),
            )
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> prewarmClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )

        vm.prewarmLikelySwitchTargets(listOf("recent"))
        advanceUntilIdle()

        val runtime = runtimeCache.cachedRuntimesForHost(1L).single()
        val recoveryJob = runtime.paneSeedRecoveryJobs["%4"]
        assertNotNull(
            "an all-empty-capture prewarm must carry its parked seed-recovery job into the " +
                "cache entry so it is cancellable on eviction",
            recoveryJob,
        )
        assertTrue(
            "the parked recovery job must be active (waiting on first %output) before teardown",
            recoveryJob!!.isActive,
        )

        // Cache eviction / deactivate must cancel the parked recovery job.
        runtime.closeCachedRuntime()
        advanceUntilIdle()

        assertTrue(
            "the parked seed-recovery job MUST be cancelled on cache close - a promoted " +
                "prewarmed pane's recovery job must not leak until whole-VM teardown",
            recoveryJob.isCancelled,
        )
        assertFalse(
            "the cancelled recovery job must no longer be active",
            recoveryJob.isActive,
        )
    }

    private fun FakeTmuxClient.withSinglePane(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private fun renderedTranscriptFrom(state: TerminalSurfaceState): String {
        val bridgeField = TerminalSurfaceState::class.java.getDeclaredField("bridge").apply {
            isAccessible = true
        }
        val bridge = bridgeField.get(state) as? SshTerminalBridge ?: return ""
        return bridge.emulator.screen.transcriptText
    }

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected lease connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    private class FakeSshSession : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward {
            throw NotImplementedError()
        }

        override fun startShell(): SshShell {
            throw NotImplementedError()
        }

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        // Issue #1206: distinctive content the retry / deferred-reseed capture
        // returns, so the pane transcript can be asserted to CONTAIN it (proving
        // the reseed - not some incidental output - filled the grid).
        const val ISSUE_1206_SEED_MARKER = "ISSUE1206-RESEED-CONTENT-fresh-pane-grid"
    }
}
