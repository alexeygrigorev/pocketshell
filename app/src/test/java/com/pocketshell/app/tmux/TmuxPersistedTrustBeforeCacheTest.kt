package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.ssh.HostKeyTrustPromptRouter
import com.pocketshell.app.testaccess.AuthoritativeSshLeaseConnector
import com.pocketshell.core.ssh.ChangedHostKeyException
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.ssh.UnknownHostKeyException
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/** Regression for #2433's persisted-trust-before-runtime-cache integration boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
class TmuxPersistedTrustBeforeCacheTest : TmuxSessionViewModelTestBase() {
    @Test
    fun persistedTrustResolutionFailureUsesControllerOwnedTerminalState() = runTest(scheduler) {
        val hostDao = SingleHostDao(
            host = host(fingerprint = VERIFIED_FINGERPRINT),
            lookupFailure = IllegalStateException("trust store unavailable"),
        )
        val delegate = RecordingConnector { Result.success(TestSshSession()) }
        val manager = testLeaseManager(
            AuthoritativeSshLeaseConnector(delegate, hostDao, HostKeyTrustPromptRouter()),
            this,
            idleTtlMillis = 60_000L,
        )
        val vm = newVm(sshLeaseManager = manager, hostDao = hostDao)

        vm.connect(
            hostId = HOST_ID,
            hostName = "alpha",
            host = HOSTNAME,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            passphrase = null,
            sessionName = "work",
        )
        advanceUntilIdle()

        assertTrue(
            "a pre-cache trust failure must project the controller's terminal state",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "connect failed: trust store unavailable",
            (vm.connectionStatus.value as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
        assertTrue(
            "trust resolution failure must not reach the physical connector",
            delegate.targets.isEmpty(),
        )
        assertEquals("work", vm.connectingTarget?.sessionName)
    }

    @Test
    fun replacingTrustForTheActiveTargetForcesTeardownAndRedial() = runTest(scheduler) {
        val hostDao = SingleHostDao(host(fingerprint = VERIFIED_FINGERPRINT))
        val delegate = RecordingConnector { Result.success(TestSshSession()) }
        val manager = testLeaseManager(
            AuthoritativeSshLeaseConnector(delegate, hostDao, HostKeyTrustPromptRouter()),
            this,
            idleTtlMillis = 60_000L,
        )
        val oldLease = manager.acquire(provisionalTarget()).getOrThrow()
        val oldClient = FakeTmuxClient()
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%2")
        val vm = newVm(sshLeaseManager = manager, hostDao = hostDao)
        vm.replaceClientForTest(
            hostId = HOST_ID,
            hostName = "alpha",
            host = HOSTNAME,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            sessionName = "work",
            client = oldClient,
            lease = oldLease,
            trustedHostKeySha256 = VERIFIED_FINGERPRINT,
        )
        vm.setTmuxClientFactoryForTest { _, _, _ -> replacementClient }
        hostDao.replace(host(fingerprint = REPLACEMENT_FINGERPRINT))

        vm.connect(
            hostId = HOST_ID,
            hostName = "alpha",
            host = HOSTNAME,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            passphrase = null,
            sessionName = "work",
        )
        advanceUntilIdle()

        assertEquals("replacement trust must establish a new physical lease", 2, delegate.targets.size)
        assertTrue("old active tmux client must be torn down", oldClient.closed)
        assertEquals(REPLACEMENT_FINGERPRINT, vm.activeTarget?.trustedHostKeySha256)
    }

    @Test
    fun replacingTrustWhileConnectIsInFlightCannotDedupeOntoOldFingerprint() = runTest(scheduler) {
        val hostDao = SingleHostDao(host(fingerprint = VERIFIED_FINGERPRINT))
        val delegate = BlockingFirstConnector()
        val manager = testLeaseManager(
            AuthoritativeSshLeaseConnector(delegate, hostDao, HostKeyTrustPromptRouter()),
            this,
            idleTtlMillis = 60_000L,
        )
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%2")
        val vm = newVm(sshLeaseManager = manager, hostDao = hostDao)
        vm.setTmuxClientFactoryForTest { _, _, _ -> replacementClient }

        vm.connect(
            hostId = HOST_ID,
            hostName = "alpha",
            host = HOSTNAME,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()
        delegate.firstStarted.await()
        hostDao.replace(host(fingerprint = REPLACEMENT_FINGERPRINT))

        vm.connect(
            hostId = HOST_ID,
            hostName = "alpha",
            host = HOSTNAME,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            passphrase = null,
            sessionName = "work",
        )
        advanceUntilIdle()

        assertEquals("new fingerprint must not dedupe onto old in-flight target", 2, delegate.targets.size)
        assertEquals(REPLACEMENT_FINGERPRINT, vm.activeTarget?.trustedHostKeySha256)
    }

    @Test
    fun verifiedPersistedTrustActivatesTheExactWarmRuntimeWithoutAnotherDial() = runTest(scheduler) {
        val hostDao = SingleHostDao(host(fingerprint = VERIFIED_FINGERPRINT))
        val session = TestSshSession()
        val delegate = RecordingConnector { Result.success(session) }
        val router = HostKeyTrustPromptRouter()
        val connector = AuthoritativeSshLeaseConnector(delegate, hostDao, router)
        val manager = testLeaseManager(connector, this, idleTtlMillis = 60_000L)
        val lease = manager.acquire(provisionalTarget()).getOrThrow()
        val cachedClient = FakeTmuxClient()
        val cache = TmuxSessionRuntimeCache()
        cache.put(cachedRuntime("work", VERIFIED_FINGERPRINT, session, cachedClient, lease))
        val registry = ActiveTmuxClients()
        val vm = newVm(
            registry = registry,
            runtimeCache = cache,
            sshLeaseManager = manager,
            hostDao = hostDao,
        )

        vm.connect(
            hostId = HOST_ID,
            hostName = "alpha",
            host = HOSTNAME,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            passphrase = null,
            sessionName = "work",
        )
        advanceUntilIdle()

        assertSame(cachedClient, registry.clients.value[HOST_ID]?.client)
        assertEquals("verified cache activation must not redial", 1, delegate.targets.size)
        assertEquals("host-key:$VERIFIED_FINGERPRINT", lease.key.knownHostsId)
        assertTrue(cache.snapshotKeys().none { it.trustedHostKeySha256 == null })
    }

    @Test
    fun sameHostFastSwitchResolvesTrustBeforeReusingTheVerifiedLease() = runTest(scheduler) {
        val hostDao = SingleHostDao(host(fingerprint = VERIFIED_FINGERPRINT))
        val session = TestSshSession()
        val delegate = RecordingConnector { Result.success(session) }
        val connector = AuthoritativeSshLeaseConnector(delegate, hostDao, HostKeyTrustPromptRouter())
        val manager = testLeaseManager(connector, this, idleTtlMillis = 60_000L)
        val lease = manager.acquire(provisionalTarget()).getOrThrow()
        val cache = TmuxSessionRuntimeCache()
        val registry = ActiveTmuxClients()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient().withSinglePane("other", "%2")
        val vm = newVm(
            registry = registry,
            runtimeCache = cache,
            sshLeaseManager = manager,
            hostDao = hostDao,
        )
        vm.replaceClientForTest(
            hostId = HOST_ID,
            hostName = "alpha",
            host = HOSTNAME,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            sessionName = "work",
            client = oldClient,
            lease = lease,
            trustedHostKeySha256 = VERIFIED_FINGERPRINT,
        )
        vm.setTmuxClientFactoryForTest { _, _, _ -> newClient }

        vm.connect(
            hostId = HOST_ID,
            hostName = "alpha",
            host = HOSTNAME,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            passphrase = null,
            sessionName = "other",
        )
        advanceUntilIdle()

        assertSame(newClient, registry.clients.value[HOST_ID]?.client)
        assertEquals("verified warm switch must reuse the existing transport", 1, delegate.targets.size)
        assertTrue(cache.contains(runtimeKey("work", VERIFIED_FINGERPRINT)))
        assertTrue(cache.snapshotKeys().none { it.trustedHostKeySha256 == null })
    }

    @Test
    fun unknownPersistedTrustCannotActivateAFormerVerifiedRuntime() = runTest(scheduler) {
        val failure = UnknownHostKeyException(HOSTNAME, PORT, "ssh-ed25519", PRESENTED_FINGERPRINT)
        assertUntrustedPathMissesFormerRuntime(persistedFingerprint = null, failure = failure)
    }

    @Test
    fun changedPersistedTrustCannotActivateAStaleFingerprintRuntime() = runTest(scheduler) {
        val failure = ChangedHostKeyException(
            HOSTNAME,
            PORT,
            "ssh-ed25519",
            expectedSha256 = REPLACEMENT_FINGERPRINT,
            presentedSha256 = PRESENTED_FINGERPRINT,
        )
        assertUntrustedPathMissesFormerRuntime(
            persistedFingerprint = REPLACEMENT_FINGERPRINT,
            failure = failure,
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertUntrustedPathMissesFormerRuntime(
        persistedFingerprint: String?,
        failure: Throwable,
    ) {
        val hostDao = SingleHostDao(host(fingerprint = persistedFingerprint))
        val delegate = RecordingConnector { Result.failure(failure) }
        val router = HostKeyTrustPromptRouter()
        val connector = AuthoritativeSshLeaseConnector(delegate, hostDao, router)
        val manager = testLeaseManager(connector, this, idleTtlMillis = 60_000L)
        val staleClient = FakeTmuxClient()
        val cache = TmuxSessionRuntimeCache().apply {
            put(cachedRuntime("work", VERIFIED_FINGERPRINT, TestSshSession(), staleClient))
        }
        val registry = ActiveTmuxClients()
        val vm = newVm(
            registry = registry,
            runtimeCache = cache,
            sshLeaseManager = manager,
            hostDao = hostDao,
        )

        vm.connect(
            hostId = HOST_ID,
            hostName = "alpha",
            host = HOSTNAME,
            port = PORT,
            user = USER,
            keyPath = KEY_PATH,
            passphrase = null,
            sessionName = "work",
        )
        advanceUntilIdle()

        assertEquals(1, delegate.targets.size)
        assertEquals(setOf(HOST_ID), router.hostsNeedingTrust.value)
        assertTrue("stale runtime must remain unactivated", registry.clients.value[HOST_ID] == null)
        assertTrue("stale cached client must never be attached", !staleClient.connectCalled)
        assertTrue("stale trust runtime must be atomically removed", cache.snapshotKeys().isEmpty())
        assertTrue("removed stale runtime owner must be closed", staleClient.closed)
        val observedPolicy = delegate.targets.single().knownHosts as KnownHostsPolicy.VerifiedFingerprint
        assertEquals(persistedFingerprint, observedPolicy.expectedSha256)
    }

    private fun cachedRuntime(
        sessionName: String,
        fingerprint: String,
        session: TestSshSession,
        client: FakeTmuxClient,
        lease: com.pocketshell.core.ssh.SshLease? = null,
    ): CachedTmuxRuntime = CachedTmuxRuntime(
        key = runtimeKey(sessionName, fingerprint),
        hostName = "alpha",
        startDirectory = null,
        session = session,
        client = client,
        panes = emptyList(),
        paneRows = emptyMap(),
        paneProducerJobs = emptyMap(),
        paneInputQueues = emptyMap(),
        paneInputJobs = emptyMap(),
        paneAgentInputs = emptyMap(),
        agentConversations = emptyMap(),
        remoteColumns = 0,
        remoteRows = 0,
        lease = lease,
    )

    private fun runtimeKey(sessionName: String, fingerprint: String) = TmuxRuntimeKey(
        hostId = HOST_ID,
        hostname = HOSTNAME,
        port = PORT,
        username = USER,
        keyPath = KEY_PATH,
        sessionName = sessionName,
        trustedHostKeySha256 = fingerprint,
    )

    private fun provisionalTarget() = SshLeaseTarget(
        leaseKey = SshLeaseKey(
            host = HOSTNAME,
            port = PORT,
            user = USER,
            credentialId = "$HOST_ID:$KEY_PATH",
            knownHostsId = com.pocketshell.core.ssh.SshLeaseManager.UNCONFIRMED_HOST_KEY_ID,
        ),
        key = SshKey.Path(java.io.File(KEY_PATH)),
        knownHosts = KnownHostsPolicy.VerifiedFingerprint(null),
    )

    private fun host(fingerprint: String?) = HostEntity(
        id = HOST_ID,
        name = "alpha",
        hostname = HOSTNAME,
        port = PORT,
        username = USER,
        keyId = 1L,
        trustedHostKeyAlgorithm = fingerprint?.let { "ssh-ed25519" },
        trustedHostKeySha256 = fingerprint,
    )

    private fun FakeTmuxClient.withSinglePane(sessionName: String, paneId: String) = apply {
        responses.addLast(
            CommandResponse(1L, listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"), false),
        )
        capturePaneResponses.addLast(CommandResponse(2L, listOf("$sessionName ready"), false))
        cursorQueryResponses.addLast(CommandResponse(3L, listOf("0,0"), false))
    }

    private class RecordingConnector(
        private val result: (SshLeaseTarget) -> Result<SshSession>,
    ) : SshLeaseConnector {
        val targets = mutableListOf<SshLeaseTarget>()
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            targets += target
            return result(target)
        }
    }

    private class TestSshSession : SshSession {
        private var closed = false
        override val isConnected: Boolean get() = !closed
        override suspend fun exec(command: String): ExecResult = ExecResult("", "", 0)
        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()
        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job = Job()
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

    private class BlockingFirstConnector : SshLeaseConnector {
        val targets = mutableListOf<SshLeaseTarget>()
        val firstStarted = CompletableDeferred<Unit>()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            targets += target
            if (targets.size == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
            return Result.success(TestSshSession())
        }
    }

    private class SingleHostDao(
        private var host: HostEntity,
        private val lookupFailure: Throwable? = null,
    ) : HostDao {
        fun replace(replacement: HostEntity) {
            host = replacement
        }

        override fun getAll(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun getById(id: Long): HostEntity? {
            lookupFailure?.let { throw it }
            return host.takeIf { it.id == id }
        }
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun insert(host: HostEntity): Long = host.id
        override suspend fun update(host: HostEntity) = Unit
        override suspend fun delete(host: HostEntity) = Unit
        override suspend fun deleteById(id: Long) = Unit
    }

    private companion object {
        const val HOST_ID = 1L
        const val HOSTNAME = "alpha.example"
        const val PORT = 22
        const val USER = "alex"
        const val KEY_PATH = "/keys/a"
        const val VERIFIED_FINGERPRINT = "SHA256:verified"
        const val REPLACEMENT_FINGERPRINT = "SHA256:replacement"
        const val PRESENTED_FINGERPRINT = "SHA256:presented"
    }
}
