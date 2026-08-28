package com.pocketshell.app.sessions

import com.pocketshell.app.projects.EnginesGateway
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream

class HostTmuxSessionsGatewayTest {
    private val parser = HostTmuxSessionListParser()
    private val activeTmuxClients = ActiveTmuxClients()

    @Before
    fun resetTelemetry() {
        SshOpenTelemetry.resetForTest()
    }

    /**
     * Issue #2377: this used to be `sameHostLiveClientListsSessionsWithoutOpeningSsh`
     * and pinned the defect — the live `-CC` client's rows returned as the WHOLE
     * picker list, with `SSH_SOURCE_SESSION_PICKER_LIST == 0` proving no lease was
     * dialled at all. A control client sees ONE tmux server, so that shortcut is
     * the undercount. What #692 actually bought on this surface, and what is
     * asserted now, is that the warm path does strictly LESS lease work than the
     * cold one: the live rows answer `tmux list-sessions`, so the lease only pays
     * the one host-wide enumerator exec. The union itself is covered by
     * [Issue2377SessionPickerUndercountTest].
     */
    @Test
    fun sameHostLiveClientListsSessionsFromOneEnumeratorExec() = runTest {
        val client = FakeTmuxClient()
        client.responses += CommandResponse(
            number = 1L,
            output = listOf(
                "\$2::beta::101::301::1::/home/alexey/git/pocketshell",
                "\$1::alpha::100::300::0::/home/alexey/git/other",
            ),
            isError = false,
        )
        activeTmuxClients.register(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            client = client,
        )
        // The host has no working enumerator, so the live rows are the whole
        // truth here — this test is about the SHAPE of the warm path, not the
        // union (which Issue2377SessionPickerUndercountTest owns).
        val session = FakeSshSession()
        val manager = SshLeaseManager(
            connector = CountingConnector(Result.success(session)),
            scope = this,
            idleTtlMillis = 30_000L,
        )
        val gateway = SshHostTmuxSessionsGateway(parser, activeTmuxClients, manager)

        try {
            val result = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

            assertTrue(result is HostTmuxSessionListResult.Sessions)
            val rows = (result as HostTmuxSessionListResult.Sessions).rows
            assertEquals(listOf("beta", "alpha"), rows.map { it.name })
            assertEquals(listOf("\$2", "\$1"), rows.map { it.tmuxSessionId })
            assertEquals(
                listOf("/home/alexey/git/pocketshell", "/home/alexey/git/other"),
                rows.map { it.path },
            )
            assertEquals(
                listOf(SshHostTmuxSessionsGateway.LIVE_LIST_SESSIONS_COMMAND),
                client.sentCommands,
            )
            // The load-bearing #692 property: the lease is NOT asked to redo the
            // per-socket read the live client just answered.
            assertEquals(
                "the warm path must not re-run tmux list-sessions over the lease",
                0,
                session.execCommands.count { it.contains("list-sessions") },
            )
            assertEquals(1, SshOpenTelemetry.count(SSH_SOURCE_SESSION_PICKER_LIST))
        } finally {
            manager.close()
        }
    }

    @Test
    fun liveClientMapsCustomRecordedKindThroughEngineRegistry() = runTest {
        val client = FakeTmuxClient()
        client.responses += CommandResponse(
            number = 1L,
            output = listOf(
                "\$2::nested-agent::101::301::1::custom-codex::/srv/app",
            ),
            isError = false,
        )
        activeTmuxClients.register(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            client = client,
        )
        // Issue #2377: the warm path now also reads the host-wide enumerator over
        // the lease, so a connector that refuses to dial would surface
        // ConnectFailed. This host simply has no enumerator (`Fetch.Failed`), so
        // the live rows remain the whole answer and the recorded-kind mapping —
        // the property under test — is unaffected by the union.
        val manager = SshLeaseManager(
            connector = CountingConnector(Result.success(FakeSshSession())),
            scope = this,
            idleTtlMillis = 30_000L,
        )
        val gateway = SshHostTmuxSessionsGateway(
            parser = parser,
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = manager,
            leaseBlockTimeoutMs = 250L,
            liveEnumTimeoutMs = 250L,
            enginesGateway = FamilyGateway("custom-codex"),
        )

        try {
            val result = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

            assertTrue(result is HostTmuxSessionListResult.Sessions)
            val row = (result as HostTmuxSessionListResult.Sessions).rows.single()
            assertEquals("custom-codex", row.recordedKindId)
            assertEquals(SessionAgentKind.Codex, row.recordedKind)
            assertEquals(SessionAgentKind.Codex, row.agentKind)
        } finally {
            manager.close()
        }
    }

