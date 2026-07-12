package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections

private const val ASYNC_AWAIT_TIMEOUT_MS = 15_000L

class TmuxClientExecLaneTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
        scope.cancel()
    }

    @Test
    fun `captureWithCursor runs on the exec lane and returns capture and cursor`() = runBlocking {
        // Issue #1297: the heal/seed capture runs on a DEDICATED exec channel
        // (not the -CC control shell). One exec carries display-message +
        // capture-pane, split on a sentinel line, and the parsed result exposes
        // the pane lines and the cursor.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = healExecHandler(cursor = "4,2", captureLines = listOf("line-one", "line-two")),
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val combined = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.captureWithCursor("%3", scrollbackLines = 200)
            }
            assertFalse(combined.capture.isError)
            assertEquals(listOf("line-one", "line-two"), combined.capture.output)
            assertEquals("4,2", combined.cursorReply)

            // Proof it ran on an exec channel: not the -CC control shell. Nothing
            // was written to the -CC stdin for the capture, and the exec command
            // carries both pane-targeted tmux sub-commands.
            val execCmd = session.execCommands.single { it.contains("capture-pane") }
            assertTrue(
                "exec must carry the pane-targeted capture-pane",
                execCmd.contains("tmux capture-pane -p -e -S -200 -t '%3'"),
            )
            assertTrue(
                "exec must carry the pane-targeted cursor query",
                execCmd.contains("tmux display-message -p -t '%3' '#{cursor_x},#{cursor_y}'"),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `captureWithCursor degrades to null cursor when display-message yields nothing`() = runBlocking {
        // Issue #640/#259/#1297: a missing/empty cursor reply must NOT fail the
        // capture; the seed degrades to no explicit cursor restore.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = healExecHandler(cursor = null, captureLines = listOf("only-capture")),
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val combined = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.captureWithCursor("%3", scrollbackLines = 200)
            }
            assertFalse(combined.capture.isError)
            assertEquals(listOf("only-capture"), combined.capture.output)
            assertNull(combined.cursorReply)
        } finally {
            client.close()
        }
    }

    @Test
    fun `captureWithCursor surfaces an error response when the pane is gone`() = runBlocking {
        // Issue #1297 section 4 req 5: a non-zero exec exit (pane/session gone)
        // must surface as an ERROR CommandResponse, not a thrown timeout.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = healExecHandler(
                cursor = null,
                captureLines = emptyList(),
                exitCode = 1,
                stderr = "can't find pane: %9",
            ),
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val combined = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.captureWithCursor("%9", scrollbackLines = 200)
            }
            assertTrue("a gone pane must surface as an error response", combined.capture.isError)
            assertEquals(listOf("can't find pane: %9"), combined.capture.output)
        } finally {
            client.close()
        }
    }

    @Test
    fun `capturePaneTextViaExec runs plain visible capture on the exec lane`() = runBlocking {
        // Submit-ack probes only need visible text. They must not use the shared
        // `-CC` best-effort capture lane, because a busy agent can wedge that
        // mutex while Send is waiting to press Enter.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = {
                ExecResult(stdout = "first\nsecond\n", stderr = "", exitCode = 0)
            },
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val response = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.capturePaneTextViaExec("%3", timeoutMs = 2_500L)
            }

            assertFalse(response.isError)
            assertEquals(listOf("first", "second"), response.output)
            assertEquals(
                "tmux capture-pane -p -t '%3'",
                session.execCommands.single { it.contains("capture-pane") },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `captureWithCursor exec lane times out on a wedged transport within the short ceiling`() = runBlocking {
        // Issue #926/#1297: a genuinely wedged/half-open transport (the exec never
        // returns) must surface a TmuxClientException within the SHORT seed
        // ceiling, never the full 30 s command ceiling.
        val shell = FakeShell()
        val neverReturns = CompletableDeferred<ExecResult>()
        val session = FakeSession(shell, execHandler = { neverReturns.await() })
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
        try {
            client.connect()
            val startedAtMs = System.currentTimeMillis()
            val thrown = runCatching {
                withTimeout(5_000) {
                    client.captureWithCursor("%3", scrollbackLines = 200, timeoutMs = 250L)
                }
            }.exceptionOrNull()
            val elapsedMs = System.currentTimeMillis() - startedAtMs
            assertTrue(
                "a wedged exec must surface a TmuxClientException from the short " +
                    "ceiling, not hang to the 30 s command ceiling (was $thrown)",
                thrown is TmuxClientException,
            )
            assertTrue(
                "the heal capture must time out within ~the short ceiling (elapsed ${elapsedMs}ms)",
                elapsedMs < 5_000L,
            )
            neverReturns.cancel()
        } finally {
            client.close()
        }
    }

    @Test
    fun `heal capture completes on the exec lane while the -CC sendMutex is wedged`() = runBlocking {
        // Issue #1297: a busy agent can wedge the one per-host sendMutex. The heal
        // capture must run on the dedicated exec channel and return independently.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = healExecHandler(cursor = "1,1", captureLines = listOf("healed")),
        )
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val wedger = scope.async { runCatching { client.sendCommand("list-clients") } }
            withTimeout(2_000) {
                while (!shell.stdinAsString().contains("list-clients")) { yield(); delay(10) }
            }

            val startedAtMs = System.currentTimeMillis()
            val combined = withTimeout(5_000) {
                client.captureWithCursor("%3", scrollbackLines = 200, timeoutMs = 2_500L)
            }
            val elapsedMs = System.currentTimeMillis() - startedAtMs

            assertEquals(listOf("healed"), combined.capture.output)
            assertEquals("1,1", combined.cursorReply)
            assertTrue(
                "the heal capture must complete well under the 2.5 s mutex-acquire " +
                    "ceiling (elapsed ${elapsedMs}ms) proving it did not wait on the " +
                    "wedged sendMutex",
                elapsedMs < 2_000L,
            )
            wedger.cancel()
        } finally {
            client.close()
        }
    }

    @Test
    fun `listPanesViaExec runs on the exec lane and returns the pane rows`() = runBlocking {
        // Issue #1316: attach reconcile `list-panes` runs on the dedicated exec
        // channel and returns rows in the shape the caller's parser expects.
        val rows = listOf("%0|PS|@0|PS|0|PS|\$1", "%1|PS|@0|PS|0|PS|\$1")
        val shell = FakeShell()
        val session = FakeSession(shell, execHandler = listPanesExecHandler(rows))
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val command = "list-panes -s -t 'sess' -F '#{pane_id}|PS|#{window_id}'"
            val response = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.listPanesViaExec(command)
            }
            assertFalse(response.isError)
            assertEquals(rows, response.output)

            // Proof it ran on an exec channel: not the -CC control shell. Nothing
            // was written to -CC stdin for the reconcile, and the exec carries the
            // tmux-prefixed list-panes command verbatim.
            val execCmd = session.execCommands.single { it.contains("list-panes") }
            assertEquals("tmux $command", execCmd)
        } finally {
            client.close()
        }
    }

    @Test
    fun `listPanesViaExec surfaces an error response when the session is gone`() = runBlocking {
        // Issue #1316: a non-zero exec exit (session/server gone) must surface as
        // an ERROR CommandResponse carrying stderr.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = listPanesExecHandler(
                rows = emptyList(),
                exitCode = 1,
                stderr = "can't find session: sess",
            ),
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val response = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.listPanesViaExec("list-panes -s -t 'sess' -F '#{pane_id}'")
            }
            assertTrue("a gone session must surface as an error response", response.isError)
            assertEquals(listOf("can't find session: sess"), response.output)
        } finally {
            client.close()
        }
    }

    @Test
    fun `listPanesViaExec bounds itself and throws within the short ceiling on a wedged exec`() =
        runBlocking {
            // Issue #1316: a genuinely wedged/half-open transport (the exec never
            // returns) must surface a TmuxClientException within the caller's SHORT
            // ceiling, not hang to the full command ceiling.
            val shell = FakeShell()
            val neverReturns = CompletableDeferred<ExecResult>()
            val session = FakeSession(shell, execHandler = { neverReturns.await() })
            val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
            try {
                client.connect()
                val startedAtMs = System.currentTimeMillis()
                val thrown = runCatching {
                    withTimeout(5_000) {
                        client.listPanesViaExec(
                            "list-panes -s -t 'sess' -F '#{pane_id}'",
                            timeoutMs = 250L,
                        )
                    }
                }.exceptionOrNull()
                val elapsedMs = System.currentTimeMillis() - startedAtMs
                assertTrue(
                    "a wedged exec must surface a TmuxClientException from the short " +
                        "ceiling, not hang to the 30 s command ceiling (was $thrown)",
                    thrown is TmuxClientException,
                )
                assertTrue(
                    "the reconcile must time out within ~the short ceiling (elapsed ${elapsedMs}ms)",
                    elapsedMs < 5_000L,
                )
                neverReturns.cancel()
            } finally {
                client.close()
            }
        }

    @Test
    fun `list-panes reconcile completes on the exec lane while the -CC channel is wedged by a burst`() =
        runBlocking {
            // Issue #1316: a busy agent can wedge the shared `-CC` control channel.
            // Reconcile must run on the dedicated exec channel and return quickly.
            val rows = listOf("%7|PS|@0|PS|0|PS|\$1")
            val shell = FakeShell()
            val session = FakeSession(shell, execHandler = listPanesExecHandler(rows))
            val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
            try {
                client.connect()
                withTimeout(2_000) {
                    while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
                }
                shell.resetStdin()

                val wedger = scope.async { runCatching { client.sendCommand("list-clients") } }
                withTimeout(2_000) {
                    while (!shell.stdinAsString().contains("list-clients")) { yield(); delay(10) }
                }

                val startedAtMs = System.currentTimeMillis()
                val response = withTimeout(5_000) {
                    client.listPanesViaExec(
                        "list-panes -s -t 'sess' -F '#{pane_id}|PS|#{window_id}'",
                        timeoutMs = 6_000L,
                    )
                }
                val elapsedMs = System.currentTimeMillis() - startedAtMs

                assertFalse("the reconcile must succeed during a -CC burst", response.isError)
                assertEquals(rows, response.output)
                assertTrue(
                    "the reconcile must complete well under the -CC mutex ceiling " +
                        "(elapsed ${elapsedMs}ms) proving it did not wait on the wedged " +
                        "-CC sendMutex",
                    elapsedMs < 2_000L,
                )
                wedger.cancel()
            } finally {
                client.close()
            }
        }

    // ---------------------------------------------------------------------
    // Issue #1460: the send-lane wedge — the residual `sendCommand` SPOF the
    // #1459 Codex-freeze audit found after #1316 fixed the attach side. Typing
    // a message and hitting Send to a live agent mid-`%output`-burst
    // head-of-line-blocked the send's `%begin`/`%end` reply behind the burst on
    // the ONE sshj reader (the maintainer's "app froze, had to restart"). The
    // fix moves the interactive `send-keys` round-trips onto the exec lane.
    // ---------------------------------------------------------------------

    @Test
    fun `RED — send-keys on the shared -CC sendCommand wedges behind a held control channel`() =
        runBlocking {
            // Issue #1460 (reproduce-first): this is the BUG. When the shared
            // per-host `-CC` sendMutex is held by an in-flight command whose
            // reply is head-of-line-blocked behind a live agent's `%output`
            // burst, a `send-keys` issued through `sendCommand` (the OLD send
            // path) cannot acquire the mutex and wedges — exactly the composer
            // Send freeze. Documents WHY the send lane moved off `-CC`; the
            // sibling GREEN tests below prove `sendKeysViaExec` does NOT wedge.
            val shell = FakeShell()
            val session = FakeSession(shell, execHandler = sendKeysExecHandler())
            val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
            try {
                client.connect()
                withTimeout(2_000) {
                    while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
                }
                shell.resetStdin()

                // Wedge `-CC`: this command never gets a `%begin`/`%end` reply
                // (the FakeShell never answers), modelling a reply stuck behind
                // a sustained `%output` burst on the single sshj reader. It holds
                // the sendMutex.
                val wedger = scope.async { runCatching { client.sendCommand("list-clients") } }
                withTimeout(2_000) {
                    while (!shell.stdinAsString().contains("list-clients")) { yield(); delay(10) }
                }

                // The OLD send path (`sendCommand("send-keys …")`) must NOT
                // complete within a generous budget — it is wedged behind the
                // held mutex. This is the reproduced freeze.
                val completed = withTimeoutOrNull(1_500) {
                    client.sendCommand("send-keys -l -t %0 -- 'hello'")
                }
                assertNull(
                    "BUG REPRO: send-keys on the shared -CC sendCommand must wedge " +
                        "behind the held control channel (it returned $completed)",
                    completed,
                )
                wedger.cancel()
            } finally {
                client.close()
            }
        }

    @Test
    fun `GREEN — literal send-keys completes on the exec lane while the -CC channel is wedged`() =
        runBlocking {
            // Issue #1460: the FIX. The same held `-CC` mutex no longer blocks a
            // literal `send-keys -l` — it rides the dedicated exec channel and
            // returns fast. Same wedge as the RED test above; the only change is
            // `sendCommand` → `sendKeysViaExec`.
            assertSendKeysViaExecCompletesDuringCcWedge(
                sendKeysCommand = "send-keys -l -t %0 -- 'hello'",
                expectExecContains = "tmux send-keys -l -t %0 -- 'hello'",
            )
        }

    @Test
    fun `GREEN — named-key Enter send-keys completes on the exec lane while the -CC channel is wedged`() =
        runBlocking {
            // Issue #1460 class coverage: the submit Enter (composer Send's final
            // round-trip) is the round-trip most likely to be issued mid-burst.
            assertSendKeysViaExecCompletesDuringCcWedge(
                sendKeysCommand = "send-keys -t %0 Enter",
                expectExecContains = "tmux send-keys -t %0 Enter",
            )
        }

    @Test
    fun `GREEN — hex bracketed-paste send-keys -H completes on the exec lane while the -CC channel is wedged`() =
        runBlocking {
            // Issue #1460 class coverage: multi-line paste + raw-byte injection
            // (write-stdin) both funnel through `send-keys -H`. It must not wedge
            // behind a burst either.
            assertSendKeysViaExecCompletesDuringCcWedge(
                sendKeysCommand = "send-keys -H -t %0 1b 5b 32 30 30 7e",
                expectExecContains = "tmux send-keys -H -t %0 1b 5b 32 30 30 7e",
            )
        }

    @Test
    fun `GREEN — copy-mode cancel send-keys -X completes on the exec lane while the -CC channel is wedged`() =
        runBlocking {
            // Issue #1460 class coverage: `ensurePaneAcceptsInput` fires a
            // `send-keys -X … cancel` before input when a pane is in copy-mode.
            // It is on the send lane too, so it must not wedge behind a burst.
            assertSendKeysViaExecCompletesDuringCcWedge(
                sendKeysCommand = "send-keys -X -t %0 cancel",
                expectExecContains = "tmux send-keys -X -t %0 cancel",
            )
        }

    @Test
    fun `sendKeysViaExec surfaces an error response when the pane is gone`() = runBlocking {
        // Issue #1460: a non-zero exec exit (pane/session gone) must surface as
        // an ERROR CommandResponse carrying stderr — so a failed send still
        // fails honestly (the composer keeps the unsent draft), not a silent
        // success.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = sendKeysExecHandler(
                exitCode = 1,
                stderr = "can't find pane: %9",
            ),
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val response = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.sendKeysViaExec("send-keys -l -t %9 -- 'hi'")
            }
            assertTrue("a gone pane must surface as an error response", response.isError)
            assertEquals(listOf("can't find pane: %9"), response.output)
        } finally {
            client.close()
        }
    }

    @Test
    fun `sendKeysViaExec succeeds with an empty non-error response on a normal send`() = runBlocking {
        // Issue #1460: `send-keys` prints nothing on success, so a zero exit must
        // yield an empty, non-error response (the caller's `throwIfTmuxError`
        // treats it as success).
        val shell = FakeShell()
        val session = FakeSession(shell, execHandler = sendKeysExecHandler())
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val response = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.sendKeysViaExec("send-keys -l -t %0 -- 'hi'")
            }
            assertFalse("a normal send must not be an error", response.isError)
            assertTrue("send-keys prints nothing on success", response.output.isEmpty())
            assertEquals(
                "tmux send-keys -l -t %0 -- 'hi'",
                session.execCommands.single { it.contains("send-keys") },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `sendKeysViaExec bounds itself and throws within the short ceiling on a wedged exec`() =
        runBlocking {
            // Issue #1460: a genuinely wedged/half-open transport (the exec never
            // returns) must surface a TmuxClientException within the caller's
            // SHORT ceiling, not hang to the full command ceiling — so a send
            // against a dead link fails fast and the composer keeps the draft.
            val shell = FakeShell()
            val neverReturns = CompletableDeferred<ExecResult>()
            val session = FakeSession(shell, execHandler = { neverReturns.await() })
            val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
            try {
                client.connect()
                val startedAtMs = System.currentTimeMillis()
                val thrown = runCatching {
                    withTimeout(5_000) {
                        client.sendKeysViaExec("send-keys -l -t %0 -- 'hi'", timeoutMs = 250L)
                    }
                }.exceptionOrNull()
                val elapsedMs = System.currentTimeMillis() - startedAtMs
                assertTrue(
                    "a wedged send-keys exec must surface a TmuxClientException from " +
                        "the short ceiling, not hang to the command ceiling (was $thrown)",
                    thrown is TmuxClientException,
                )
                assertTrue(
                    "the send must time out within ~the short ceiling (elapsed ${elapsedMs}ms)",
                    elapsedMs < 5_000L,
                )
                neverReturns.cancel()
            } finally {
                client.close()
            }
        }

    // ---------------------------------------------------------------------
    // Issue #1496: the session-lifecycle wedge — the residual `sendCommand`
    // SPOF the 2026-07-12 Codex-freeze audit found after #1460 (send lane) and
    // #1488 (share lane). The in-session Rename and the sessions dashboard's
    // Create / Rename / Kill actions and its live `list-sessions` poll all rode
    // the shared per-host `-CC` `sendCommand`, so a live Codex `%output` burst
    // head-of-line-blocked their `%begin`/`%end` reply ~30-40s behind it on the
    // ONE sshj reader (10s mutex acquire + 30s ceiling; the 10s idle gate never
    // fires while output flows). The RED tests document WHY each of the five
    // command strings wedges on `-CC`; the GREEN tests prove `sendLifecycleViaExec`
    // does NOT wedge — it rides the dedicated exec channel. Covers the CLASS: all
    // five call sites (rename in-session + dashboard share the same command
    // string), not one.
    // ---------------------------------------------------------------------

    @Test
    fun `RED — rename-session on the shared -CC sendCommand wedges behind a held control channel`() =
        runBlocking { assertSendCommandWedgesDuringCcWedge("rename-session -t 'work' 'renamed'") }

    @Test
    fun `RED — new-session -d on the shared -CC sendCommand wedges behind a held control channel`() =
        runBlocking { assertSendCommandWedgesDuringCcWedge("new-session -d -s 'next' -c '~'") }

    @Test
    fun `RED — kill-session on the shared -CC sendCommand wedges behind a held control channel`() =
        runBlocking { assertSendCommandWedgesDuringCcWedge("kill-session -t 'work'") }

    @Test
    fun `RED — list-sessions poll on the shared -CC sendCommand wedges behind a held control channel`() =
        runBlocking {
            assertSendCommandWedgesDuringCcWedge(
                "list-sessions -F '#{session_name}::#{session_created}'",
            )
        }

    @Test
    fun `GREEN — rename-session completes on the exec lane while the -CC channel is wedged`() =
        runBlocking {
            assertSendLifecycleViaExecCompletesDuringCcWedge(
                sessionCommand = "rename-session -t 'work' 'renamed'",
                expectExecEquals = "tmux rename-session -t 'work' 'renamed'",
            )
        }

    @Test
    fun `GREEN — new-session -d completes on the exec lane while the -CC channel is wedged`() =
        runBlocking {
            assertSendLifecycleViaExecCompletesDuringCcWedge(
                sessionCommand = "new-session -d -s 'next' -c '~'",
                expectExecEquals = "tmux new-session -d -s 'next' -c '~'",
            )
        }

    @Test
    fun `GREEN — kill-session completes on the exec lane while the -CC channel is wedged`() =
        runBlocking {
            assertSendLifecycleViaExecCompletesDuringCcWedge(
                sessionCommand = "kill-session -t 'work'",
                expectExecEquals = "tmux kill-session -t 'work'",
            )
        }

    @Test
    fun `GREEN — list-sessions poll completes on the exec lane and returns rows while -CC is wedged`() =
        runBlocking {
            // Issue #1496 class coverage: the dashboard live poll returns rows
            // (unlike the rename/create/kill mutations). Prove it completes fast
            // off `-CC` under a burst AND parses the rows the caller's row
            // parser expects — the exact `-CC` per-row contract, unchanged.
            val rows = listOf("work::1::2::1::::", "next::3::4::0::::")
            val listCommand = "list-sessions -F '#{session_name}::#{session_created}'"
            val shell = FakeShell()
            val session = FakeSession(
                shell,
                execHandler = sendLifecycleExecHandler(stdout = rows.joinToString("\n") + "\n"),
            )
            val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
            try {
                client.connect()
                withTimeout(2_000) {
                    while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
                }
                shell.resetStdin()

                val wedger = scope.async { runCatching { client.sendCommand("list-clients") } }
                withTimeout(2_000) {
                    while (!shell.stdinAsString().contains("list-clients")) { yield(); delay(10) }
                }

                val startedAtMs = System.currentTimeMillis()
                val response = withTimeout(5_000) {
                    client.sendLifecycleViaExec(listCommand)
                }
                val elapsedMs = System.currentTimeMillis() - startedAtMs

                assertFalse("the poll must succeed during a -CC burst", response.isError)
                assertEquals(rows, response.output)
                assertTrue(
                    "the poll must complete well under the -CC mutex ceiling " +
                        "(elapsed ${elapsedMs}ms) proving it did not wait on the wedged " +
                        "-CC sendMutex",
                    elapsedMs < 2_000L,
                )
                assertEquals(
                    "the list-sessions must ride the exec lane verbatim",
                    "tmux $listCommand",
                    session.execCommands.single { it.contains("list-sessions") },
                )
                assertFalse(
                    "the list-sessions must NOT be written to the wedged -CC shell",
                    shell.stdinAsString().contains("list-sessions"),
                )
                wedger.cancel()
            } finally {
                client.close()
            }
        }

    @Test
    fun `sendLifecycleViaExec surfaces an error response when the session is gone`() = runBlocking {
        // Issue #1496: a non-zero exec exit (name collision, session gone) must
        // surface as an ERROR CommandResponse carrying stderr — so a failed
        // rename/create/kill still fails honestly (the dashboard shows its
        // error banner), matching the `-CC` error contract exactly.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = sendLifecycleExecHandler(
                exitCode = 1,
                stderr = "duplicate session: next",
            ),
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val response = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.sendLifecycleViaExec("new-session -d -s 'next'")
            }
            assertTrue("a gone/colliding session must surface as an error response", response.isError)
            assertEquals(listOf("duplicate session: next"), response.output)
        } finally {
            client.close()
        }
    }

    @Test
    fun `sendLifecycleViaExec succeeds with an empty non-error response on a normal mutation`() =
        runBlocking {
            // Issue #1496: `rename-session` / `kill-session` print nothing on
            // success, so a zero exit must yield an empty, non-error response
            // (the caller treats it as success).
            val shell = FakeShell()
            val session = FakeSession(shell, execHandler = sendLifecycleExecHandler())
            val client = RealTmuxClient(session, scope)
            try {
                client.connect()
                val response = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                    client.sendLifecycleViaExec("rename-session -t 'work' 'renamed'")
                }
                assertFalse("a normal mutation must not be an error", response.isError)
                assertTrue("rename-session prints nothing on success", response.output.isEmpty())
                assertEquals(
                    "tmux rename-session -t 'work' 'renamed'",
                    session.execCommands.single { it.contains("rename-session") },
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun `sendLifecycleViaExec bounds itself and throws within the short ceiling on a wedged exec`() =
        runBlocking {
            // Issue #1496: a genuinely wedged/half-open transport (the exec never
            // returns) must surface a TmuxClientException within the caller's
            // SHORT ceiling, not hang to the full command ceiling — so a
            // dashboard action against a dead link fails fast.
            val shell = FakeShell()
            val neverReturns = CompletableDeferred<ExecResult>()
            val session = FakeSession(shell, execHandler = { neverReturns.await() })
            val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
            try {
                client.connect()
                val startedAtMs = System.currentTimeMillis()
                val thrown = runCatching {
                    withTimeout(5_000) {
                        client.sendLifecycleViaExec("kill-session -t 'work'", timeoutMs = 250L)
                    }
                }.exceptionOrNull()
                val elapsedMs = System.currentTimeMillis() - startedAtMs
                assertTrue(
                    "a wedged lifecycle exec must surface a TmuxClientException from " +
                        "the short ceiling, not hang to the command ceiling (was $thrown)",
                    thrown is TmuxClientException,
                )
                assertTrue(
                    "the action must time out within ~the short ceiling (elapsed ${elapsedMs}ms)",
                    elapsedMs < 5_000L,
                )
                neverReturns.cancel()
            } finally {
                client.close()
            }
        }

    /**
     * Issue #1496 RED driver: connect, wedge the shared `-CC` control channel
     * with a never-answered command (modelling a reply stuck behind a live agent
     * `%output` burst on the one sshj reader), then assert the OLD path
     * [RealTmuxClient.sendCommand] on [sessionCommand] does NOT complete within a
     * generous budget — it is wedged behind the held sendMutex. This is the
     * reproduced freeze for each of the five session-management round-trips.
     */
    private suspend fun assertSendCommandWedgesDuringCcWedge(sessionCommand: String) {
        val shell = FakeShell()
        val session = FakeSession(shell, execHandler = sendLifecycleExecHandler())
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            // Wedge `-CC`: this command never gets a `%begin`/`%end` reply (the
            // FakeShell never answers), modelling a reply stuck behind a
            // sustained `%output` burst on the single sshj reader. It holds the
            // sendMutex.
            val wedger = scope.async { runCatching { client.sendCommand("list-clients") } }
            withTimeout(2_000) {
                while (!shell.stdinAsString().contains("list-clients")) { yield(); delay(10) }
            }

            val completed = withTimeoutOrNull(1_500) {
                client.sendCommand(sessionCommand)
            }
            assertNull(
                "BUG REPRO: '$sessionCommand' on the shared -CC sendCommand must " +
                    "wedge behind the held control channel (it returned $completed)",
                completed,
            )
            wedger.cancel()
        } finally {
            client.close()
        }
    }

    /**
     * Issue #1496 GREEN driver: same held-`-CC` wedge as the RED driver, but
     * [sessionCommand] rides [RealTmuxClient.sendLifecycleViaExec] on the
     * dedicated exec channel and returns WELL under the `-CC` mutex ceiling —
     * proving it did not wait on the wedged sendMutex. Asserts the mutation is a
     * non-error empty response, that the exec carried the command verbatim (via
     * [expectExecEquals]), and that it never reached the wedged `-CC` stdin.
     */
    private suspend fun assertSendLifecycleViaExecCompletesDuringCcWedge(
        sessionCommand: String,
        expectExecEquals: String,
    ) {
        val shell = FakeShell()
        val session = FakeSession(shell, execHandler = sendLifecycleExecHandler())
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val wedger = scope.async { runCatching { client.sendCommand("list-clients") } }
            withTimeout(2_000) {
                while (!shell.stdinAsString().contains("list-clients")) { yield(); delay(10) }
            }

            val startedAtMs = System.currentTimeMillis()
            val response = withTimeout(5_000) {
                client.sendLifecycleViaExec(sessionCommand)
            }
            val elapsedMs = System.currentTimeMillis() - startedAtMs

            assertFalse("the mutation must succeed during a -CC burst", response.isError)
            assertTrue(
                "a mutation prints nothing on success (was ${response.output})",
                response.output.isEmpty(),
            )
            assertTrue(
                "the mutation must complete well under the -CC mutex ceiling " +
                    "(elapsed ${elapsedMs}ms) proving it did not wait on the wedged " +
                    "-CC sendMutex",
                elapsedMs < 2_000L,
            )
            assertTrue(
                "the session command must ride the exec lane verbatim " +
                    "(exec commands were ${session.execCommands})",
                session.execCommands.contains(expectExecEquals),
            )
            assertFalse(
                "the session command must NOT be written to the wedged -CC shell",
                shell.stdinAsString().contains(sessionCommand.substringBefore(' ')),
            )
            wedger.cancel()
        } finally {
            client.close()
        }
    }

    private fun sendLifecycleExecHandler(
        stdout: String = "",
        exitCode: Int = 0,
        stderr: String = "",
        delayMs: Long = 0L,
    ): suspend (String) -> ExecResult = { _ ->
        if (delayMs > 0L) delay(delayMs)
        // A mutation prints nothing on success; a `list-sessions` read returns
        // its rows via [stdout]; a non-zero exit carries stderr.
        ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }

    /**
     * Issue #1460 shared driver: connect, wedge the shared `-CC` control channel
     * with a never-answered command (modelling a reply stuck behind a live agent
     * `%output` burst on the one sshj reader), then assert [sendKeysCommand] rides
     * the dedicated exec channel and returns WELL under the `-CC` mutex ceiling —
     * proving it did not wait on the wedged sendMutex. Verifies the exec carried
     * the exact command via [expectExecContains].
     */
    private suspend fun assertSendKeysViaExecCompletesDuringCcWedge(
        sendKeysCommand: String,
        expectExecContains: String,
    ) {
        val shell = FakeShell()
        val session = FakeSession(shell, execHandler = sendKeysExecHandler())
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val wedger = scope.async { runCatching { client.sendCommand("list-clients") } }
            withTimeout(2_000) {
                while (!shell.stdinAsString().contains("list-clients")) { yield(); delay(10) }
            }

            val startedAtMs = System.currentTimeMillis()
            val response = withTimeout(5_000) {
                client.sendKeysViaExec(sendKeysCommand)
            }
            val elapsedMs = System.currentTimeMillis() - startedAtMs

            assertFalse("the send must succeed during a -CC burst", response.isError)
            assertTrue(
                "the send must complete well under the -CC mutex ceiling " +
                    "(elapsed ${elapsedMs}ms) proving it did not wait on the wedged " +
                    "-CC sendMutex",
                elapsedMs < 2_000L,
            )
            assertEquals(
                "the send-keys must ride the exec lane verbatim",
                expectExecContains,
                session.execCommands.single { it.contains("send-keys") },
            )
            // And the send-keys never reached the wedged -CC stdin.
            assertFalse(
                "the send-keys must NOT be written to the wedged -CC shell",
                shell.stdinAsString().contains("send-keys"),
            )
            wedger.cancel()
        } finally {
            client.close()
        }
    }

    private fun sendKeysExecHandler(
        exitCode: Int = 0,
        stderr: String = "",
        delayMs: Long = 0L,
    ): suspend (String) -> ExecResult = { _ ->
        if (delayMs > 0L) delay(delayMs)
        // `send-keys` prints nothing on success.
        ExecResult(stdout = "", stderr = stderr, exitCode = exitCode)
    }

    private fun healExecHandler(
        cursor: String?,
        captureLines: List<String>,
        exitCode: Int = 0,
        stderr: String = "",
        delayMs: Long = 0L,
    ): suspend (String) -> ExecResult = { command ->
        if (delayMs > 0L) delay(delayMs)
        val marker = command.substringAfter("printf '%s\\n' '").substringBefore("'")
        val stdout = buildString {
            if (cursor != null) {
                append(cursor)
                append('\n')
            }
            append(marker)
            append('\n')
            for (line in captureLines) {
                append(line)
                append('\n')
            }
        }
        ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }

    private fun listPanesExecHandler(
        rows: List<String>,
        exitCode: Int = 0,
        stderr: String = "",
        delayMs: Long = 0L,
    ): suspend (String) -> ExecResult = { _ ->
        if (delayMs > 0L) delay(delayMs)
        val stdout = if (rows.isEmpty()) "" else rows.joinToString(separator = "\n") + "\n"
        ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }

    private class FakeSession(
        private val shell: SshShell,
        private val execHandler: (suspend (String) -> ExecResult)? = null,
    ) : SshSession {
        @Volatile
        private var closed = false

        val execCommands: MutableList<String> =
            Collections.synchronizedList(mutableListOf())

        override val isConnected: Boolean get() = !closed

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean = false

        override suspend fun exec(command: String): ExecResult {
            execCommands.add(command)
            val handler = execHandler
                ?: error("exec not stubbed in this TmuxClient unit test")
            return handler(command)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used in TmuxClient unit tests")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used in TmuxClient unit tests")

        override fun startShell(): SshShell {
            check(!closed) { "session closed" }
            return shell
        }

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
            shell.close()
        }
    }

    private class FakeShell : SshShell {
        private val pipeOut = PipedOutputStream()
        private val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
        private val stdinCapture = SynchronizedByteArrayOutputStream()

        @Volatile
        var closed: Boolean = false
            private set

        override val stdin: OutputStream = stdinCapture
        override val stdout: InputStream = pipeIn
        override val stderr: InputStream = object : InputStream() {
            override fun read(): Int = -1
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { pipeOut.close() }
            runCatching { pipeIn.close() }
            runCatching { stdinCapture.close() }
        }

        fun stdinBytes(): ByteArray = stdinCapture.snapshot()
        fun stdinAsString(): String = String(stdinBytes(), StandardCharsets.UTF_8)
        fun resetStdin() {
            stdinCapture.reset()
        }
    }

    private class SynchronizedByteArrayOutputStream : ByteArrayOutputStream() {
        @Volatile
        private var closedForWrites: Boolean = false

        override fun write(b: Int) {
            maybeThrowIfClosed()
            synchronized(this) {
                maybeThrowIfClosed()
                super.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            maybeThrowIfClosed()
            synchronized(this) {
                maybeThrowIfClosed()
                super.write(b, off, len)
            }
        }

        override fun close() {
            closedForWrites = true
            synchronized(this) {
                super.close()
            }
        }

        @Synchronized
        fun snapshot(): ByteArray = toByteArray()

        @Synchronized
        override fun reset() {
            super.reset()
        }

        private fun maybeThrowIfClosed() {
            if (closedForWrites) throw IOException("stdin closed")
        }
    }
}
