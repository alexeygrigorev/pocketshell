package com.pocketshell.app.hosts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SshKeyStorageTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
        File(context.filesDir, "ssh-keys").deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "ssh-keys").deleteRecursively()
    }

    @Test
    fun persistKey_reusesExistingEntity_forSamePayload() = runTest {
        val first = SshKeyStorage.persistKey(
            context = context,
            sshKeyDao = db.sshKeyDao(),
            name = "hetzner",
            content = PrivateKey,
        )

        val second = SshKeyStorage.persistKey(
            context = context,
            sshKeyDao = db.sshKeyDao(),
            name = "renamed-copy",
            content = "\n$PrivateKey\n",
        )

        val rows = db.sshKeyDao().getAll().first()
        val files = File(context.filesDir, "ssh-keys").listFiles().orEmpty()
        assertEquals(first.id, second.id)
        assertEquals(1, rows.size)
        assertEquals(first.privateKeyPath, second.privateKeyPath)
        assertEquals(SshKeyStorage.fingerprintFor(PrivateKey), rows[0].fingerprint)
        assertEquals(1, files.size)
    }

    @Test
    fun persistKey_keepsSeparateRows_forDifferentPayloadsWithSameName() = runTest {
        val first = SshKeyStorage.persistKey(
            context = context,
            sshKeyDao = db.sshKeyDao(),
            name = "hetzner",
            content = PrivateKey,
        )

        val second = SshKeyStorage.persistKey(
            context = context,
            sshKeyDao = db.sshKeyDao(),
            name = "hetzner",
            content = OtherPrivateKey,
        )

        val rows = db.sshKeyDao().getAll().first()
        val files = File(context.filesDir, "ssh-keys").listFiles().orEmpty()
        assertEquals(2, rows.size)
        assertTrue(first.id != second.id)
        assertTrue(first.privateKeyPath != second.privateKeyPath)
        assertEquals(2, files.size)
    }

    @Test
    fun hasPrivateKeyPassphrase_detectsEncryptedPemHeaders() {
        val encrypted = """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,00112233445566778899AABBCCDDEEFF
            abc
            -----END RSA PRIVATE KEY-----
        """.trimIndent()

        assertTrue(SshKeyStorage.hasPrivateKeyPassphrase(encrypted))
    }

    @Test
    fun hasPrivateKeyPassphrase_detectsPkcs8EncryptedPrivateKeyHeader() {
        val encrypted = """
            -----BEGIN ENCRYPTED PRIVATE KEY-----
            abc
            -----END ENCRYPTED PRIVATE KEY-----
        """.trimIndent()

        assertTrue(SshKeyStorage.hasPrivateKeyPassphrase(encrypted))
    }

    @Test
    fun hasPrivateKeyPassphrase_detectsOpenSshEncryptionFromDecodedHeader() {
        val encrypted = openSshPrivateKey(cipherName = "aes256-ctr", kdfName = "bcrypt")
        val unencrypted = openSshPrivateKey(cipherName = "none", kdfName = "none")

        assertTrue(SshKeyStorage.hasPrivateKeyPassphrase(encrypted))
        assertFalse(SshKeyStorage.hasPrivateKeyPassphrase(unencrypted))
    }

    private fun openSshPrivateKey(cipherName: String, kdfName: String): String {
        val bytes = java.io.ByteArrayOutputStream().apply {
            write("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))
            writeOpenSshString(cipherName)
            writeOpenSshString(kdfName)
        }.toByteArray()
        val body = java.util.Base64.getEncoder()
            .encodeToString(bytes)
            .chunked(64)
            .joinToString("\n")
        return """
            -----BEGIN OPENSSH PRIVATE KEY-----
            $body
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    }

    private fun java.io.ByteArrayOutputStream.writeOpenSshString(value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        write(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        write(bytes)
    }

    private companion object {
        val PrivateKey = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            abc
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()

        val OtherPrivateKey = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            def
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    }
}
