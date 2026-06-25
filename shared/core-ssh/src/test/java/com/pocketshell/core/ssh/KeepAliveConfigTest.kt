package com.pocketshell.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Docker-free guard for the issue #847 / #766-slice-1 keep-alive REMOVAL — the
 * always-runnable JVM backstop for "no background transport writer".
 *
 * Issue #548 had switched the provider to `KeepAliveProvider.KEEP_ALIVE` so a
 * `sshj-KeepAliveRunner` thread sent `keepalive@openssh.com` every 15s. That
 * background thread is a SECOND writer on the live transport; its periodic
 * global-request could land in a KEX/rekey window and desync the encoder
 * sequence counter, so the server logged `ssh_dispatch_run_fatal: ...
 * Connection corrupted` ~one keepalive interval after the handshake (the real
 * cause of the v0.4.10/v0.4.11 "loading tree" connect hang). The
 * single-transport-writer rule ([TransportDispatcher]) cannot tolerate an
 * un-ownable background writer, so the keepalive is removed (D22 hard-cut).
 *
 * These tests do not open a socket — they only inspect how [SshConnection]
 * configures the sshj client, which is exactly where the corrupting writer was
 * wired in. They INVERT the old #548 assertions: a freshly built client must
 * NOT be enabling a keepalive writer.
 */
class KeepAliveConfigTest {

    @Test
    fun `client has no keepalive interval so no background writer thread is started`() {
        // `SSHClient.onConnect()` only `start()`s the keepalive thread when
        // `KeepAlive.isEnabled()` (== `keepAliveInterval > 0`). With the #847
        // removal nothing sets an interval, so it stays 0 and no
        // `sshj-KeepAliveRunner` background writer is ever spawned.
        val client = SshConnection.createClient()
        client.use {
            assertEquals(
                "no keepalive interval should be configured (#847: the racing " +
                    "background writer is removed); got " +
                    it.connection.keepAlive.keepAliveInterval,
                0,
                it.connection.keepAlive.keepAliveInterval,
            )
        }
    }

    @Test
    fun `clearLiveChannelReadTimeout scopes the socket read timeout to connect-only (927)`() {
        // Issue #927: `SSHClient.timeout` maps to `Socket.setSoTimeout`, i.e. the
        // BLOCKING-READ timeout the sshj Reader arms on every `read`. We set it to
        // the 30s connect timeout for the bounded connect+auth phase, then clear
        // it once the transport is live so the long-lived `-CC` control channel is
        // not governed by a connect-phase read deadline (dead-peer detection is the
        // foreground `LivenessProbe`'s job, not a socket read deadline).
        //
        // Inspects sshj client config only (no socket opened) — the same Docker-free
        // shape as the keep-alive guards above.
        val client = SshConnection.createClient()
        client.use {
            // Simulate the connect-phase posture: a non-zero read timeout armed.
            it.timeout = SshConnection.DEFAULT_TIMEOUT_MS
            assertEquals(
                "connect-phase read timeout should be the connect timeout",
                SshConnection.DEFAULT_TIMEOUT_MS,
                it.timeout,
            )

            // Post-auth scoping: clear it for the live channel.
            SshConnection.clearLiveChannelReadTimeout(it)
            assertEquals(
                "the live `-CC` channel must have an infinite (0) socket read " +
                    "timeout so a normal idle gap on an alive link never arms a " +
                    "SocketTimeoutException on the live reader (#927)",
                0,
                it.timeout,
            )
        }
    }

    @Test
    fun `no live sshj-KeepAliveRunner thread exists after building the client`() {
        // The corruption-source is a LIVE `sshj-KeepAliveRunner-*` thread.
        // Building the client must not bring one to life (the old #548 config
        // set the provider so the thread type was a KeepAliveRunner; the #847
        // removal means no keepalive thread runs at all).
        val client = SshConnection.createClient()
        client.use {
            val keepAliveThreadAlive = Thread.getAllStackTraces().keys.any { t ->
                t.isAlive && t.name.contains("KeepAlive")
            }
            assertFalse(
                "no sshj-KeepAliveRunner background writer thread should be live " +
                    "after building the client (#847)",
                keepAliveThreadAlive,
            )
        }
    }
}
