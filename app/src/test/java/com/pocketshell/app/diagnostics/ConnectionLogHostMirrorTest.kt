package com.pocketshell.app.diagnostics

import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Issue #972 (wiring #969 part 3): the host connection-log mirror writer.
 *
 * Reproduce-first: these assert the host file `~/.pocketshell/connection-log.jsonl`
 * is actually WRITTEN — the dead-method gap #972 closes (the writer existed
 * untested-but-unwired; before this writer landed there was NO host file at all).
 * They also pin the fail-soft contract (a mirror must never throw or close the
 * live transport) and the warm-lease reuse (one handshake, not a fresh dial).
 */
class ConnectionLogHostMirrorTest {

    private val jsonl =
        """{"sequence":1,"category":"reconnect","name":"cause_trail","metadata":{"cause":"keepalive_dead"}}""" +
            "\n" +
            """{"sequence":2,"category":"reconnect","name":"cause_trail","metadata":{"stage":"lease_transport"}}"""

    @Test
    fun writesTheTrailToTheHostConnectionLogOverTheWarmLease() = runTest {
        val session = RecordingSshSession()
        val connector = CountingConnector(session)
        val manager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 30_000L)

        val result = ConnectionLogHostMirror.mirror(manager, TARGET, jsonl)

        // The host file was produced (the #972 acceptance: the maintainer can open it).
        assertTrue("mirror must succeed", result.isSuccess)
        assertEquals(ConnectionLogHostMirror.REMOTE_PATH, result.getOrNull())
        assertEquals(".pocketshell/connection-log.jsonl", ConnectionLogHostMirror.REMOTE_PATH)
        // mkdir -p ran for the diagnostics dir.
        assertTrue(
            "must mkdir -p ~/.pocketshell",
            session.execCommands.any { it.contains("mkdir -p") && it.contains(".pocketshell") },
        )
        // The uploaded payload is EXACTLY the reconnect trail JSONL (incl. keepalive_dead).
        assertEquals(jsonl, session.uploadedText())
        assertEquals(ConnectionLogHostMirror.REMOTE_FILENAME, session.uploadedName)
        assertEquals(ConnectionLogHostMirror.REMOTE_PATH, session.uploadedRemotePath)
        // Warm-lease reuse: ONE handshake, and the transport stays open (never closed).
        assertEquals(1, connector.connectCount)
        assertFalse("warm transport must stay open after the mirror", session.closed)
    }

    @Test
    fun blankTrailIsANoOpAndNeverDialsTheHost() = runTest {
        val session = RecordingSshSession()
        val connector = CountingConnector(session)
        val manager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 30_000L)

        val result = ConnectionLogHostMirror.mirror(manager, TARGET, jsonl = "   ")

        assertTrue("blank trail is a no-op success", result.isSuccess)
        assertNull("no remote path for a no-op", result.getOrNull())
        assertEquals("a blank trail must not even dial the host", 0, connector.connectCount)
        assertTrue(session.execCommands.isEmpty())
    }

    @Test
    fun mkdirFailureIsFailSoftAndDoesNotThrowOrCloseTheTransport() = runTest {
        // mkdir denied (e.g. read-only home) — the mirror must FAIL-SOFT.
        val session = RecordingSshSession(
            execResult = { cmd ->
                if (cmd.contains("mkdir")) ExecResult("", "Permission denied", 1) else ExecResult("", "", 0)
            },
        )
        val connector = CountingConnector(session)
        val manager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 30_000L)

        val result = ConnectionLogHostMirror.mirror(manager, TARGET, jsonl)

        assertTrue("a host-write error must be a fail-soft Result.failure", result.isFailure)
        // The upload must NOT have run after the mkdir failed.
        assertNull("no upload after mkdir failure", session.uploadedName)
        // FAIL-SOFT hard contract: the warm transport is NOT closed by a write failure.
        assertFalse("a mirror failure must never close the live transport", session.closed)
    }

    @Test
    fun uploadFailureIsFailSoftAndDoesNotThrowOrCloseTheTransport() = runTest {
        val session = RecordingSshSession(failUpload = true)
        val connector = CountingConnector(session)
        val manager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 30_000L)

        val result = ConnectionLogHostMirror.mirror(manager, TARGET, jsonl)

        assertTrue("an upload error must be a fail-soft Result.failure", result.isFailure)
        assertFalse("a mirror failure must never close the live transport", session.closed)
    }

    private class CountingConnector(private val session: RecordingSshSession) : SshLeaseConnector {
        var connectCount: Int = 0
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    private class RecordingSshSession(
        private val execResult: (String) -> ExecResult = { ExecResult("", "", 0) },
        private val failUpload: Boolean = false,
    ) : SshSession {
        val execCommands: MutableList<String> = mutableListOf()
        private var uploadedBytes: ByteArray? = null
        var uploadedName: String? = null
        var uploadedRemotePath: String? = null
        var closed: Boolean = false

        fun uploadedText(): String? = uploadedBytes?.toString(Charsets.UTF_8)

        override val isConnected: Boolean get() = !closed
        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return execResult(command)
        }
        override fun tail(path: String, onLine: (String) -> Unit) = error("not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(input: InputStream, length: Long, name: String, remotePath: String): String {
            if (failUpload) throw IOException("scp upload failed")
            uploadedBytes = input.readBytes()
            uploadedName = name
            uploadedRemotePath = remotePath
            return remotePath
        }
        override fun close() {
            closed = true
        }
    }

    private companion object {
        val TARGET = LeaseSessionTarget(
            hostId = 7L,
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyPath = "/tmp/pocketshell-test-key",
            passphrase = null,
        )
    }
}
