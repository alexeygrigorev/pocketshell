package com.pocketshell.next.usage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.core.usage.PocketshellUsageJsonParser
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.connect.FakeHostConnectionFactory
import com.pocketshell.next.connect.RoomTrustStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The P-5 usage stack assembled for a host-JVM test: a real in-memory Room
 * database, the real [ConnectionsRegistry] and the real [UsageFetcher] — only
 * the sshj dial is swapped for [FakeHostConnectionFactory] (same shape as
 * `TestForwardingStack`/`ConnectionsRegistryTest`).
 */
class TestUsageStack {

    private val context: Context = ApplicationProvider.getApplicationContext()

    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()

    val factory = FakeHostConnectionFactory()

    val registry = ConnectionsRegistry(
        factory = factory,
        trustStore = RoomTrustStore(db.hostDao(), Dispatchers.Unconfined),
        hostDao = db.hostDao(),
        dispatcher = Dispatchers.Unconfined,
    )

    val fetcher = UsageFetcher(
        hostDao = db.hostDao(),
        connections = registry,
        parser = PocketshellUsageJsonParser(),
    )

    /** Inserts an `ssh_keys` row + a `hosts` row, returning the host id. */
    fun seedHost(name: String = "fixture"): Long = runBlocking {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "$name-key", privateKeyPath = "/dev/null"),
        )
        db.hostDao().insert(
            HostEntity(
                name = name,
                hostname = "10.0.2.2",
                port = 2222,
                username = "testuser",
                keyId = keyId,
            ),
        )
    }

    /** Dials [hostId] through the fake factory so [UsageFetcher] sees it as connected. */
    fun connect(hostId: Long) = runBlocking { registry.getOrConnect(hostId) }

    /** Scripts every future dial's `pocketshell usage --json` reply. */
    fun scriptUsage(stdout: String, exitCode: Int = 0, stderr: String = "") {
        factory.script = { connection: FakeHostConnection ->
            connection.onExec(
                "pocketshell usage --json",
                com.pocketshell.core.transport.ExecResult(exitCode, stdout, stderr, false),
            )
        }
    }

    fun close() = db.close()
}
