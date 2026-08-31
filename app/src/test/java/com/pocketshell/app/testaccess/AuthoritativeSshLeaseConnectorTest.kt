package com.pocketshell.app.testaccess

import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.ssh.ChangedHostKeyException
import com.pocketshell.app.ssh.HostKeyTrustPromptRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeSshLeaseConnectorTest {
    @Test
    fun typedChangedKeyFromAnyPooledCallerRoutesToSharedReplacementUi() = runTest {
        val router = HostKeyTrustPromptRouter()
        val connector = AuthoritativeSshLeaseConnector(
            delegate = SshLeaseConnector {
                Result.failure(
                    ChangedHostKeyException(
                        "trusted.example", 2202, "ssh-ed25519", "SHA256:old", "SHA256:new",
                    ),
                )
            },
            trustPromptRouter = router,
        )

        connector.connect(target(SshLeaseKey("trusted.example", 2202, "user", "7:/key")))

        assertEquals(7L, router.pendingHostId.value)
    }

    @Test
    fun productionConnectorResolvesPersistedTrustBeforePhysicalDial() = runTest {
        var delegated: SshLeaseTarget? = null
        val host = HostEntity(
            id = 7,
            name = "trusted",
            hostname = "trusted.example",
            port = 2202,
            username = "testuser",
            keyId = 1,
            trustedHostKeyAlgorithm = "ssh-ed25519",
            trustedHostKeySha256 = "SHA256:persisted",
        )
        val connector = AuthoritativeSshLeaseConnector(
            delegate = SshLeaseConnector { target ->
                delegated = target
                Result.failure(IllegalStateException("stop after capture"))
            },
            hostDao = SingleHostDao(host),
        )
        val requested = target(
            SshLeaseKey("trusted.example", 2202, "testuser", "7:/key", "host-key:unconfirmed"),
        )

        delegated = connector.resolveTarget(requested)

        assertEquals("host-key:SHA256:persisted", delegated?.leaseKey?.knownHostsId)
        assertEquals(
            KnownHostsPolicy.VerifiedFingerprint("SHA256:persisted"),
            delegated?.knownHosts,
        )
    }

    @Test
    fun outageBlocksOnlyItsExactLeaseKeyUntilExactHandleEnds() = runTest {
        val delegated = mutableListOf<SshLeaseKey>()
        val delegateFailure = IllegalStateException("delegated")
        val connector = AuthoritativeSshLeaseConnector(
            SshLeaseConnector { target ->
                delegated += target.leaseKey
                Result.failure(delegateFailure)
            },
        )
        val keyA = leaseKey(host = "a")
        val keyB = leaseKey(host = "b")

        assertSame(delegateFailure, connector.connect(target(keyA)).exceptionOrNull())
        val outage = connector.beginSustainedOutageForLastLeaseForTest()
        assertEquals(keyA, outage.leaseKey)

        assertTrue(connector.connect(target(keyA)).exceptionOrNull() is java.io.IOException)
        assertSame(delegateFailure, connector.connect(target(keyB)).exceptionOrNull())
        assertEquals(1, outage.blockedAttemptCount)
        assertEquals(listOf(keyA, keyB), delegated)

        connector.endSustainedOutageForTest(outage)
        assertSame(delegateFailure, connector.connect(target(keyA)).exceptionOrNull())
        assertEquals(listOf(keyA, keyB, keyA), delegated)
    }

    @Test
    fun outageAlsoFencesSameKeyDialAlreadyInsideDelegate() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connector = AuthoritativeSshLeaseConnector(
            SshLeaseConnector {
                entered.complete(Unit)
                release.await()
                Result.failure(IllegalStateException("late delegate result"))
            },
        )
        val pending = async { connector.connect(target(leaseKey(host = "a"))) }
        runCurrent()
        entered.await()

        val outage = connector.beginSustainedOutageForLastLeaseForTest()
        release.complete(Unit)
        runCurrent()

        assertTrue(pending.await().exceptionOrNull() is java.io.IOException)
        assertEquals(1, outage.blockedAttemptCount)
    }

    private fun leaseKey(host: String) = SshLeaseKey(
        host = host,
        port = 22,
        user = "testuser",
        credentialId = "credential",
    )

    private fun target(key: SshLeaseKey) = SshLeaseTarget(
        leaseKey = key,
        key = SshKey.Pem("not-used"),
    )

    private class SingleHostDao(private var host: HostEntity) : HostDao {
        override fun getAll(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun getById(id: Long): HostEntity? = host.takeIf { it.id == id }
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun insert(host: HostEntity): Long = host.id.also { this.host = host }
        override suspend fun update(host: HostEntity) { this.host = host }
        override suspend fun delete(host: HostEntity) = Unit
        override suspend fun deleteById(id: Long) = Unit
    }
}
