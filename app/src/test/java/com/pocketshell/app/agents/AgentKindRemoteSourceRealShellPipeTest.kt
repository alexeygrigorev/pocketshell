package com.pocketshell.app.agents

import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Issue #847 — NON-VACUOUS regression for the cold-start "loading tree" /
 * connect HANG, executed through a REAL `/bin/sh`.
 *
 * ## Why this test exists
 *
 * The pre-existing [AgentKindRemoteSourceTest.sendsTheRpcRequestShapeAsStdinJson]
 * passed VACUOUSLY: it asserts the command STRING against a `FakeSshSession`
 * that never runs a shell, so the stdin-hang was invisible. The real on-device
 * break was structural:
 *
 * [com.pocketshell.app.pocketshell.PocketshellCommand.wrap] returns a
 * MULTI-statement shell sequence
 * (`export PATH=…; __ps_bin=…; "$__ps_bin" agents kind`). The old code piped to
 * it as `printf %s '<json>' | <wrap>`, so the pipe bound ONLY to the FIRST
 * statement (`export PATH=…`). The discovered `pocketshell agents kind` then
 * inherited the SSH exec channel's stdin instead of the JSON pipe — and since
 * the app never writes/closes that channel stdin, the CLI blocked on
 * `read(stdin)` FOREVER, wedging the folder enumeration until the 12s reconcile
 * bound tripped ("Session list didn't load within 12000ms") so the tree never
 * loaded. The fix groups the wrapper as `… | { <wrap> ; }` so the pipe reaches
 * the real `agents kind` invocation.
 *
 * ## How this reproduces the bug for real
 *
 * The production [AgentKindRemoteSource.classify] builds the EXACT command
 * string and hands it to an [SshSession] whose `exec` runs it through a real
 * `/bin/sh -c` (the same single non-interactive shell semantics as the SSH exec
 * channel). The shell's stdin is redirected from `/dev/null`, so a `pocketshell`
 * that wrongly reads the channel stdin sees an immediate EOF and emits an EMPTY
 * verdict — the deterministic, terminating stand-in for the on-device infinite
 * hang. A `pocketshell` that receives the piped JSON emits the real envelope.
 *
 * A fake `pocketshell` is dropped on the wrap()'s resolution PATH
 * (`$HOME/.local/bin/pocketshell`, the dominant real install location): its
 * `agents kind` branch `cat`s its stdin and echoes a `results` envelope built
 * from the panes it was actually handed on stdin. Therefore:
 *  - GROUPED (fixed) form  → the JSON reaches `cat` → real envelope → kind set.
 *  - UNGROUPED (bug) form  → `cat` reads /dev/null EOF → empty → kind absent.
 *
 * This is the red→green proof the brief mandates: replacing the grouped command
 * with the ungrouped one (see [classifyWithUngroupedPipe]) makes the same
 * assertion FAIL, and a `waitFor` timeout HARD-fails the genuine infinite-hang
 * case rather than wedging the suite.
 */
class AgentKindRemoteSourceRealShellPipeTest {

    private val source = AgentKindRemoteSource()
    private lateinit var fakeHome: File

    @Before
    fun setUp() {
        // A throwaway HOME whose ~/.local/bin holds a fake `pocketshell` so the
        // multi-statement wrap() actually RESOLVES a real binary (the export-
        // PATH + `command -v` path), exactly like a real host with the CLI in
        // ~/.local/bin. This is the install location that makes wrap() emit its
        // multi-statement form, which is the precondition for the bug.
        fakeHome = File.createTempFile("ps847-home", "").apply {
            delete()
            mkdirs()
        }
        val localBin = File(fakeHome, ".local/bin").apply { mkdirs() }
        val pocketshell = File(localBin, "pocketshell")
        // `agents kind`: read the stdin JSON and emit one result row per pane id
        // it finds, all classified `claude`. If stdin is empty (the bug's
        // channel-stdin-EOF case), `grep` finds no pane ids and the envelope is
        // `{"results":[]}` — i.e. NO verdict, the observable symptom.
        pocketshell.writeText(
            """
            #!/bin/sh
            if [ "${'$'}1" = "agents" ] && [ "${'$'}2" = "kind" ]; then
              payload="${'$'}(cat)"
              rows=""
              for id in ${'$'}(printf '%s' "${'$'}payload" | grep -o '"pane_id":"[^"]*"' | sed 's/"pane_id":"//;s/"//'); do
                if [ -n "${'$'}rows" ]; then rows="${'$'}rows,"; fi
                rows="${'$'}rows{\"pane_id\":\"${'$'}id\",\"agent_kind\":\"claude\",\"scope\":\"x.scope\"}"
              done
              printf '{"results":[%s]}\n' "${'$'}rows"
              exit 0
            fi
            exit 0
            """.trimIndent(),
        )
        pocketshell.setExecutable(true)
    }

