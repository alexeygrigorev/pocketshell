package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxRead
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #2348 — v0.4.45 added `pocketshell sessions list --json` on the folder
 * reconcile path so the phone matches `tmuxctl list` / `t`. Doing that as a
 * *serial* extra SSH exec (and a second `tmux list-sessions` enrichment when
 * JSON already succeeded) blows #1876's unchanged 12s mobile bound.
 *
 * Mutation that must redden these tests:
 *  - start the JSON enumerator only after the landing `list-sessions`+
 *    `list-panes` exec returns ([jsonEnumeratorOverlapsLandingEnumeration]);
 *  - after a non-empty JSON success, issue [POCKETSHELL_SESSIONS_TMUX_COMMAND]
 *    ([jsonSuccessDoesNotIssueSecondTmuxListExec]).
 */
class Issue2348FolderListListPathHopTest {
    @Test
    fun reconcileBoundStaysTwelveSeconds() {
        assertEquals(12_000L, FolderListViewModel.RECONCILE_TIMEOUT_MS)
    }

    @Test
    fun jsonEnumeratorOverlapsLandingEnumeration() = runBlocking {
        val session = OverlapSession()
        val gateway = gateway(session, execReadTimeoutMs = 400L)

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        assertTrue(
            "JSON must overlap the landing enumeration; a serial extra hop deadlocks " +
                "the latch and surfaces $result execs=${session.execCommands}",
            result is FolderListResult.Sessions,
        )
        val names = (result as FolderListResult.Sessions).rows.map { it.sessionName }
        assertTrue("native default-socket row missing in $names", "native-only" in names)
        assertTrue("tmuxctl extra missing in $names — reverted to default-socket list?", "tmuxctl-extra" in names)
        assertTrue("aplexer extra missing in $names", "aplexer-one" in names)
        assertEquals(
            "mutation: a second JSON/human/tmux list hop after landing reddens this",
            1,
            session.execCommands.count { it.contains("sessions list --json") },
        )
        assertEquals(
            0,
            session.execCommands.count { it.contains("sessions list --by") },
        )
        assertEquals(
            0,
            extraTmuxListCount(session.execCommands),
        )
    }

    @Test
    fun jsonSuccessDoesNotIssueSecondTmuxListExec() = runTest {
        val session = JsonSuccessFallbackSession()
        val gateway = SshFolderListGateway()

        val result = gateway.listSessionsFromNativeOrPocketshell(
            session = session,
            listSessions = ExecResult(
                stdout = "",
                stderr = "error connecting to /tmp/tmux-1000/default (No such file or directory)",
                exitCode = 1,
            ),
            probes = gateway.serialSideProbes(session, HOST, emptyList()),
        )

        assertTrue(result is FolderListResult.Sessions)
        val names = (result as FolderListResult.Sessions).rows.map { it.sessionName }
        assertEquals(
            "JSON is the authority on the native-tmux-absent path, including overlay names",
            listOf("tmuxctl-extra", "aplexer-one", "native-only"),
            names,
        )
        assertTrue("structured enrichment must not invent a row", "should-not-enrich" !in names)
        assertEquals(
            "mutation: adding POCKETSHELL_SESSIONS_TMUX_COMMAND after JSON success reddens this",
            0,
            extraTmuxListCount(session.execCommands),
        )
        assertEquals(
            0,
            session.execCommands.count { it.contains("sessions list --by") },
        )
        assertEquals(
            1,
            session.execCommands.count { it.contains("sessions list --json") },
        )
        val aplexer = (result as FolderListResult.Sessions).rows.single { it.sessionName == "aplexer-one" }
        assertEquals("aplexer", aplexer.sessionManager)
        assertEquals("b3feff71-4a78-4055-a2d3-6c99187ecffb", aplexer.aplexerId)
    }

    @Test
    fun nativeSuccessUnionsTmuxctlAndAplexerWithoutHumanFallback() = runTest {
        val session = NativeUnionSession()
        val gateway = gateway(session)

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        assertTrue(result is FolderListResult.Sessions)
        val rows = (result as FolderListResult.Sessions).rows
        assertEquals(
            listOf("tmuxctl-extra", "aplexer-one", "native-only"),
            rows.map { it.sessionName },
        )
        assertEquals("tmux", rows.first { it.sessionName == "tmuxctl-extra" }.sessionManager)
        assertEquals("aplexer", rows.first { it.sessionName == "aplexer-one" }.sessionManager)
        assertEquals("/work/native", rows.first { it.sessionName == "native-only" }.cwd)
        assertEquals(
            0,
            session.execCommands.count { it.contains("sessions list --by") },
        )
        assertEquals(
            "mutation: a second tmux list-sessions after JSON success reddens this",
            0,
            extraTmuxListCount(session.execCommands),
        )
    }

