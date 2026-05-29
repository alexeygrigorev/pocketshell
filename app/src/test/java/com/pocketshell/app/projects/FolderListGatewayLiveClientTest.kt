package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.SSH_SOURCE_FOLDER_LIST_PROBE
import com.pocketshell.app.sessions.SshOpenTelemetry
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FolderListGatewayLiveClientTest {
    private val activeTmuxClients = ActiveTmuxClients()

    @Before
    fun resetTelemetry() {
        SshOpenTelemetry.resetForTest()
    }

    @Test
    fun sameHostLiveClientListsFolderRowsWithoutOpeningSsh() = runTest {
        val client = FakeTmuxClient()
        client.responses += CommandResponse(
            number = 1L,
            output = listOf(
                "issue278-a::100::300::1::/home/testuser/git/pocketshell",
                "issue278-b::101::301::0::/home/testuser",
            ),
            isError = false,
        )
        client.responses += CommandResponse(
            number = 2L,
            output = listOf(
                "issue278-a::1::/home/testuser/git/pocketshell::/dev/pts/1::sh",
                "issue278-b::1::/tmp/issue278::/dev/pts/2::bash",
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
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = activeTmuxClients,
        )

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        val rows = (result as FolderListResult.Sessions).rows
        assertEquals(listOf("issue278-a", "issue278-b"), rows.map { it.sessionName })
        assertEquals(listOf("/home/testuser/git/pocketshell", "/tmp/issue278"), rows.map { it.cwd })
        assertEquals(listOf(SessionAgentKind.Shell, SessionAgentKind.Shell), rows.map { it.agentKind })
        assertEquals(0, SshOpenTelemetry.count(SSH_SOURCE_FOLDER_LIST_PROBE))
        assertEquals(
            listOf(
                "list-sessions -F '#{session_name}::#{session_created}::#{session_activity}::#{session_attached}::#{session_path}'",
                "list-panes -a -F '#{session_name}::#{pane_active}::#{pane_current_path}::#{pane_tty}::#{pane_current_command}'",
            ),
            client.sentCommands,
        )
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