    @After
    fun tearDown() {
        fakeHome.deleteRecursively()
    }

    @Test
    fun groupedPipeDeliversStdinToTheRealCliAndReturnsTheVerdict() = runTest {
        // Drives the PRODUCTION classify(): it builds the grouped command and
        // our session runs it through a real /bin/sh. The verdict only appears
        // if the piped JSON actually reached `pocketshell agents kind`'s stdin.
        val result = source.classify(
            session = RealShellSession(fakeHome),
            panes = listOf(
                AgentKindRemoteSource.PaneRef("%7", 2647034),
                AgentKindRemoteSource.PaneRef("%8", 99),
            ),
        )

        assertEquals(
            "the grouped pipe must deliver the pane JSON to the real CLI's stdin",
            AgentKind.ClaudeCode,
            result["%7"]?.kind,
        )
        assertEquals(AgentKind.ClaudeCode, result["%8"]?.kind)
    }

    @Test
    fun ungroupedPipeStarvesTheCliOfStdin_provingTheBugIsReal() = runTest {
        // The SAME panes + SAME fake CLI, but the command is built the OLD
        // (ungrouped) way. The pipe binds only to `export PATH=…`, so the real
        // `agents kind` reads the shell's /dev/null stdin (the terminating
        // stand-in for the on-device channel-stdin hang) and returns an EMPTY
        // envelope — no verdict. This is the RED the grouped fix turns GREEN.
        val command = classifyWithUngroupedPipe(
            listOf(
                AgentKindRemoteSource.PaneRef("%7", 2647034),
                AgentKindRemoteSource.PaneRef("%8", 99),
            ),
        )
        val result = RealShellSession(fakeHome).exec(command)
        assertEquals("the ungrouped command must still exit 0 (it just lost stdin)", 0, result.exitCode)
        assertTrue(
            "BUG REPRODUCTION: the ungrouped pipe must NOT deliver any pane verdict " +
                "(stdin never reached the CLI); got: ${result.stdout}",
            result.stdout.contains("\"results\":[]"),
        )
        assertTrue(
            "the ungrouped pipe must NOT classify the pane",
            !result.stdout.contains("\"pane_id\":\"%7\""),
        )
    }

    /**
     * Reconstructs the EXACT command the source built BEFORE the #847 fix:
     * `printf %s '<json>' | <wrap>` with NO `{ …; }` grouping. Used only to
     * prove the red side; production code uses the grouped form.
     */
    private fun classifyWithUngroupedPipe(panes: List<AgentKindRemoteSource.PaneRef>): String {
        val json = buildString {
            append("{\"panes\":[")
            panes.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append("{\"pane_id\":\"").append(p.paneId).append("\",\"pane_pid\":").append(p.panePid).append('}')
            }
            append("]}")
        }
        val quoted = "'" + json.replace("'", "'\"'\"'") + "'"
        return "printf %s $quoted | " +
            com.pocketshell.app.pocketshell.PocketshellCommand.wrap("agents kind")
    }

    /**
     * An [SshSession] that runs [exec]'s command string through a real
     * `/bin/sh -c`, with HOME pointed at the fake-CLI tree and the shell's own
     * stdin redirected from `/dev/null`. The `/dev/null` redirect is the safe,
     * terminating model of the on-device hang: a CLI that wrongly reads the
     * channel stdin gets EOF and finishes (empty), while a CLI fed by the pipe
     * gets the JSON. A [waitFor] timeout HARD-fails a true infinite hang.
     */
    private class RealShellSession(private val home: File) : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            val process = ProcessBuilder("/bin/sh", "-c", command)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .also {
                    it.environment()["HOME"] = home.absolutePath
                    // Strip an inherited PATH that already has pocketshell so the
                    // wrap() resolution genuinely exercises ~/.local/bin.
                    it.environment()["PATH"] = "/usr/bin:/bin"
                }
                .start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw AssertionError("command hung (>15s) reading stdin — the #847 bug: $command")
            }
            return ExecResult(stdout, stderr, process.exitValue())
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("tail not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("uploadFile not used")
        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used")

        override fun close() = Unit
    }
}
