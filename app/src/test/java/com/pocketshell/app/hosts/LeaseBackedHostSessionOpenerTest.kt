package com.pocketshell.app.hosts

import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Class coverage for issue #1085 freeze cause F1 (secondary): the
 * session-open key-file `File.exists()` disk stat was moved off the Main
 * thread into `withContext(Dispatchers.IO)`. These tests prove that move did
 * not change the early-return contract:
 *
 * - a MISSING key file still short-circuits to `null` WITHOUT dialing, and
 * - an EXISTING key file still proceeds past the gate to dial the lease.
 *
 * (The off-main move itself is verified by the StrictMode/responsiveness
 * journey; this is the behavioural-correctness guard so the dispatcher hop
 * can't silently break the gate.)
 */
class LeaseBackedHostSessionOpenerTest {

    private val host = HostEntity(
        name = "h",
        hostname = "h.example",
        username = "u",
        keyId = 1L,
    )

    @Test
    fun open_returns_null_without_dialing_when_key_file_missing() = runBlocking {
        val dialed = AtomicBoolean(false)
        val connector = SshLeaseConnector { _ ->
            dialed.set(true)
            Result.failure(IllegalStateException("should not dial for a missing key file"))
        }
        val opener = LeaseBackedHostSessionOpener(SshLeaseManager(connector))

        val result: SshSession? =
            opener.open(host, keyPath = "/does/not/exist/key.pem", passphrase = null)

        assertNull("missing key file must short-circuit to null", result)
        assertFalse("must not dial when the key file is missing", dialed.get())
    }

    @Test
    fun open_dials_when_key_file_exists() = runBlocking {
        val keyFile = File.createTempFile("pocketshell-key", ".pem").apply { deleteOnExit() }
        val dialed = AtomicBoolean(false)
        val connector = SshLeaseConnector { _ ->
            dialed.set(true)
            // Fail the dial so acquire returns a failure and open() returns
            // null — we only need to prove the file-exists gate let us through.
            Result.failure(IllegalStateException("dial refused by fake"))
        }
        val opener = LeaseBackedHostSessionOpener(SshLeaseManager(connector))

        val result: SshSession? =
            opener.open(host, keyPath = keyFile.absolutePath, passphrase = null)

        assertNull("a refused dial yields null", result)
        assertTrue("an existing key file must proceed to dial the lease", dialed.get())
    }
}
