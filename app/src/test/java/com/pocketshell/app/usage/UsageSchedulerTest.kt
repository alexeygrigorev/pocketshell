package com.pocketshell.app.usage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Unit tests for [UsageScheduler] (issue #117).
 *
 * The test exercises the host-filter + command-override plumbing and the
 * empty-snapshot reset behaviour. The SSH transport is mocked out via
 * the package-private [UsageScheduler.fetchHost] seam so we do not need
 * a Docker fixture to exercise the cadence logic.
 *
 * Covered:
 * - Only hosts with `quseInstalled == true` are polled.
 * - The per-host `usageCommandOverride` is forwarded to the fetch lambda.
 * - Missing-tool hosts surface as [UsageSnapshot.ToolMissing] (not a
 *   failed fetch) — this is the [UsageScreenState.missingToolHosts]
 *   contract from the issue body.
 * - Snapshots are cleared when no eligible hosts remain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UsageSchedulerTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun refreshNow_emptyWhenNoHostHasQuse() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        db.hostDao().insert(
            HostEntity(name = "h1", hostname = "h1", username = "u", keyId = keyId, quseInstalled = false),
        )
        db.hostDao().insert(
            HostEntity(name = "h2", hostname = "h2", username = "u", keyId = keyId, quseInstalled = null),
        )
        val scheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())
        val seen = mutableListOf<HostEntity>()
        scheduler.fetchHost = { host -> seen += host; null }

        scheduler.refreshNow()

        assertTrue("scheduler should not poll hosts without quseInstalled = true", seen.isEmpty())
        assertTrue(scheduler.snapshots.value.isEmpty())
    }

    @Test
    fun refreshNow_pollsOnlyQuseInstalledHosts_andForwardsCommandOverride() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val skippedId = db.hostDao().insert(
            HostEntity(name = "skipped", hostname = "s", username = "u", keyId = keyId, quseInstalled = false),
        )
        val defaultCmdId = db.hostDao().insert(
            HostEntity(name = "default", hostname = "d", username = "u", keyId = keyId, quseInstalled = true),
        )
        val customCmdId = db.hostDao().insert(
            HostEntity(
                name = "custom",
                hostname = "c",
                username = "u",
                keyId = keyId,
                quseInstalled = true,
                usageCommandOverride = "mycorp-usage --json",
            ),
        )
        val scheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())
        val polled = mutableListOf<Pair<Long, String?>>()
        scheduler.fetchHost = { host ->
            polled += host.id to host.usageCommandOverride
            UsageSnapshot.Records(
                hostId = host.id,
                hostName = host.name,
                records = listOf(
                    UsageProviderRecord(
                        provider = "claude",
                        status = UsageStatus.Ok,
                        windows = emptyList(),
                        rawStatus = "ok",
                    ),
                ),
                fetchedAt = Instant.now(),
                command = host.usageCommandOverride ?: UsageRemoteSource.defaultUsageCommand,
            )
        }

        scheduler.refreshNow()

        assertEquals(2, polled.size)
        val map = polled.toMap()
        assertNull("default-command host receives null override", map[defaultCmdId])
        assertEquals("mycorp-usage --json", map[customCmdId])
        assertEquals(2, scheduler.snapshots.value.size)
        assertTrue(scheduler.snapshots.value.containsKey(defaultCmdId))
        assertTrue(scheduler.snapshots.value.containsKey(customCmdId))
        assertNull(scheduler.snapshots.value[skippedId])
    }

    @Test
    fun refreshNow_emitsToolMissingSnapshot_notFailed_whenQuseGoneAtRuntime() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId, quseInstalled = true),
        )
        val scheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())
        scheduler.fetchHost = { host ->
            UsageSnapshot.ToolMissing(hostId = host.id, hostName = host.name, fetchedAt = Instant.now())
        }

        scheduler.refreshNow()

        val snapshot = scheduler.snapshots.value[hostId]
        assertTrue("missing quse must surface as ToolMissing, not Failed", snapshot is UsageSnapshot.ToolMissing)
    }

    @Test
    fun refreshNow_clearsStaleSnapshots_whenHostLosesQuse() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId, quseInstalled = true),
        )
        val scheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())
        scheduler.fetchHost = { host ->
            UsageSnapshot.Records(
                hostId = host.id,
                hostName = host.name,
                records = emptyList(),
                fetchedAt = Instant.now(),
                command = UsageRemoteSource.defaultUsageCommand,
            )
        }

        scheduler.refreshNow()
        assertTrue(scheduler.snapshots.value.containsKey(hostId))

        // Simulate the bootstrap flow flipping quseInstalled back to false
        // (e.g. user removed quse on the host).
        val current = db.hostDao().getById(hostId)!!
        db.hostDao().update(current.copy(quseInstalled = false))

        scheduler.refreshNow()
        assertTrue("stale snapshot must be dropped when quse is gone", scheduler.snapshots.value.isEmpty())
    }

    @Test
    fun setForegroundActive_togglesFlag() {
        val scheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())
        assertEquals(false, scheduler.foregroundActive.value)
        scheduler.setForegroundActive(true)
        assertEquals(true, scheduler.foregroundActive.value)
        scheduler.setForegroundActive(false)
        assertEquals(false, scheduler.foregroundActive.value)
    }
}
