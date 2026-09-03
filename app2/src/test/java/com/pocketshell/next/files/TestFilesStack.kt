package com.pocketshell.next.files

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.transport.ExecResult
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.core.transport.FakeSftpChannel
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.connect.FakeHostConnectionFactory
import com.pocketshell.next.connect.RoomTrustStore
import com.pocketshell.next.nav.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The whole file-screen stack assembled for a host-JVM test: a real in-memory
 * Room database, the real [RoomTrustStore], the real [ConnectionsRegistry] and
 * the real ViewModels — with only the sshj dial swapped for
 * `core-transport`'s scripted [FakeHostConnection] and its in-memory
 * [FakeSftpChannel].
 *
 * Everything except the socket is production code, deliberately: a test that
 * also stubbed the registry could not tell whether the file screens re-use the
 * host's one connection or open their own, and a test that stubbed
 * `SftpChannel` with a hand-written double could not tell whether an over-size
 * read produces the same typed failure a real server does.
 *
 * The connection is dialled LAZILY (a screen asks the registry for it), so
 * [seedSftp] is applied at dial time through [FakeHostConnectionFactory.script]
 * rather than up front — set it before the first `refresh()`/`load()`.
 */
class TestFilesStack(
    homeDirectory: String = "/home/testuser",
    presentedFingerprint: String? = null,
) {

    val db: AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppDatabase::class.java,
    )
        .allowMainThreadQueries()
        // Same reason as TestConnectStack: keep Room off the ArchTaskExecutor
        // IO thread so `advanceUntilIdle()` means "the read finished", not
        // "the read was handed to another thread".
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

    /** Applied to the in-memory filesystem the moment a connection is dialled. */
    var seedSftp: (FakeSftpChannel) -> Unit = {}

    private var live: FakeHostConnection? = null

    init {
        factory.script = { connection ->
            // The explorer resolves "no path argument" by asking the host where
            // home is; without this the fake answers exit 127 and the screen
            // would fall back to the root.
            connection.onExec("pwd", ExecResult(0, "$homeDirectory\n", "", false))
            live = connection
            seedSftp(connection.sftpFixture())
        }
    }

    /** The connection the registry dialled. Fails loudly before the first dial. */
    val connection: FakeHostConnection
        get() = requireNotNull(live) { "nothing has dialled yet — call refresh()/load() first" }

    /** The host's in-memory filesystem, as the code under test sees it. */
    val sftp: FakeSftpChannel get() = connection.sftpFixture()

    /** Inserts an `ssh_keys` row + a `hosts` row, returning the host id. */
    fun seedHost(trustedHostKeySha256: String? = null): Long = runBlocking {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "files-key", privateKeyPath = "/dev/null"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "fixture",
                hostname = "10.0.2.2",
                port = 2222,
                username = "testuser",
                keyId = keyId,
                trustedHostKeyAlgorithm = trustedHostKeySha256?.let { "SHA256" },
                trustedHostKeySha256 = trustedHostKeySha256,
            ),
        )
    }

    fun savedState(hostId: Long, path: String? = null): SavedStateHandle =
        SavedStateHandle(
            buildMap {
                put(Destination.ARG_HOST_ID, hostId)
                if (path != null) put(Destination.ARG_PATH, path)
            },
        )

    fun close() = db.close()
}
