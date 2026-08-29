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
import com.pocketshell.core.tmux.TmuxRead
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #2160, acceptance criterion 3 (G2 class coverage): the OTHER user-option
 * reads with the same exposure.
 *
 * `@ps_agent_source` was the site that was noticed because it is the one whose
 * value carries a control byte (the TAB delimiter). But the sanitiser is a
 * property of the tmux CLIENT, not of one option: on a host whose SSH exec
 * carries no UTF-8 locale, EVERY byte tmux prints that is not printable ASCII
 * comes back as `_`. The session enumeration reads four user options
 * (`@ps_agent_kind`, `@ps_agent_profile`, `@ps_agent_state`,
 * `@ps_agent_state_updated_at`) plus `#{session_path}` / `#{pane_current_path}`.
 *
 * Confirmed, not assumed (the issue asks for exactly this): the four option
 * VALUES carry no control bytes today —
 *  - `@ps_agent_kind` is one of `claude` / `codex` / `opencode` / `shell`
 *    (`record_agent_kind`, `agents.py`);
 *  - `@ps_agent_state` is `idle` / `waiting_for_input` / `working` (`hooks.py`);
 *  - `@ps_agent_state_updated_at` is an ISO-8601 timestamp (`hooks.py`);
 *  - `@ps_agent_source_generation` is `uuid.uuid4().hex` (`agents.py`),
 * so none of them can redden a TAB-shaped assertion.
 *
 * `@ps_agent_profile` and the two path fields are different: they are
 * **free-form human text** — a profile label the user writes in their host
 * config, and a project directory path. Non-ASCII there is destroyed exactly
 * like the TAB, and the maintainer routinely works in Russian. That is the case
 * this test pins.
 */
class Issue2160LocaleProofEnumerationTest {

    private companion object {
        val HOST = HostEntity(
            id = 21_60L,
            name = "affected",
            hostname = "affected.example",
            port = 22,
            username = "alex",
            keyId = 1L,
        )
        const val KEY_PATH = "/keys/a"
        const val SESSION = "почта"
        const val CWD = "/home/u/проекты/почта"
        const val PROFILE = "Claude (Я.Облако)"

        /**
         * tmux `utf8_sanitize()` — every character that is not printable ASCII
         * becomes a single `_`. Verified against real tmux 3.4 and 3.6b.
         */
        fun utf8Sanitize(value: String): String =
            value.map { ch -> if (ch.code in 0x20..0x7e) ch else '_' }.joinToString("")
    }

    /**
     * A host whose sshd hands the exec channel no locale. It answers the
     * enumeration with a genuine `list-sessions`/`list-panes` payload and then
     * applies tmux's sanitiser to it — unless the invocation asked for UTF-8
     * output. A fixture that always returns clean bytes cannot enter the failing
     * state and would prove nothing.
     */
    private class NonUtf8EnumerationSession : SshSession {
        val execCommands: MutableList<String> = mutableListOf()
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            if (!command.contains("list-sessions")) {
                return ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
            val sep = SshFolderListGateway.FIELD_SEP
            val marker = SshFolderListGateway.ENUMERATION_MARKER
            val sessions = listOf(
                SESSION, "\$0", "1700000000", "1700000900", "0",
                "claude", PROFILE, "working", "1700000900", CWD,
            ).joinToString(sep)
            val panes = listOf(
                SESSION, "0", "main", "1", "1", CWD, "/dev/pts/3", "claude", "@0", "4242",
            ).joinToString(sep)
            val payload = buildString {
                append(sessions).append("\n")
                append("\n").append(marker).append(" 0\n")
                append(panes).append("\n")
                append("\n").append(marker).append(" 0\n")
            }
            // The whole enumeration payload is printed by ONE tmux client, so the
            // client's UTF-8 mode decides the bytes for every field at once.
            val stdout = if (TmuxRead.isLocaleProofInvocation(command)) payload else utf8Sanitize(payload)
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError()

        override fun startShell(): SshShell = throw NotImplementedError()

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    private class FixedConnector(private val session: SshSession) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    /**
     * RED on base (`44c93855`): the enumeration runs `tmux list-sessions -F …`
     * with no `-u`, so the Cyrillic session name, project path and profile label
     * all come back as runs of `_` and the tree shows mojibake for a session it
     * can no longer target by name.
     */
    @Test
    fun enumerationReadsUserOptionsAndPathsIntactOnAHostWithNoUtf8Locale() = runTest {
        val session = NonUtf8EnumerationSession()
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = FixedConnector(session),
                scope = this,
                idleTtlMillis = 30_000L,
            ),
        )

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)
        assertTrue("expected Sessions, got $result", result is FolderListResult.Sessions)
        val row = (result as FolderListResult.Sessions).rows.single()

        assertEquals("the tmux session name must survive the enumeration read", SESSION, row.sessionName)
        assertEquals("the project path must survive the enumeration read", CWD, row.cwd)
        assertEquals(
            "#2160 (G2): @ps_agent_profile is free-form human text — a non-ASCII " +
                "profile label must survive the enumeration read intact",
            PROFILE,
            row.recordedProfile,
        )
    }

    /**
     * The structural half of criterion 2 for this path: the enumeration command
     * constants themselves. The behavioural test above can only redden on
     * non-ASCII; this one also covers the options whose values are ASCII today
     * and would therefore never redden a value assertion, and it is the ratchet
     * against a future field being added to a bare `tmux list-…`.
     */
    @Test
    fun enumerationCommandsUseTheLocaleProofClient() {
        assertTrue(
            "LIST_SESSIONS_COMMAND must use `${TmuxRead.CLIENT}`; " +
                "got ${SshFolderListGateway.LIST_SESSIONS_COMMAND}",
            TmuxRead.isLocaleProofInvocation(SshFolderListGateway.LIST_SESSIONS_COMMAND),
        )
        assertTrue(
            "LIST_PANES_COMMAND must use `${TmuxRead.CLIENT}`; " +
                "got ${SshFolderListGateway.LIST_PANES_COMMAND}",
            TmuxRead.isLocaleProofInvocation(SshFolderListGateway.LIST_PANES_COMMAND),
        )
    }
}
