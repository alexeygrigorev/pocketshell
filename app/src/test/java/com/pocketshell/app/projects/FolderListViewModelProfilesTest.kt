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
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
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

    private val testDispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())

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
    fun pickerOpenRetriesTransientBindFailure() = runTest(testDispatcher) {
        val gateway = FakeProfilesGateway(
            ProfilesResult.ConnectFailed(IllegalStateException("transient bind failure")),
        )
        val vm = buildViewModel(gateway)
        try {
            bind(vm)
            assertTrue(vm.claudeProfiles.value.isEmpty())

            gateway.result = ProfilesResult.Profiles(
                listOf(
                    RemoteProfile("Claude", "claude", null, default = true),
                    RemoteProfile("Claude (Z.AI)", "claude", "/home/u/.zlaude", default = false),
                ),
            )
            vm.refreshProfilesForPicker()

            assertEquals(
                listOf(
                    ClaudeProfile("Claude", default = true),
                    ClaudeProfile("Claude (Z.AI)", default = false),
                ),
                vm.claudeProfiles.value,
            )
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

    @Test
    fun bindEnumeratesTreeOnlyAfterEngineRegistryReadCompletes() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val allowEngineReadToComplete = CompletableDeferred<Unit>()
        val engineGateway = FakeEnginesGateway(
            result = EnginesResult.Engines(
                listOf(
                    RemoteEngine(
                        id = "custom-codex",
                        familyId = "codex",
                        family = com.pocketshell.uikit.model.SessionAgentKind.Codex,
                        label = "Custom Codex",
                    ),
                ),
            ),
            eventLog = events,
            completionGate = allowEngineReadToComplete,
        )
        val folderGateway = NoopFolderListGateway(
            rawListSessions = listOf(
                tmuxListSessionsRow(
                    sessionName = "custom-codex-session",
                    rawKindId = "custom-codex",
                    cwd = "/home/test/custom-codex",
                    sessionId = "\$42",
                ),
                tmuxListSessionsRow(
                    sessionName = "unknown-engine-session",
                    rawKindId = "unknown-engine",
                    cwd = "/home/test/unknown-engine",
                    sessionId = "\$43",
                ),
                tmuxListSessionsRow(
                    sessionName = "foreign-session",
                    rawKindId = null,
                    cwd = "/home/test/foreign",
                    sessionId = "\$44",
                ),
            ).joinToString("\n"),
            familyForRawId = engineGateway::declaredFamilyForRawId,
            eventLog = events,
        )
        val vm = buildViewModel(
            profilesGateway = null,
            enginesGateway = engineGateway,
            folderGateway = folderGateway,
        )

        try {
            bind(vm)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "the first tree enumeration must wait on the in-flight registry read",
                0,
                folderGateway.listCallCount,
            )
            assertEquals(listOf("engine-read-start"), events)

            allowEngineReadToComplete.complete(Unit)
            testDispatcher.scheduler.advanceUntilIdle()

            val registryCompletedAt = events.indexOf("engine-read-complete")
            val firstTreeEnumerationAt = events.indexOf("tree-enumeration")
            assertTrue("the engine read must complete", registryCompletedAt >= 0)
            assertTrue(
                "the first tree enumeration must occur; events=$events, listCalls=${folderGateway.listCallCount}",
                firstTreeEnumerationAt >= 0,
            )
            assertTrue(
                "bind must enumerate only after the registry result is available",
                registryCompletedAt < firstTreeEnumerationAt,
            )
            assertEquals(
                listOf("custom-codex" to com.pocketshell.uikit.model.SessionAgentKind.Codex),
                vm.engines.value.map { it.rawId to it.family },
            )
            val ready = vm.state.value as? FolderListUiState.Ready
                ?: error("expected a Ready state, was ${vm.state.value}")
            val treeSessions = ready.treeRoots
                .flatMap { it.folders }
                .flatMap { it.sessions }

            // Mutation proof: if parseRow stops retaining the raw option id,
            // the custom and unknown id assertions fail even when family
            // mapping still works. If parseListSessionsRows stops invoking
            // familyForRawId, the custom row becomes Unknown in BOTH
            // projections instead of the declared Codex family.
            val expectedRows: List<Triple<String, String?, SessionAgentKind>> = listOf(
                Triple("custom-codex-session", "custom-codex", SessionAgentKind.Codex),
                Triple("unknown-engine-session", "unknown-engine", SessionAgentKind.Unknown),
                Triple("foreign-session", null, SessionAgentKind.Shell),
            )
            for ((sessionName, rawKindId, family) in expectedRows) {
                val flatSession = ready.flatSessions.single { it.sessionName == sessionName }
                val treeSession = treeSessions.single { it.sessionName == sessionName }
                assertEquals("$sessionName flat raw id", rawKindId, flatSession.recordedKindId)
                assertEquals("$sessionName flat family", family, flatSession.agentKind)
                assertEquals("$sessionName tree raw id", rawKindId, treeSession.recordedKindId)
                assertEquals("$sessionName tree family", family, treeSession.agentKind)
            }
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun bindAndPickerOpenTriggerEngineReadThroughWithoutPolling() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val gateway = FakeEnginesGateway(
            result = EnginesResult.Engines(
                listOf(
                    RemoteEngine(
                        id = "custom-codex",
                        familyId = "codex",
                        family = com.pocketshell.uikit.model.SessionAgentKind.Codex,
                        label = "Custom Codex",
                    ),
                ),
            ),
            eventLog = events,
        )
        val vm = buildViewModel(profilesGateway = null, enginesGateway = gateway)

        try {
            bind(vm)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, gateway.callCount)
            assertEquals(1, events.count { it == "engine-read-complete" })
            assertEquals(listOf("custom-codex"), vm.engines.value.map { it.rawId })

            gateway.result = EnginesResult.Failed("temporary")
            vm.refreshEnginesForPicker()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(2, gateway.callCount)
            assertEquals(2, events.count { it == "engine-read-complete" })
            assertEquals(listOf("custom-codex"), vm.engines.value.map { it.rawId })

            testDispatcher.scheduler.advanceTimeBy(60_000L)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("no engine polling after bind", 2, gateway.callCount)
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun successfulPickerEngineRefreshReconcilesAnAlreadyBoundTree() = runTest(testDispatcher) {
        val engineGateway = FakeEnginesGateway(
            EnginesResult.Engines(
                listOf(
                    RemoteEngine(
                        id = "custom-codex",
                        familyId = "codex",
                        family = com.pocketshell.uikit.model.SessionAgentKind.Codex,
                        label = "Custom Codex",
                    ),
                ),
            ),
        )
        val folderGateway = NoopFolderListGateway()
        val vm = buildViewModel(
            profilesGateway = null,
            enginesGateway = engineGateway,
            folderGateway = folderGateway,
        )

        try {
            bind(vm)
            testDispatcher.scheduler.advanceUntilIdle()
            val callsAfterBind = folderGateway.listCallCount

            vm.refreshEnginesForPicker()
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue("a completed picker refresh must re-run the tree projection", folderGateway.listCallCount > callsAfterBind)
        } finally {
            vm.stopPolling()
        }
    }

    @Test
    fun disabledUnavailableEngineKeepsExistingSessionInFlatAndTreeRows() = runTest(testDispatcher) {
        val availableEngine = RemoteEngine(
            id = "custom-codex",
            familyId = "codex",
            family = com.pocketshell.uikit.model.SessionAgentKind.Codex,
            label = "Custom Codex",
        )
        val engineGateway = FakeEnginesGateway(
            result = EnginesResult.Engines(
                listOf(availableEngine),
            ),
        )
        val folderGateway = NoopFolderListGateway(
            rawListSessions = tmuxListSessionsRow(
                sessionName = "existing-custom-session",
                rawKindId = "custom-codex",
                cwd = "/home/test/project",
                sessionId = "\$42",
            ),
            familyForRawId = engineGateway::declaredFamilyForRawId,
        )
        val vm = buildViewModel(
            profilesGateway = null,
            enginesGateway = engineGateway,
            folderGateway = folderGateway,
        )

        try {
            bind(vm)
            testDispatcher.scheduler.advanceUntilIdle()

            val callsAfterBind = folderGateway.listCallCount
            engineGateway.result = EnginesResult.Engines(
                listOf(
                    availableEngine.copy(
                        label = "Custom Codex (not installed)",
                        enabled = false,
                        available = false,
                        availableForCreate = false,
                    ),
                ),
            )
            vm.refreshEnginesForPicker()
            testDispatcher.scheduler.advanceUntilIdle()

            val engine = vm.engines.value.single()
            assertEquals("custom-codex", engine.rawId)
            assertEquals(com.pocketshell.uikit.model.SessionAgentKind.Codex, engine.family)
            assertEquals(false, engine.enabled)
            assertEquals(false, engine.available)
            assertEquals(false, engine.availableForCreate)
            assertTrue(
                "a completed picker refresh must re-enumerate the retained session",
                folderGateway.listCallCount > callsAfterBind,
            )

            // This assertion is after the disabled/unavailable refresh, not
            // just the initial bind. If the refresh path drops the raw id, or
            // if familyForRawId is no longer supplied to the production
            // parser, this retained custom session reddens in both views.
            val ready = vm.state.value as? FolderListUiState.Ready
                ?: error("expected a Ready state, was ${vm.state.value}")
            val flatRow = ready.flatSessions.single { it.sessionName == "existing-custom-session" }
            assertEquals("custom-codex", flatRow.recordedKindId)
            assertEquals(
                com.pocketshell.uikit.model.SessionAgentKind.Codex,
                flatRow.agentKind,
            )

            val treeRow = ready.treeRoots
                .flatMap { it.folders }
                .flatMap { it.sessions }
                .single { it.sessionName == "existing-custom-session" }
            assertEquals("custom-codex", treeRow.recordedKindId)
            assertEquals(
                com.pocketshell.uikit.model.SessionAgentKind.Codex,
                treeRow.agentKind,
            )
        } finally {
            vm.stopPolling()
        }
    }

    private fun TestScope.buildViewModel(
        profilesGateway: ProfilesGateway?,
        enginesGateway: EnginesGateway? = null,
        folderGateway: NoopFolderListGateway = NoopFolderListGateway(),
    ): FolderListViewModel =
        FolderListViewModel(
            gateway = folderGateway,
            hostDao = MapHostDao(HOST),
            projectRootDao = EmptyProjectRootDao(),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector {
                    Result.failure(IllegalStateException("prewarm disabled for profiles test"))
                },
                scope = this,
                idleTtlMillis = 0L,
                connectTimeoutContext = testDispatcher,
                abortTimeoutContext = testDispatcher,
                nowMillis = { testScheduler.currentTime },
            ),
            forwardingController = ForwardingController(ApplicationProvider.getApplicationContext()),
            profilesGateway = profilesGateway,
            enginesGateway = enginesGateway,
            attachLifecycle = false,
        ).also {
            it.ioDispatcher = testDispatcher as CoroutineDispatcher
            it.treeDispatcher = testDispatcher as CoroutineDispatcher
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

    private class FakeProfilesGateway(var result: ProfilesResult) : ProfilesGateway {
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

    private class FakeEnginesGateway(
        var result: EnginesResult,
        private val eventLog: MutableList<String>? = null,
        private val completionGate: CompletableDeferred<Unit>? = null,
    ) : EnginesGateway {
        var callCount: Int = 0

        fun declaredFamilyForRawId(rawId: String?): SessionAgentKind? =
            (result as? EnginesResult.Engines)
                ?.engines
                ?.firstOrNull { it.rawId == rawId }
                ?.family

        override suspend fun listEngines(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): EnginesResult {
            callCount += 1
            eventLog?.add("engine-read-start")
            completionGate?.await()
            eventLog?.add("engine-read-complete")
            return result
        }
    }

    private class NoopFolderListGateway(
        private val rows: List<FolderSessionRow> = emptyList(),
        private val rawListSessions: String? = null,
        private val familyForRawId: (String?) -> SessionAgentKind? = { null },
        private val eventLog: MutableList<String>? = null,
    ) : FolderListGateway {
        var listCallCount: Int = 0

        // Keep this fake at the same raw-wire seam as the production gateway.
        // Prebuilt FolderSessionRow fixtures would stay green if raw-id
        // retention or the familyForRawId callback were accidentally removed.
        private fun parsedRawRows(): List<FolderSessionRow> =
            rawListSessions?.let { stdout ->
                SshFolderListGateway.parseListSessionsRows(
                    stdout = stdout,
                    familyForRawId = familyForRawId,
                )
            }.orEmpty()

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            listCallCount += 1
            eventLog?.add("tree-enumeration")
            return FolderListResult.Sessions(rows = rows + parsedRawRows())
        }

        override suspend fun createSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            cwd: String,
            startCommand: String?,
            namePolicy: SessionNamePolicy,
        ): Result<SessionCreateOutcome> = error("not used")

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

        /** Build one current-format tmux `list-sessions` row for the fake. */
        fun tmuxListSessionsRow(
            sessionName: String,
            rawKindId: String?,
            cwd: String,
            sessionId: String,
            activity: Long = 100L,
        ): String = listOf(
            sessionName,
            sessionId,
            "1", // session_created
            activity.toString(),
            "0", // session_attached
            rawKindId.orEmpty(), // @ps_agent_kind
            "", // @ps_agent_profile
            "", // @ps_agent_state
            "", // @ps_agent_state_updated_at
            cwd,
        ).joinToString("::")

        val HOST = HostEntity(
            id = 707L,
            name = "host",
            hostname = "10.0.0.7",
            username = "tester",
            keyId = 1L,
        )
    }
}
