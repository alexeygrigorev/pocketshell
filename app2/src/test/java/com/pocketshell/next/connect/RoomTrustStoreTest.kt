package com.pocketshell.next.connect

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.transport.AuthMaterial
import com.pocketshell.core.transport.HostTarget
import com.pocketshell.core.transport.TrustDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [RoomTrustStore] against a real in-memory database: every branch of the TOFU
 * decision plus the persistence round-trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomTrustStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var trustStore: RoomTrustStore
    private var hostId: Long = 0

    private val presented = "SHA256:presentedKeyFingerprint"
    private val other = "SHA256:someOtherKeyFingerprint"

    private fun target(id: Long = hostId) = HostTarget(
        hostId = id,
        hostname = "dev.invalid",
        port = 22,
        username = "tester",
        auth = AuthMaterial.KeyRef(1L),
    )

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        trustStore = RoomTrustStore(db.hostDao(), Dispatchers.Unconfined)

        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "fixture", privateKeyPath = "/tmp/fixture_ed25519"),
        )
        hostId = db.hostDao().insert(
            HostEntity(name = "dev box", hostname = "dev.invalid", username = "tester", keyId = keyId),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `no stored key is Unknown`() = runTest {
        assertEquals(TrustDecision.Unknown(presented), trustStore.evaluate(target(), presented))
    }

    @Test
    fun `a matching stored key is Trusted`() = runTest {
        trustStore.recordTrusted(target(), presented)

        assertEquals(TrustDecision.Trusted, trustStore.evaluate(target(), presented))
    }

    @Test
    fun `a differing stored key is a Mismatch carrying both fingerprints`() = runTest {
        trustStore.recordTrusted(target(), other)

        assertEquals(
            TrustDecision.Mismatch(storedSha256 = other, presentedSha256 = presented),
            trustStore.evaluate(target(), presented),
        )
    }

    @Test
    fun `recordTrusted persists onto the host row`() = runTest {
        trustStore.recordTrusted(target(), presented)

        val stored = db.hostDao().getById(hostId)!!
        assertEquals(presented, stored.trustedHostKeySha256)
        assertEquals(RoomTrustStore.FINGERPRINT_DIGEST, stored.trustedHostKeyAlgorithm)
        // Other columns survive the update.
        assertEquals("dev box", stored.name)
    }

    @Test
    fun `recordTrusted replaces a previously trusted key`() = runTest {
        trustStore.recordTrusted(target(), other)
        trustStore.recordTrusted(target(), presented)

        assertEquals(TrustDecision.Trusted, trustStore.evaluate(target(), presented))
        assertEquals(presented, db.hostDao().getById(hostId)!!.trustedHostKeySha256)
    }

    @Test
    fun `a missing host row is Unknown, never Trusted`() = runTest {
        assertEquals(
            TrustDecision.Unknown(presented),
            trustStore.evaluate(target(hostId + 999), presented),
        )
    }

    @Test
    fun `recordTrusted for a missing host row stores nothing`() = runTest {
        trustStore.recordTrusted(target(hostId + 999), presented)

        assertNull(db.hostDao().getById(hostId)!!.trustedHostKeySha256)
    }

    @Test
    fun `trust is per host row, not per hostname`() = runTest {
        val secondHostId = db.hostDao().insert(
            HostEntity(
                name = "same address, different row",
                hostname = "dev.invalid",
                username = "tester",
                keyId = db.sshKeyDao().insert(
                    SshKeyEntity(name = "other-key", privateKeyPath = "/tmp/other_ed25519"),
                ),
            ),
        )
        trustStore.recordTrusted(target(), presented)

        assertEquals(
            TrustDecision.Unknown(presented),
            trustStore.evaluate(target(secondHostId), presented),
        )
    }
}
