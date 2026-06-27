package com.pocketshell.app.usage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
 * - Only hosts with `pocketshellInstalled == true` are polled.
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
    fun refreshNow_emptyWhenNoHostHasPocketshell() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        db.hostDao().insert(
            HostEntity(name = "h1", hostname = "h1", username = "u", keyId = keyId, pocketshellInstalled = false),
        )
        db.hostDao().insert(
            HostEntity(name = "h2", hostname = "h2", username = "u", keyId = keyId, pocketshellInstalled = null),
        )
        val scheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())
        val seen = mutableListOf<HostEntity>()
        scheduler.fetchHost = { host -> seen += host; null }

        scheduler.refreshNow()

        assertTrue("scheduler should not poll hosts without pocketshellInstalled = true", seen.isEmpty())
        assertTrue(scheduler.snapshots.value.isEmpty())
    }

    @Test
    fun refreshNow_pollsOnlyPocketshellInstalledHosts_andForwardsCommandOverride() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val skippedId = db.hostDao().insert(
            HostEntity(name = "skipped", hostname = "s", username = "u", keyId = keyId, pocketshellInstalled = false),
        )
        val defaultCmdId = db.hostDao().insert(
            HostEntity(name = "default", hostname = "d", username = "u", keyId = keyId, pocketshellInstalled = true),
        )
        val customCmdId = db.hostDao().insert(
            HostEntity(
                name = "custom",
                hostname = "c",
                username = "u",
                keyId = keyId,
                pocketshellInstalled = true,
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
    fun refreshNow_skipsPocketshellHost_whenCliVersionIsIncompatible() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        db.hostDao().insert(
            HostEntity(
                name = "mismatch",
                hostname = "m",
                username = "u",
                keyId = keyId,
                pocketshellInstalled = true,
                pocketshellCliVersion = "0.3.6",
                pocketshellExpectedCliVersion = "0.3.7",
                pocketshellVersionCompatible = false,
            ),
        )
        val scheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())
        val seen = mutableListOf<HostEntity>()
        scheduler.fetchHost = { host -> seen += host; null }

        scheduler.refreshNow()

        assertTrue("scheduler should not poll app-incompatible pocketshell CLIs", seen.isEmpty())
        assertTrue(scheduler.snapshots.value.isEmpty())
    }

    @Test
    fun refreshNow_emitsToolMissingSnapshot_notFailed_whenPocketshellGoneAtRuntime() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId, pocketshellInstalled = true),
        )
        val scheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())
        scheduler.fetchHost = { host ->
            UsageSnapshot.ToolMissing(hostId = host.id, hostName = host.name, fetchedAt = Instant.now())
        }

        scheduler.refreshNow()

        val snapshot = scheduler.snapshots.value[hostId]
        assertTrue("missing pocketshell must surface as ToolMissing, not Failed", snapshot is UsageSnapshot.ToolMissing)
    }

    @Test
    fun refreshNow_clearsStaleSnapshots_whenHostLosesPocketshell() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId, pocketshellInstalled = true),
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

        // Simulate the bootstrap flow flipping pocketshellInstalled back to false
        // (e.g. user removed pocketshell on the host).
        val current = db.hostDao().getById(hostId)!!
        db.hostDao().update(current.copy(pocketshellInstalled = false))

        scheduler.refreshNow()
        assertTrue("stale snapshot must be dropped when pocketshell is gone", scheduler.snapshots.value.isEmpty())
    }

    @Test
    fun refreshNow_boundsHungHostFetch_andDoesNotWedgeLaterManualRefresh() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hungHostId = db.hostDao().insert(
            HostEntity(name = "a-hung", hostname = "hung", username = "u", keyId = keyId, pocketshellInstalled = true),
        )
        val healthyHostId = db.hostDao().insert(
            HostEntity(
                name = "b-healthy",
                hostname = "healthy",
                username = "u",
                keyId = keyId,
                pocketshellInstalled = true,
            ),
        )
        val scheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())
        scheduler.hostFetchTimeoutMs = 50L

        val hungStarted = CompletableDeferred<Unit>()
        val releaseHungFetch = CompletableDeferred<Unit>()
        var hungFetches = 0
        scheduler.fetchHost = { host ->
            when (host.id) {
                hungHostId -> {
                    hungFetches += 1
                    if (hungFetches == 1) {
                        hungStarted.complete(Unit)
                        releaseHungFetch.await()
                    }
                    UsageSnapshot.Failed(
                        hostId = host.id,
                        hostName = host.name,
                        reason = "later manual refresh recovered",
                        fetchedAt = Instant.now(),
                    )
                }

                healthyHostId -> UsageSnapshot.Records(
                    hostId = host.id,
                    hostName = host.name,
                    records = emptyList(),
                    fetchedAt = Instant.now(),
                    command = UsageRemoteSource.defaultUsageCommand,
                )

                else -> null
            }
        }

        val firstRefresh = async { scheduler.refreshNow() }
        hungStarted.await()

        val secondRefresh = async { scheduler.refreshNow() }
        val secondCompleted = withTimeoutOrNull(500L) {
            secondRefresh.await()
            true
        }

        releaseHungFetch.complete(Unit)
        firstRefresh.await()

        assertEquals("later manual refresh must not stay parked behind a hung host", true, secondCompleted)
        assertTrue(scheduler.snapshots.value[healthyHostId] is UsageSnapshot.Records)
    }

    /**
     * Issue #116 (usage-panel Fix B): the `worstBadgeRecord` extension
     * picks a blocked provider over a near-limit one, falls back to a
     * near-limit provider when no blocked one is present, and returns
     * `null` when no record warrants a badge. The map below tabulates
     * the three legs of the rule.
     */
    @Test
    fun worstBadgeRecord_prefersBlockedOverNearLimit_andReturnsNullForHealthy() {
        val blocked = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Blocked,
            windows = listOf(UsageWindow("5h", 100.0, 100.0, "percent", null)),
            rawStatus = "limited",
        )
        val near = UsageProviderRecord(
            provider = "claude",
            status = UsageStatus.Ok,
            windows = listOf(UsageWindow("5h", 92.0, 100.0, "percent", null)),
            rawStatus = "ok",
        )
        val healthy = UsageProviderRecord(
            provider = "copilot",
            status = UsageStatus.Ok,
            windows = listOf(UsageWindow("7d", 10.0, 100.0, "percent", null)),
            rawStatus = "ok",
        )

        val blockedSnapshot = UsageSnapshot.Records(
            hostId = 1, hostName = "h", records = listOf(near, blocked),
            fetchedAt = Instant.now(), command = "pocketshell usage --json",
        )
        assertEquals(blocked, blockedSnapshot.worstBadgeRecord())

        val nearSnapshot = UsageSnapshot.Records(
            hostId = 1, hostName = "h", records = listOf(healthy, near),
            fetchedAt = Instant.now(), command = "pocketshell usage --json",
        )
        assertEquals(near, nearSnapshot.worstBadgeRecord())

        val healthySnapshot = UsageSnapshot.Records(
            hostId = 1, hostName = "h", records = listOf(healthy),
            fetchedAt = Instant.now(), command = "pocketshell usage --json",
        )
        assertNull(healthySnapshot.worstBadgeRecord())

        // ToolMissing + Failed never produce a chip — the host card
        // already conveys the issue via the setup-state badge.
        assertNull(
            UsageSnapshot.ToolMissing(1, "h", Instant.now()).worstBadgeRecord(),
        )
        assertNull(
            UsageSnapshot.Failed(1, "h", "boom", Instant.now()).worstBadgeRecord(),
        )
    }

    /**
     * Issue #116: `updateSnapshots` merges externally-produced
     * snapshots (e.g. the pull-to-refresh path on `UsageViewModel`)
     * into the scheduler's flow so cross-surface badges (the host
     * list strip and Usage panel state) stay in sync without waiting
     * for the next polling tick.
     */
    @Test
    fun updateSnapshots_mergesAndPreservesExistingEntries() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        db.hostDao().insert(
            HostEntity(name = "host-a", hostname = "a", username = "u", keyId = keyId, pocketshellInstalled = true),
        )
        val scheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())
        assertTrue(scheduler.snapshots.value.isEmpty())

        val externalRecord = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Blocked,
            windows = listOf(UsageWindow("5h", 100.0, 100.0, "percent", null)),
            rawStatus = "limited",
        )
        scheduler.updateSnapshots(
            mapOf(
                42L to UsageSnapshot.Records(
                    hostId = 42L,
                    hostName = "external",
                    records = listOf(externalRecord),
                    fetchedAt = Instant.now(),
                    command = "pocketshell usage --json",
                ),
            ),
        )
        assertTrue(scheduler.snapshots.value.containsKey(42L))
        assertEquals(
            externalRecord,
            (scheduler.snapshots.value[42L] as UsageSnapshot.Records).records.single(),
        )
    }

    @Test
    fun updateSnapshots_publishesMergedSnapshotsToUsageNotifier() = runTest {
        val notifier = RecordingUsageNotifier()
        val scheduler = UsageScheduler(
            db.hostDao(),
            db.sshKeyDao(),
            UsageRemoteSource(),
            usageNotifier = notifier,
        )
        val record = UsageProviderRecord(
            provider = "codex",
            status = UsageStatus.Ok,
            windows = listOf(UsageWindow("7d", 80.0, 100.0, "percent", null)),
            rawStatus = "ok",
        )
        scheduler.updateSnapshots(
            mapOf(
                99L to UsageSnapshot.Records(
                    hostId = 99L,
                    hostName = "usage-host",
                    records = listOf(record),
                    fetchedAt = Instant.now(),
                    command = "pocketshell usage --json",
                ),
            ),
        )

        assertEquals(1, notifier.snapshots.size)
        assertEquals(record, (notifier.snapshots.single()[99L] as UsageSnapshot.Records).records.single())
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

    private class RecordingUsageNotifier : UsageNotifier {
        val snapshots = mutableListOf<Map<Long, UsageSnapshot>>()

        override fun onSnapshotsChanged(snapshots: Map<Long, UsageSnapshot>) {
            this.snapshots += snapshots
        }
    }
}
