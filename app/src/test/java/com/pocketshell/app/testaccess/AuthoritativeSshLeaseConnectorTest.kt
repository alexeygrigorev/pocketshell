package com.pocketshell.app.testaccess

import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeSshLeaseConnectorTest {
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
}
