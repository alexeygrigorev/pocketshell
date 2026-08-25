package com.pocketshell.app.projects

import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy

/**
 * Focused contract tests for [TreeSyncCoordinator] (#2307).
 *
 * These are deliberately coordinator-level tests rather than command-string
 * checks.  The load-bearing mutations are documented beside each test:
 * bypassing [HostTreeModel.reconcile], treating an incomplete delta as final,
 * removing the outer timeout, converting cancellation to failure, or allowing
 * concurrent triggers would make the corresponding assertion fail.
 */
class TreeSyncCoordinatorTest {

    private val coordinators = mutableListOf<TreeSyncCoordinator>()

    @After
    fun closeCoordinators() {
        coordinators.forEach(TreeSyncCoordinator::close)
        coordinators.clear()
    }

    @Test
    fun successfulFullReconcileMutatesHeldTreeAndPublishesSuccess() = runTest {
        val remote = FakeRemote(
            fullResults = ArrayDeque(listOf(success("alpha"))),
        )
        val listener = RecordingListener()
        val coordinator = newCoordinator(remote, listener)

        coordinator.bind(PARAMS, flowOf(emptyList()))
        advanceUntilIdle()

        // Mutation caught: replacing this with a callback-only success leaves
        // the held tree empty even though the remote returned a real row.
        assertEquals(
            "fullCalls=${remote.fullCalls}, successes=${listener.successes}, failures=${listener.failures}",
            listOf("alpha"),
            coordinator.tree.sessionEntries().map { it.sessionName },
        )
        assertEquals(1, remote.fullCalls)
        assertEquals(1, listener.successes)
        assertFalse(coordinator.isRefreshing)
        coordinator.close()
    }

    @Test
    fun durableCacheHydrateIsAdvisoryAndFullProbeStillWins() = runTest {
        val remote = FakeRemote(
            fullResults = ArrayDeque(listOf(success("live"))),
            treeResult = TreeRemoteSource.TreeResult(
                nodes = listOf(
                    TreeRemoteSource.TreeNode(
                        session = "cached",
                        order = 0,
                        folderPath = "/cached",
                        collapsed = true,
                    ),
                ),
            ),
            hasDurableTree = true,
        )
        val listener = RecordingListener()
        val coordinator = newCoordinator(
            remote = remote,
            listener = listener,
            cache = FakeCache(),
        )

        coordinator.bind(PARAMS, flowOf(emptyList()))
        advanceUntilIdle()

        // Mutation caught: making hydrate authoritative would resurrect the
        // cached placeholder after the live probe and hide the current row.
        assertEquals(listOf("live"), coordinator.tree.sessionEntries().map { it.sessionName })
        assertTrue(listener.treeChanges >= 1)
    }

    @Test
    fun incompleteResumeDeltaEscalatesToAuthoritativeFullReconcile() = runTest {
        val remote = FakeRemote(
            fullResults = ArrayDeque(listOf(success("before"), success("after"))),
            hasDurableTree = true,
            deltas = ArrayDeque(
                listOf(
                    TreeRemoteSource.ReconcileDelta(
                        alive = emptyList(),
                        gone = listOf("before"),
                        added = emptyList(),
                    ),
                ),
            ),
        )
        val listener = RecordingListener()
        val coordinator = newCoordinator(remote, listener)

        coordinator.bind(PARAMS, flowOf(emptyList()))
        advanceUntilIdle()
        val fullCallsBeforeResume = remote.fullCalls

        // A resume is only eligible after the held tree is stale and a genuine
        // foreground generation occurs.
        coordinator.forceTreeStaleForTest()
        coordinator.setProcessStartedForTest(false)
        advanceUntilIdle()
        coordinator.setProcessStartedForTest(true)
        advanceUntilIdle()

        // Mutation caught: accepting a name-only `gone` delta would remove or
        // retain by display name without the authoritative generation-safe probe.
        assertEquals(fullCallsBeforeResume + 1, remote.fullCalls)
        assertEquals(listOf("after"), coordinator.tree.sessionEntries().map { it.sessionName })
        assertEquals(1, remote.deltaCalls)
    }

