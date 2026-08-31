package com.pocketshell.app.projects

import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.core.ssh.ExecResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2348 — `pocketshell sessions list --json` is the tmuxctl+aplexer
 * enumerator. It must be fail-safe and must not spend a second unbounded
 * (or even a second bounded) SSH exec on empty-success or a hung JSON read.
 *
 * Mutation that must redden [jsonHangDoesNotFallThroughToHuman]: catching a
 * JSON timeout/throw and falling through to `pocketshell sessions list --by
 * activity`. That second exec is what turns a 3.5s bounded miss into a 7s
 * serial stall on the 12s mobile reconcile path. Issue #2377 added the second
 * half of that assertion: a JSON hop that fails BOTH its attempts must
 * resolve to [Fetch.Unavailable], not [Fetch.Empty] — collapsing "we could
 * not read it" into "the host has none" is what let a narrower enumeration
 * ship as the truth.
 *
 * Issue #2444: a transient JSON-hop loss (mobile RTT + packet loss) must NOT
 * fail the whole reconcile closed the way #2377's un-retried design did — see
 * [jsonHangRecoversOnASubsequentRetryAndNeverHitsHuman] for the
 * red-on-#2377/green-on-this-fix reproduction, and
 * [jsonHangDoesNotFallThroughToHuman] (updated) for the still-Unavailable case
 * when EVERY attempt (up to `HostSessionEnumerator.MAX_EXEC_ATTEMPTS`) fails.
 *
 * Mutation that must redden [jsonExitZeroHumanTableDoesNotExecHumanFallback]:
 * JSON exit 0 with `IDX  SESSION…` stdout (the 0.4.45 agents fixture) falling
 * through to `humanCommand`. Blank / `{"sessions":[]}` tests do not cover this.
 */
class FolderListPocketshellEnumeratorTest {
    private val parser = HostTmuxSessionListParser()

    @Test
    fun jsonExecBodyClosesStdinSoWrapCannotParkTheHop() {
        assertTrue(
            FolderListPocketshellEnumerator.JSON_EXEC_BODY.contains(
                SshFolderListGateway.POCKETSHELL_SESSIONS_JSON_COMMAND,
            ),
        )
        assertTrue(
            "mutation: drop </dev/null and a wrap()-style first-statement read() parks until the 12s bound",
            FolderListPocketshellEnumerator.JSON_EXEC_BODY.contains("</dev/null"),
        )
    }

    @Test
    fun jsonEmptySuccessDoesNotExecHumanFallback() = runTest {
        val commands = mutableListOf<String>()
        val fetched = FolderListPocketshellEnumerator.fetch(
            parser = parser,
            exec = { command ->
                commands += command
                ExecResult(stdout = "", stderr = "", exitCode = 0)
            },
            jsonCommand = JSON_CMD,
            humanCommand = HUMAN_CMD,
        )

        assertTrue(fetched is FolderListPocketshellEnumerator.Fetch.Empty)
        assertEquals(emptyList<FolderSessionRow>(), fetched.rows)
        assertEquals(listOf(JSON_CMD), commands)
    }

    @Test
    fun jsonEmptyObjectDoesNotExecHumanFallback() = runTest {
        val commands = mutableListOf<String>()
        val fetched = FolderListPocketshellEnumerator.fetch(
            parser = parser,
            exec = { command ->
                commands += command
                ExecResult(stdout = """{"sessions":[]}""", stderr = "", exitCode = 0)
            },
            jsonCommand = JSON_CMD,
            humanCommand = HUMAN_CMD,
        )

        assertTrue(fetched is FolderListPocketshellEnumerator.Fetch.Json)
        assertEquals(emptyList<FolderSessionRow>(), fetched.rows)
        assertEquals(listOf(JSON_CMD), commands)
    }

    @Test
    fun jsonHangDoesNotFallThroughToHuman() = runTest {
        // #2444: EVERY attempt at the JSON hop hangs, so this must still
        // resolve Unavailable — the un-retried #2377 design and this fix agree
        // here (only the failure count differs: MAX_EXEC_ATTEMPTS, not 1).
        val commands = mutableListOf<String>()
        val fetched = FolderListPocketshellEnumerator.fetch(
            parser = parser,
            exec = { command ->
                commands += command
                if (command == JSON_CMD) {
                    throw FolderListExecTimeoutException(command, 40L)
                }
                ExecResult(
                    stdout = "IDX  SESSION               CREATED\n" +
                        "1    should-not-appear     2026-05-30 00:20:01\n",
                    stderr = "",
                    exitCode = 0,
                )
            },
            jsonCommand = JSON_CMD,
            humanCommand = HUMAN_CMD,
        )

        assertTrue(
            "issue #2377: a hung enumerator is UNAVAILABLE (unknown), never Empty " +
                "(authoritatively no sessions) — Empty let the caller publish the " +
                "default-socket subset as the whole host",
            fetched is FolderListPocketshellEnumerator.Fetch.Unavailable,
        )
        assertEquals(
            "a hung JSON enumerator must not spend a second exec",
            emptyList<FolderSessionRow>(),
            fetched.rows,
        )
        assertEquals(
            "issue #2444: the JSON hop gets a bounded number of retries " +
                "(MAX_EXEC_ATTEMPTS = 3 total attempts) before Unavailable — never an " +
                "unbounded retry loop and never the human hop",
            listOf(JSON_CMD, JSON_CMD, JSON_CMD),
            commands,
        )
        assertTrue(commands.none { it == HUMAN_CMD })
    }