    @Test
    fun jsonExitZeroHumanTableDoesNotIssueSecondHumanExec() = runTest {
        val session = FixtureHumanTableSession()
        val gateway = gateway(session)

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        assertTrue(result is FolderListResult.Sessions)
        val names = (result as FolderListResult.Sessions).rows.map { it.sessionName }
        assertTrue("tmuxctl extra missing in $names", "tmuxctl-extra" in names)
        assertTrue("native default-socket row missing in $names", "native-only" in names)
        assertEquals(
            "mutation: JSON exit 0 + IDX table must not spend a second human exec",
            0,
            session.execCommands.count { it.contains("sessions list --by") },
        )
        assertEquals(
            1,
            session.execCommands.count { it.contains("sessions list --json") },
        )
        assertEquals(0, extraTmuxListCount(session.execCommands))
    }

    private fun gateway(
        session: SshSession,
        execReadTimeoutMs: Long = SshFolderListGateway.EXEC_READ_TIMEOUT_MS,
    ): SshFolderListGateway =
        SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector { Result.success(session) },
            ),
            sessionListParser = HostTmuxSessionListParser(),
            execReadTimeoutMs = execReadTimeoutMs,
            enginesGateway = null,
        )

    /**
     * Completes the landing enumeration only after JSON has started, and
     * vice versa. Serial JSON-after-landing deadlocks until [execBounded]
     * trips; overlapping hops both complete.
     */
    private class OverlapSession : SshSession {
        val execCommands: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        private val enumStarted = CompletableDeferred<Unit>()
        private val jsonStarted = CompletableDeferred<Unit>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            synchronized(execCommands) { execCommands += command }
            return when {
                command.contains("sessions list --json") -> {
                    jsonStarted.complete(Unit)
                    enumStarted.await()
                    ExecResult(JSON_STDOUT, "", 0)
                }
                command.contains(SshFolderListGateway.ENUMERATION_MARKER) -> {
                    enumStarted.complete(Unit)
                    jsonStarted.await()
                    ExecResult(ENUMERATION_STDOUT, "", 0)
                }
                else -> ExecResult("", "", 1)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
        override fun close() = Unit
    }

    private class JsonSuccessFallbackSession : SshSession {
        val execCommands: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return when {
                command.contains("sessions list --json") -> ExecResult(JSON_STDOUT, "", 0)
                command.contains("sessions list --by") ->
                    ExecResult("IDX  SESSION\n1    should-not-appear  2026-05-30 00:20:01\n", "", 0)
                command.contains("list-sessions") ->
                    ExecResult("${'$'}9::should-not-enrich::1::1::0::::/tmp\n", "", 0)
                else -> ExecResult("", "", 1)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
        override fun close() = Unit
    }

    private class FixtureHumanTableSession : SshSession {
        val execCommands: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return when {
                command.contains("sessions list --json") -> ExecResult(FIXTURE_HUMAN_TABLE, "", 0)
                command.contains(SshFolderListGateway.ENUMERATION_MARKER) ->
                    ExecResult(ENUMERATION_STDOUT, "", 0)
                command.contains("sessions list --by") ->
                    error("human fallback must not run on JSON exit 0 human-table body")
                else -> ExecResult("", "", 1)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
        override fun close() = Unit
    }

    private class NativeUnionSession : SshSession {
        val execCommands: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return when {
                command.contains("sessions list --json") -> ExecResult(JSON_STDOUT, "", 0)
                command.contains(SshFolderListGateway.ENUMERATION_MARKER) ->
                    ExecResult(ENUMERATION_STDOUT, "", 0)
                command.contains("sessions list --by") ->
                    error("human fallback must not run on JSON success")
                else -> ExecResult("", "", 1)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
        override fun close() = Unit
    }

    private companion object {
        const val KEY_PATH: String = "/tmp/pocketshell-test-key"
        val HOST: HostEntity = HostEntity(
            id = 42L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
        val SEP: String = SshFolderListGateway.FIELD_SEP
        val MARKER: String = SshFolderListGateway.ENUMERATION_MARKER
        val ENUMERATION_STDOUT: String =
            "native-only${SEP}1${SEP}2${SEP}1${SEP}claude${SEP}${SEP}/work/native\n" +
                "$MARKER 0\n" +
                "native-only${SEP}0${SEP}w${SEP}1${SEP}1${SEP}/work/native${SEP}/dev/pts/1${SEP}node${SEP}@0${SEP}111\n" +
                "$MARKER 0\n"
        const val FIXTURE_HUMAN_TABLE: String =
            "IDX  SESSION               CREATED\n" +
                "1    tmuxctl-extra         2026-05-30 00:20:01\n" +
                "2    native-only           2026-05-30 00:19:58\n"
        const val JSON_STDOUT: String =
            """{"managers":["tmux","aplexer"],"sessions":[""" +
                """{"name":"tmuxctl-extra","manager":"tmux"},""" +
                """{"name":"aplexer-one","manager":"aplexer",""" +
                """"id":"b3feff71-4a78-4055-a2d3-6c99187ecffb"},""" +
                """{"name":"native-only","manager":"tmux"}""" +
                """]}"""

        fun extraTmuxListCount(commands: List<String>): Int =
            commands.count { command ->
                command.contains("${TmuxRead.CLIENT} list-sessions") &&
                    !command.contains(SshFolderListGateway.ENUMERATION_MARKER)
            }
    }
}
