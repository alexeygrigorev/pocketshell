package com.pocketshell.core.ssh

import net.schmizz.keepalive.KeepAliveRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Docker-free guards for the issue #548 keep-alive fix.
 *
 * These do not open a socket — they only inspect how [SshConnection]
 * configures the sshj client, which is exactly where the ordering/provider
 * bug lived.
 */
class KeepAliveConfigTest {

    @Test
    fun `client uses the KEEP_ALIVE provider so dead peers are detected`() {
        // SshConnection.createSshConfig() must set
        // KeepAliveProvider.KEEP_ALIVE on the DefaultConfig BEFORE the
        // SSHClient is constructed. The constructor builds the
        // ConnectionImpl (and its KeepAlive) from that provider, so the
        // running keep-alive type is fixed at construction. If the provider
        // were still the DefaultConfig default (HEARTBEAT), this would be a
        // Heartbeater, which writes SSH_MSG_IGNORE but never detects a dead
        // peer.
        val client = SshConnection.createClient()
        client.use {
            assertTrue(
                "expected KeepAliveRunner (KEEP_ALIVE provider) so missed " +
                    "keepalives are counted and CONNECTION_LOST is raised; got " +
                    it.connection.keepAlive.javaClass.name,
                it.connection.keepAlive is KeepAliveRunner,
            )
        }
    }

    @Test
    fun `keep-alive interval starts at zero until connect-time wiring enables it`() {
        // The KeepAlive instance is created disabled (interval 0). The
        // connect() path sets the interval BEFORE client.connect() so sshj's
        // onConnect() sees isEnabled() == true and starts the thread. This
        // test documents the precondition: a freshly built client is NOT yet
        // sending keepalives, which is why setting the interval at the right
        // moment matters.
        val client = SshConnection.createClient()
        client.use {
            assertEquals(
                "a freshly built client has keep-alive disabled until connect() wires it",
                0,
                it.connection.keepAlive.keepAliveInterval,
            )
        }
    }

    @Test
    fun `tolerance window matches the 60s background grace`() {
        // Interval x maxAliveCount is the ride-through budget: how long a
        // transient outage can last before sshj declares CONNECTION_LOST.
        // Keep it aligned with the 60s background-grace window (#450).
        val toleranceSeconds =
            SshConnection.DEFAULT_KEEP_ALIVE_SECONDS * SshConnection.DEFAULT_MAX_ALIVE_COUNT
        assertEquals(
            "interval x maxAliveCount should give a 60s ride-through window",
            60,
            toleranceSeconds,
        )
    }
}
