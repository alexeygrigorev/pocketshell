package com.pocketshell.app.sessions

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
 * Issue #2160, round 2 — the read site the first sweep MISSED.
 *
 * [SshHostTmuxSessionsGateway.listSessions] is the host session picker's COLD
 * path: it runs whenever there is no warm `-CC` client for the host, over the
 * same SSH exec lane and with the same command shape as
 * [com.pocketshell.app.projects.SshFolderListGateway.LIST_SESSIONS_COMMAND],
 * which round 1 did fix. It expands `#{session_name}`, which is free-form and
 * routinely non-ASCII for this maintainer.
 *
 * On a host whose sshd hands the exec channel no locale, a tmux client that is
 * not in UTF-8 mode runs everything it prints through `utf8_sanitize()`, which
 * replaces each non-printable-ASCII character with a single `_`. A Cyrillic
 * session name therefore comes back as a run of underscores, and the picker
 * cannot even target by name the session it is showing.
 *
 * RED before the round-2 fix (`tmux list-sessions -F …`, no `-u`): the row's
 * name is `_____`. GREEN with `${'$'}{TmuxRead.CLIENT}`.
 */
class Issue2160HostSessionPickerLocaleProofTest {

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

        /**
         * tmux `utf8_sanitize()` — every character that is not printable ASCII
         * (`0x20`..`0x7e`) becomes a single `_`. Verified against real tmux 3.4
         * and 3.6b: `A<TAB>B` -> `A_B`, `ПРИВЕТ-✓` -> `______-_`, per CHARACTER.
         */
        fun utf8Sanitize(value: String): String =
            value.map { ch -> if (ch.code in 0x20..0x7e) ch else '_' }.joinToString("")
    }

    /**
     * A host whose sshd exports no `LANG`/`LC_*` (the repository's own Docker
     * `agents` fixture, an Alpine/BusyBox container, a hardened sshd). It
     * serves a genuine `list-sessions -F` payload and then applies tmux's
     * sanitiser to it **unless the invocation asked for UTF-8 output** — a
     * fixture that always returns clean bytes cannot enter the failing state
     * and would prove nothing (the v0.4.10/#847 lesson).
     */
    private class NonUtf8PickerSession : SshSession {
        val execCommands: MutableList<String> = mutableListOf()
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            if (!command.contains("list-sessions")) {
                return ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
            val payload = "\$7::$SESSION::1700000000::1700000900::0\n"
            val stdout =
                if (TmuxRead.isLocaleProofInvocation(command)) payload else utf8Sanitize(payload)
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
            Job()

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
     * LOAD-BEARING (G6): the symptom-defining signal is the session NAME the
     * picker shows and navigates by. `_____` means the enumeration was read
     * through a tmux client that sanitised it.
     */
    @Test
    fun pickerColdEnumerationReadsSessionNamesIntactOnAHostWithNoUtf8Locale() = runTest {
        val session = NonUtf8PickerSession()
        val manager = SshLeaseManager(
            connector = FixedConnector(session),
            scope = this,
            idleTtlMillis = 30_000L,
        )
        try {
            val gateway = SshHostTmuxSessionsGateway(
                parser = HostTmuxSessionListParser(),
                activeTmuxClients = ActiveTmuxClients(),
                sshLeaseManager = manager,
            )

            val result = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

            assertTrue("expected Sessions, got $result", result is HostTmuxSessionListResult.Sessions)
            val row = (result as HostTmuxSessionListResult.Sessions).rows.single()
            assertEquals(
                "#2160: the host session picker's cold enumeration must read " +
                    "`#{session_name}` intact on a host whose SSH exec carries no " +
                    "UTF-8 locale. `${utf8Sanitize(SESSION)}` means the name was " +
                    "sanitised on read and the picker cannot target the session it " +
                    "is showing. commands=${session.execCommands}",
                SESSION,
                row.name,
            )
            assertEquals("\$7", row.tmuxSessionId)
        } finally {
            manager.close()
        }
    }

    /**
     * The structural half: the constant itself. The behavioural test above can
     * only redden on non-ASCII content; this one is the ratchet against the
     * `-u` being dropped again on a payload that happens to be ASCII in the
     * fixture of the day.
     */
    @Test
    fun pickerColdEnumerationCommandUsesTheLocaleProofClient() {
        assertTrue(
            "SshHostTmuxSessionsGateway.LIST_SESSIONS_COMMAND must use " +
                "`${TmuxRead.CLIENT}`; got ${SshHostTmuxSessionsGateway.LIST_SESSIONS_COMMAND}",
            TmuxRead.isLocaleProofInvocation(SshHostTmuxSessionsGateway.LIST_SESSIONS_COMMAND),
        )
    }
}
