package com.pocketshell.next.share

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.transport.ExecResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ShareUploader] over the REAL connect stack — a real Room database, the real
 * [com.pocketshell.next.connect.ConnectionsRegistry], the real content reader —
 * with only the sshj dial and the source app's provider substituted (see
 * [TestShareStack]).
 *
 * Every success assertion reads the bytes back out of the host filesystem the
 * uploader wrote to. A test that counted `write` calls instead could not tell a
 * file that landed in the inbox from one that landed in `/`, which is precisely
 * the class of bug the sanitiser exists to prevent.
 *
 * Robolectric (`AndroidJUnit4`) is needed for the in-memory Room database and
 * for `android.net.Uri`; the uploader itself touches no Android API.
 */
@RunWith(AndroidJUnit4::class)
class ShareUploaderTest {

    private lateinit var stack: TestShareStack

    @Before
    fun setUp() {
        stack = TestShareStack()
    }

    @After
    fun tearDown() = stack.close()

    @Test
    fun `a shared file lands in the host inbox under a timestamped name`() = runTest {
        val hostId = stack.seedHost()
        val item = stack.uriItem(
            uri = "content://media/external/images/42",
            bytes = PNG_BYTES,
            providerName = "photo.png",
        )

        val result = stack.uploader.upload(hostId, item)

        val path = result.getOrThrow()
        assertEquals("${stack.inbox}/${TestShareStack.fixedTimestamp}-photo.png", path)
        // THE load-bearing assertion: the host holds the shared bytes, at that
        // exact path — not "write was called".
        assertArrayEquals(PNG_BYTES, stack.sftp.bytesAt(path))
    }

    @Test
    fun `the inbox directory is created before the write`() = runTest {
        val hostId = stack.seedHost()

        stack.uploader.upload(hostId, stack.uriItem("content://doc/1", "hi".toByteArray()))
            .getOrThrow()

        val mkdir = stack.connection.executedCommands.single { it.startsWith("mkdir") }
        assertEquals(
            "mkdir -p \"\$HOME/inbox/pocketshell\" && cd \"\$HOME/inbox/pocketshell\" && pwd",
            mkdir,
        )
    }

    @Test
    fun `the provider's display name beats the intent's guess`() = runTest {
        val hostId = stack.seedHost()
        // What a gallery share really looks like: EXTRA_STREAM's tail is an id,
        // and only the provider knows the file is called `holiday.jpg`.
        val item = stack.uriItem(
            uri = "content://media/external/images/1000000042",
            bytes = "jpeg".toByteArray(),
            displayName = "1000000042",
            providerName = "holiday.jpg",
        )

        val path = stack.uploader.upload(hostId, item).getOrThrow()

        assertTrue("expected the provider name, got $path", path.endsWith("-holiday.jpg"))
    }

    @Test
    fun `a path-traversing name cannot escape the inbox`() = runTest {
        val hostId = stack.seedHost()
        val item = stack.uriItem(
            uri = "content://evil/1",
            bytes = "ssh-rsa AAAA attacker".toByteArray(),
            providerName = "../../.ssh/authorized_keys",
        )

        val path = stack.uploader.upload(hostId, item).getOrThrow()

        assertTrue("expected a path inside the inbox, got $path", path.startsWith("${stack.inbox}/"))
        assertTrue("expected no traversal segment in $path", !path.contains(".."))
        assertNull(
            "nothing may be written outside the inbox",
            stack.sftp.bytesAt("${stack.home}/.ssh/authorized_keys"),
        )
        assertNotNull(stack.sftp.bytesAt(path))
    }

    @Test
    fun `two files shared in the same second under one name do not overwrite each other`() =
        runTest {
            val hostId = stack.seedHost()
            val first = stack.uriItem("content://doc/a", "FIRST".toByteArray(), providerName = "log.txt")
            val second = stack.uriItem("content://doc/b", "SECOND".toByteArray(), providerName = "log.txt")

            val firstPath = stack.uploader.upload(hostId, first).getOrThrow()
            val secondPath = stack.uploader.upload(hostId, second).getOrThrow()

            // The clock is pinned, so both names start identical — the collision
            // suffix is the ONLY thing keeping the first file alive. The shipping
            // client overwrote here and still reported both as uploaded.
            assertEquals("${stack.inbox}/${TestShareStack.fixedTimestamp}-log.txt", firstPath)
            assertEquals("${stack.inbox}/${TestShareStack.fixedTimestamp}-log-2.txt", secondPath)
            assertEquals("FIRST", stack.sftp.textAt(firstPath))
            assertEquals("SECOND", stack.sftp.textAt(secondPath))
        }

