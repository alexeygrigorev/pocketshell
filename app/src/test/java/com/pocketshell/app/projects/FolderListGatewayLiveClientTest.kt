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
                "git-cable-world::100::300::1::/home/testuser/git/cable-world",
                "git-cable-world-map::101::301::0::/home/testuser",
            ),
            isError = false,
        )
        client.responses += CommandResponse(
            number = 2L,
            output = listOf(
                "git-cable-world::0::shell::0::1::/home/testuser/git/cable-world::/dev/pts/1::sh",
                "git-cable-world::1::claude::1::1::/home/testuser/git/cable-world/app::/dev/pts/2::claude",
                "git-cable-world-map::0::map::1::1::/tmp/cable-world-map::/dev/pts/3::bash",
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
        assertEquals(listOf("git-cable-world", "git-cable-world-map"), rows.map { it.sessionName })
        assertEquals(listOf("/home/testuser/git/cable-world/app", "/tmp/cable-world-map"), rows.map { it.cwd })
        assertEquals(listOf(SessionAgentKind.Shell, SessionAgentKind.Shell), rows.map { it.agentKind })
        assertEquals(listOf(0, 1), rows[0].windows.map { it.index })
        assertEquals(listOf("shell", "claude"), rows[0].windows.map { it.name })
        assertEquals(listOf(false, true), rows[0].windows.map { it.active })
        assertEquals(listOf("/home/testuser/git/cable-world", "/home/testuser/git/cable-world/app"), rows[0].windows.map { it.cwd })
        assertEquals(0, SshOpenTelemetry.count(SSH_SOURCE_FOLDER_LIST_PROBE))
        assertEquals(
            listOf(
                SshFolderListGateway.CONTROL_LIST_SESSIONS_COMMAND,
                SshFolderListGateway.CONTROL_LIST_PANES_COMMAND,
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
