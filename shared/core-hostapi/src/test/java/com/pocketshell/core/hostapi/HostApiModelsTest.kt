package com.pocketshell.core.hostapi

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The wire-value mappings and the exec contract, exercised directly. */
class HostApiModelsTest {

    @Test
    fun `backend wire mapping covers both managers and falls back to UNKNOWN`() {
        assertEquals(Backend.TMUX, Backend.fromWire("tmux"))
        assertEquals(Backend.APLEXER, Backend.fromWire("aplexer"))
        assertEquals(Backend.UNKNOWN, Backend.fromWire("zellij"))
        assertEquals(Backend.UNKNOWN, Backend.fromWire(""))
        // Case is not normalised on purpose: the host emits lowercase, and
        // quietly accepting "TMUX" would hide a host-side contract change.
        assertEquals(Backend.UNKNOWN, Backend.fromWire("TMUX"))
    }

    @Test
    fun `agent state wire mapping`() {
        assertEquals(AgentState.IDLE, AgentState.fromWire("idle"))
        assertEquals(AgentState.WAITING, AgentState.fromWire("waiting"))
        assertEquals(AgentState.WORKING, AgentState.fromWire("working"))
        assertNull(AgentState.fromWire(null))
        assertNull(AgentState.fromWire("thinking"))
    }

    @Test
    fun `agent state source wire mapping`() {
        assertEquals(AgentStateSource.REPORTED, AgentStateSource.fromWire("reported"))
        assertEquals(AgentStateSource.HEURISTIC, AgentStateSource.fromWire("heuristic"))
        assertNull(AgentStateSource.fromWire(null))
        assertNull(AgentStateSource.fromWire("psychic"))
    }

    @Test
    fun `RemoteExec is a SAM interface a caller can satisfy with a lambda`() {
        // Task K-1 ships no implementation; this pins the shape the K-2 client
        // and its test doubles will be written against, and proves the module
        // needs no coroutines artifact to be usable (suspension is stdlib).
        var seen: Pair<String, Long>? = null
        val exec = RemoteExec { command, timeoutMs ->
            seen = command to timeoutMs
            ExecOutcome(exitCode = 0, stdout = "ok", stderr = "", timedOut = false)
        }

        val outcome = runSuspending { exec.exec("pocketshell sessions list --json", 5_000L) }

        assertEquals("pocketshell sessions list --json" to 5_000L, seen)
        assertEquals(ExecOutcome(0, "ok", "", false), outcome)
    }

    @Test
    fun `a timed-out exec is an ordinary outcome, not an exception`() {
        val exec = RemoteExec { _, _ ->
            ExecOutcome(exitCode = -1, stdout = "partial", stderr = "", timedOut = true)
        }

        val outcome = runSuspending { exec.exec("sleep 60", 10L) }

        assertEquals(true, outcome.timedOut)
        assertEquals("partial", outcome.stdout)
    }

    /**
     * Minimal suspend runner. Deliberately not `runBlocking`: this module has
     * no kotlinx-coroutines dependency, and it should stay that way until
     * something actually needs a dispatcher.
     */
    private fun <T> runSuspending(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return checkNotNull(result) { "block suspended; this runner only supports direct returns" }
            .getOrThrow()
    }
}
