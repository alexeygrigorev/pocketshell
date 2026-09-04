package com.pocketshell.next.hosts

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SshKeyMaterial] + [SshKeyStore] against a real in-memory Room database and a
 * real temp directory.
 *
 * The store's whole job is the DB row and the file staying consistent, so both
 * halves are real here — a fake DAO would let a test pass while
 * `RoomAuthSecretResolver` finds nothing at `privateKeyPath` on device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SshKeyStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var store: SshKeyStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = SshKeyStore(
            keysDir = File(temporaryFolder.root, "ssh-keys"),
            sshKeyDao = db.sshKeyDao(),
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * The acceptance for "key generate round-trips": generated → on disk →
     * readable back → registered → usable as a `hosts.keyId`. That last hop is
     * what makes it a key rather than a file, and it is the assertion
     * `RoomAuthSecretResolver` depends on.
     */
    @Test
    fun `a generated key lands on disk, in the table, and reads back`() = runTest {
        val key = store.generateKey("laptop")

        assertTrue(key.id > 0)
        assertEquals("laptop", key.name)
        assertFalse("generated keys are never passphrase-protected", key.hasPassphrase)

        val file = File(key.privateKeyPath)
        assertTrue("key file must exist at privateKeyPath", file.isFile)
        val pem = store.readPem(key)
        assertNotNull(pem)
        assertTrue(SshKeyMaterial.looksLikePrivateKey(pem!!))
        assertFalse(SshKeyMaterial.isEncrypted(pem))
        assertEquals(file.readText(), pem)

        // Registered, and the fingerprint indexes it.
        assertEquals(listOf("laptop"), db.sshKeyDao().getAll().first().map { it.name })
        assertEquals(key.id, db.sshKeyDao().getByFingerprint(key.fingerprint)?.id)
    }

    @Test
    fun `two generated keys are distinct rows with distinct files`() = runTest {
        val first = store.generateKey("k")
        val second = store.generateKey("k")

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.privateKeyPath, second.privateKeyPath)
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertTrue(File(first.privateKeyPath).isFile)
        assertTrue(File(second.privateKeyPath).isFile)
    }

    @Test
    fun `an imported key's content is stored byte for byte`() = runTest {
        val key = store.importKey("id_ed25519", UNENCRYPTED_PEM)

        assertEquals(UNENCRYPTED_PEM.trim(), store.readPem(key))
        assertEquals(SshKeyMaterial.fingerprint(UNENCRYPTED_PEM), key.fingerprint)
        assertFalse(key.hasPassphrase)
    }

    @Test
    fun `re-importing the same key reuses the row instead of duplicating the secret`() = runTest {
        val first = store.importKey("id_ed25519", UNENCRYPTED_PEM)
        val again = store.importKey("a-different-name", "\n$UNENCRYPTED_PEM\n")

        assertEquals(first.id, again.id)
        assertEquals(1, db.sshKeyDao().getAll().first().size)
    }

    @Test
    fun `a passphrase-protected key is refused with an explanation`() = runTest {
        val error = runCatching { store.importKey("locked", ENCRYPTED_OPENSSH_PEM) }.exceptionOrNull()

        assertTrue(error is EncryptedKeyUnsupportedException)
        assertTrue(error!!.message!!.contains("passphrase-protected"))
        // Nothing partial was written: no row, and no file left behind.
        assertTrue(db.sshKeyDao().getAll().first().isEmpty())
    }

    @Test
    fun `a classic encrypted PEM is refused too`() = runTest {
        val classic = """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,0123456789ABCDEF0123456789ABCDEF

            AAAA
            -----END RSA PRIVATE KEY-----
        """.trimIndent()

        assertTrue(SshKeyMaterial.isEncrypted(classic))
        assertTrue(
            runCatching { store.importKey("x", classic) }
                .exceptionOrNull() is EncryptedKeyUnsupportedException,
        )
    }

    @Test
    fun `text that is not a key is refused`() = runTest {
        val error = runCatching { store.importKey("notes", "just some text") }.exceptionOrNull()

        assertTrue(error is NotAPrivateKeyException)
        assertTrue(db.sshKeyDao().getAll().first().isEmpty())
    }

    /**
     * A key name comes off a scanned QR, and it doubles as the filename. A
     * payload naming its key `../../databases/pocketshell.db` must not choose
     * where the write lands.
     */
    @Test
    fun `a traversal-shaped key name cannot escape the keys directory`() = runTest {
        val key = store.importKey("../../databases/pocketshell.db", UNENCRYPTED_PEM)

        val keysDir = File(temporaryFolder.root, "ssh-keys").canonicalFile
        assertEquals(keysDir, File(key.privateKeyPath).canonicalFile.parentFile)
        assertEquals("pocketshell.db", key.name)
    }

    @Test
    fun `deleting a key removes the file before the row`() = runTest {
        val key = store.generateKey("doomed")
        val file = File(key.privateKeyPath)

        store.deleteKey(key)

        assertFalse(file.exists())
        assertNull(db.sshKeyDao().getById(key.id))
    }

    private companion object {
        /**
         * An OpenSSH private-key container whose header names cipher `none` /
         * kdf `none` — the bytes [SshKeyMaterial.isEncrypted] actually reads.
         * Truncated after that header: the store never parses the key body, and
         * committing a complete (even throwaway) private key to the repo to
         * assert a header check would be a bad trade.
         */
        val UNENCRYPTED_PEM: String = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAAB
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()

        /**
         * The same container shape with `aes256-ctr` / `bcrypt` named instead —
         * an identical header to the one above, which is exactly why the
         * encryption check has to parse the body.
         */
        val ENCRYPTED_OPENSSH_PEM: String = buildEncryptedOpenSshPem()

        private fun buildEncryptedOpenSshPem(): String {
            val body = java.io.ByteArrayOutputStream()
            body.write("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))
            body.writeSshString("aes256-ctr")
            body.writeSshString("bcrypt")
            body.writeSshString("salt-and-rounds")
            val base64 = java.util.Base64.getEncoder().encodeToString(body.toByteArray())
            return buildString {
                appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
                base64.chunked(70).forEach { appendLine(it) }
                append("-----END OPENSSH PRIVATE KEY-----")
            }
        }

        private fun java.io.ByteArrayOutputStream.writeSshString(value: String) {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            write(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            write(bytes)
        }
    }
}
