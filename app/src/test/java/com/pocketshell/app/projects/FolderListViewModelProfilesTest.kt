package com.pocketshell.app.projects

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the issue #718 (S2) profile-fetch wiring in
 * [FolderListViewModel].
 *
 * On bind the view model fetches the host-discovered agent profiles via
 * [ProfilesGateway] and projects them onto the picker's [claudeProfiles] /
 * [codexProfiles] flows. These tests drive that path with a fake gateway and
 * assert the flows reflect the fetch (split by engine, default-flag carried)
 * and the failure/unavailable cases collapse to empty.
 *
 * [MainDispatcherRule] installs an [UnconfinedTestDispatcher] as `Main`, so
 * `viewModelScope` launches run synchronously; the same dispatcher backs the
 * VM's `ioDispatcher`, so the profile fetch has settled by the time `bind`
 * returns under `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FolderListViewModelProfilesTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey = 0

    @After
    fun tearDown() {
        viewModelStore.clear()
    }

    @Test
    fun bindFetchesAndSplitsProfilesByEngine() = runTest(testDispatcher) {
        val gateway = FakeProfilesGateway(
            ProfilesResult.Profiles(
                listOf(
                    RemoteProfile("Claude", "claude", null, default = true),
                    RemoteProfile("Claude (Z.AI)", "claude", "/home/u/.zlaude", default = false),
                    RemoteProfile("Codex", "codex", null, default = true),
                ),
            ),
        )
        val vm = buildViewModel(gateway)

        try {
            bind(vm)

            assertEquals(HOST.id, gateway.lastHostId)
            assertEquals(KEY_PATH, gateway.lastKeyPath)
            assertEquals(
                listOf(ClaudeProfile("Claude", default = true), ClaudeProfile("Claude (Z.AI)", default = false)),
                vm.claudeProfiles.value,
            )
            assertEquals(
                listOf(CodexProfile("Codex", default = true)),
                vm.codexProfiles.value,
            )
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun toolUnavailableLeavesProfilesEmpty() = runTest(testDispatcher) {
        val gateway = FakeProfilesGateway(ProfilesResult.ToolUnavailable)
        val vm = buildViewModel(gateway)
        try {
            bind(vm)
            assertTrue(vm.claudeProfiles.value.isEmpty())
            assertTrue(vm.codexProfiles.value.isEmpty())
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun connectFailureLeavesProfilesEmpty() = runTest(testDispatcher) {
        val gateway = FakeProfilesGateway(ProfilesResult.ConnectFailed(IllegalStateException("no net")))
        val vm = buildViewModel(gateway)
        try {
            bind(vm)
            assertTrue(vm.claudeProfiles.value.isEmpty())
            assertTrue(vm.codexProfiles.value.isEmpty())
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun noGatewayKeepsProfilesEmpty() = runTest(testDispatcher) {
        // Null gateway = unit-test path with no profile fetch.
        val vm = buildViewModel(profilesGateway = null)
        try {
            bind(vm)
            assertTrue(vm.claudeProfiles.value.isEmpty())
            assertTrue(vm.codexProfiles.value.isEmpty())
        } finally {
            vm.stopPolling()
        }
    }

    private fun buildViewModel(profilesGateway: ProfilesGateway?): FolderListViewModel =
        FolderListViewModel(
            gateway = NoopFolderListGateway(),
            hostDao = MapHostDao(HOST),
            projectRootDao = EmptyProjectRootDao(),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector {
                    Result.failure(IllegalStateException("prewarm disabled for profiles test"))
                },
                idleTtlMillis = 0L,
            ),
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
            profilesGateway = profilesGateway,
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = testDispatcher as CoroutineDispatcher
            it.setProcessStartedForTest(true)
            viewModelStore.put("FolderListViewModel-${nextViewModelKey++}", it)
        }

    private fun bind(vm: FolderListViewModel) {
        vm.bind(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            passphrase = null,
        )
    }

    private class FakeProfilesGateway(private val result: ProfilesResult) : ProfilesGateway {
        var lastHostId: Long = -1L
        var lastKeyPath: String = ""

        override suspend fun listProfiles(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            engine: String?,
        ): ProfilesResult {
            lastHostId = host.id
            lastKeyPath = keyPath
            return result
        }
    }

    private class NoopFolderListGateway : FolderListGateway {
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

        override suspend fun killSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
        ): Result<Unit> = error("not used")
    }

    private class MapHostDao(vararg hosts: HostEntity) : HostDao {
        private val hostsById = hosts.associateBy { it.id }

        override fun getAll(): Flow<List<HostEntity>> = flowOf(hostsById.values.toList())
        override suspend fun getById(id: Long): HostEntity? = hostsById[id]
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(hostsById.values.toList())
        override suspend fun insert(host: HostEntity): Long = error("not used")
        override suspend fun update(host: HostEntity) = error("not used")
        override suspend fun delete(host: HostEntity) = error("not used")
        override suspend fun deleteById(id: Long) = error("not used")
    }

    private class EmptyProjectRootDao : ProjectRootDao {
        override fun getByHostId(hostId: Long): Flow<List<ProjectRootEntity>> = flowOf(emptyList())
        override suspend fun insert(root: ProjectRootEntity): Long = error("not used")
        override suspend fun update(root: ProjectRootEntity) = error("not used")
        override suspend fun delete(root: ProjectRootEntity) = error("not used")
    }

    private companion object {
        const val KEY_PATH = "/tmp/pocketshell-profiles-test-key"
        val HOST = HostEntity(
            id = 707L,
            name = "host",
            hostname = "10.0.0.7",
            username = "tester",
            keyId = 1L,
        )
    }
}
