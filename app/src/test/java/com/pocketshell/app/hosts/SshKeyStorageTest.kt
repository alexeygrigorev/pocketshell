package com.pocketshell.app.hosts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
