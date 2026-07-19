package com.pocketshell.app.ssh

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Issue #1693 — the DETERMINISM gate for the #780-model synthetic
 * self-inflicted-close seam in [BoundedSessionExec].
 *
 * ## Why this exists
 *
 * The reconnect-storm reproduction (`MobileLatencyStormSelfInflictedCloseE2eTest`)
 * used to drive its RED by MANUALLY restoring the v0.4.38 `close()`-on-timeout
 * shim. That RED was flaky (~1/3 of runs storm) because the modern
 * `RealSshSession.close()` is idempotent + async + `isCloseInitiated`-tagging +
 * lease-refcount-aware (#1135/#1139/#1222/#1567), so the shim no longer reliably
 * reaches the shared `-CC` reader. A flaky RED is not a clean D33 gate.
 *
 * The fix is a test-only seam: on a bounded-exec TIMEOUT for an ARMED caller
 * site, [BoundedSessionExec] deterministically fires an injected action (the
 * connected repro force-kills the shared transport raw). This JVM test proves the
 * SEAM MECHANISM is deterministic and correctly keyed, at per-push speed — the
 * connected storm/no-storm behaviour is proven separately in the nightly
 * network-fault cohort.
 *
 * The load-bearing properties, each keyed strictly on `agent_kind_classify`:
 *  - ARMED site + timeout → action fires with the exact session, EVERY run (20/20).
 *  - DISARMED (production default) → action NEVER fires (no behaviour change, D22).
 *  - STRICT KEY → arming a sibling site (`session_cards_rpc`, a separate lease
 *    that does not storm) does NOT fire on an `agent_kind_classify` timeout, and
 *    vice-versa (the #1681 finding — a broad key would let RED pass vacuously).
 *  - Only a TIMEOUT fires it — an exec that completes within the bound never does.
 */
class Issue1693SyntheticSelfInflictedCloseSeamTest {

    private companion object {
        const val CLASSIFY_SITE = "agent_kind_classify"
        const val CARDS_SITE = "session_cards_rpc"
    }

    @After
    fun disarmSeam() {
        // Mirrors the KeepAliveTestOverride teardown contract: never leak the
        // process-global seam onto a sibling test.
        BoundedSessionExec.onTimeoutSyntheticActionsForTest = emptyMap()
    }

    @Test
    fun armedClassifyTimeoutForcesSelfInflictedCloseEveryRun() = runBlocking {
        // #1633 determinism method: prove the seam fires 20/20 (the flakiness the
        // manual shim-revert RED suffered is gone by construction).
        repeat(20) { run ->
            val killed = CopyOnWriteArrayList<SshSession>()
            val session = WedgingSession()
            BoundedSessionExec.onTimeoutSyntheticActionsForTest =
                mapOf(CLASSIFY_SITE to { s -> killed += s })

            val result = execBoundedClassify(session)

            assertNull("run $run: a wedged exec must degrade to null", result)
            assertEquals(
                "run $run: the armed agent_kind_classify timeout must self-close exactly once",
                1,
                killed.size,
            )
            assertSame(
                "run $run: the self-close must target the exec's own shared-lease session",
                session,
                killed.first(),
            )
        }
    }

    @Test
    fun disarmedTimeoutNeverSelfCloses() = runBlocking {
        // Production default: the seam map is empty, so a timeout is pure abandon
        // (real #1641 semantics) — no self-close, no behaviour change.
        assertEquals(
            "the seam must default to EMPTY so production never self-closes",
            emptyMap<String, (SshSession) -> Unit>(),
            BoundedSessionExec.onTimeoutSyntheticActionsForTest,
        )

        val result = execBoundedClassify(WedgingSession())

        // With the map empty, `onTimeoutSyntheticActionsForTest[callerSite]` is
        // null, so the timeout is pure abandon (real #1641 semantics): the exec
        // degrades to null and no self-close ever runs.
        assertNull("a disarmed bounded exec must abandon (null), never self-close", result)
    }

    @Test
    fun strictKeyingSiblingSiteDoesNotFireOnClassifyTimeout() = runBlocking {
        // Arm ONLY the sibling site. An agent_kind_classify timeout must NOT fire
        // it — the #1681 finding: session_cards_rpc rides a SEPARATE lease and does
        // not storm, so a broad key would let the RED pass vacuously.
        val fired = CopyOnWriteArrayList<SshSession>()
        BoundedSessionExec.onTimeoutSyntheticActionsForTest =
            mapOf(CARDS_SITE to { s -> fired += s })

        val result = execBoundedClassify(WedgingSession())

        assertNull(result)
        assertTrue(
            "arming session_cards_rpc must NOT self-close an agent_kind_classify timeout",
            fired.isEmpty(),
        )
    }

    @Test
    fun strictKeyingClassifyArmDoesNotFireOnSiblingTimeout() = runBlocking {
        // The mirror: arm agent_kind_classify, run a session_cards_rpc timeout — it
        // must NOT self-close (only the classify's shared-lease overrun storms).
        val fired = CopyOnWriteArrayList<SshSession>()
        BoundedSessionExec.onTimeoutSyntheticActionsForTest =
            mapOf(CLASSIFY_SITE to { s -> fired += s })

        val result = BoundedSessionExec.execBounded(
            session = WedgingSession(),
            command = "pocketshell session-cards",
            timeoutMs = 50L,
            dispatcher = Dispatchers.Default,
            callerSite = CARDS_SITE,
        )

        assertNull(result)
        assertTrue(
            "the classify arm must NOT self-close a session_cards_rpc timeout",
            fired.isEmpty(),
        )
    }

    @Test
    fun completedExecUnderBoundNeverSelfCloses() = runBlocking {
        // Only a TIMEOUT triggers the synthetic close: an exec that returns within
        // the bound must never self-close, even with the site armed.
        val fired = CopyOnWriteArrayList<SshSession>()
        BoundedSessionExec.onTimeoutSyntheticActionsForTest =
            mapOf(CLASSIFY_SITE to { s -> fired += s })

        val session = FastSession()
        val result = BoundedSessionExec.execBounded(
            session = session,
            command = "pocketshell classify",
            timeoutMs = 5_000L,
            dispatcher = Dispatchers.Default,
            callerSite = CLASSIFY_SITE,
        )

        assertNotNull("a fast exec must return its result", result)
        assertEquals(0, result?.exitCode)
        assertTrue(
            "a completed (non-timed-out) exec must never self-close, even when armed",
            fired.isEmpty(),
        )
    }

    private suspend fun execBoundedClassify(session: SshSession): ExecResult? =
        BoundedSessionExec.execBounded(
            session = session,
            command = "pocketshell classify",
            timeoutMs = 50L,
            dispatcher = Dispatchers.Default,
            callerSite = CLASSIFY_SITE,
        )

    /**
     * A slow-but-ALIVE host: exec never returns within the bound, so `execBounded`
     * always times out. The synthetic-close action captures THIS instance so the
     * test can assert it (and only it) was targeted.
     */
    private open class WedgingSession : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult = awaitCancellation()

        override fun close() = Unit

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("upload not used")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("upload not used")
    }

    /** A healthy host whose exec returns immediately (never times out). */
    private class FastSession : WedgingSession() {
        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = """{"results":[]}""", stderr = "", exitCode = 0)
    }
}
