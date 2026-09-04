package com.pocketshell.next.connect

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [RoomAuthSecretResolver] against a real in-memory database and real files on
 * disk: the happy read, plus each not-supported-yet path proving it raises a
 * TYPED failure rather than returning something sshj would choke on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomAuthSecretResolverTest {

    @get:Rule
    val keyDir = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var resolver: RoomAuthSecretResolver

    private val pem = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gt
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        resolver = RoomAuthSecretResolver(db.sshKeyDao(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertKey(
        path: String,
        hasPassphrase: Boolean = false,
        name: String = "fixture",
    ): Long = runBlocking {
        db.sshKeyDao().insert(
            SshKeyEntity(name = name, privateKeyPath = path, hasPassphrase = hasPassphrase),
        )
    }

    @Test
    fun `reads the PEM of a passphrase-less key off disk`() = runTest {
        val file = keyDir.newFile("id_ed25519").apply { writeText(pem) }
        val keyId = insertKey(file.absolutePath)

        assertEquals(pem, resolver.resolvePrivateKeyPem(keyId))
    }

    @Test
    fun `a passphrase-protected key raises PassphraseRequiredException`() = runTest {
        val file = keyDir.newFile("id_locked").apply { writeText(pem) }
        val keyId = insertKey(file.absolutePath, hasPassphrase = true, name = "locked")

        val failure = assertThrows(PassphraseRequiredException::class.java) {
            runBlocking { resolver.resolvePrivateKeyPem(keyId) }
        }

        assertEquals(keyId, failure.keyId)
        assertEquals("locked", failure.keyName)
        assertTrue(failure.message!!.contains("passphrase"))
    }

    @Test
    fun `a missing key row raises MissingSshKeyException`() = runTest {
        assertThrows(MissingSshKeyException::class.java) {
            runBlocking { resolver.resolvePrivateKeyPem(4242L) }
        }
    }

    @Test
    fun `a key row whose file is gone raises MissingSshKeyException`() = runTest {
        val keyId = insertKey(keyDir.root.resolve("never_written").absolutePath)

        val failure = assertThrows(MissingSshKeyException::class.java) {
            runBlocking { resolver.resolvePrivateKeyPem(keyId) }
        }

        assertTrue(failure.message!!.contains("never_written"))
    }

    @Test
    fun `password auth is explicitly unsupported`() = runTest {
        val failure = assertThrows(PasswordAuthUnsupportedException::class.java) {
            runBlocking { resolver.resolvePassword("pref://host/1") }
        }

        assertEquals("pref://host/1", failure.secretRef)
    }
}
