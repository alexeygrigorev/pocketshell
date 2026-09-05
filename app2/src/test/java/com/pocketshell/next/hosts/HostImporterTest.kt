package com.pocketshell.next.hosts

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The full QR export → import round trip, and the ways a scanned payload is
 * refused.
 *
 * This is the acceptance for "QR import round-trip": a host is encoded with
 * the payload codec + chunk envelope, then fed to [HostImporter] the way the
 * scanner feeds it, and the host that comes out the other end is compared
 * field by field.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HostImporterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var keyStore: SshKeyStore
    private lateinit var importer: HostImporter

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        keyStore = SshKeyStore(
            keysDir = File(temporaryFolder.root, "ssh-keys"),
            sshKeyDao = db.sshKeyDao(),
            dispatcher = UnconfinedTestDispatcher(),
        )
        importer = HostImporter(
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            keyStore = keyStore,
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Export on device A → scan on device B. The receiving device already has
     * the named key (which is the whole premise of the key-reference stance:
     * the QR never carries key material).
     */
    @Test
    fun `a host exported as a QR imports back with the same details`() = runTest {
        val key = keyStore.generateKey("hetzner-key")
        val exported = HostEntity(
            name = "hetzner",
            hostname = "135.181.114.209",
            port = 2222,
            username = "alexey",
            keyId = key.id,
        )

        val envelopes = encodeLikeTheShareScreen(exported, keyName = key.name)
        assertEquals("a small host payload fits one QR", 1, envelopes.size)

        val outcome = importer.import(envelopes.single())

        assertTrue("expected an import, got $outcome", outcome is ImportOutcome.Imported)
        val row = db.hostDao().getAll().first().single()
        assertEquals("hetzner", row.name)
        assertEquals("135.181.114.209", row.hostname)
        assertEquals(2222, row.port)
        assertEquals("alexey", row.username)
        assertEquals(key.id, row.keyId)
    }

    /**
     * The export payload must not contain key material. Asserted on the bytes
     * rather than on the model, because this is the property a reader of the QR
     * on someone's screen cares about.
     */
    @Test
    fun `the exported payload carries no private key material`() = runTest {
        val key = keyStore.generateKey("hetzner-key")
        val pem = keyStore.readPem(key)!!
        val host = HostEntity(name = "h", hostname = "10.0.0.1", username = "u", keyId = key.id)

        val payload = SshImportPayloadCodec.encode(
            SshImportConfig("h", "10.0.0.1", host.port, "u", SshImportAuth.KeyReference(key.name)),
        )

        assertTrue(payload.contains("keyRef"))
        assertTrue(!payload.contains("privateKeyPem"))
        assertTrue(!payload.contains("PRIVATE KEY"))
        // No line of the actual key body appears in the payload either.
        pem.lines().filter { it.length > 20 && !it.startsWith("-----") }.forEach { line ->
            assertTrue("payload leaked a key line", !payload.contains(line))
        }
    }

    /** The desktop-emitter path: the payload carries the key, so it gets stored. */
    @Test
    fun `a payload carrying key material imports the key alongside the host`() = runTest {
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "builder",
                host = "10.0.0.7",
                port = 22,
                username = "root",
                auth = SshImportAuth.PrivateKey("builder-key", UNENCRYPTED_PEM),
            ),
        )

        val outcome = importer.import(QrChunkCodec.encode(payload).single())

        assertTrue(outcome is ImportOutcome.Imported)
        val key = db.sshKeyDao().getAll().first().single()
        assertEquals("builder-key", key.name)
        assertEquals(UNENCRYPTED_PEM, File(key.privateKeyPath).readText())
        assertEquals(key.id, db.hostDao().getAll().first().single().keyId)
    }

    @Test
    fun `a payload carrying an encrypted key is refused without writing anything`() = runTest {
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "locked",
                host = "10.0.0.8",
                port = 22,
                username = "root",
                auth = SshImportAuth.PrivateKey("locked-key", ENCRYPTED_PEM),
            ),
        )

        val outcome = importer.import(payload)

        assertTrue(outcome is ImportOutcome.Failed)
        assertTrue((outcome as ImportOutcome.Failed).message.contains("passphrase-protected"))
        assertTrue(db.hostDao().getAll().first().isEmpty())
        assertTrue(db.sshKeyDao().getAll().first().isEmpty())
    }

    @Test
    fun `a key reference the device does not have is refused with the key's name`() = runTest {
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig("h", "10.0.0.1", 22, "u", SshImportAuth.KeyReference("missing-key")),
        )

        val outcome = importer.import(payload)

        assertTrue(outcome is ImportOutcome.Failed)
        assertTrue((outcome as ImportOutcome.Failed).message.contains("missing-key"))
        assertTrue(db.hostDao().getAll().first().isEmpty())
    }

    @Test
    fun `re-scanning a host already configured reports it instead of duplicating`() = runTest {
        val key = keyStore.generateKey("k")
        db.hostDao().insert(
            HostEntity(name = "hetzner", hostname = "10.0.0.1", port = 22, username = "alexey", keyId = key.id),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig("hetzner-again", "10.0.0.1", 22, "alexey", SshImportAuth.KeyReference("k")),
        )

        val outcome = importer.import(payload)

        assertEquals(ImportOutcome.AlreadyPresent("hetzner"), outcome)
        assertEquals(1, db.hostDao().getAll().first().size)
    }

    /**
     * A different login on the same machine is a different host, not a
     * duplicate — matching on `(hostname, port)` alone would block a legitimate
     * second account.
     */
    @Test
    fun `the same endpoint with another username imports as a separate host`() = runTest {
        val key = keyStore.generateKey("k")
        db.hostDao().insert(
            HostEntity(name = "hetzner", hostname = "10.0.0.1", port = 22, username = "alexey", keyId = key.id),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig("hetzner-root", "10.0.0.1", 22, "root", SshImportAuth.KeyReference("k")),
        )

        val outcome = importer.import(payload)

        assertTrue(outcome is ImportOutcome.Imported)
        assertEquals(2, db.hostDao().getAll().first().size)
    }

    @Test
    fun `bare JSON with no envelope also imports`() = runTest {
        keyStore.generateKey("k")
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig("h", "10.0.0.1", 22, "u", SshImportAuth.KeyReference("k")),
        )

        assertTrue(importer.import(payload) is ImportOutcome.Imported)
    }

    /**
     * One chunk of a multi-part transmission handed to a non-scanner path can
     * never be completed there. Saying so beats a JSON parse error on half a
     * document.
     */
    @Test
    fun `a single chunk of a multi-part payload says to use the scanner`() = runTest {
        val big = QrChunkCodec.encode("x".repeat(QrChunkCodec.CHUNK_SIZE * 2))

        val outcome = importer.import(big.first())

        assertTrue(outcome is ImportOutcome.Failed)
        assertTrue((outcome as ImportOutcome.Failed).message.contains("Scan QR"))
    }

    @Test
    fun `a foreign QR is refused cleanly`() = runTest {
        listOf("https://example.com", "", "{}", "pocketshell.qr.v1?garbage").forEach { payload ->
            val outcome = importer.import(payload)
            assertTrue("expected '$payload' to fail", outcome is ImportOutcome.Failed)
            assertNotNull((outcome as ImportOutcome.Failed).message.ifBlank { null })
        }
        assertTrue(db.hostDao().getAll().first().isEmpty())
    }

    /** Payload + chunk envelope the scanner feeds to [HostImporter]. */
    private fun encodeLikeTheShareScreen(host: HostEntity, keyName: String): List<String> =
        QrChunkCodec.encode(
            SshImportPayloadCodec.encode(
                SshImportConfig(
                    name = host.name,
                    host = host.hostname,
                    port = host.port,
                    username = host.username,
                    auth = SshImportAuth.KeyReference(name = keyName),
                ),
            ),
        )

    private companion object {
        val UNENCRYPTED_PEM: String = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAAB
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()

        val ENCRYPTED_PEM: String = """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,0123456789ABCDEF0123456789ABCDEF

            AAAA
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
    }
}
