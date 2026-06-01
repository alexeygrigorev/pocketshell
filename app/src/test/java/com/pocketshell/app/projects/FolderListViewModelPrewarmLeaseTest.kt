package com.pocketshell.app.projects

import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelPrewarmLeaseTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun stopPollingReleasesPrewarmLeaseIntoLeaseManagerTtl() = runTest {
        val session = FakeSshSession()
        val connector = CountingLeaseConnector(session)
        val manager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 1_000L,
        )
        val vm = FolderListViewModel(
            gateway = EmptyFolderListGateway(),
            hostDao = FakeHostDao(HOST),
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = manager,
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
        )

        vm.bind(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            passphrase = null,
        )
        runCurrent()

        assertEquals(1, connector.connectCount)
        assertEquals("10.0.2.2", connector.targets.single().leaseKey.host)
        assertFalse(session.closed)

        vm.stopPolling()
        advanceTimeBy(FolderListViewModel.WARM_RELEASE_DELAY_MS - 1)
        runCurrent()
        assertFalse("prewarm lease should stay held during the existing release delay", session.closed)

        advanceTimeBy(1)
        runCurrent()
        assertFalse("released prewarm lease should remain idle until the lease TTL expires", session.closed)

        advanceTimeBy(999)
        runCurrent()
        assertFalse(session.closed)

        advanceTimeBy(1)
        runCurrent()
        assertTrue("lease manager TTL should close the released prewarm session", session.closed)
    }

    @Test
    fun cancelledPrewarmAfterAcquireReleasesUnassignedLease() = runTest {
        val session = FakeSshSession()
        val connector = CountingLeaseConnector(session)
        val manager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 0L,
        )
        val vm = FolderListViewModel(
            gateway = EmptyFolderListGateway(),
            hostDao = FakeHostDao(HOST),
            projectRootDao = FakeProjectRootDao(),
            sshLeaseManager = manager,
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
        )
        vm.warmLeaseAcquiredForTest = {
            throw CancellationException("cancelled after prewarm acquire")
        }

        vm.bind(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            passphrase = null,
        )
        runCurrent()

        assertEquals(1, connector.connectCount)
        assertTrue("unassigned prewarm lease should be released on cancellation", session.closed)
    }

    private class CountingLeaseConnector(
        private val session: FakeSshSession,
    ) : SshLeaseConnector {
        val targets: MutableList<SshLeaseTarget> = mutableListOf()
        val connectCount: Int get() = targets.size

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            targets += target
            return Result.success(session)
        }
    }

    private class EmptyFolderListGateway : FolderListGateway {
        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult = FolderListResult.Sessions(rows = emptyList())

        override suspend fun createSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            cwd: String,
            startCommand: String?,
        ): Result<String> = error("not used")

        override suspend fun createEmptyProject(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            parentPath: String,
            folderName: String,
        ): Result<String> = error("not used")

        override suspend fun importFile(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            folderPath: String,
            payload: FolderImportPayload,
        ): Result<String> = error("not used")
    }

    private class FakeHostDao(private val host: HostEntity) : HostDao {
        override fun getAll(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun getById(id: Long): HostEntity? = host.takeIf { it.id == id }
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun insert(host: HostEntity): Long = error("not used")
        override suspend fun update(host: HostEntity) = error("not used")
        override suspend fun delete(host: HostEntity) = error("not used")
        override suspend fun deleteById(id: Long) = error("not used")
    }

    private class FakeProjectRootDao : ProjectRootDao {
        private val roots = MutableStateFlow<List<ProjectRootEntity>>(emptyList())
        override fun getByHostId(hostId: Long): Flow<List<ProjectRootEntity>> = roots
        override suspend fun insert(root: ProjectRootEntity): Long = error("not used")
        override suspend fun update(root: ProjectRootEntity) = error("not used")
        override suspend fun delete(root: ProjectRootEntity) = error("not used")
    }

    private class FakeSshSession : SshSession {
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            error("not used")

        override fun tail(path: String, onLine: (String) -> Unit) =
            error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val KEY_PATH: String = "/tmp/pocketshell-test-key"
        val HOST: HostEntity = HostEntity(
            id = 42L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
    }
}