    @Test
    fun reconcileTimeoutSurfacesFailureAndReleasesRefreshingState() = runTest {
        val remote = FakeRemote(wedgeFull = true)
        val listener = RecordingListener()
        val coordinator = newCoordinator(
            remote = remote,
            listener = listener,
            policy = TreeSyncPolicy(
                reconcileTimeoutMs = 100L,
                hydrateTimeoutMs = 100L,
                periodicEnabled = false,
            ),
        )

        coordinator.bind(PARAMS, flowOf(emptyList()))
        advanceUntilIdle()
        advanceTimeBy(101L)
        advanceUntilIdle()

        // Mutation caught: removing withTimeoutOrNull leaves a wedged remote
        // call active and never gives the UI a retryable failure.
        assertTrue(listener.failures.single() is TreeSyncFailure.Timeout)
        assertFalse(coordinator.isRefreshing)
        assertTrue(remote.fullCancelled)
    }

    @Test
    fun partialFailurePreservesTheLastAuthoritativeTree() = runTest {
        val remote = FakeRemote(
            fullResults = ArrayDeque(
                listOf(
                    success("held"),
                    TreeSyncRemote.FullResult.Failed("partial probe failure"),
                ),
            ),
        )
        val listener = RecordingListener()
        val coordinator = newCoordinator(remote, listener)

        coordinator.bind(PARAMS, flowOf(emptyList()))
        advanceUntilIdle()
        coordinator.requestReconcile()
        advanceUntilIdle()

        // Mutation caught: clearing the held tree before a failed refresh would
        // turn a recoverable partial failure into a blank screen.
        assertEquals(listOf("held"), coordinator.tree.sessionEntries().map { it.sessionName })
        assertTrue(listener.failures.last() is TreeSyncFailure.Failed)
        assertFalse(coordinator.isRefreshing)
    }

    @Test
    fun cancellationDoesNotBecomeFailureOrApplyLateRows() = runTest {
        val fullStarted = CompletableDeferred<Unit>()
        val remote = FakeRemote(
            wedgeFull = true,
            firstCallStarted = fullStarted,
        )
        val listener = RecordingListener()
        val coordinator = newCoordinator(remote, listener)

        coordinator.bind(PARAMS, flowOf(emptyList()))
        // Wait for the real bind -> hydrate -> initial full entry point to
        // enter the remote call, then cancel that in-flight operation.  This
        // catches cancellation being turned into a failure without advancing
        // virtual time through the reconcile timeout first.
        fullStarted.await()
        coordinator.close()
        advanceUntilIdle()

        // Mutation caught: catching CancellationException as an ordinary
        // failure would show an error after stop/host teardown; applying a late
        // result would repopulate a coordinator that has been closed.
        assertTrue(remote.fullCancelled)
        assertTrue(listener.failures.isEmpty())
        assertTrue(coordinator.tree.sessionEntries().isEmpty())
    }

    @Test
    fun concurrentTriggersCancelOlderProbeAndOnlyLatestResultWins() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val remote = FakeRemote(
            fullResults = ArrayDeque(listOf(success("latest"))),
            firstCallStarted = firstStarted,
            wedgeFirstFull = true,
        )
        val listener = RecordingListener()
        val coordinator = newCoordinator(remote, listener)

        coordinator.bind(PARAMS, flowOf(emptyList()))
        firstStarted.await()
        coordinator.requestReconcile()
        advanceUntilIdle()

