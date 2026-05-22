package com.pocketshell.app.portfwd

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class PortForwardPanelViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun enablingAutoForward_connectsAndPublishesTunnelRows() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.autoForwardEnabled)
        assertEquals(PortForwardConnectionState.Connected, state.connectionState)
        assertEquals(1, state.tunnels.size)
        val tunnel = state.tunnels.single()
        assertEquals(3000, tunnel.remotePort)
        assertEquals(3000, tunnel.localPort)
        assertEquals("node", tunnel.process)
        assertEquals(com.pocketshell.core.portfwd.TunnelInfo.Status.FORWARDING, tunnel.status)
        assertEquals(listOf(3000), session.openedForwards.map { it.remotePort })

        viewModel.setAutoForwardEnabled(false)
        runCurrent()
        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun disablingAutoForwardStopsForwarderAndClosesSession() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()
        assertNotNull(session.openedForwards.singleOrNull())

        viewModel.setAutoForwardEnabled(false)
        runCurrent()

        assertFalse(viewModel.state.value.autoForwardEnabled)
        assertTrue(session.closed)
        assertFalse(session.openedForwards.single().isActive)
        assertEquals(emptyList<com.pocketshell.core.portfwd.TunnelInfo>(), viewModel.state.value.tunnels)
    }

    @Test
    fun failedConnectionSurfacesErrorAndLeavesToggleOff() = runTest {
        val hostId = insertHost()
        val viewModel = newViewModel(FakeConnector(Result.failure(RuntimeException("no route"))))

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        val state = viewModel.state.value
        assertFalse(state.autoForwardEnabled)
        assertEquals(PortForwardConnectionState.Error, state.connectionState)
        assertEquals("no route", state.error)
    }

    @Test
    fun loadingDifferentHostStopsExistingForwarderAndSession() = runTest {
        val hostA = insertHost(name = "a", keyPath = "/tmp/a", maxAutoPort = 4000, skipPortsBelow = 1000)
        val hostB = insertHost(name = "b", keyPath = "/tmp/b", maxAutoPort = 5000, skipPortsBelow = 1000)
        val sessionA = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val sessionB = FakeSshSession(
            ssOutput = "127.0.0.1:4000 users:((\"python\",pid=44,fd=3))\n",
        )
        val connector = QueueConnector(listOf(Result.success(sessionA), Result.success(sessionB)))
        val viewModel = newViewModel(connector)

        viewModel.load(hostA, "/tmp/a")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()
        assertTrue(viewModel.state.value.autoForwardEnabled)

        viewModel.load(hostB, "/tmp/b")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertTrue(sessionA.closed)
        assertFalse(sessionA.openedForwards.single().isActive)
        assertTrue(viewModel.state.value.autoForwardEnabled)
        assertEquals("b", viewModel.state.value.host?.name)
        assertEquals(listOf("/tmp/a", "/tmp/b"), connector.keys)
        assertFalse(sessionB.closed)

        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun sameHostReloadWithNewKeyUsesNewKeyPath() = runTest {
        val hostId = insertHost(keyPath = "/tmp/db-key")
        val session = FakeSshSession(ssOutput = "")
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/old")
        runCurrent()
        viewModel.load(hostId, "/tmp/new")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertEquals(listOf("/tmp/new"), connector.keys)

        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun sameHostReloadWithNewPassphraseUsesNewPassphrase() = runTest {
        val hostId = insertHost(keyPath = "/tmp/key")
        val session = FakeSshSession(ssOutput = "")
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", "old".toCharArray())
        runCurrent()
        viewModel.load(hostId, "/tmp/key", "new".toCharArray())
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertEquals(listOf("new"), connector.passphrases)

        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun staleConnectCannotWinAfterPassphraseReload() = runTest {
        val hostId = insertHost(keyPath = "/tmp/key")
        val staleSession = FakeSshSession(ssOutput = "")
        val freshSession = FakeSshSession(ssOutput = "")
        val connector = NonCooperativeDeferredConnector()
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", "old".toCharArray())
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        viewModel.load(hostId, "/tmp/key", "new".toCharArray())
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        connector.completeCall(1, Result.success(freshSession))
        runCurrent()
        connector.completeCall(0, Result.success(staleSession))
        runCurrent()

        assertEquals(listOf("old", "new"), connector.passphrases)
        assertEquals(PortForwardConnectionState.Connected, viewModel.state.value.connectionState)
        assertTrue(staleSession.closed)
        assertFalse(freshSession.closed)

        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun leavePanelClearsStoredPassphrase() = runTest {
        val hostId = insertHost(keyPath = "/tmp/key")
        val session = FakeSshSession(ssOutput = "")
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", "secret".toCharArray())
        runCurrent()
        viewModel.leavePanel()
        runCurrent()
        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertEquals(listOf(null), connector.passphrases)

        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun reloadingSamePanelAfterLeaveAutostartsEnabledHost() = runTest {
        val hostId = insertHost(keyPath = "/tmp/key", enabled = true)
        val firstSession = FakeSshSession(ssOutput = "")
        val secondSession = FakeSshSession(ssOutput = "")
        val connector = QueueConnector(listOf(Result.success(firstSession), Result.success(secondSession)))
        val viewModel = newViewModel(connector)

        viewModel.load(hostId, "/tmp/key", "secret".toCharArray())
        runCurrent()
        assertEquals(PortForwardConnectionState.Connected, viewModel.state.value.connectionState)

        viewModel.leavePanel()
        runCurrent()
        viewModel.load(hostId, "/tmp/key")
        runCurrent()

        assertEquals(listOf("secret", null), connector.passphrases)
        assertEquals(PortForwardConnectionState.Connected, viewModel.state.value.connectionState)
        assertTrue(viewModel.state.value.autoForwardEnabled)
        assertTrue(firstSession.closed)
        assertFalse(secondSession.closed)

        viewModel.leavePanel()
        runCurrent()
    }

    @Test
    fun staleLoadAfterLeaveDoesNotRepopulateOrAutostart() = runTest {
        val host = testHost(id = 1, enabled = true)
        val hostDao = NonCooperativeHostDao()
        val connector = QueueConnector(listOf(Result.success(FakeSshSession(ssOutput = ""))))
        val viewModel = newViewModel(connector, hostDao = hostDao)

        viewModel.load(host.id, "/tmp/key")
        runCurrent()
        viewModel.leavePanel()
        runCurrent()
        hostDao.completeCall(0, host)
        runCurrent()

        assertEquals(PortForwardPanelState(), viewModel.state.value)
        assertEquals(emptyList<String>(), connector.keys)
    }

    @Test
    fun staleLoadCannotOverwriteNewerLoad() = runTest {
        val hostA = testHost(id = 1, name = "a", enabled = true)
        val hostB = testHost(id = 2, name = "b")
        val hostDao = NonCooperativeHostDao()
        val viewModel = newViewModel(FakeConnector(Result.success(FakeSshSession(ssOutput = ""))), hostDao = hostDao)

        viewModel.load(hostA.id, "/tmp/a")
        runCurrent()
        viewModel.load(hostB.id, "/tmp/b")
        runCurrent()
        hostDao.completeCall(1, hostB)
        runCurrent()
        hostDao.completeCall(0, hostA)
        runCurrent()

        assertEquals("b", viewModel.state.value.host?.name)
        assertFalse(viewModel.state.value.autoForwardEnabled)
        assertEquals(PortForwardConnectionState.Idle, viewModel.state.value.connectionState)
    }

    @Test
    fun enablingDuringPendingReloadDoesNotConnectPreviousHostWithNewCredentials() = runTest {
        val hostA = testHost(id = 1, name = "a")
        val hostB = testHost(id = 2, name = "b")
        val hostDao = NonCooperativeHostDao()
        val session = FakeSshSession(ssOutput = "")
        val connector = QueueConnector(listOf(Result.success(session)))
        val viewModel = newViewModel(connector, hostDao = hostDao)

        viewModel.load(hostA.id, "/tmp/a", "old".toCharArray())
        runCurrent()
        hostDao.completeCall(0, hostA)
        runCurrent()

        viewModel.load(hostB.id, "/tmp/b", "new".toCharArray())
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        assertEquals(null, viewModel.state.value.host)
        assertEquals(emptyList<String>(), connector.hosts)
        assertEquals(emptyList<String>(), connector.keys)
        assertEquals(emptyList<String?>(), connector.passphrases)
        assertFalse(session.closed)

        hostDao.completeCall(1, hostB)
        runCurrent()

        assertEquals("b", viewModel.state.value.host?.name)
        assertFalse(viewModel.state.value.autoForwardEnabled)
        assertEquals(emptyList<String>(), connector.hosts)
    }

    @Test
    fun leavePanelStopsForwarderAndClosesSession() = runTest {
        val hostId = insertHost(maxAutoPort = 4000, skipPortsBelow = 1000)
        val session = FakeSshSession(
            ssOutput = "127.0.0.1:3000 users:((\"node\",pid=42,fd=3))\n",
        )
        val viewModel = newViewModel(FakeConnector(Result.success(session)))

        viewModel.load(hostId, "/tmp/key")
        runCurrent()
        viewModel.setAutoForwardEnabled(true)
        runCurrent()

        viewModel.leavePanel()
        runCurrent()

        assertFalse(viewModel.state.value.autoForwardEnabled)
        assertEquals(PortForwardConnectionState.Idle, viewModel.state.value.connectionState)
        assertTrue(session.closed)
        assertFalse(session.openedForwards.single().isActive)
    }

    @Test
    fun formatBytesUsesCompactUnits() {
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.0 MB", formatBytes(2 * 1024 * 1024))
    }

    private suspend fun insertHost(
        name: String = "dev",
        keyPath: String = "/tmp/key",
        maxAutoPort: Int = 10_000,
        skipPortsBelow: Int = 1000,
        enabled: Boolean = false,
    ): Long {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k-$name", privateKeyPath = keyPath))
        return db.hostDao().insert(
            HostEntity(
                name = name,
                hostname = "$name.example",
                username = "alexey",
                keyId = keyId,
                maxAutoPort = maxAutoPort,
                skipPortsBelow = skipPortsBelow,
                scanIntervalSec = 5,
                enabled = enabled,
            ),
        )
    }

    private fun testHost(
        id: Long,
        name: String = "dev",
        keyId: Long = 1,
        maxAutoPort: Int = 10_000,
        skipPortsBelow: Int = 1000,
        enabled: Boolean = false,
    ): HostEntity =
        HostEntity(
            id = id,
            name = name,
            hostname = "$name.example",
            username = "alexey",
            keyId = keyId,
            maxAutoPort = maxAutoPort,
            skipPortsBelow = skipPortsBelow,
            scanIntervalSec = 5,
            enabled = enabled,
        )

    private fun newViewModel(
        connector: PortForwardConnector,
        hostDao: HostDao = db.hostDao(),
        sshKeyDao: SshKeyDao = db.sshKeyDao(),
    ): PortForwardPanelViewModel =
        PortForwardPanelViewModel(
            hostDao = hostDao,
            sshKeyDao = sshKeyDao,
            connector = connector,
        )

    private class FakeConnector(
        private val result: Result<SshSession>,
    ) : PortForwardConnector {
        override suspend fun connect(host: HostEntity, keyPath: String, passphrase: CharArray?): Result<SshSession> = result
    }

    private class QueueConnector(
        results: List<Result<SshSession>>,
    ) : PortForwardConnector {
        private val queue = ArrayDeque(results)
        val hosts = mutableListOf<String>()
        val keys = mutableListOf<String>()
        val passphrases = mutableListOf<String?>()

        override suspend fun connect(host: HostEntity, keyPath: String, passphrase: CharArray?): Result<SshSession> {
            hosts += host.name
            keys += keyPath
            passphrases += passphrase?.concatToString()
            return queue.removeFirstOrNull() ?: Result.failure(AssertionError("missing connector stub"))
        }
    }

    private class NonCooperativeDeferredConnector : PortForwardConnector {
        private val calls = mutableListOf<CompletableDeferred<Result<SshSession>>>()
        val passphrases = mutableListOf<String?>()

        override suspend fun connect(host: HostEntity, keyPath: String, passphrase: CharArray?): Result<SshSession> {
            val deferred = CompletableDeferred<Result<SshSession>>()
            calls += deferred
            passphrases += passphrase?.concatToString()
            return try {
                deferred.await()
            } catch (_: CancellationException) {
                withContext(NonCancellable) { deferred.await() }
            }
        }

        fun completeCall(index: Int, result: Result<SshSession>) {
            calls[index].complete(result)
        }
    }

    private class NonCooperativeHostDao : HostDao {
        private val calls = mutableListOf<CompletableDeferred<HostEntity?>>()

        override fun getAll(): Flow<List<HostEntity>> = flowOf(emptyList())

        override suspend fun getById(id: Long): HostEntity? {
            val deferred = CompletableDeferred<HostEntity?>()
            calls += deferred
            return try {
                deferred.await()
            } catch (_: CancellationException) {
                withContext(NonCancellable) { deferred.await() }
            }
        }

        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(emptyList())

        override suspend fun insert(host: HostEntity): Long = error("insert not used")

        override suspend fun update(host: HostEntity) {
            error("update not used")
        }

        override suspend fun delete(host: HostEntity) {
            error("delete not used")
        }

        override suspend fun deleteById(id: Long) {
            error("deleteById not used")
        }

        fun completeCall(index: Int, host: HostEntity?) {
            calls[index].complete(host)
        }
    }

    private class FakeSshSession(
        private val ssOutput: String,
    ) : SshSession {
        val openedForwards = mutableListOf<FakePortForward>()
        var closed = false
            private set

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = ssOutput, stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward {
            return FakePortForward(remoteHost, remotePort, localPort).also { openedForwards += it }
        }

        override fun startShell(): SshShell = error("shell not used")

        override fun close() {
            closed = true
            openedForwards.forEach { it.close() }
        }
    }

    private class FakePortForward(
        override val remoteHost: String,
        override val remotePort: Int,
        override val localPort: Int,
    ) : SshPortForward {
        override var isActive: Boolean = true
            private set
        override val bytesForwarded: Long = 0
        override val bytesReceived: Long = 0

        override fun close() {
            isActive = false
        }
    }
}
