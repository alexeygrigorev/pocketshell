package com.pocketshell.app.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.model.SessionAgentState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1237: connected read-back proof for the agent-state chip. The
 * host-side stop/idle hook bus writes the session-scoped tmux options
 * `@ps_agent_state` (`idle` / `waiting_for_input`) and
 * `@ps_agent_state_updated_at`; PocketShell reads them back on the SAME warm
 * `list-sessions` enumeration the folder tree uses and resolves them to the chip
 * [SessionAgentState].
 *
 * This drives the deterministic `agents` Docker fixture (the one the connected
 * journey suite already uses) — no new fixture/port. To reproduce the "agent
 * goes idle / waiting" event deterministically WITHOUT a real agent CLI, the
 * test sets the exact `@ps_agent_state` / `@ps_agent_state_updated_at` options a
 * hook would write (via `tmux set-option`) on a real tmux session, then asserts
 * the gateway's read-back path resolves the chip state. It covers the class:
 * idle, waiting_for_input, the stale case (activity newer than the state write →
 * Unknown, no chip), and the absent case (no option → Unknown, no chip).
 *
 * Issue #1570: adds the WORKING case with the timestamp format the real host
 * hook actually writes — `datetime.now(timezone.utc).isoformat()`, an ISO-8601
 * string, NOT the epoch int the other cases (and every prior fixture) used. A
 * live Codex that recorded `idle` on its last turn-stop and then resumed (its
 * `Working (…· esc to interrupt)` redraw bumps session_activity past the recorded
 * stop) must read back as [SessionAgentState.Working], not the wrong "Idle" the
 * maintainer saw. This is the #847-class fixture gap that let the bug ship: the
 * connected test never exercised the ISO format.
 */
