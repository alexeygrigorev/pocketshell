package com.pocketshell.next.ports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.PortRemappingEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.transport.ExecResult
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.next.connect.FakeHostConnectionFactory
import com.pocketshell.next.connect.RoomTrustStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The P-4 forwarding stack assembled for a host-JVM test: a real in-memory Room
 * database, the real [RoomTrustStore], the real [ForwardingController] and the
 * real `core-portfwd` engine — with only the sshj dial swapped for
 * [FakeHostConnectionFactory].
 *
 * Everything except the socket is production code, deliberately: a test that also
 * stubbed the supervisor could not tell whether enabling a host actually persists
 * the intent and opens a forward, which is the only thing worth asserting here.
 */
class TestForwardingStack(
    dispatcher: CoroutineDispatcher,
    /**
     * When set, every dial evaluates this fingerprint against the real
     * [RoomTrustStore] the way `RealHostConnectionFactory` does, so a host whose
     * `trustedHostKeySha256` column is empty (or holds a different key) comes
     * back as [com.pocketshell.core.transport.ConnectResult.NeedsTrust] — the
     * unconfirmed/rotated-key fixture #2491 needs. Null keeps the default
     * always-trusted dial the other tests want.
     */
    presentedFingerprint: String? = null,
) {

    private val context: Context = ApplicationProvider.getApplicationContext()

    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        // Room's suspend DAO functions otherwise hop onto the ArchTaskExecutor IO
        // thread — REAL concurrency the virtual-time scheduler knows nothing
        // about, so `runCurrent()` would return while a query is still in flight.
        // Running them inline keeps the whole chain on the test's thread.
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()

    val factory = FakeHostConnectionFactory(presentedFingerprint)

    /**
     * The real store over Robolectric's SharedPreferences. `Unconfined` so its
     * suspend accessors resolve inline — the disk hop is the production concern,
     * not something a test wants to schedule around.
     */
    val showAllPortsStore = ShowAllPortsStore(context, Dispatchers.Unconfined)

    val controller = ForwardingController(
        hostDao = db.hostDao(),
        forwardingIntentDao = db.forwardingIntentDao(),
        remappingDao = db.portRemappingDao(),
        connectionFactory = factory,
        trustStore = RoomTrustStore(db.hostDao(), Dispatchers.Unconfined),
        dispatcher = dispatcher,
    )

    /**
     * Makes every dialled connection report [ports] as listening, so the
     * forwarder's scan discovers them. Format is the awk-filtered `ss -tlnp`
     * output `PortScanner` parses.
     */
    fun listenOn(vararg ports: Pair<Int, String>) {
        val output = ports.joinToString("\n") { (port, process) ->
            "0.0.0.0:$port users:((\"$process\",pid=1,fd=4))"
        }
        factory.script = { connection: FakeHostConnection ->
            connection.onExecMatching("ss -tlnp ...", match = { it.startsWith("ss -tlnp") }) {
                ExecResult(exitCode = 0, stdout = output, stderr = "", timedOut = false)
            }
            connection.defaultExec = ExecResult(exitCode = 0, stdout = "", stderr = "", timedOut = false)
        }
    }

    /** Inserts an `ssh_keys` row + a `hosts` row, returning the host id. */
    fun seedHost(
        name: String = "fixture",
        enabled: Boolean = false,
        scanIntervalSec: Int = 1,
        skipPortsBelow: Int = 1_024,
        maxAutoPort: Int = 10_000,
    ): Long = runBlocking {
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
                enabled = enabled,
                scanIntervalSec = scanIntervalSec,
                skipPortsBelow = skipPortsBelow,
                maxAutoPort = maxAutoPort,
            ),
        )
    }

    fun seedRemapping(hostId: Long, remotePort: Int, localPort: Int) = runBlocking {
        db.portRemappingDao().insert(
            PortRemappingEntity(hostId = hostId, remotePort = remotePort, localPort = localPort),
        )
    }

    fun isEnabled(hostId: Long): Boolean = runBlocking { db.hostDao().getById(hostId)?.enabled == true }

    /** Deletes the host row out from under a running forward. */
    fun deleteHost(hostId: Long) = runBlocking { db.hostDao().deleteById(hostId) }

    /** Confirms [fingerprint] for [hostId], as the host list's trust sheet does. */
    fun trustHostKey(hostId: Long, fingerprint: String) = runBlocking {
        val host = requireNotNull(db.hostDao().getById(hostId))
        db.hostDao().update(
            host.copy(
                trustedHostKeyAlgorithm = RoomTrustStore.FINGERPRINT_DIGEST,
                trustedHostKeySha256 = fingerprint,
            ),
        )
    }

    fun close() = db.close()
}
