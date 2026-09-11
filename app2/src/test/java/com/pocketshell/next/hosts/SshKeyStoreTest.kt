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
        assertFalse("the default generated key has no passphrase", key.hasPassphrase)

        val file = File(key.privateKeyPath)
        assertTrue("key file must exist at privateKeyPath", file.isFile)
        val pem = store.readPem(key)
        assertNotNull(pem)
        assertTrue(SshKeyMaterial.looksLikePrivateKey(pem!!))
        assertFalse(SshKeyMaterial.isEncrypted(pem))
        assertEquals(file.readText(), pem)

        val publicKey = store.readPublicKey(key)
        assertNotNull(publicKey)
        assertTrue(publicKey!!.startsWith("ssh-ed25519 "))
        assertTrue(publicKey.split(' ').size >= 2)
        assertEquals("ED25519", SshKeyMaterial.keyAlgorithmLabel(publicKey))
        assertTrue(SshKeyMaterial.publicKeyFingerprint(publicKey).startsWith("SHA256:"))

        // Registered, and the fingerprint indexes it.
        assertEquals(listOf("laptop"), db.sshKeyDao().getAll().first().map { it.name })
        assertEquals(key.id, db.sshKeyDao().getByFingerprint(key.fingerprint)?.id)
    }

    @Test
    fun `the unencrypted agents fixture reads its embedded public key`() = runTest {
        val fixtureDir = File("../tests/docker")
        val privateKey = File(fixtureDir, "test_key").readText()
        val expectedPublicKey = File(fixtureDir, "test_key.pub").readText().trim()
        val key = store.importKey("fixture-key", privateKey)

        val actualPublicKey = requireNotNull(store.readPublicKey(key))
        // The app deliberately emits its own stable comment; the authorized
        // key payload is the contract shared with the fixture.
        assertEquals(expectedPublicKey.substringBeforeLast(' '), actualPublicKey.substringBeforeLast(' '))
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
    fun `generation supports an RSA compatibility key protected by a passphrase`() = runTest {
        val key = store.generateKey(
            name = "compatibility",
            type = SshKeyGenerationType.RSA,
            passphrase = "correct horse battery staple".toCharArray(),
        )

        assertTrue(key.hasPassphrase)
        val pem = requireNotNull(store.readPem(key))
        assertTrue(SshKeyMaterial.isEncrypted(pem))
        assertTrue(store.readPublicKey(key, "correct horse battery staple".toCharArray())!!.startsWith("ssh-rsa "))
        assertTrue(runCatching {
            store.readPublicKey(key, "wrong".toCharArray())
        }.isFailure)
    }

    @Test
    fun `generation supports a protected modern key`() = runTest {
        val key = store.generateKey(
            name = "modern-protected",
            type = SshKeyGenerationType.ED25519,
            passphrase = "correct horse battery staple".toCharArray(),
        )

        assertTrue(key.hasPassphrase)
        val pem = requireNotNull(store.readPem(key))
        assertTrue(SshKeyMaterial.isEncrypted(pem))
        assertTrue(
            store.readPublicKey(key, "correct horse battery staple".toCharArray())
                ?.startsWith("ssh-ed25519 ") == true,
        )
        assertTrue(runCatching {
            store.readPublicKey(key, "wrong".toCharArray())
        }.isFailure)
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
    fun `a passphrase-protected key is retained for a later unlock`() = runTest {
        val key = store.importKey("locked", ENCRYPTED_OPENSSH_PEM)

        assertTrue(key.hasPassphrase)
        assertTrue(File(key.privateKeyPath).isFile)
        assertEquals(1, db.sshKeyDao().getAll().first().size)
    }

    @Test
    fun `a classic encrypted PEM is retained too`() = runTest {
        val classic = """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,0123456789ABCDEF0123456789ABCDEF

            AAAAAAAAAAAAAAAAAAAAAA==
            -----END RSA PRIVATE KEY-----
        """.trimIndent()

        assertTrue(SshKeyMaterial.isEncrypted(classic))
        val key = store.importKey("x", classic)
        assertTrue(key.hasPassphrase)
        assertEquals(classic, File(key.privateKeyPath).readText())
    }

    @Test
    fun `a truncated key-shaped PEM is refused before it is persisted`() = runTest {
        val lines = UNENCRYPTED_PEM.lines()
        val truncated = lines.dropLast(2).joinToString("\n") + "\n" + lines.last()

        val error = runCatching { store.importKey("truncated", truncated) }.exceptionOrNull()

        assertTrue(error is NotAPrivateKeyException)
        assertTrue(db.sshKeyDao().getAll().first().isEmpty())
    }

    @Test
    fun `text that is not a key is refused`() = runTest {
        val error = runCatching { store.importKey("notes", "just some text") }.exceptionOrNull()

        assertTrue(error is NotAPrivateKeyException)
        assertTrue(db.sshKeyDao().getAll().first().isEmpty())
    }

    /**
     * A key name comes from user-supplied import input, and it doubles as the
     * filename. A name of `../../databases/pocketshell.db` must not choose
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
        /** A complete generated OpenSSH key used by the import tests. */
        val UNENCRYPTED_PEM: String by lazy {
            SshKeyMaterial.generatePrivateKeyPem()
        }

        /** A complete generated encrypted OpenSSH key used by the import tests. */
        val ENCRYPTED_OPENSSH_PEM: String by lazy {
            SshKeyMaterial.generatePrivateKeyPem(
                type = SshKeyGenerationType.ED25519,
                passphrase = "test passphrase".toCharArray(),
            )
        }
    }
}
