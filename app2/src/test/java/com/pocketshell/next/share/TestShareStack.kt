package com.pocketshell.next.share

import android.net.Uri
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
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The share stack assembled for a host-JVM test: a real in-memory Room database,
 * the real [ConnectionsRegistry], the real [ShareUploader] and the real
 * [ContentResolverShareContentReader] — with only two things substituted, and
 * both of them outside PocketShell's code:
 *
 * - the sshj dial, by `core-transport`'s scripted [FakeHostConnection] and its
 *   in-memory [FakeSftpChannel];
 * - the source app's `ContentResolver`, by the [documents] map (a share arrives
 *   from ANOTHER app's provider, which no unit test can host).
 *
 * The point of assembling it this way rather than mocking the uploader is that
 * an upload assertion reads the bytes back out of the same filesystem the code
 * under test wrote to, and a "did it create the inbox?" assertion looks at the
 * directory rather than at a recorded call.
 *
 * Modelled on [com.pocketshell.next.files.TestFilesStack] — same reasoning,
 * same shape.
 */
class TestShareStack(
    val home: String = "/home/testuser",
    private val nowMs: () -> Long = { FIXED_NOW },
    presentedFingerprint: String? = null,
) {

    val db: AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppDatabase::class.java,
    )
        .allowMainThreadQueries()
        // Keep Room off the ArchTaskExecutor IO thread so `advanceUntilIdle()`
        // means "the read finished", not "the read was handed to another thread".
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

    /** What the "other app's" content provider exposes, keyed by URI string. */
    val documents = mutableMapOf<String, ByteArray>()

    /** Provider display names, keyed by URI string. Absent = provider says nothing. */
    val providerNames = mutableMapOf<String, String>()

    /** Set to make `openInputStream` answer null, the way a revoked grant does. */
    var openStreamFails: Boolean = false

    val reader = ContentResolverShareContentReader(
        dispatcher = Dispatchers.Unconfined,
        openStream = { uri: Uri -> openDocument(uri) },
        queryDisplayName = { uri: Uri -> providerNames[uri.toString()] },
    )

    val uploader = ShareUploader(registry = registry, content = reader, now = nowMs)

    val notifier = ShareUploadNotifier(ApplicationProvider.getApplicationContext())

    /** Applied to the connection the moment the registry dials it. */
    var onDial: (FakeHostConnection) -> Unit = {}

    /**
     * How the host answers the uploader's ONE exec (make the inbox, echo its
     * absolute path). A `var` read at call time rather than a rule registered up
     * front, so a test can script the refused-`mkdir` branch without racing the
     * default rule — [FakeHostConnection.exec] answers the FIRST matching rule,
     * so a second registration would never be reached.
     */
    var mkdirResult: ExecResult =
        ExecResult(0, "$home/${ShareUploader.INBOX_RELATIVE_PATH}\n", "", false)

    private var live: FakeHostConnection? = null

    init {
        factory.script = { connection ->
            connection.onExecMatching(
                description = "mkdir -p the inbox",
                match = { it.startsWith("mkdir -p") },
                reply = { mkdirResult },
            )
            live = connection
            onDial(connection)
        }
    }

    /** Dials the host so [sftp] can be seeded before the code under test runs. */
    suspend fun dial(hostId: Long) {
        registry.getOrConnect(hostId)
    }

    /** The connection the registry dialled. Fails loudly before the first dial. */
    val connection: FakeHostConnection
        get() = requireNotNull(live) { "nothing has dialled yet — run an upload first" }

    /** The host's in-memory filesystem, as the code under test sees it. */
    val sftp: FakeSftpChannel get() = connection.sftpFixture()

    /** The absolute inbox directory the uploader resolves. */
    val inbox: String get() = "$home/${ShareUploader.INBOX_RELATIVE_PATH}"

    /** Inserts an `ssh_keys` row + a `hosts` row, returning the host id. */
    fun seedHost(name: String = "fixture", username: String = "testuser"): Long = runBlocking {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "share-key-$name", privateKeyPath = "/dev/null"),
        )
        db.hostDao().insert(
            HostEntity(
                name = name,
                hostname = "10.0.2.2",
                port = 2222,
                username = username,
                keyId = keyId,
            ),
        )
    }

    /** Stages a document the share intent can point at, returning its URI. */
    fun seedDocument(
        uri: String,
        bytes: ByteArray,
        providerName: String? = null,
    ): Uri {
        documents[uri] = bytes
        if (providerName != null) providerNames[uri] = providerName
        return Uri.parse(uri)
    }

    fun uriItem(
        uri: String,
        bytes: ByteArray,
        displayName: String? = null,
        providerName: String? = null,
        mimeType: String? = null,
        fallbackExtension: String? = null,
    ): ShareableItem.UriItem = ShareableItem.UriItem(
        uri = seedDocument(uri, bytes, providerName),
        displayName = displayName,
        mimeType = mimeType,
        fallbackExtension = fallbackExtension,
    )

    private fun openDocument(uri: Uri): InputStream? {
        if (openStreamFails) return null
        val bytes = documents[uri.toString()] ?: return null
        return ByteArrayInputStream(bytes)
    }

    fun close() = db.close()

    companion object {
        /** 2024-05-14 09:30:12 UTC-agnostic pin: the timestamp prefix must be stable. */
        const val FIXED_NOW: Long = 1_715_679_012_000L

        /** The `yyyyMMdd-HHmmss` prefix [FIXED_NOW] renders to in the JVM's zone. */
        val fixedTimestamp: String get() = ShareUploader.timestamp(FIXED_NOW)
    }
}
