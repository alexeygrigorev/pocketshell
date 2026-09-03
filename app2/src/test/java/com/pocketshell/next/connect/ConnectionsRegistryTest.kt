package com.pocketshell.next.connect

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.transport.AuthMaterial
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.TransportState
import com.pocketshell.core.transport.TrustDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives [ConnectionsRegistry] against the scripted `core-transport` fake and a
 * REAL in-memory Room database (same setup `:shared:core-storage` uses), so the
 * host-row → [com.pocketshell.core.transport.HostTarget] mapping and the
 * [RoomTrustStore] round-trip are exercised, not stubbed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ConnectionsRegistryTest {

    private lateinit var db: AppDatabase
    private var hostId: Long = 0
    private var keyId: Long = 0

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "fixture", privateKeyPath = "/tmp/fixture_ed25519"),
        )
        hostId = db.hostDao().insert(
            HostEntity(
                name = "dev box",
                hostname = "dev.invalid",
                port = 2222,
                username = "tester",
                keyId = keyId,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun registry(
        factory: FakeHostConnectionFactory,
        dispatcher: CoroutineDispatcher,
        trustStore: RoomTrustStore = RoomTrustStore(db.hostDao(), dispatcher),
    ) = ConnectionsRegistry(
        factory = factory,
        trustStore = trustStore,
        hostDao = db.hostDao(),
        dispatcher = dispatcher,
    )

    @Test
    fun `two concurrent getOrConnect calls share one dial`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = FakeHostConnectionFactory()
        factory.gate = CompletableDeferred()
        val registry = registry(factory, dispatcher)

        val first = async { registry.getOrConnect(hostId) }
        // Resumes only once the dial is actually running, i.e. the registry
        // mutex is held and the connection is not stored yet — the exact window
        // in which a second caller could race into a second dial.
        factory.dialStarts.receive()

        val second = async { registry.getOrConnect(hostId) }
        runCurrent()
        assertEquals("second caller must park, not dial", 1, factory.dialCount)

        factory.gate!!.complete(Unit)
        val a = first.await()
        val b = second.await()

        assertTrue(a is ConnectResult.Connected)
        assertTrue(b is ConnectResult.Connected)
        assertEquals(1, factory.dialCount)
        assertEquals(1, factory.peakConcurrentDials)
        assertSame(
            (a as ConnectResult.Connected).connection,
            (b as ConnectResult.Connected).connection,
        )
        assertSame(a.connection, registry.current(hostId))
    }

    @Test
    fun `a live connection is reused without dialing again`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = FakeHostConnectionFactory()
        val registry = registry(factory, dispatcher)

        val first = registry.getOrConnect(hostId) as ConnectResult.Connected
        val second = registry.getOrConnect(hostId) as ConnectResult.Connected

        assertEquals(1, factory.dialCount)
        assertSame(first.connection, second.connection)
    }

    @Test
    fun `host row drives the dial target`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = FakeHostConnectionFactory()
        val registry = registry(factory, dispatcher)

        registry.getOrConnect(hostId)

        val target = factory.targets.single()
        assertEquals(hostId, target.hostId)
        assertEquals("dev.invalid", target.hostname)
        assertEquals(2222, target.port)
        assertEquals("tester", target.username)
        assertEquals(AuthMaterial.KeyRef(keyId), target.auth)
    }

    @Test
    fun `a lost connection is replaced by a fresh dial`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = FakeHostConnectionFactory()
        val registry = registry(factory, dispatcher)

        val first = (registry.getOrConnect(hostId) as ConnectResult.Connected).connection
        factory.connections.single().markLost("network dropped")
        assertNull("a spent connection must not be handed out", registry.current(hostId))

        val second = (registry.getOrConnect(hostId) as ConnectResult.Connected).connection

        assertEquals(2, factory.dialCount)
        assertNotSame(first, second)
        assertEquals(TransportState.Connected, second.state.value)
        assertSame(second, registry.current(hostId))
    }

    @Test
    fun `a closed connection is replaced by a fresh dial`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = FakeHostConnectionFactory()
        val registry = registry(factory, dispatcher)

        val first = (registry.getOrConnect(hostId) as ConnectResult.Connected).connection
        first.close()

        val second = (registry.getOrConnect(hostId) as ConnectResult.Connected).connection

        assertEquals(2, factory.dialCount)
        assertNotSame(first, second)
    }

    @Test
    fun `unknown host key surfaces NeedsTrust and connects after recordTrusted`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fingerprint = "SHA256:AAAAfingerprintAAAA"
        val factory = FakeHostConnectionFactory(presentedFingerprint = fingerprint)
        val trustStore = RoomTrustStore(db.hostDao(), dispatcher)
        val registry = registry(factory, dispatcher, trustStore)

        val needsTrust = registry.getOrConnect(hostId)
        assertTrue("first contact must ask, not connect", needsTrust is ConnectResult.NeedsTrust)
        val decision = (needsTrust as ConnectResult.NeedsTrust).decision
        assertEquals(TrustDecision.Unknown(fingerprint), decision)
        assertNull("nothing is registered while trust is unresolved", registry.current(hostId))

        val prompt = TrustPromptState.from(hostId, decision)!!
        assertEquals(fingerprint, prompt.fingerprintSha256)
        assertFalse(prompt.isMismatch)

        // The user taps "Trust", then the flow retries.
        trustStore.recordTrusted(factory.targets.first(), fingerprint)
        val retried = needsTrust.retry()

        assertTrue(retried is ConnectResult.Connected)
        assertEquals(2, factory.dialCount)
        assertSame(
            "the retry's connection must land in the registry, not float free",
            (retried as ConnectResult.Connected).connection,
            registry.current(hostId),
        )
        assertEquals(fingerprint, db.hostDao().getById(hostId)!!.trustedHostKeySha256)
    }

    @Test
    fun `an unknown host id fails instead of dialing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = FakeHostConnectionFactory()
        val registry = registry(factory, dispatcher)

        val result = registry.getOrConnect(hostId + 999)

        assertTrue(result is ConnectResult.Failed)
        assertEquals(0, factory.dialCount)
        assertNull(registry.current(hostId + 999))
    }

    @Test
    fun `a failed dial registers nothing and is retried next time`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = FakeHostConnectionFactory()
        factory.failWith = "connection refused"
        val registry = registry(factory, dispatcher)

        assertTrue(registry.getOrConnect(hostId) is ConnectResult.Failed)
        assertNull(registry.current(hostId))

        factory.failWith = null
        assertTrue(registry.getOrConnect(hostId) is ConnectResult.Connected)
        assertEquals(2, factory.dialCount)
    }

    @Test
    fun `closeAll closes every connection and empties the table`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val factory = FakeHostConnectionFactory()
        val registry = registry(factory, dispatcher)

        val otherHostId = db.hostDao().insert(
            HostEntity(
                name = "second",
                hostname = "second.invalid",
                username = "tester",
                keyId = keyId,
            ),
        )
        registry.getOrConnect(hostId)
        registry.getOrConnect(otherHostId)
        assertEquals(2, factory.connections.size)

        registry.closeAll()

        assertTrue(factory.connections.all { it.isClosed })
        assertNull(registry.current(hostId))
        assertNull(registry.current(otherHostId))

        // Idempotent, and a later caller gets a fresh connection.
        registry.closeAll()
        assertTrue(registry.getOrConnect(hostId) is ConnectResult.Connected)
        assertEquals(3, factory.dialCount)
    }
}
