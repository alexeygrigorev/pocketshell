package com.pocketshell.app.systemsurfaces

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardingChooserViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun openHostResolvesPlainKeyWithoutPassphrase() = runTest {
        val host = insertHost(key = SshKeyEntity(name = "plain", privateKeyPath = "/tmp/plain"))
        val viewModel = newViewModel()
        var resolved: ForwardingHostKey? = null

        viewModel.openHost(host) { resolved = it }
        runCurrent()

        val key = resolved ?: error("key was not resolved")
        assertEquals(host, key.host)
        assertEquals("/tmp/plain", key.keyPath)
        assertEquals("plain", key.keyName)
        assertFalse(key.hasPassphrase)
    }

    @Test
    fun openHostPreservesPassphraseProtectedKeyMetadata() = runTest {
        val host = insertHost(
            key = SshKeyEntity(name = "locked", privateKeyPath = "/tmp/locked", hasPassphrase = true),
        )
        val viewModel = newViewModel()
        var resolved: ForwardingHostKey? = null

        viewModel.openHost(host) { resolved = it }
        runCurrent()

        val key = resolved ?: error("key was not resolved")
        assertEquals("/tmp/locked", key.keyPath)
        assertEquals("locked", key.keyName)
        assertTrue(key.hasPassphrase)
    }

    private suspend fun insertHost(key: SshKeyEntity): HostEntity {
        val keyId = db.sshKeyDao().insert(key)
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "dev",
                hostname = "dev.example",
                username = "alexey",
                keyId = keyId,
            ),
        )
        return db.hostDao().getById(hostId) ?: error("host not inserted")
    }

    private fun newViewModel(): ForwardingChooserViewModel =
        ForwardingChooserViewModel(
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
        )
}
