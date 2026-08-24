package com.pocketshell.app.projects

import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FolderListEngineDiscoveryTest {

    @Test
    fun refreshPopulatesRowsOnceAndDoesNotPoll() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val gateway = FakeEnginesGateway(
            EnginesResult.Engines(
                listOf(
                    RemoteEngine(
                        id = "custom-codex",
                        familyId = "codex",
                        family = SessionAgentKind.Codex,
                        label = "Custom Codex",
                        enabled = false,
                        available = false,
                        availableForCreate = false,
                    ),
                ),
            ),
        )
        val discovery = FolderListEngineDiscovery(
            enginesGateway = gateway,
            hostDao = SingleHostDao(HOST),
            scope = this,
            ioDispatcher = { dispatcher },
            isCurrentHost = { it == HOST.id },
        )

        discovery.refresh(PARAMS)
        runCurrent()
        testScheduler.advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(1, gateway.callCount)
        assertEquals(listOf("custom-codex"), discovery.engines.value.map { it.rawId })
        assertEquals(false, discovery.engines.value.single().availableForCreate)
    }

    @Test
    fun failedRefreshKeepsLastSuccessfulRowsUntilExplicitRetryCompletes() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val gateway = FakeEnginesGateway(
            EnginesResult.Engines(
                listOf(
                    RemoteEngine(
                        id = "custom-codex",
                        familyId = "codex",
                        family = SessionAgentKind.Codex,
                        label = "Custom Codex",
                    ),
                ),
            ),
        )
        val discovery = FolderListEngineDiscovery(
            enginesGateway = gateway,
            hostDao = SingleHostDao(HOST),
            scope = this,
            ioDispatcher = { dispatcher },
            isCurrentHost = { true },
        )

        discovery.refresh(PARAMS)
        runCurrent()
        gateway.result = EnginesResult.Failed("temporary")
        discovery.refresh(PARAMS)
        runCurrent()

        assertEquals(2, gateway.callCount)
        assertEquals(listOf("custom-codex"), discovery.engines.value.map { it.rawId })
        assertEquals(SessionAgentKind.Codex, discovery.engines.value.single().family)
    }

    @Test
    fun firstProjectionCanAwaitRegistryAndAppliedReadNotifiesOnce() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val deferred = CompletableDeferred<EnginesResult>()
        val gateway = FakeEnginesGateway(
            result = EnginesResult.Failed("not used"),
            deferredResult = deferred,
        )
        val appliedHosts = mutableListOf<Long>()
        val discovery = FolderListEngineDiscovery(
            enginesGateway = gateway,
            hostDao = SingleHostDao(HOST),
            scope = this,
            ioDispatcher = { dispatcher },
            isCurrentHost = { it == HOST.id },
            onRefreshApplied = appliedHosts::add,
        )

        discovery.refresh(PARAMS)
        val waiter = async { discovery.awaitLatestRefresh(HOST.id) }
        runCurrent()

        assertFalse("the first projection must wait for the registry", waiter.isCompleted)
        deferred.complete(
            EnginesResult.Engines(
                listOf(
                    RemoteEngine(
                        id = "custom-codex",
                        familyId = "codex",
                        family = SessionAgentKind.Codex,
                        label = "Custom Codex",
                    ),
                ),
            ),
        )
        runCurrent()
        waiter.await()

        assertTrue(waiter.isCompleted)
        assertEquals(listOf(HOST.id), appliedHosts)
        assertEquals(listOf("custom-codex"), discovery.engines.value.map { it.rawId })
    }

    @Test
    fun missingHostDoesNotEraseLastGoodRegistryOrReportApplied() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dao = SingleHostDao(HOST)
        val gateway = FakeEnginesGateway(
            EnginesResult.Engines(
                listOf(
                    RemoteEngine(
                        id = "custom-codex",
                        familyId = "codex",
                        family = SessionAgentKind.Codex,
                        label = "Custom Codex",
                    ),
                ),
            ),
        )
        val appliedHosts = mutableListOf<Long>()
        val discovery = FolderListEngineDiscovery(
            enginesGateway = gateway,
            hostDao = dao,
            scope = this,
            ioDispatcher = { dispatcher },
            isCurrentHost = { true },
            onRefreshApplied = appliedHosts::add,
        )

        discovery.refresh(PARAMS)
        runCurrent()
        dao.host = null
        gateway.result = EnginesResult.Failed("host disappeared")
        discovery.refresh(PARAMS)
        runCurrent()

        assertEquals(listOf("custom-codex"), discovery.engines.value.map { it.rawId })
        assertEquals(listOf(HOST.id), appliedHosts)
    }

    @Test
    fun switchingHostsClearsStaleRowsBeforeValidEmptyRegistry() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val hostB = HOST.copy(id = 2321L, hostname = "10.0.0.2321")
        val paramsB = PARAMS.copy(hostId = hostB.id, hostName = hostB.name, hostname = hostB.hostname)
        val dao = SingleHostDao(HOST)
        val gateway = FakeEnginesGateway(
            EnginesResult.Engines(
                listOf(
                    RemoteEngine(
                        id = "custom-codex",
                        familyId = "codex",
                        family = SessionAgentKind.Codex,
                        label = "Custom Codex",
                    ),
                ),
            ),
        )
        val discovery = FolderListEngineDiscovery(
            enginesGateway = gateway,
            hostDao = dao,
            scope = this,
            ioDispatcher = { dispatcher },
            isCurrentHost = { true },
        )

        discovery.refresh(PARAMS)
        runCurrent()
        dao.host = hostB
        gateway.result = EnginesResult.Engines(emptyList())
        discovery.refresh(paramsB)
        assertTrue("a host switch must not expose the old registry", discovery.engines.value.isEmpty())
        runCurrent()

        assertTrue(discovery.engines.value.isEmpty())
        assertEquals(2, gateway.callCount)
    }

    private class FakeEnginesGateway(
        var result: EnginesResult,
        var deferredResult: CompletableDeferred<EnginesResult>? = null,
    ) : EnginesGateway {
        var callCount: Int = 0

        override suspend fun listEngines(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ): EnginesResult {
            callCount += 1
            return deferredResult?.await() ?: result
        }
    }

    private class SingleHostDao(var host: HostEntity?) : HostDao {
        override fun getAll(): Flow<List<HostEntity>> = flowOf(host?.let(::listOf).orEmpty())
        override suspend fun getById(id: Long): HostEntity? = host?.takeIf { it.id == id }
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(host?.let(::listOf).orEmpty())
        override suspend fun insert(host: HostEntity): Long = error("not used")
        override suspend fun update(host: HostEntity) = error("not used")
        override suspend fun delete(host: HostEntity) = error("not used")
        override suspend fun deleteById(id: Long) = error("not used")
    }

    private companion object {
        const val KEY_PATH = "/tmp/pocketshell-engine-discovery-key"
        val HOST = HostEntity(
            id = 2320L,
            name = "host",
            hostname = "10.0.0.2320",
            username = "tester",
            keyId = 1L,
        )
        val PARAMS = BoundParams(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            passphrase = null,
        )
    }
}