        // Mutation caught: launching the second trigger without cancelling or
        // fencing the first permits overlap and allows the stale first result
        // to overwrite the newer projection.
        assertEquals(1, remote.maxConcurrent)
        assertEquals(listOf("latest"), coordinator.tree.sessionEntries().map { it.sessionName })
        assertTrue(remote.firstFullCancelled)
    }

    @Test
    fun hostSwitchDoesNotReuseThePreviousWarmLeaseForTreeOrUpgrade() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val firstHostSession = HostTaggedSshSession("first-host")
        val secondHostSession = HostTaggedSshSession("second-host")
        val connector = HostRoutingConnector(
            sessions = mapOf(
                PARAMS.hostname to firstHostSession,
                OTHER_PARAMS.hostname to secondHostSession,
            ),
        )
        val leaseManager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 0L,
            connectTimeoutContext = dispatcher,
            abortTimeoutContext = dispatcher,
        )
        val treeSource = TreeRemoteSource().apply {
            remoteExecDispatcher = dispatcher
            remoteExecTimeoutMs = 1_000L
        }
        val remote = FolderListTreeSyncRemote(
            gateway = unusedProxy(),
            hostDao = unusedProxy(),
            treeRemoteSource = treeSource,
            sshLeaseManager = leaseManager,
            activeTmuxClients = null,
            scope = this,
            dispatcher = { dispatcher },
            warmSessionAwaitMs = { 1_000L },
        )

        val firstBinding = TreeSyncBinding(PARAMS, generation = 1L)
        val secondBinding = TreeSyncBinding(OTHER_PARAMS, generation = 2L)
        remote.bind(firstBinding)
        remote.ensureWarmConnected(firstBinding)
        remote.bind(secondBinding)
        remote.ensureWarmConnected(secondBinding)
        val tree = remote.getTree(secondBinding)
        val upgradeSession = remote.acquireSessionForUpgrade(secondBinding)

        // Mutation caught: returning the still-connected first lease before
        // checking BoundParams makes both the tree RPC and upgrade use the
        // previous host; the connector then records only the first dial.
        assertEquals(listOf(PARAMS.hostname, OTHER_PARAMS.hostname), connector.requestedHosts)
        assertEquals("second-host", tree.nodes.single().session)
        assertSame(secondHostSession, upgradeSession)
        assertTrue(firstHostSession.closed)
    }

    @Test
    fun queuedPersistenceAndUpgradeCannotInvokeRemoteAfterHostSwitch() = runTest {
        val remote = FakeRemote(
            fullResults = ArrayDeque(listOf(success("held"))),
        )
        val coordinator = newCoordinator(remote, RecordingListener())

        coordinator.bind(PARAMS, flowOf(emptyList()))
        advanceUntilIdle()
        remote.upsertBindings.clear()
        remote.upgradeBindings.clear()

        // Both jobs capture A but remain queued on the coordinator dispatcher.
        // Bind(B) is the only lifecycle action between scheduling and running
        // them, so the fake must record an invocation if either coordinator
        // generation fence is removed.  The fake deliberately has no stale
        // binding guard of its own.
        coordinator.persistCurrentTree()
        val upgradeResult = CompletableDeferred<SshSession?>()
        backgroundScope.launch {
            upgradeResult.complete(coordinator.acquireSessionForUpgrade(PARAMS))
        }
        coordinator.bind(OTHER_PARAMS, flowOf(emptyList()))
        advanceUntilIdle()

        // Mutation caught: removing TreeSyncCoordinator.persist's
        // isCurrent(binding) check at its remote-call boundary invokes
        // upsertTree(A) here.  This assertion therefore cannot be laundered by
        // a remote double that independently rejects stale bindings.
        assertTrue(remote.upsertBindings.isEmpty())
        assertTrue(remote.upgradeBindings.isEmpty())
        assertNull(upgradeResult.await())
        assertEquals(OTHER_PARAMS, remote.currentBinding?.params)
    }

    @Test
    fun inFlightPersistenceCannotReacquireOldLeaseAfterHostSwitch() = runTest {
        val aConnectStarted = CompletableDeferred<Unit>()
        val releaseAConnect = CompletableDeferred<Unit>()
        val firstHostSession = HostTaggedSshSession("first-host")
        val secondHostSession = HostTaggedSshSession("second-host")
        val connector = BlockingHostRoutingConnector(
            sessions = mapOf(
                PARAMS.hostname to firstHostSession,
                OTHER_PARAMS.hostname to secondHostSession,
            ),
            blockedHost = PARAMS.hostname,
            connectStarted = aConnectStarted,
            releaseConnect = releaseAConnect,
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val leaseManager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 0L,
            connectTimeoutContext = dispatcher,
            abortTimeoutContext = dispatcher,
        )
        val treeSource = TreeRemoteSource().apply {
            remoteExecDispatcher = dispatcher
            remoteExecTimeoutMs = 1_000L
        }
        val remote = FolderListTreeSyncRemote(
            gateway = unusedProxy(),
            hostDao = unusedProxy(),
            treeRemoteSource = treeSource,
            sshLeaseManager = leaseManager,
            activeTmuxClients = null,
            scope = this,
            dispatcher = { dispatcher },
            warmSessionAwaitMs = { 1_000L },
        )
        val firstBinding = TreeSyncBinding(PARAMS, generation = 1L)
        val secondBinding = TreeSyncBinding(OTHER_PARAMS, generation = 2L)

        remote.bind(firstBinding)
        val persistence = backgroundScope.launch {
            remote.upsertTree(
                firstBinding,
                listOf(
                    TreeRemoteSource.TreeNode(
                        session = "persisted",
                        order = 0,
                        folderPath = "/persisted",
                        collapsed = false,
                    ),
                ),
            )
        }
        aConnectStarted.await()

        // The A operation is suspended in the real adapter before its warm
        // lease is acquired.  B becomes current while that operation is live;
        // releasing A must not let the stale caller reacquire A or issue the
        // persistence RPC with A's session.
        remote.bind(secondBinding)
        val bWarm = backgroundScope.launch { remote.ensureWarmConnected(secondBinding) }
        val staleEnsure = backgroundScope.launch {
            // This is the delayed ensure that a fire-and-forget persistence
            // caller can reach after the host bind has already advanced.
            remote.ensureWarmConnected(firstBinding)
        }
        releaseAConnect.complete(Unit)
        advanceUntilIdle()
        persistence.join()
        staleEnsure.join()
        bWarm.join()

        // Mutation caught: removing the adapter's generation checks permits
        // a second A lease request or an A tree RPC after bind(B).  The only
        // valid post-switch connection is B, and A must never receive a tree
        // command.
        assertEquals(
            listOf(PARAMS.hostname, OTHER_PARAMS.hostname),
            connector.requestedHosts,
        )
        assertTrue(firstHostSession.execCommands.isEmpty())
        assertFalse(persistence.isCancelled)
        remote.releaseWarm()
    }

    private fun TestScope.newCoordinator(
        remote: TreeSyncRemote,
        listener: RecordingListener,
        cache: TreeSyncCache? = null,
        policy: TreeSyncPolicy = TreeSyncPolicy(periodicEnabled = false),
    ): TreeSyncCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return TreeSyncCoordinator(
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            remote = remote,
            cache = cache,
            processStarted = MutableStateFlow(true),
            dispatcher = { dispatcher },
            clock = { 10_000L },
            policy = policy,
            listener = listener,
        ).also(coordinators::add)
    }

    private class RecordingListener : TreeSyncCoordinator.Listener {
        var successes = 0
        var synchronousTreeChanges = 0
        var treeChanges = 0
        val failures = mutableListOf<TreeSyncFailure>()

        override fun onLoadingRequested() = Unit

        override fun onRefreshingChanged(refreshing: Boolean) = Unit

        override fun onTreeChanged(synchronous: Boolean) {
            treeChanges++
            if (synchronous) synchronousTreeChanges++
        }

        override fun onReconcileSuccess(result: FolderListResult.Sessions) {
            successes++
        }

        override fun onReconcileFailure(failure: TreeSyncFailure) {
            failures += failure
        }

        override fun onUnexpectedFailure(cause: Throwable) {
            failures += TreeSyncFailure.Unexpected(cause)
        }

        override fun onPayloadCliVersion(version: String) = Unit
    }

    private class FakeRemote(
        private val fullResults: ArrayDeque<TreeSyncRemote.FullResult> = ArrayDeque(),
        private val deltas: ArrayDeque<TreeRemoteSource.ReconcileDelta?> = ArrayDeque(),
        private val treeResult: TreeRemoteSource.TreeResult = TreeRemoteSource.TreeResult.Empty,
        override val hasDurableTree: Boolean = false,
        private val wedgeFull: Boolean = false,
        private val firstCallStarted: CompletableDeferred<Unit>? = null,
        private val wedgeFirstFull: Boolean = false,
    ) : TreeSyncRemote {
        val eventFlow = MutableSharedFlow<TreeSyncEvent>(extraBufferCapacity = 8)
        var fullCalls = 0
            private set
        var deltaCalls = 0
            private set
        var maxConcurrent = 0
            private set
        var fullCancelled = false
            private set
        var firstFullCancelled = false
            private set
        val upsertBindings = mutableListOf<TreeSyncBinding>()
        val upgradeBindings = mutableListOf<TreeSyncBinding>()
        var currentBinding: TreeSyncBinding? = null
            private set
        private var concurrent = 0

        override fun bind(binding: TreeSyncBinding) {
            currentBinding = binding
        }

        override fun events(binding: TreeSyncBinding): Flow<TreeSyncEvent> = eventFlow

        override suspend fun ensureWarmConnected(binding: TreeSyncBinding) = Unit

        override suspend fun fullReconcile(
            binding: TreeSyncBinding,
            watchedFolders: List<ProjectRootEntity>,
        ): TreeSyncRemote.FullResult {
            fullCalls++
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            try {
                if (fullCalls == 1) firstCallStarted?.complete(Unit)
                if (wedgeFull || (wedgeFirstFull && fullCalls == 1)) {
                    try {
                        awaitCancellation()
                    } catch (cancelled: CancellationException) {
                        if (fullCalls == 1) firstFullCancelled = true
                        fullCancelled = true
                        throw cancelled
                    }
                }
                return fullResults.removeFirstOrNull()
                    ?: TreeSyncRemote.FullResult.Sessions(
                        FolderListResult.Sessions(rows = emptyList()),
                    )
            } finally {
                concurrent--
            }
        }

        override suspend fun getTree(binding: TreeSyncBinding): TreeRemoteSource.TreeResult = treeResult

        override suspend fun reconcileTree(binding: TreeSyncBinding): TreeRemoteSource.ReconcileDelta? {
            deltaCalls++
            return deltas.removeFirstOrNull()
        }

        override suspend fun upsertTree(
            binding: TreeSyncBinding,
            nodes: List<TreeRemoteSource.TreeNode>,
        ): Boolean {
            upsertBindings += binding
            return true
        }

        override suspend fun acquireSessionForUpgrade(binding: TreeSyncBinding): SshSession? {
            upgradeBindings += binding
            return null
        }

        override suspend fun releaseWarm() = Unit
    }

    private class HostRoutingConnector(
        private val sessions: Map<String, HostTaggedSshSession>,
    ) : SshLeaseConnector {
        val requestedHosts = mutableListOf<String>()

        override suspend fun connect(target: com.pocketshell.core.ssh.SshLeaseTarget): Result<SshSession> {
            requestedHosts += target.leaseKey.host
            return Result.success(sessions.getValue(target.leaseKey.host))
        }
    }

    private class BlockingHostRoutingConnector(
        private val sessions: Map<String, HostTaggedSshSession>,
        private val blockedHost: String,
        private val connectStarted: CompletableDeferred<Unit>,
        private val releaseConnect: CompletableDeferred<Unit>,
    ) : SshLeaseConnector {
        val requestedHosts = mutableListOf<String>()

        override suspend fun connect(target: com.pocketshell.core.ssh.SshLeaseTarget): Result<SshSession> {
            val host = target.leaseKey.host
            requestedHosts += host
            if (host == blockedHost && requestedHosts.count { it == blockedHost } == 1) {
                connectStarted.complete(Unit)
                releaseConnect.await()
            }
            return Result.success(sessions.getValue(host))
        }
    }

    private class HostTaggedSshSession(
        private val hostTag: String,
    ) : SshSession {
        var closed = false
        val execCommands = mutableListOf<String>()

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return ExecResult(
                stdout = """
                    {"nodes":[{"session":"$hostTag","order":0,"folder_path":"/$hostTag","collapsed":false}]}
                """.trimIndent(),
                stderr = "",
                exitCode = 0,
            )
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

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

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> unusedProxy(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        error("${method.name} must not be called by this test")
    } as T

    private class FakeCache : TreeSyncCache {
        override fun peek(host: String): TreeClientCache.CachedTree? = null

        override fun read(host: String): TreeClientCache.CachedTree =
            TreeClientCache.CachedTree(emptyList())

        override fun write(host: String, tree: TreeClientCache.CachedTree) = Unit
    }

    private fun success(name: String): TreeSyncRemote.FullResult.Sessions =
        TreeSyncRemote.FullResult.Sessions(
            FolderListResult.Sessions(
                rows = listOf(
                    FolderSessionRow(
                        sessionName = name,
                        lastActivity = 1L,
                        attached = false,
                        cwd = "/home/alexey/git/$name",
                    ),
                ),
            ),
        )

    private companion object {
        val PARAMS = BoundParams(
            hostId = 1L,
            hostName = "test-host",
            hostname = "127.0.0.1",
            port = 22,
            username = "test",
            keyPath = "/tmp/key",
            passphrase = null,
        )
        val OTHER_PARAMS = PARAMS.copy(
            hostId = 2L,
            hostName = "other-host",
            hostname = "192.0.2.2",
        )
    }
}