    @Test
    fun `an existing extension-less name also gets a suffix`() = runTest {
        val hostId = stack.seedHost()
        stack.dial(hostId)
        stack.sftp.seedFile("${stack.inbox}/${TestShareStack.fixedTimestamp}-README", "old")

        val path = stack.uploader
            .upload(hostId, stack.uriItem("content://doc/r", "new".toByteArray(), providerName = "README"))
            .getOrThrow()

        assertEquals("${stack.inbox}/${TestShareStack.fixedTimestamp}-README-2", path)
        assertEquals("old", stack.sftp.textAt("${stack.inbox}/${TestShareStack.fixedTimestamp}-README"))
    }

    @Test
    fun `a text share lands as a txt file holding the text`() = runTest {
        val hostId = stack.seedHost()
        val item = ShareableItem.TextItem(text = "https://example.com/thing", displayName = "A link")

        val path = stack.uploader.upload(hostId, item).getOrThrow()

        assertEquals("${stack.inbox}/${TestShareStack.fixedTimestamp}-A_link.txt", path)
        assertEquals("https://example.com/thing", stack.sftp.textAt(path))
    }

    @Test
    fun `a source the phone cannot read fails with a readable message and writes nothing`() =
        runTest {
            val hostId = stack.seedHost()
            stack.openStreamFails = true

            val result = stack.uploader.upload(
                hostId,
                stack.uriItem("content://revoked/1", "x".toByteArray(), providerName = "note.txt"),
            )

            val message = result.exceptionOrNull()?.message.orEmpty()
            assertEquals("Could not read the shared file", message)
            // Nothing was dialled: an unreadable source must not open a transport.
            assertEquals(0, stack.factory.dialCount)
        }

    @Test
    fun `a host that refuses the inbox mkdir fails with the host's own reason`() = runTest {
        val hostId = stack.seedHost()
        stack.mkdirResult =
            ExecResult(1, "", "mkdir: cannot create directory: Permission denied", false)

        val result = stack.uploader.upload(
            hostId,
            stack.uriItem("content://doc/1", "x".toByteArray(), providerName = "note.txt"),
        )

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("expected the host's reason in `$message`", message.contains("Permission denied"))
        assertTrue("expected no stack trace in `$message`", !message.contains("\n"))
    }

    @Test
    fun `a connection that died before the transfer fails cleanly`() = runTest {
        val hostId = stack.seedHost()
        stack.onDial = { connection -> connection.markLost("network dropped") }

        val result = stack.uploader.upload(
            hostId,
            stack.uriItem("content://doc/1", "x".toByteArray(), providerName = "note.txt"),
        )

        assertEquals(
            "Connection lost during the upload",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `an unreachable host reports the dial failure rather than throwing`() = runTest {
        val hostId = stack.seedHost()
        stack.factory.failWith = "No route to host"

        val result = stack.uploader.upload(
            hostId,
            stack.uriItem("content://doc/1", "x".toByteArray(), providerName = "note.txt"),
        )

        assertEquals("No route to host", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a host whose key is not trusted yet points the user at the host list`() = runTest {
        // A host row with NO trusted fingerprint, dialled against a server that
        // presents one: exactly the state a fresh install is in.
        val untrusted = TestShareStack(presentedFingerprint = "SHA256:whatever")
        try {
            val hostId = untrusted.seedHost()

            val result = untrusted.uploader.upload(
                hostId,
                untrusted.uriItem("content://doc/1", "x".toByteArray(), providerName = "note.txt"),
            )

            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(
                "expected trust guidance, got `$message`",
                message.contains("key") && message.contains("host list"),
            )
        } finally {
            untrusted.close()
        }
    }

    private companion object {
        val PNG_BYTES: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
    }
}