@RunWith(AndroidJUnit4::class)
class AgentStateReadBackDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val cleanupCommands = mutableListOf<String>()

    @Before
    fun setUp(): Unit { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyText = instrumentation.context.assets.open("test_key")
            .bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = instrumentation.targetContext.cacheDir
        keyFile = File(cacheDir, "issue1237-state-readback-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        if (cleanupCommands.isNotEmpty()) {
            runCatching {
                withTimeout(15_000) {
                    withSshSession { session ->
                        session.exec(cleanupCommands.joinToString("\n"))
                    }
                }
            }
        }
        runCatching { keyFile.delete() }
    } }

    @Test
    fun gatewayReadsBackHookWrittenAgentStateAndResolvesTheChip(): Unit { runBlocking {
        val gateway = SshFolderListGateway()
        val host = dockerHost()
        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val cwd = "/tmp/issue1237-state-$suffix"
        cleanupCommands += "rm -rf ${shellQuote(cwd)} 2>/dev/null || true"
        ensureRemoteDir(cwd)

        // 1) IDLE: session_activity at-or-before the state write → fresh Idle.
        val idle = "issue1237-idle-$suffix"
        createSessionWithState(
            sessionName = idle,
            cwd = cwd,
            state = "idle",
            // Timestamp slightly in the FUTURE relative to the session's current
            // activity so the fresh-state branch is taken deterministically even
            // though `new-session` bumps activity to "now".
            updatedAtOffsetSec = 300,
        )

        // 2) WAITING_FOR_INPUT: same shape, waiting.
        val waiting = "issue1237-waiting-$suffix"
        createSessionWithState(
            sessionName = waiting,
            cwd = cwd,
            state = "waiting_for_input",
            updatedAtOffsetSec = 300,
        )

        // 3) STALE: recorded idle but the state write is far in the PAST relative
        //    to session activity → the recorded resting state is stale → Unknown.
        val stale = "issue1237-stale-$suffix"
        createSessionWithState(
            sessionName = stale,
            cwd = cwd,
            state = "idle",
            updatedAtOffsetSec = -3600,
        )

        // 5) WORKING (#1570): a live Codex agent that recorded `idle` on its last
        //    turn-stop, then resumed — the host wrote the timestamp in the REAL
        //    ISO-8601 format the hook uses (not an epoch int), far in the PAST
        //    relative to session activity. It MUST read back as Working, not the
        //    wrong "Idle" (the maintainer's report). Requires @ps_agent_kind=codex
        //    so the session is treated as a live agent.
        val working = "issue1237-working-$suffix"
        createAgentSessionWithIsoState(
            sessionName = working,
            cwd = cwd,
            kind = "codex",
            state = "idle",
            updatedAtOffsetSec = -3600,
        )

        // 4) ABSENT: a plain session with no @ps_agent_state option → Unknown.
        val absent = "issue1237-absent-$suffix"
        cleanupCommands += "tmux kill-session -t ${shellQuote(absent)} 2>/dev/null || true"
        withTimeout(30_000) {
            gateway.createSession(
                host = host,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                sessionName = absent,
                cwd = cwd,
                startCommand = null,
            ).getOrThrow()
        }

        // Determinism gate (reviewer round 2): the four sessions are each created
        // over a SEPARATE short-lived SSH connection (three via `set-option`, one
        // via the gateway's own `new-session`). A just-created session is not
        // always immediately enumerable on the NEXT connection — the reviewer hit
        // a genuine assertion failure where the first-created `idle` session was
        // missing from the read-back while the other three were present. That is a
        // fixture-visibility race between the rapid create-over-fresh-connections
        // and the immediate read-back, NOT a weakness in the resolver. Poll the
        // raw tmux server directly (independent of the gateway under test) until
        // ALL four sessions are enumerable AND the three stateful sessions'
        // `@ps_agent_state` + `@ps_agent_state_updated_at` options are readable,
        // BEFORE the single read-back below. This is a HARD bounded wait: it
        // throws if the sessions never stabilise, so the flake can never be
        // silently passed. Polling raw tmux — rather than the gateway — keeps the
        // product enumeration path honest: if `listSessionsWithFolder` then still
        // drops a session that raw tmux confirms is live, the assertions below
        // fail loudly (a real product enumeration gap is surfaced, not masked).
        awaitSessionsEnumerable(
            statefulSessions = listOf(idle, waiting, stale, working),
            plainSessions = listOf(absent),
        )

        val result = withTimeout(30_000) {
            gateway.listSessionsWithFolder(host, keyFile.absolutePath, null)
        }
        assertTrue("expected Sessions result, got $result", result is FolderListResult.Sessions)
        val rows = (result as FolderListResult.Sessions).rows

        assertEquals(
            "hook-written idle must read back as raw idle",
            "idle",
            rowFor(rows, idle).agentStateRaw,
        )
        assertEquals(
            "fresh idle resolves to the Idle chip",
            SessionAgentState.Idle,
            rowFor(rows, idle).toSessionEntry().agentState,
        )
        assertEquals(
            "fresh waiting resolves to the WaitingForInput chip",
            SessionAgentState.WaitingForInput,
            rowFor(rows, waiting).toSessionEntry().agentState,
        )
        assertEquals(
            "a resting state older than session activity is stale → no chip",
            SessionAgentState.Unknown,
            rowFor(rows, stale).toSessionEntry().agentState,
        )
        assertEquals(
            "a session with no @ps_agent_state option shows no chip",
            SessionAgentState.Unknown,
            rowFor(rows, absent).toSessionEntry().agentState,
        )
        // #1570: the working Codex recorded `idle` with the REAL ISO-8601
        // timestamp; the resolver must parse it, see activity is newer, and — for
        // a live agent — surface Working, not the wrong "Idle".
        assertEquals(
            "hook-written idle must read back as raw idle",
            "idle",
            rowFor(rows, working).agentStateRaw,
        )
        assertEquals(
            "#1570: a live Codex whose ISO-stamped idle is stale reads back Working, not Idle",
            SessionAgentState.Working,
            rowFor(rows, working).toSessionEntry().agentState,
        )
    } }

    private fun rowFor(rows: List<FolderSessionRow>, name: String): FolderSessionRow =
        rows.firstOrNull { it.sessionName == name }
            ?: error("gateway did not return session '$name'; rows=${rows.map { it.sessionName }}")

    private suspend fun createSessionWithState(
        sessionName: String,
        cwd: String,
        state: String,
        updatedAtOffsetSec: Long,
    ) {
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        withTimeout(30_000) {
            withSshSession { session ->
                // Create a real detached tmux session, then stamp the exact
                // options the stop/idle hook bus would write. `date +%s` on the
                // host anchors the timestamp to the fixture's clock.
                val script = buildString {
                    append("tmux new-session -d -s ${shellQuote(sessionName)} -c ${shellQuote(cwd)}; ")
                    append("__ts=\$(( \$(date +%s) + ($updatedAtOffsetSec) )); ")
                    append("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_state ${shellQuote(state)}; ")
                    append("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_state_updated_at \"\$__ts\"")
                }
                session.exec(script)
            }
        }
    }

    /**
     * Like [createSessionWithState] but records a live-agent kind
     * (`@ps_agent_kind`) and stamps `@ps_agent_state_updated_at` in the REAL
     * ISO-8601 format the host hook writes (`date -u ... +%Y-%m-%dT%H:%M:%S+00:00`),
     * not an epoch int — issue #1570. Used to reproduce a working agent whose
     * stale ISO-stamped idle must resolve to Working on the connected path.
     */
    private suspend fun createAgentSessionWithIsoState(
        sessionName: String,
        cwd: String,
        kind: String,
        state: String,
        updatedAtOffsetSec: Long,
    ) {
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        withTimeout(30_000) {
            withSshSession { session ->
                val script = buildString {
                    append("tmux new-session -d -s ${shellQuote(sessionName)} -c ${shellQuote(cwd)}; ")
                    // ISO-8601 UTC timestamp offset from now, mirroring the host
                    // hook's datetime.now(timezone.utc).isoformat().
                    append("__iso=\$(date -u -d @\$(( \$(date +%s) + ($updatedAtOffsetSec) )) ")
                    append("+%Y-%m-%dT%H:%M:%S+00:00); ")
                    append("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind ${shellQuote(kind)}; ")
                    append("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_state ${shellQuote(state)}; ")
                    append("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_state_updated_at \"\$__iso\"")
                }
                session.exec(script)
            }
        }
    }

    /**
     * Poll the raw tmux server (over fresh short-lived SSH connections, exactly
     * as the create path used) until every session is enumerable and readable,
     * so the single [SshFolderListGateway.listSessionsWithFolder] read-back below
     * observes a fully-settled server. HARD-fails (throws) if the sessions never
     * stabilise within the bound — never silently proceeds — so the read-back
     * assertions can never pass vacuously.
     *
     * `statefulSessions` must additionally have BOTH `@ps_agent_state` and
     * `@ps_agent_state_updated_at` readable (the stop/idle-hook options the
     * resolver keys on); `plainSessions` need only exist (the `absent` case,
     * which deliberately carries no state option).
     */
    private suspend fun awaitSessionsEnumerable(
        statefulSessions: List<String>,
        plainSessions: List<String>,
    ) {
        val attempts = 30
        val delayMs = 500L
        var lastReport = "<never probed>"
        repeat(attempts) { attempt ->
            val report = runCatching {
                withTimeout(15_000) {
                    withSshSession { session ->
                        session.exec(buildReadinessProbe(statefulSessions, plainSessions)).stdout
                    }
                }
            }.getOrElse { "PROBE_ERROR: ${it.javaClass.simpleName}: ${it.message}" }
            lastReport = report.trim()

            val statusByName = lastReport.lineSequence()
                .mapNotNull { line ->
                    val parts = line.split('=', limit = 2)
                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }
                .toMap()
            val allRequired = statefulSessions + plainSessions
            val allReady = allRequired.isNotEmpty() &&
                allRequired.all { statusByName[it] == "READY" }
            if (allReady) return
            if (attempt < attempts - 1) kotlinx.coroutines.delay(delayMs)
        }
        error(
            "sessions never became enumerable within " +
                "${attempts * delayMs}ms; last probe:\n$lastReport",
        )
    }

    /**
     * Build a single shell probe that reports `<session>=READY|MISSING|NOOPT` for
     * every session, so one round-trip settles the whole fixture. Stateful
     * sessions require the two hook options to be non-empty; plain sessions only
     * require existence.
     */
    private fun buildReadinessProbe(
        statefulSessions: List<String>,
        plainSessions: List<String>,
    ): String = buildString {
        for (name in statefulSessions) {
            val q = shellQuote(name)
            append("if ! tmux has-session -t $q 2>/dev/null; then echo ${shellQuote("$name=MISSING")}; ")
            append("else st=\$(tmux show-options -t $q -v @ps_agent_state 2>/dev/null); ")
            append("ts=\$(tmux show-options -t $q -v @ps_agent_state_updated_at 2>/dev/null); ")
            append("if [ -n \"\$st\" ] && [ -n \"\$ts\" ]; then echo ${shellQuote("$name=READY")}; ")
            append("else echo ${shellQuote("$name=NOOPT")}; fi; fi\n")
        }
        for (name in plainSessions) {
            val q = shellQuote(name)
            append("if tmux has-session -t $q 2>/dev/null; then echo ${shellQuote("$name=READY")}; ")
            append("else echo ${shellQuote("$name=MISSING")}; fi\n")
        }
    }

    private suspend fun ensureRemoteDir(path: String) {
        withTimeout(15_000) {
            withSshSession { session ->
                session.exec("mkdir -p ${shellQuote(path)}")
            }
        }
    }

    private suspend fun <T> withSshSession(block: suspend (SshSession) -> T): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        return session.use { block(it) }
    }

    private fun dockerHost(): HostEntity = HostEntity(
        id = 1237L,
        name = "issue1237-agents",
        hostname = DEFAULT_HOST,
        port = DEFAULT_PORT,
        username = DEFAULT_USER,
        keyId = 1L,
    )

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
