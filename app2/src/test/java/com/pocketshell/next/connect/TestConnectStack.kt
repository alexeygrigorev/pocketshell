package com.pocketshell.next.connect

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The whole U-2 connect stack, assembled for a host-JVM test: a real in-memory
 * Room database, the real [RoomTrustStore], the real [ConnectionsRegistry] and
 * the real [ConnectViewModel] — with only the sshj dial swapped for
 * [FakeHostConnectionFactory].
 *
 * Everything except the socket is production code, which is the point: a test
 * that also stubbed the trust store or the registry could not tell whether
 * "Trust" actually persists a fingerprint, and that is exactly the assertion
 * that matters here.
 */
class TestConnectStack(presentedFingerprint: String? = null) {

    val db: AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppDatabase::class.java,
    )
        .allowMainThreadQueries()
        // Room's suspend DAO functions otherwise hop onto the ArchTaskExecutor
        // IO thread, which is REAL concurrency the virtual-time scheduler knows
        // nothing about: `advanceUntilIdle()` returns while the query is still
        // in flight and the assertion reads a half-finished state. Running the
        // queries inline keeps the whole connect chain on the test's thread, so
        // "the scheduler is idle" actually means "the connect finished".
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()

    val factory = FakeHostConnectionFactory(presentedFingerprint)

    val registry = ConnectionsRegistry(
        factory = factory,
        trustStore = RoomTrustStore(db.hostDao(), Dispatchers.Unconfined),
        hostDao = db.hostDao(),
        dispatcher = Dispatchers.Unconfined,
    )

    val viewModel = ConnectViewModel(registry, db.hostDao())

    /** Inserts an `ssh_keys` row + a `hosts` row, returning the host id. */
    fun seedHost(
        name: String = "fixture",
        hostname: String = "10.0.2.2",
        username: String = "testuser",
        port: Int = 2222,
        trustedHostKeySha256: String? = null,
    ): Long = runBlocking {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "$name-key", privateKeyPath = "/dev/null"),
        )
        db.hostDao().insert(
            HostEntity(
                name = name,
                hostname = hostname,
                port = port,
                username = username,
                keyId = keyId,
                trustedHostKeyAlgorithm = trustedHostKeySha256?.let { "SHA256" },
                trustedHostKeySha256 = trustedHostKeySha256,
            ),
        )
    }

    /** The persisted trusted fingerprint for [hostId], or null when none. */
    fun storedFingerprint(hostId: Long): String? =
        runBlocking { db.hostDao().getById(hostId)?.trustedHostKeySha256 }

    fun close() = db.close()
}
