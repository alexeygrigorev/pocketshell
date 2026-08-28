package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #2377 — the phone's session list severely UNDERCOUNTED the host.
 *
 * Reported 2026-08-28 13:36 on `hetzner`: the folder-list host summary read
 * "1 active · 0 idle · 1 session" while the host itself reported ten:
 *
 * ```
 * $ pocketshell sessions list --json | jq '.sessions | length'
 * 10   # 7 tmux-managed (one per tmuxctl-* socket) + 3 aplexer-managed
 * $ tmuxctl list
 * 1  git-pocketshell   … 7  git-aplexer
 * $ tmux list-sessions        # the DEFAULT socket, the app's whole answer
 * git-pocketshell: 1 windows
 * ```
 *
 * Root cause (a gap #2348's fix never covered, not a revert of it): a live
 * `-CC` control client is attached to exactly ONE tmux server, so its
 * `list-sessions` sees one socket. [SshFolderListGateway.listSessionsWithFolder]
 * used to `return` those single-socket rows verbatim (issue #692's "no watched
 * roots → no lease at all" shortcut) and, when watched roots existed, still
 * never consulted the tmuxctl+aplexer enumerator on that branch. #2348 only
 * ever unioned the enumerator on the NO-live-client branch. The maintainer had
 * just created `git-pocketshell` from the New Session sheet and been attached
 * into it — which is precisely what registers the live client — so the poll
 * that followed rendered the one session on that one socket.
 *
 * Class coverage (G2), all three trigger conditions the issue names:
 *  - cold load with a live client and no watched roots
 *    ([coldLoadWithLiveClientListsEveryHostSessionNotJustTheAttachedSocket]),
 *  - routine reconcile with watched roots configured
 *    ([reconcileWithWatchedRootsAndLiveClientListsEveryHostSession]),
 *  - the poll immediately after a create+attach
 *    ([reconcileRightAfterCreateAttachDoesNotCollapseToTheCreatedSession]).
 *
 * Plus the silent-degradation half (AC3): an enumerator that could not be READ
 * must surface a retryable error, never a confidently-wrong narrower count
 * ([unreadableEnumeratorOnLiveClientPathFailsInsteadOfUndercounting],
 * [unreadableEnumeratorOnNativePathFailsInsteadOfUndercounting]).
 */
class Issue2377SessionListUndercountTest {

    @Test
    fun coldLoadWithLiveClientListsEveryHostSessionNotJustTheAttachedSocket() = runTest {
        val activeTmuxClients = ActiveTmuxClients()
        registerLiveClient(activeTmuxClients, attachedSessionRow())
        val session = HostEnumeratorSession()
        val gateway = gateway(session, activeTmuxClients)

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        assertTrue("expected a session list, got $result", result is FolderListResult.Sessions)
        val names = (result as FolderListResult.Sessions).rows.map { it.sessionName }
        assertEquals(
            "the app's list must match `pocketshell sessions list --json`, not the " +
                "one socket the live -CC client happens to be attached to",
            HOST_CLI_SESSION_NAMES,
            names,
        )
        assertEquals(
            "the reported symptom was a count of 1 against a host reporting 10",
            10,
            names.size,
        )
        assertEquals(
            "the enumerator must be read exactly once per poll",
            1,
            session.execCommands.count { it.contains("sessions list --json") },
        )
    }

    @Test
    fun liveClientRowsStillContributeCwdAndWindowsForTheAttachedSocket() = runTest {
        val activeTmuxClients = ActiveTmuxClients()
        registerLiveClient(activeTmuxClients, attachedSessionRow())
        val session = HostEnumeratorSession()
        val gateway = gateway(session, activeTmuxClients)

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        val rows = (result as FolderListResult.Sessions).rows
        val attached = rows.single { it.sessionName == ATTACHED_SESSION }
        assertEquals(
            "the live client stays the metadata overlay for its own socket",
            "/home/alexey/git/pocketshell",
            attached.cwd,
        )
        assertEquals(listOf("editor"), attached.windows.map { it.name })
        // …and the aplexer manager/id survives the union so the row is not
        // silently re-typed as a tmux session.
        val aplexer = rows.single { it.sessionName == "aplexer-follow:yolo" }
        assertEquals("aplexer", aplexer.sessionManager)
        assertEquals("b3feff71-4a78-4055-a2d3-6c99187ecffb", aplexer.aplexerId)
        assertEquals(
            "tmux",
            rows.single { it.sessionName == "git-ai-shipping-labs" }.sessionManager,
        )
    }

    @Test
    fun reconcileWithWatchedRootsAndLiveClientListsEveryHostSession() = runTest {
        val activeTmuxClients = ActiveTmuxClients()
        registerLiveClient(activeTmuxClients, attachedSessionRow())
        val session = HostEnumeratorSession()
        val gateway = gateway(session, activeTmuxClients)

        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = listOf(
                ProjectRootEntity(id = 1L, hostId = HOST.id, label = "git", path = "~/git"),
            ),
        )

        assertTrue("expected a session list, got $result", result is FolderListResult.Sessions)
        assertEquals(
            HOST_CLI_SESSION_NAMES,
            (result as FolderListResult.Sessions).rows.map { it.sessionName },
        )
    }

    @Test
    fun reconcileRightAfterCreateAttachDoesNotCollapseToTheCreatedSession() = runTest {
        // The observed trigger: "New session" created `git-pocketshell`, the app
        // attached into it, and the very next poll ran with a live -CC client
        // bound to that brand-new server. The host CLI already reports the new
        // session too, so a correct union is still the full ten.
        val activeTmuxClients = ActiveTmuxClients()
        registerLiveClient(activeTmuxClients, attachedSessionRow())
        val session = HostEnumeratorSession()
        val gateway = gateway(session, activeTmuxClients)

        val names = (1..2).map { poll ->
            val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)
            assertTrue("poll $poll expected a session list, got $result", result is FolderListResult.Sessions)
            (result as FolderListResult.Sessions).rows.map { it.sessionName }
        }

        assertEquals(
            "the post-create poll must not collapse the list to the just-created session",
            listOf(HOST_CLI_SESSION_NAMES, HOST_CLI_SESSION_NAMES),
            names,
        )
        assertTrue(
            "the just-created session must still be present exactly once",
            names.all { it.count { name -> name == ATTACHED_SESSION } == 1 },
        )
    }

    @Test
    fun nativeTmuxPathWithoutLiveClientKeepsUnioningTheHostEnumerator() = runTest {
        // #2348's guarantee, re-pinned here so a future live-client change can't
        // regress the branch it did cover.
        val session = HostEnumeratorSession()
        val gateway = gateway(session, ActiveTmuxClients())

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        assertEquals(
            HOST_CLI_SESSION_NAMES,
            (result as FolderListResult.Sessions).rows.map { it.sessionName },
        )
    }

    @Test
    fun unreadableEnumeratorOnLiveClientPathFailsInsteadOfUndercounting() = runTest {
        val activeTmuxClients = ActiveTmuxClients()
        registerLiveClient(activeTmuxClients, attachedSessionRow())
        val session = HostEnumeratorSession(enumeratorThrows = true)
        val gateway = gateway(session, activeTmuxClients)

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        assertTrue(
            "an unreadable enumerator must surface an error, not publish the " +
                "single attached socket as the whole host; got $result",
            result is FolderListResult.Failed,
        )
        assertEquals(
            SshFolderListGateway.ENUMERATOR_UNAVAILABLE_MESSAGE,
            (result as FolderListResult.Failed).message,
        )
    }

    @Test
    fun unreadableEnumeratorOnNativePathFailsInsteadOfUndercounting() = runTest {
        val session = HostEnumeratorSession(enumeratorThrows = true)
        val gateway = gateway(session, ActiveTmuxClients())

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        assertTrue(
            "an unreadable enumerator must surface an error, not publish the " +
                "default-socket rows as the whole host; got $result",
            result is FolderListResult.Failed,
        )
        assertEquals(
            SshFolderListGateway.ENUMERATOR_UNAVAILABLE_MESSAGE,
            (result as FolderListResult.Failed).message,
        )
    }

    @Test
    fun authoritativeEmptyEnumeratorIsNotTreatedAsUnreadable() = runTest {
        // The counterpart guard: a host that genuinely reports zero rows must
        // still render (the default-socket overlay), not an error panel.
        val session = HostEnumeratorSession(enumeratorJson = """{"sessions":[]}""")
        val gateway = gateway(session, ActiveTmuxClients())

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        assertTrue("expected a session list, got $result", result is FolderListResult.Sessions)
        assertEquals(
            listOf(DEFAULT_SOCKET_SESSION),
            (result as FolderListResult.Sessions).rows.map { it.sessionName },
        )
    }

    private fun gateway(
        session: SshSession,
        activeTmuxClients: ActiveTmuxClients,
    ): SshFolderListGateway =
        SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector { Result.success(session) },
            ),
            sessionListParser = HostTmuxSessionListParser(),
            execReadTimeoutMs = SshFolderListGateway.EXEC_READ_TIMEOUT_MS,
            enginesGateway = null,
        )

    /**
     * A live `-CC` control client attached to ONE tmux server, replaying the
     * chained `list-sessions` + `list-panes` batch for that server only. Every
     * `listSessionsWithFolder` call sends the batch again, so the responses are
     * refilled on demand.
     */
    private fun registerLiveClient(
        activeTmuxClients: ActiveTmuxClients,
        sessionLine: String,
    ) {
        val client = FakeTmuxClient()
        repeat(REPLAY_POLLS) {
            client.responses += CommandResponse(number = 1L, output = listOf(sessionLine), isError = false)
            client.responses += CommandResponse(
                number = 2L,
                output = listOf(
                    "$ATTACHED_SESSION${SEP}0${SEP}editor${SEP}1${SEP}1$SEP" +
                        "/home/alexey/git/pocketshell${SEP}/dev/pts/4${SEP}nvim${SEP}@0${SEP}4242",
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

    private fun attachedSessionRow(): String =
        "$ATTACHED_SESSION$SEP\$1${SEP}1787900039${SEP}1787900039${SEP}1$SEP$SEP$SEP$SEP$SEP" +
            "/home/alexey/git/pocketshell"

    /**
     * The maintainer's host as captured in the issue: the DEFAULT socket holds
     * exactly the session the app just created, while `pocketshell sessions
     * list --json` reports all ten across the `tmuxctl-*` sockets plus aplexer.
     */
    private class HostEnumeratorSession(
        private val enumeratorThrows: Boolean = false,
        private val enumeratorJson: String = HOST_CLI_JSON,
    ) : SshSession {
        val execCommands: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            synchronized(execCommands) { execCommands += command }
            return when {
                command.contains("sessions list --json") -> {
                    if (enumeratorThrows) {
                        throw FolderListExecTimeoutException(
                            command,
                            SshFolderListGateway.EXEC_READ_TIMEOUT_MS,
                        )
                    }
                    ExecResult(enumeratorJson, "", 0)
                }
                command.contains("sessions list --by") ->
                    error("the JSON enumerator owns this hop (#2348); a second human exec is the regression")
                command.contains(SshFolderListGateway.ENUMERATION_MARKER) ->
                    ExecResult(DEFAULT_SOCKET_ENUMERATION, "", 0)
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

        /**
         * The default socket holds ONLY the just-created session — this is the
         * bare `tmux list-sessions` answer that used to reach the user as "1
         * session" (and, on the live-client path, the one-socket `-CC` answer).
         */
        const val DEFAULT_SOCKET_SESSION: String = "git-pocketshell"

        val HOST: HostEntity = HostEntity(
            id = 42L,
            name = "hetzner",
            hostname = "10.0.2.2",
            port = 2222,
            username = "alexey",
            keyId = 7L,
        )
        val SEP: String = SshFolderListGateway.FIELD_SEP
        val MARKER: String = SshFolderListGateway.ENUMERATION_MARKER

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
         * The sectioned landing probe: `tmux list-sessions` then `list-panes`,
         * both against the DEFAULT socket, which on the reported host holds only
         * the session the app just created.
         */
        val DEFAULT_SOCKET_ENUMERATION: String =
            "$DEFAULT_SOCKET_SESSION${SEP}\$1${SEP}1787900039${SEP}1787900039${SEP}1" +
                "$SEP$SEP$SEP$SEP$SEP/home/alexey/git/pocketshell\n" +
                "$MARKER 0\n" +
                "$DEFAULT_SOCKET_SESSION${SEP}0${SEP}editor${SEP}1${SEP}1$SEP" +
                "/home/alexey/git/pocketshell${SEP}/dev/pts/4${SEP}nvim${SEP}@0${SEP}4242\n" +
                "$MARKER 0\n"
    }
}
