package com.pocketshell.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #305: validates the launch-only mapping from a persisted
 * default-host preference to the post-host destination. Navigation
 * priority itself is covered in [MainActivityDeepLinkTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DefaultHostLaunchResolverTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
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
    fun `valid default host resolves to folder list destination`() = runTest {
        val keyPath = temp.newFile("id_ed25519").absolutePath
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "default-key", privateKeyPath = keyPath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "Default Box",
                hostname = "10.0.0.5",
                port = 2222,
                username = "testuser",
                keyId = keyId,
            ),
        )
        SettingsRepository(context).setDefaultHostId(hostId)

        val dest = resolveDefaultHostLaunchDestination(
            defaultHostId = SettingsRepository(context).settings.value.defaultHostId,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
        )

        assertEquals(
            AppDestination.FolderList(
                hostId = hostId,
                hostName = "Default Box",
                hostname = "10.0.0.5",
                port = 2222,
                username = "testuser",
                keyPath = keyPath,
                passphrase = null,
            ),
            dest,
        )
    }

    @Test
    fun `missing selected host falls back to host list`() = runTest {
        val dest = resolveDefaultHostLaunchDestination(
            defaultHostId = 404L,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
        )

        assertNull(dest)
    }

    @Test
    fun `passphrase protected key does not auto-open`() = runTest {
        val keyPath = temp.newFile("protected_id_ed25519").absolutePath
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(
                name = "protected",
                privateKeyPath = keyPath,
                hasPassphrase = true,
            ),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "Protected",
                hostname = "10.0.0.6",
                username = "testuser",
                keyId = keyId,
            ),
        )

        val dest = resolveDefaultHostLaunchDestination(
            defaultHostId = hostId,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
        )

        assertNull(dest)
    }

    @Test
    fun `missing key file does not auto-open`() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "missing", privateKeyPath = temp.root.resolve("missing").absolutePath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "Missing key",
                hostname = "10.0.0.7",
                username = "testuser",
                keyId = keyId,
            ),
        )

        val dest = resolveDefaultHostLaunchDestination(
            defaultHostId = hostId,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
        )

        assertNull(dest)
    }

    // ---- Issue #951 (#928 D2): crash-loop containment ----

    /**
     * The crash-loop fix: the off-Main launch resolve goes through
     * [resolveDefaultHostLaunchDestinationSafely], which must swallow a throwing
     * Room read (corrupt / locked DB) and return `null` (= land on the host
     * list) instead of letting the throw escape and crash the activity on every
     * launch. The unwrapped [resolveDefaultHostLaunchDestination] would
     * propagate — that is exactly what the deleted in-`onCreate` `runBlocking`
     * did.
     */
    @Test
    fun `safely wrapper swallows a throwing Room read and returns null`() = runTest {
        val throwingHostDao = ThrowingGetByIdHostDao(db.hostDao())

        val dest = resolveDefaultHostLaunchDestinationSafely(
            defaultHostId = 7L,
            hostDao = throwingHostDao,
            sshKeyDao = db.sshKeyDao(),
        )

        assertNull("a throwing default-host Room read must NOT crash launch", dest)
    }

    @Test
    fun `safely wrapper returns the destination on the happy path`() = runTest {
        val keyPath = temp.newFile("safe_id_ed25519").absolutePath
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "safe-key", privateKeyPath = keyPath),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "Safe Box",
                hostname = "10.0.0.8",
                port = 2222,
                username = "testuser",
                keyId = keyId,
            ),
        )

        val dest = resolveDefaultHostLaunchDestinationSafely(
            defaultHostId = hostId,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
        )

        assertEquals(
            AppDestination.FolderList(
                hostId = hostId,
                hostName = "Safe Box",
                hostname = "10.0.0.8",
                port = 2222,
                username = "testuser",
                keyPath = keyPath,
                passphrase = null,
            ),
            dest,
        )
    }
}

/**
 * Issue #951: a [HostDao] that throws on the launch-resolution read
 * (`getById`) to simulate a corrupt / locked DB, delegating everything else to
 * the real DAO. Proves [resolveDefaultHostLaunchDestinationSafely]'s
 * crash-loop containment.
 */
private class ThrowingGetByIdHostDao(
    private val delegate: HostDao,
) : HostDao by delegate {
    override suspend fun getById(id: Long): HostEntity =
        throw IllegalStateException("simulated corrupt/locked DB on launch resolve")
}
