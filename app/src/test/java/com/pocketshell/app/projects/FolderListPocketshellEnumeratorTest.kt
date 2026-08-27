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
 * serial stall on the 12s mobile reconcile path.
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

        assertTrue(fetched is FolderListPocketshellEnumerator.Fetch.Empty)
        assertEquals(
            "a hung JSON enumerator must fail-safe to empty, not spend a second exec",
            emptyList<FolderSessionRow>(),
            fetched.rows,
        )
        assertEquals(listOf(JSON_CMD), commands)
        assertTrue(commands.none { it == HUMAN_CMD })
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
    }
}