    @Test
    fun pickerListKeepsAndReusesLeaseSessionAcrossLoads() = runTest {
        val session = FakeSshSession(
            responses = ArrayDeque(
                listOf(
                    ExecResult(stdout = "\$1::alpha::100::300::0\n", stderr = "", exitCode = 0),
                    ExecResult(stdout = "\$2::beta::101::301::1\n", stderr = "", exitCode = 0),
                ),
            ),
        )
        val connector = CountingConnector(Result.success(session))
        val manager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = Long.MAX_VALUE,
        )
        val gateway = SshHostTmuxSessionsGateway(parser, activeTmuxClients, manager)

        try {
            val first = gateway.listSessions(HOST, KEY_PATH, passphrase = null)
            val second = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

            assertTrue(first is HostTmuxSessionListResult.Sessions)
            assertTrue(second is HostTmuxSessionListResult.Sessions)
            assertEquals(listOf("alpha"), (first as HostTmuxSessionListResult.Sessions).rows.map { it.name })
            assertEquals(listOf("beta"), (second as HostTmuxSessionListResult.Sessions).rows.map { it.name })
            assertEquals(1, connector.connectCount)
            assertEquals(HOST.hostname, connector.targets.single().leaseKey.host)
            assertEquals(HOST.port, connector.targets.single().leaseKey.port)
            assertEquals(HOST.username, connector.targets.single().leaseKey.user)
            assertEquals("${HOST.id}:$KEY_PATH", connector.targets.single().leaseKey.credentialId)
            assertEquals("accept-all", connector.targets.single().leaseKey.knownHostsId)
            assertFalse(session.closed)
            // Issue #2160: derived from the production constant, so an
            // invocation change (e.g. adding/removing the locale-proof `-u`)
            // cannot silently turn this behavioural assertion into a false
            // negative. The locale-proof property itself is asserted by
            // `Issue2160HostSessionPickerLocaleProofTest`.
            assertEquals(
                listOf(
                    SshHostTmuxSessionsGateway.LIST_SESSIONS_COMMAND,
                    SshHostTmuxSessionsGateway.LIST_SESSIONS_COMMAND,
                ).map { ReposRemoteSource.pathAwareCommand(it) },
                session.execCommands.filter { it.contains("list-sessions") },
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun coldLeaseMapsCustomRecordedKindThroughEngineRegistry() = runTest {
        val session = FakeSshSession(
            responses = ArrayDeque(
                listOf(
                    ExecResult(
                        stdout = "\$3::sub-agent::100::300::0::custom-codex::/srv/app\n",
                        stderr = "",
                        exitCode = 0,
                    ),
                ),
            ),
        )
        val manager = SshLeaseManager(
            connector = CountingConnector(Result.success(session)),
            scope = this,
            idleTtlMillis = 30_000L,
        )
        val gateway = SshHostTmuxSessionsGateway(
            parser = parser,
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = manager,
            leaseBlockTimeoutMs = 250L,
            liveEnumTimeoutMs = 250L,
            enginesGateway = FamilyGateway("custom-codex"),
        )

        try {
            val result = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

            assertTrue(result is HostTmuxSessionListResult.Sessions)
            val row = (result as HostTmuxSessionListResult.Sessions).rows.single()
            assertEquals("custom-codex", row.recordedKindId)
            assertEquals(SessionAgentKind.Codex, row.recordedKind)
            assertEquals(SessionAgentKind.Codex, row.agentKind)
        } finally {
            manager.close()
        }
    }

    @Test
    fun liveClientTimeoutFallsBackToLeaseEnumeration() = runTest {
        val client = FakeTmuxClient().apply {
            suspendForeverOnCommandPrefix = "list-sessions"
        }
        activeTmuxClients.register(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            client = client,
        )
        val session = FakeSshSession(
            responses = ArrayDeque(
                listOf(
                    ExecResult(stdout = "\$3::lease::100::300::0\n", stderr = "", exitCode = 0),
                ),
            ),
        )
        val connector = CountingConnector(Result.success(session))
        val manager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 30_000L,
        )
        val gateway = SshHostTmuxSessionsGateway(
            parser = parser,
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = manager,
            leaseBlockTimeoutMs = 250L,
            liveEnumTimeoutMs = 250L,
        )

        try {
            val result = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

            assertTrue(result is HostTmuxSessionListResult.Sessions)
            assertEquals(
                listOf("lease"),
                (result as HostTmuxSessionListResult.Sessions).rows.map { it.name },
            )
            assertEquals(1, connector.connectCount)
            assertEquals(1, SshOpenTelemetry.count(SSH_SOURCE_SESSION_PICKER_LIST))
            assertEquals(
                listOf(SshHostTmuxSessionsGateway.LIVE_LIST_SESSIONS_COMMAND),
                client.sentCommands,
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun wedgedLeaseEnumerationSurfacesConnectFailedInsteadOfHanging() = runTest {
        val first = FakeSshSession(blockForever = true)
        val second = FakeSshSession(blockForever = true)
        val connector = SequenceConnector(listOf(first, second))
        val manager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 30_000L,
        )
        val gateway = SshHostTmuxSessionsGateway(
            parser = parser,
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = manager,
            leaseBlockTimeoutMs = 250L,
            liveEnumTimeoutMs = 250L,
        )

        val result = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

        assertTrue(result is HostTmuxSessionListResult.ConnectFailed)
        val cause = (result as HostTmuxSessionListResult.ConnectFailed).cause
        assertTrue(
            "wedged lease enumeration should surface the bounded timeout, got ${cause::class.java.name}",
            cause is LeaseSessionBlockTimeoutException,
        )
        assertEquals(2, connector.connectCount)
        assertTrue(first.closed)
        assertTrue(second.closed)
    }

    @Test
    fun wedgedTmuxListSessionsExecSurfacesTypedTimeoutInsteadOfHanging() = runTest {
        val session = FakeSshSession(blockForever = true)
        val connector = CountingConnector(Result.success(session))
        val manager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = Long.MAX_VALUE,
        )
        val gateway = SshHostTmuxSessionsGateway(
            parser = parser,
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = manager,
            leaseBlockTimeoutMs = 250L,
            liveEnumTimeoutMs = 250L,
            tmuxExecTimeoutMs = 50L,
        )

        try {
            val result = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

            assertTrue(result is HostTmuxSessionListResult.ConnectFailed)
            val cause = (result as HostTmuxSessionListResult.ConnectFailed).cause
            assertTrue(
                "wedged tmux list-sessions must surface its typed timeout, got ${cause::class.java.name}",
                cause is TmuxSessionListExecTimeoutException,
            )
            assertEquals(50L, (cause as TmuxSessionListExecTimeoutException).timeoutMs)
            assertEquals(1, connector.connectCount)
            assertFalse("bounded exec must preserve the shared lease transport", session.closed)
        } finally {
            manager.close()
        }
    }

    @Test
    fun connectFailureMapsToConnectFailed() = runTest {
        val cause = SshException("connection refused")
        val gateway = SshHostTmuxSessionsGateway(
            parser = parser,
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = SshLeaseManager(
                connector = CountingConnector(Result.failure(cause)),
                scope = this,
                idleTtlMillis = 0L,
            ),
        )

        val result = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

        assertTrue(result is HostTmuxSessionListResult.ConnectFailed)
        assertEquals(cause, (result as HostTmuxSessionListResult.ConnectFailed).cause)
    }

    @Test
    fun cancelledPickerListPropagatesCancellationAndReleasesLease() = runTest {
        val session = FakeSshSession(cancelOnExec = true)
        val gateway = SshHostTmuxSessionsGateway(
            parser = parser,
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = SshLeaseManager(
                connector = CountingConnector(Result.success(session)),
                scope = this,
                idleTtlMillis = 0L,
            ),
        )

        val thrown =
            try {
                gateway.listSessions(HOST, KEY_PATH, passphrase = null)
                null
            } catch (e: CancellationException) {
                e
            }

        assertEquals("cancelled during picker list", thrown?.message)
        assertTrue("cancelled picker list should release and close the lease", session.closed)
    }

    private class CountingConnector(
        private val result: Result<SshSession>,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
        val targets: MutableList<SshLeaseTarget> = mutableListOf()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            targets += target
            return result
        }
    }

    private class SequenceConnector(
        private val sessions: List<SshSession>,
    ) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val session = sessions[connectCount.coerceAtMost(sessions.lastIndex)]
            connectCount += 1
            return Result.success(session)
        }
    }

    private class FamilyGateway(private val customId: String) : EnginesGateway {
        override suspend fun listEngines(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ) = error("not used")

        override fun familyForRawId(hostId: Long, rawId: String?): SessionAgentKind? =
            SessionAgentKind.Codex.takeIf { rawId == customId }
    }

    private class FakeSshSession(
        private val responses: ArrayDeque<ExecResult> = ArrayDeque(),
        private val cancelOnExec: Boolean = false,
        private val blockForever: Boolean = false,
    ) : SshSession {
        val execCommands: MutableList<String> = mutableListOf()
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            if (cancelOnExec) {
                throw CancellationException("cancelled during picker list")
            }
            if (blockForever) {
                awaitCancellation()
            }
            execCommands += command
            if (command.contains("pocketshell sessions list")) {
                return ExecResult(stdout = "", stderr = "", exitCode = 1)
            }
            return responses.removeFirstOrNull()
                ?: ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
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
    }
}
