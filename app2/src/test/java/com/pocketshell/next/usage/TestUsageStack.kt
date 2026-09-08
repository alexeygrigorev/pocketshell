package com.pocketshell.next.usage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.transport.ExecResult
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.core.usage.PocketshellUsageJsonParser
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.connect.FakeHostConnectionFactory
import com.pocketshell.next.connect.RoomTrustStore
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.hostcli.asRemoteExec
import com.pocketshell.next.settings.SettingsRepository
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

    val settings = SettingsRepository(context)

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

    /** The production binding, verbatim (see `AppModule.provideHostCliClientFactory`). */
    val clients = HostCliClientFactory { connection -> HostCliClient(connection.asRemoteExec()) }

    /**
     * Exec rules applied to every connection the fake factory produces.
     *
     * The registry dials LAZILY, so a test cannot hold the connection and
     * script it up front; each `script*` call appends a rule here and they are
     * all applied at dial time. A list rather than one lambda because a
     * session-focused pill test scripts TWO commands (`usage --json` and
     * `sessions list --json`) and neither may clobber the other.
     */
    private val execRules = mutableListOf<(FakeHostConnection) -> Unit>()

    init {
        factory.script = { connection -> execRules.forEach { rule -> rule(connection) } }
    }

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
        script("pocketshell usage --json", stdout, exitCode, stderr)
    }

    /**
     * Scripts every future dial's `pocketshell sessions list --json` reply —
     * the listing [UsageGlanceViewModel] reads to find the open session's
     * aplexer-detected agent (issue #2579).
     */
    fun scriptSessions(stdout: String, exitCode: Int = 0, stderr: String = "") {
        script("pocketshell sessions list --json", stdout, exitCode, stderr)
    }

    private fun script(command: String, stdout: String, exitCode: Int, stderr: String) {
        execRules += { connection: FakeHostConnection ->
            connection.onExec(command, ExecResult(exitCode, stdout, stderr, false))
        }
    }

    fun close() = db.close()
}
