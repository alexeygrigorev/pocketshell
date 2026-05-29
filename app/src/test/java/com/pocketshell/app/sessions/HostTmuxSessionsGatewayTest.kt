package com.pocketshell.app.sessions

import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostTmuxSessionsGatewayTest {
    private val parser = HostTmuxSessionListParser()
    private val activeTmuxClients = ActiveTmuxClients()

    @Before
    fun resetTelemetry() {
        SshOpenTelemetry.resetForTest()
    }

    @Test
    fun sameHostLiveClientListsSessionsWithoutOpeningSsh() = runTest {
        val client = FakeTmuxClient()
        client.responses += CommandResponse(
            number = 1L,
            output = listOf(
                "beta::101::301::1",
                "alpha::100::300::0",
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
        val gateway = SshHostTmuxSessionsGateway(parser, activeTmuxClients)

        val result = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

        assertTrue(result is HostTmuxSessionListResult.Sessions)
        val rows = (result as HostTmuxSessionListResult.Sessions).rows
        assertEquals(listOf("beta", "alpha"), rows.map { it.name })
        assertEquals(0, SshOpenTelemetry.count(SSH_SOURCE_SESSION_PICKER_LIST))
        assertEquals(
            listOf("list-sessions -F '#{session_name}::#{session_created}::#{session_activity}::#{session_attached}'"),
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
