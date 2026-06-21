package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Epic #821 slice A2 (hard-cut, D22) — D31 class-covering regression for the
 * folder-list kind authority after the output-parsing detector was deleted.
 *
 * The whole class is covered:
 *  - a recorded **Claude** session and a recorded **Codex** session classify
 *    from `@ps_agent_kind` ALONE, with **zero** kind round-trips (no
 *    `agents kind` exec) — recorded kind is the sole authority; and
 *  - a **FOREIGN** session (`recordedKind == null`) is classified by the
 *    ONE-SHOT host daemon guess (`pocketshell agents kind`) and never by
 *    output parsing.
 *
 * These FAIL on the un-fixed code: before A2 the gateway ran
 * `AgentConversationRepository.detectForPanes` (output parsing) for EVERY row
 * including recorded ones, so (a) recorded rows DID issue detection round-trips
 * (no `agents kind`, but the candidate-enumeration + `ps` execs the assertions
 * here forbid) and (b) the foreign row was classified by parsing, not by an
 * `agents kind` exec (which did not exist).
 */
class FolderListGatewayKindAuthorityTest {

    @Test
    fun recordedClaudeAndCodexSessionsClassifyFromRecordedKindWithNoKindRoundTrip() = runTest {
        // Two sessions WE launched: one recorded claude, one recorded codex.
        // Field order (#821 + #858): name, created, activity, attached,
        // @ps_agent_kind, @ps_agent_profile (blank here — default profiles),
        // path.
        val listSessions =
            "rec-claude${SEP}1${SEP}2${SEP}1${SEP}claude${SEP}${SEP}/work/claude\n" +
                "rec-codex${SEP}1${SEP}2${SEP}1${SEP}codex${SEP}${SEP}/work/codex"
        // list-panes -a: name, win_index, win_name, win_active, pane_active,
        // cwd, tty, command, window_id, pane_pid.
        val listPanes =
            "rec-claude${SEP}0${SEP}w${SEP}1${SEP}1${SEP}/work/claude${SEP}/dev/pts/1${SEP}node${SEP}@0${SEP}111\n" +
                "rec-codex${SEP}0${SEP}w${SEP}1${SEP}1${SEP}/work/codex${SEP}/dev/pts/2${SEP}node${SEP}@1${SEP}222"
        val session = FakeSshSession(enumerationStdout = "$listSessions\n$MARKER\n$listPanes")
        val gateway = gateway(session)

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        val rows = (result as FolderListResult.Sessions).rows
        assertEquals(SessionAgentKind.Claude, rows.first { it.sessionName == "rec-claude" }.agentKind)
        assertEquals(SessionAgentKind.Codex, rows.first { it.sessionName == "rec-codex" }.agentKind)
        // Recorded sessions are the sole authority — zero kind round-trips.
        assertFalse(
            "recorded sessions must issue NO `agents kind` daemon guess; execs=${session.execCommands}",
            session.execCommands.any { it.contains("agents kind") },
        )
        // The deleted output-parsing detector's candidate-enumeration + host-wide
        // `ps` scan must also be gone for recorded rows.
        assertFalse(
            "recorded sessions must issue NO output-parsing detection (no host-wide ps); " +
                "execs=${session.execCommands}",
            session.execCommands.any { it.contains("ps -eo pid,ppid,tty,comm,args") },
        )
    }

    @Test
    fun recordedProfileIsReadBackAndCarriedOnTheRow() = runTest {
        // Issue #858 end-to-end read-back: a z.ai Claude session carries its
        // recorded @ps_agent_profile; a default Claude carries none. The
        // gateway enumeration surfaces the profile on the row model.
        val listSessions =
            "zai-claude${SEP}1${SEP}2${SEP}1${SEP}claude${SEP}Claude (Z.AI)${SEP}/work/zai\n" +
                "def-claude${SEP}1${SEP}2${SEP}1${SEP}claude${SEP}${SEP}/work/def"
        val listPanes =
            "zai-claude${SEP}0${SEP}w${SEP}1${SEP}1${SEP}/work/zai${SEP}/dev/pts/1${SEP}node${SEP}@0${SEP}111\n" +
                "def-claude${SEP}0${SEP}w${SEP}1${SEP}1${SEP}/work/def${SEP}/dev/pts/2${SEP}node${SEP}@1${SEP}222"
        val session = FakeSshSession(enumerationStdout = "$listSessions\n$MARKER\n$listPanes")
        val gateway = gateway(session)

        val rows = (gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)
            as FolderListResult.Sessions).rows

        assertEquals(
            "Claude (Z.AI)",
            rows.first { it.sessionName == "zai-claude" }.recordedProfile,
        )
        assertEquals(
            "a default Claude carries no profile label",
            null,
            rows.first { it.sessionName == "def-claude" }.recordedProfile,
        )
        // The kind is still authoritative for both.
        assertEquals(SessionAgentKind.Claude, rows.first { it.sessionName == "zai-claude" }.agentKind)
        assertEquals(SessionAgentKind.Claude, rows.first { it.sessionName == "def-claude" }.agentKind)
    }

    @Test
    fun foreignSessionIsClassifiedByTheOneShotDaemonGuess() = runTest {
        // A session we did NOT launch: blank @ps_agent_kind (5th field) AND
        // blank @ps_agent_profile (6th field).
        val listSessions = "foreign${SEP}1${SEP}2${SEP}1${SEP}${SEP}${SEP}/work/foreign"
        val listPanes =
            "foreign${SEP}0${SEP}w${SEP}1${SEP}1${SEP}/work/foreign${SEP}/dev/pts/3${SEP}node${SEP}@0${SEP}333"
        val session = FakeSshSession(
            enumerationStdout = "$listSessions\n$MARKER\n$listPanes",
            // The daemon guess for the foreign pane: claude.
            agentsKindStdout = """{"results":[{"pane_id":"foreign${SEP}0","agent_kind":"claude","scope":"x.scope","evidence_pid":3030}]}""",
        )
        val gateway = gateway(session)

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        val rows = (result as FolderListResult.Sessions).rows
        assertEquals(
            "a foreign session must take the daemon's one-shot kind guess",
            SessionAgentKind.Claude,
            rows.first { it.sessionName == "foreign" }.agentKind,
        )
        assertTrue(
            "the foreign session must trigger exactly one `agents kind` daemon guess; " +
                "execs=${session.execCommands}",
            session.execCommands.count { it.contains("agents kind") } == 1,
        )
    }

    private fun gateway(session: SshSession): SshFolderListGateway =
        SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector { Result.success(session) },
            ),
        )

    private class FakeSshSession(
        private val enumerationStdout: String,
        private val agentsKindStdout: String = """{"results":[]}""",
    ) : SshSession {
        val execCommands: MutableList<String> = mutableListOf()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return when {
                // The chained enumeration (list-sessions + marker + list-panes).
                command.contains("list-sessions") ->
                    ExecResult(enumerationStdout, "", 0)
                command.contains("agents kind") ->
                    ExecResult(agentsKindStdout, "", 0)
                // Port scan + anything else: empty success.
                else -> ExecResult("", "", 0)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("not used")

        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(input: InputStream, length: Long, name: String, remotePath: String): String =
            error("not used")

        override fun close() = Unit
    }

    private companion object {
        val SEP: String = "::"
        val MARKER: String = SshFolderListGateway.ENUMERATION_MARKER
        const val KEY_PATH: String = "/tmp/pocketshell-test-key"
        val HOST: HostEntity = HostEntity(
            id = 42L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
    }
}
