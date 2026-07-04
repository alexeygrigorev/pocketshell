package com.pocketshell.app.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageWindow
import com.pocketshell.uikit.model.PillKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Issue #1241: unit coverage for the landing app-bar usage glance pill's
 * state derivation ([usageGlancePillState]). These pin the acceptance criteria
 * that are pure logic — the most-constraining percent from cache, the
 * hidden-when-no-data contract, the severity tint, and the honest-when-stale
 * flag — in the per-push Unit gate (they run under `./gradlew test`). The
 * on-device render / tap / non-crowding proof lives in
 * `app/src/androidTest/.../UsageGlancePillE2eTest`.
 */
class UsageGlancePillStateTest {

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-07-04T14:10:00Z")

    private fun window(percent: Double): UsageWindow =
        UsageWindow(name = "5h", used = percent, limit = 100.0, unit = "percent", resetAt = null)

    private fun record(
        provider: String,
        percent: Double,
        status: UsageStatus = UsageStatus.Ok,
        windows: List<UsageWindow> = listOf(window(percent)),
    ): UsageProviderRecord =
        UsageProviderRecord(
            provider = provider,
            status = status,
            windows = windows,
            rawStatus = status.name.lowercase(),
        )

    private fun snapshot(
        hostId: Long,
        records: List<UsageProviderRecord>,
        fetchedAt: Instant = now,
    ): UsageSnapshot =
        UsageSnapshot.Records(
            hostId = hostId,
            hostName = "host-$hostId",
            records = records,
            fetchedAt = fetchedAt,
            command = UsageRemoteSource.defaultUsageCommand,
        )

    // AC1: hidden/neutral when no data.
    @Test
    fun `no snapshots yields null so the pill is hidden`() {
        assertNull(usageGlancePillState(emptyMap(), warnPercent = 80.0, now = now, zoneId = zone))
    }

    @Test
    fun `tool-missing and failed snapshots alone yield null`() {
        val snapshots = mapOf(
            1L to UsageSnapshot.ToolMissing(hostId = 1L, hostName = "a", fetchedAt = now),
            2L to UsageSnapshot.Failed(hostId = 2L, hostName = "b", reason = "boom", fetchedAt = now),
        )
        assertNull(usageGlancePillState(snapshots, warnPercent = 80.0, now = now, zoneId = zone))
    }

    @Test
    fun `records with no thresholdable window yield null`() {
        // status=Ok with no windows has no percent to show → hidden.
        val snapshots = mapOf(
            1L to snapshot(1L, listOf(record("claude", 0.0, status = UsageStatus.Unsupported, windows = emptyList()))),
        )
        assertNull(usageGlancePillState(snapshots, warnPercent = 80.0, now = now, zoneId = zone))
    }

    // AC1: most-constraining provider percent from cache — across providers AND hosts.
    @Test
    fun `picks the highest percent across every provider and host`() {
        val snapshots = mapOf(
            1L to snapshot(1L, listOf(record("claude", 40.0), record("codex", 72.0))),
            2L to snapshot(2L, listOf(record("opencode", 55.0))),
        )
        val state = usageGlancePillState(snapshots, warnPercent = 80.0, now = now, zoneId = zone)!!
        assertEquals(72, state.percent)
        assertEquals("72%", state.label)
    }

    @Test
    fun `percent is rounded to nearest integer`() {
        val snapshots = mapOf(1L to snapshot(1L, listOf(record("claude", 82.6))))
        val state = usageGlancePillState(snapshots, warnPercent = 80.0, now = now, zoneId = zone)!!
        assertEquals(83, state.percent)
    }

    @Test
    fun `hard-blocked provider with no windows still surfaces as 100 percent`() {
        val snapshots = mapOf(
            1L to snapshot(
                1L,
                listOf(record("codex", 0.0, status = UsageStatus.Blocked, windows = emptyList())),
            ),
        )
        val state = usageGlancePillState(snapshots, warnPercent = 80.0, now = now, zoneId = zone)!!
        assertEquals(100, state.percent)
        assertEquals(PillKind.Blocked, state.kind)
    }

    // AC1: severity tint mirrors the warn-threshold state.
    @Test
    fun `kind reflects threshold state at the configured warn percent`() {
        // 40% healthy → Ok (green).
        assertEquals(
            PillKind.Ok,
            usageGlancePillState(mapOf(1L to snapshot(1L, listOf(record("claude", 40.0)))), 80.0, now, zoneId = zone)!!.kind,
        )
        // 84% at warn=80 → Approaching → Warn (amber).
        assertEquals(
            PillKind.Warn,
            usageGlancePillState(mapOf(1L to snapshot(1L, listOf(record("claude", 84.0)))), 80.0, now, zoneId = zone)!!.kind,
        )
        // 97% → Critical → Blocked (red); 100% → Exceeded → Blocked (red).
        assertEquals(
            PillKind.Blocked,
            usageGlancePillState(mapOf(1L to snapshot(1L, listOf(record("claude", 97.0)))), 80.0, now, zoneId = zone)!!.kind,
        )
    }

    @Test
    fun `warn threshold gate is respected — 84 percent is Ok when warn is 90`() {
        val snapshots = mapOf(1L to snapshot(1L, listOf(record("claude", 84.0))))
        assertEquals(
            PillKind.Ok,
            usageGlancePillState(snapshots, warnPercent = 90.0, now = now, zoneId = zone)!!.kind,
        )
    }

    // AC4: stale data is visually honest.
    @Test
    fun `fresh reading is not stale and has no cached-from description`() {
        val snapshots = mapOf(1L to snapshot(1L, listOf(record("claude", 60.0)), fetchedAt = now.minus(Duration.ofMinutes(2))))
        val state = usageGlancePillState(snapshots, warnPercent = 80.0, now = now, zoneId = zone)!!
        assertFalse(state.stale)
        assertEquals("Usage 60%", state.contentDescription)
    }

    @Test
    fun `reading older than the stale window is flagged stale with an HHmm clock`() {
        val captured = Instant.parse("2026-07-04T13:55:00Z") // 15 min before now → stale
        val snapshots = mapOf(1L to snapshot(1L, listOf(record("claude", 60.0)), fetchedAt = captured))
        val state = usageGlancePillState(snapshots, warnPercent = 80.0, now = now, zoneId = zone)!!
        assertTrue(state.stale)
        assertEquals("13:55", state.capturedClock)
        assertEquals("Usage 60%, cached from 13:55", state.contentDescription)
    }

    @Test
    fun `staleness follows the winning provider's own snapshot age`() {
        // The most-constraining provider (72%) is fresh; an older lower host must
        // not make the shown percent look stale.
        val snapshots = mapOf(
            1L to snapshot(1L, listOf(record("codex", 72.0)), fetchedAt = now.minus(Duration.ofMinutes(1))),
            2L to snapshot(2L, listOf(record("claude", 30.0)), fetchedAt = now.minus(Duration.ofHours(3))),
        )
        val state = usageGlancePillState(snapshots, warnPercent = 80.0, now = now, zoneId = zone)!!
        assertEquals(72, state.percent)
        assertFalse(state.stale)
    }
}
