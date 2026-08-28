package com.pocketshell.app.sessions

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #2377 — the SESSION PICKER half of the undercount.
 *
 * The folder list and the session picker are two independent surfaces reading
 * two independent gateways, and BOTH had the same defect:
 * [SshHostTmuxSessionsGateway.listSessions] opened with
 *
 * ```kotlin
 * listSessionsFromLiveClient(host, keyPath)?.let { return it }   // one socket, whole answer
 * ```
 *
 * A `tmux -CC` control client is attached to exactly ONE tmux server, so on the
 * maintainer's host — 7 tmuxctl-managed sessions, one `tmuxctl-*` socket each,
 * plus 3 aplexer-managed — the picker published the single session on whichever
 * socket the app happened to be attached to. The trigger is the same as the
 * folder list's: being inside a session is what registers the live client, so
 * the defect is present in exactly the reported state. The lease branch right
 * below it already unioned the tmuxctl+aplexer enumerator, which is itself the
 * proof that the union is required for this surface.
 *
 * Class coverage (G2) — every branch that can publish a session list:
 *  - warm live `-CC` client, gateway level
 *    ([pickerWithLiveClientListsEveryHostSessionNotJustTheAttachedSocket]),
 *  - warm live `-CC` client, through the production view model to the rendered
 *    rows ([pickerViewModelRendersEveryHostSessionWithALiveClientAttached]),
 *  - cold, no live client ([coldPickerWithoutLiveClientKeepsUnioningTheEnumerator]),
 *  - the default socket reporting `no server running` while tmuxctl/aplexer
 *    sessions exist ([noServerOnTheDefaultSocketStillListsTmuxctlAndAplexerSessions]).
 *
 * Plus the silent-degradation half: an enumerator that could not be READ must
 * surface a retryable error, never a confidently-wrong narrower count
 * ([unreadableEnumeratorOnLivePathFailsInsteadOfUndercounting],
 * [unreadableEnumeratorOnColdPathFailsInsteadOfUndercounting]) — with both
 * over-correction guards: an authoritatively EMPTY enumerator still renders
 * ([authoritativeEmptyEnumeratorIsNotTreatedAsUnreadable]) and a host with no
 * working `pocketshell` CLI at all still lists its default socket
 * ([oldHostWithoutPocketshellCliStillListsItsDefaultSocketSessions]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue2377SessionPickerUndercountTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val parser = HostTmuxSessionListParser()

    @Test
    fun pickerWithLiveClientListsEveryHostSessionNotJustTheAttachedSocket() = runTest {
        val activeTmuxClients = ActiveTmuxClients()
        registerLiveClient(activeTmuxClients)
        val session = HostEnumeratorSession()
        val manager = leaseManager(session)

        try {
            val result = gateway(activeTmuxClients, manager)
                .listSessions(HOST, KEY_PATH, passphrase = null)

            assertTrue("expected a session list, got $result", result is HostTmuxSessionListResult.Sessions)
            val names = (result as HostTmuxSessionListResult.Sessions).rows.map { it.name }
            assertEquals(
                "the picker must match `pocketshell sessions list --json`, not the one " +
                    "socket the live -CC client happens to be attached to",
                HOST_CLI_SESSION_NAMES,
                names,
            )
            assertEquals(
                "the reported symptom was a count of 1 against a host reporting 10",
                10,
                names.size,
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun pickerViewModelRendersEveryHostSessionWithALiveClientAttached() = runTest {
        // The user-visible half: HostTmuxSessionPickerState.Ready.rows is what
        // the session sheet lists.
        val activeTmuxClients = ActiveTmuxClients()
        registerLiveClient(activeTmuxClients)
        val session = HostEnumeratorSession()
        val manager = leaseManager(session)

        try {
            val vm = HostTmuxSessionPickerViewModel(gateway(activeTmuxClients, manager))
            vm.load(
                HostTmuxSessionPickerRequest(host = HOST, keyPath = KEY_PATH, passphrase = null),
            )

            val settled = vm.state.first {
                it is HostTmuxSessionPickerState.Ready ||
                    it is HostTmuxSessionPickerState.Fallback ||
                    it is HostTmuxSessionPickerState.ConnectError
            }
            assertTrue("picker must reach Ready, got $settled", settled is HostTmuxSessionPickerState.Ready)
            val names = (settled as HostTmuxSessionPickerState.Ready).rows.map { it.name }
            assertEquals(
                "the rendered picker rows must cover every session the host CLI reports; " +
                    "missing=${HOST_CLI_SESSION_NAMES - names.toSet()}",
                HOST_CLI_SESSION_NAMES.toSet(),
                names.toSet(),
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun liveClientRowsStillContributeIdAndPathForTheAttachedSocket() = runTest {
        val activeTmuxClients = ActiveTmuxClients()
        registerLiveClient(activeTmuxClients)
        val session = HostEnumeratorSession()
        val manager = leaseManager(session)

        try {
            val rows = sessionsOf(
                gateway(activeTmuxClients, manager).listSessions(HOST, KEY_PATH, passphrase = null),
            )

            val attached = rows.single { it.name == ATTACHED_SESSION }
            assertEquals(
                "the live client stays the metadata overlay for its own socket",
                "\$1",
                attached.tmuxSessionId,
            )
            assertEquals("/home/alexey/git/pocketshell", attached.path)
            assertTrue("the attached session must still read as attached", attached.attached)
            // …and the aplexer manager/id survives the union so the row is not
            // silently re-typed as a plain tmux session.
            val aplexer = rows.single { it.name == "aplexer-follow:yolo" }
            assertEquals("aplexer", aplexer.manager)
            assertEquals("b3feff71-4a78-4055-a2d3-6c99187ecffb", aplexer.aplexerId)
            assertEquals("tmux", rows.single { it.name == "git-ai-shipping-labs" }.manager)
        } finally {
            manager.close()
        }
    }

    @Test
    fun warmPickerDoesNotReRunListSessionsOverTheLease() = runTest {
        // #692's real guarantee on this surface: the warm branch must still do
        // strictly LESS lease work than the cold one. It pays exactly one
        // enumerator exec and never re-reads `tmux list-sessions` the live
        // client already answered.
        val activeTmuxClients = ActiveTmuxClients()
        registerLiveClient(activeTmuxClients)
        val session = HostEnumeratorSession()
        val manager = leaseManager(session)

        try {
            gateway(activeTmuxClients, manager).listSessions(HOST, KEY_PATH, passphrase = null)

            assertEquals(
                "the enumerator must be read exactly once per picker load",
                1,
                session.execCommands.count { it.contains("sessions list --json") },
            )
            assertEquals(
                "the live client already answered list-sessions for its socket",
                0,
                session.execCommands.count { it.contains("list-sessions") },
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun coldPickerWithoutLiveClientKeepsUnioningTheEnumerator() = runTest {
        // The branch that was already correct, re-pinned so a future live-client
        // change cannot regress it either.
        val session = HostEnumeratorSession()
        val manager = leaseManager(session)

        try {
            val rows = sessionsOf(
                gateway(ActiveTmuxClients(), manager).listSessions(HOST, KEY_PATH, passphrase = null),
            )

            assertEquals(HOST_CLI_SESSION_NAMES, rows.map { it.name })
        } finally {
            manager.close()
        }
    }

    @Test
    fun noServerOnTheDefaultSocketStillListsTmuxctlAndAplexerSessions() = runTest {
        // A tmuxctl host can legitimately have NOTHING on the default socket
        // while running ten sessions on `tmuxctl-*` ones. "no server running"
        // there used to be published as "No tmux sessions found."
        val session = HostEnumeratorSession(defaultSocketHasNoServer = true)
        val manager = leaseManager(session)

        try {
            val rows = sessionsOf(
                gateway(ActiveTmuxClients(), manager).listSessions(HOST, KEY_PATH, passphrase = null),
            )

            assertEquals(HOST_CLI_SESSION_NAMES, rows.map { it.name })
        } finally {
            manager.close()
        }
    }

    @Test
    fun unreadableEnumeratorOnLivePathFailsInsteadOfUndercounting() = runTest {
        val activeTmuxClients = ActiveTmuxClients()
        registerLiveClient(activeTmuxClients)
        val session = HostEnumeratorSession(enumeratorThrows = true)
        val manager = leaseManager(session)

        try {
            val result = gateway(activeTmuxClients, manager)
                .listSessions(HOST, KEY_PATH, passphrase = null)

            assertTrue(
                "an unreadable enumerator must surface an error, not publish the single " +
                    "attached socket as the whole host; got $result",
                result is HostTmuxSessionListResult.Failed,
            )
            assertEquals(
                SshHostTmuxSessionsGateway.ENUMERATOR_UNAVAILABLE_MESSAGE,
                (result as HostTmuxSessionListResult.Failed).message,
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun unreadableEnumeratorOnColdPathFailsInsteadOfUndercounting() = runTest {
        val session = HostEnumeratorSession(enumeratorThrows = true)
        val manager = leaseManager(session)

        try {
            val result = gateway(ActiveTmuxClients(), manager)
                .listSessions(HOST, KEY_PATH, passphrase = null)

            assertTrue(
                "an unreadable enumerator must surface an error, not publish the " +
                    "default-socket rows as the whole host; got $result",
                result is HostTmuxSessionListResult.Failed,
            )
            assertEquals(
                SshHostTmuxSessionsGateway.ENUMERATOR_UNAVAILABLE_MESSAGE,
                (result as HostTmuxSessionListResult.Failed).message,
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun authoritativeEmptyEnumeratorIsNotTreatedAsUnreadable() = runTest {
        // The counterpart guard: a host that genuinely reports zero enumerator
        // rows must still render its default-socket sessions, not an error.
        val session = HostEnumeratorSession(enumeratorJson = """{"sessions":[]}""")
        val manager = leaseManager(session)

        try {
            val rows = sessionsOf(
                gateway(ActiveTmuxClients(), manager).listSessions(HOST, KEY_PATH, passphrase = null),
            )

            assertEquals(listOf(ATTACHED_SESSION), rows.map { it.name })
        } finally {
            manager.close()
        }
    }

    @Test
    fun oldHostWithoutPocketshellCliStillListsItsDefaultSocketSessions() = runTest {
        // Fetch.Failed (the binary is missing / errors on both shapes) is NOT
        // Unavailable: such a host has no tmuxctl sockets and no aplexer either,
        // so the default socket really is the whole truth. Refusing to render
        // here would be the over-correction.
        val session = HostEnumeratorSession(enumeratorMissing = true)
        val manager = leaseManager(session)

        try {
            val rows = sessionsOf(
                gateway(ActiveTmuxClients(), manager).listSessions(HOST, KEY_PATH, passphrase = null),
            )

            assertEquals(listOf(ATTACHED_SESSION), rows.map { it.name })
        } finally {
            manager.close()
        }
    }

    private fun sessionsOf(result: HostTmuxSessionListResult): List<HostTmuxSessionRow> {
        assertTrue("expected a session list, got $result", result is HostTmuxSessionListResult.Sessions)
        return (result as HostTmuxSessionListResult.Sessions).rows
    }

    private fun leaseManager(session: SshSession): SshLeaseManager =
        SshLeaseManager(connector = SshLeaseConnector { Result.success(session) })

    private fun gateway(
        activeTmuxClients: ActiveTmuxClients,
        manager: SshLeaseManager,
    ): SshHostTmuxSessionsGateway =
        SshHostTmuxSessionsGateway(
            parser = parser,
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = manager,
        )

    /**
     * A live `-CC` control client attached to ONE tmux server, answering
     * `list-sessions` for that server only — the reported state, where the app
     * had just created a session and been attached into it.
     */
    private fun registerLiveClient(activeTmuxClients: ActiveTmuxClients) {
        val client = FakeTmuxClient()
        repeat(REPLAY_POLLS) {
            client.responses += CommandResponse(
                number = 1L,
                output = listOf(
                    "\$1::$ATTACHED_SESSION::1787900039::1787900039::1::" +
                        "::/home/alexey/git/pocketshell",
                ),
                isError = false,
            )
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
    }

    /**
     * The maintainer's host as captured in the issue: the DEFAULT socket holds
     * exactly the session the app just created, while `pocketshell sessions
     * list --json` reports all ten across the `tmuxctl-*` sockets plus aplexer.
     */
    private class HostEnumeratorSession(
        private val enumeratorThrows: Boolean = false,
        private val enumeratorMissing: Boolean = false,
        private val defaultSocketHasNoServer: Boolean = false,
        private val enumeratorJson: String = HOST_CLI_JSON,
    ) : SshSession {
        val execCommands: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            synchronized(execCommands) { execCommands += command }
            return when {
                command.contains("sessions list --json") -> when {
                    enumeratorThrows ->
                        throw TmuxSessionListExecTimeoutException(command, 3_000L)
                    enumeratorMissing ->
                        ExecResult("", "pocketshell: not found", 127)
                    else -> ExecResult(enumeratorJson, "", 0)
                }
                command.contains("sessions list --by") -> when {
                    enumeratorMissing -> ExecResult("", "pocketshell: not found", 127)
                    else -> error(
                        "the JSON enumerator owns this hop (#2348); a second human exec is the regression",
                    )
                }
                command.contains("list-sessions") ->
                    if (defaultSocketHasNoServer) {
                        ExecResult("", "no server running on /tmp/tmux-1000/default", 1)
                    } else {
                        ExecResult(DEFAULT_SOCKET_LIST_SESSIONS, "", 0)
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

    private companion object {
        const val KEY_PATH: String = "/tmp/pocketshell-test-key"
        const val REPLAY_POLLS: Int = 4
        const val ATTACHED_SESSION: String = "git-pocketshell"

        val HOST: HostEntity = HostEntity(
            id = 42L,
            name = "hetzner",
            hostname = "10.0.2.2",
            port = 2222,
            username = "alexey",
            keyId = 7L,
        )

        /** 7 tmuxctl-managed (one socket each) + 3 aplexer-managed = 10. */
        val HOST_CLI_SESSION_NAMES: List<String> = listOf(
            "git-pocketshell",
            "git-ai-dev-tools-zoomcamp",
            "git-pocketshell-release",
            "git-zcode-acp",
            "git-ai-shipping-labs",
            "git-game-tester",
            "git-aplexer",
            "aplexer-follow:yolo",
            "aplexer-follow:beta",
            "aplexer-follow:gamma",
        )

        val HOST_CLI_JSON: String = buildString {
            append("""{"managers":["tmux","aplexer"],"sessions":[""")
            append(
                HOST_CLI_SESSION_NAMES.take(7).joinToString(",") { name ->
                    """{"name":"$name","manager":"tmux","created":"2026-08-28 13:33:59"}"""
                },
            )
            append(",")
            append(
                """{"name":"aplexer-follow:yolo","manager":"aplexer",""" +
                    """"id":"b3feff71-4a78-4055-a2d3-6c99187ecffb",""" +
                    """"workspace":"/tmp/aplexer-follow"},""",
            )
            append(
                """{"name":"aplexer-follow:beta","manager":"aplexer",""" +
                    """"id":"1d2e3f40-0000-4000-8000-000000000002"},""",
            )
            append(
                """{"name":"aplexer-follow:gamma","manager":"aplexer",""" +
                    """"id":"1d2e3f40-0000-4000-8000-000000000003"}""",
            )
            append("]}")
        }

        /**
         * The bare `tmux list-sessions` answer for the DEFAULT socket, which on
         * the reported host holds only the session the app just created — the
         * one row that used to reach the user as the whole list.
         */
        val DEFAULT_SOCKET_LIST_SESSIONS: String =
            "\$1::$ATTACHED_SESSION::1787900039::1787900039::1::::/home/alexey/git/pocketshell\n"
    }
}