    /**
     * Issue #2444 — THE reproduction. RED on the pre-#2444 code (which has no
     * retry at all: the first throw alone would resolve Unavailable and the
     * command list would stop at one entry with the fetch never reaching a
     * real result), GREEN with the bounded-retry fix: the JSON hop's first TWO
     * attempts throw (a run of transient mobile-link losses — the exact shape
     * that a single-retry cap still let through often enough on the real
     * `Issue1876FolderReconcileMobileRttIntegrationTest` Docker profile, see
     * `HostSessionEnumerator.MAX_EXEC_ATTEMPTS`'s doc), but the THIRD attempt
     * of the SAME command succeeds, so the reconcile still returns a real
     * session list rather than failing the whole thing closed.
     */
    @Test
    fun jsonHangRecoversOnASubsequentRetryAndNeverHitsHuman() = runTest {
        val commands = mutableListOf<String>()
        var jsonAttempts = 0
        val fetched = FolderListPocketshellEnumerator.fetch(
            parser = parser,
            exec = { command ->
                commands += command
                if (command == JSON_CMD) {
                    jsonAttempts += 1
                    if (jsonAttempts < 3) {
                        throw FolderListExecTimeoutException(command, 40L)
                    }
                    ExecResult(stdout = FIXTURE_JSON, stderr = "", exitCode = 0)
                } else {
                    error("issue #2444: a JSON hop that recovers on a later retry must " +
                        "never fall through to the human hop")
                }
            },
            jsonCommand = JSON_CMD,
            humanCommand = HUMAN_CMD,
        )

        assertTrue(
            "two consecutive transient JSON-hop losses must not fail the whole reconcile " +
                "closed (issue #2444) — got $fetched",
            fetched is FolderListPocketshellEnumerator.Fetch.Json,
        )
        assertEquals(listOf("claude-main", "codex"), fetched.rows.map { it.sessionName })
        assertEquals(
            "exactly three JSON attempts (two transient losses, then the recovering " +
                "third attempt), no human hop",
            listOf(JSON_CMD, JSON_CMD, JSON_CMD),
            commands,
        )
    }

    @Test
    fun jsonExitZeroHumanTableDoesNotExecHumanFallback() = runTest {
        val commands = mutableListOf<String>()
        val fetched = FolderListPocketshellEnumerator.fetch(
            parser = parser,
            exec = { command ->
                commands += command
                if (command == JSON_CMD) {
                    ExecResult(stdout = FIXTURE_HUMAN_TABLE, stderr = "", exitCode = 0)
                } else {
                    ExecResult(
                        stdout = "IDX  SESSION               CREATED\n" +
                            "1    should-not-appear     2026-05-30 00:20:01\n",
                        stderr = "",
                        exitCode = 0,
                    )
                }
            },
            jsonCommand = JSON_CMD,
            humanCommand = HUMAN_CMD,
        )

        assertTrue(fetched is FolderListPocketshellEnumerator.Fetch.Human)
        assertEquals(listOf("claude-main", "codex"), fetched.rows.map { it.sessionName })
        assertEquals(
            "mutation: JSON exit 0 + IDX table must not spend a second human exec",
            listOf(JSON_CMD),
            commands,
        )
    }

    @Test
    fun jsonExitZeroGarbageDoesNotExecHumanFallback() = runTest {
        val commands = mutableListOf<String>()
        val fetched = FolderListPocketshellEnumerator.fetch(
            parser = parser,
            exec = { command ->
                commands += command
                if (command == JSON_CMD) {
                    ExecResult(stdout = "not a json object and not a table\n", stderr = "", exitCode = 0)
                } else {
                    ExecResult(
                        stdout = "IDX  SESSION               CREATED\n" +
                            "1    should-not-appear     2026-05-30 00:20:01\n",
                        stderr = "",
                        exitCode = 0,
                    )
                }
            },
            jsonCommand = JSON_CMD,
            humanCommand = HUMAN_CMD,
        )

        assertTrue(fetched is FolderListPocketshellEnumerator.Fetch.Empty)
        assertEquals(emptyList<FolderSessionRow>(), fetched.rows)
        assertEquals(listOf(JSON_CMD), commands)
    }

    @Test
    fun unknownJsonFlagStillFallsBackToHumanTable() = runTest {
        val commands = mutableListOf<String>()
        val fetched = FolderListPocketshellEnumerator.fetch(
            parser = parser,
            exec = { command ->
                commands += command
                if (command == JSON_CMD) {
                    ExecResult(
                        stdout = "",
                        stderr = "No such option: --json",
                        exitCode = 2,
                    )
                } else {
                    ExecResult(
                        stdout = "IDX  SESSION               CREATED\n" +
                            "1    legacy-session        2026-05-30 00:20:01\n",
                        stderr = "",
                        exitCode = 0,
                    )
                }
            },
            jsonCommand = JSON_CMD,
            humanCommand = HUMAN_CMD,
        )

        assertTrue(fetched is FolderListPocketshellEnumerator.Fetch.Human)
        assertEquals(listOf("legacy-session"), fetched.rows.map { it.sessionName })
        assertEquals(listOf(JSON_CMD, HUMAN_CMD), commands)
    }

    private companion object {
        const val JSON_CMD: String = "pocketshell sessions list --json"
        const val HUMAN_CMD: String = "pocketshell sessions list --by activity"
        const val FIXTURE_HUMAN_TABLE: String =
            "IDX  SESSION               CREATED\n" +
                "1    claude-main           2026-05-30 00:20:01\n" +
                "2    codex                 2026-05-30 00:19:58\n"
        const val FIXTURE_JSON: String =
            """{"sessions":[""" +
                """{"name":"claude-main","manager":"tmux","created_epoch":1748560801},""" +
                """{"name":"codex","manager":"tmux","created_epoch":1748560798}""" +
                "]}"
    }
}
