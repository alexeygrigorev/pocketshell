package com.pocketshell.next.usage

import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageWindow
import com.pocketshell.uikit.model.PillKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [usageGlancePillState] — the pure derivation behind the terminal top bar's
 * pill, with and without a [GlanceFocus] (issue #2579).
 *
 * The maintainer's report: a session running Claude Code showed "Grok 7d 83%".
 * The pill was answering "how close is the nearest limit ANYWHERE", which is
 * the tree's question, not the session's. On the session screen the question is
 * "how close is the agent I am talking to", and the answer is that agent's
 * LONGEST window with no window token — "Claude 38%".
 *
 * Every fallback path is pinned alongside it, because the focus must never be
 * able to REMOVE a pill: an unknown host, a provider with no reading, and a
 * blank provider name all have to land back on the old behaviour byte for byte.
 */
class UsageGlancePillTest {

    // --- the focused pill --------------------------------------------------

    /**
     * The headline case, with the maintainer's own numbers: Claude's 5h bucket
     * is more drained (40%) than its 7d one (38%), and the pill still shows the
     * 7d number — "longest window", not "most constrained window".
     */
    @Test
    fun `a focus shows the focused provider's longest window with no window token`() {
        val pill = pill(
            snapshots = mapOf(
                HOST to records(
                    claude(fiveHourPercent = 40.0, sevenDayPercent = 38.0),
                    grok(sevenDayPercent = 83.0),
                ),
            ),
            focus = GlanceFocus(HOST, "claude"),
        )

        assertNotNull(pill)
        assertEquals(38, pill!!.percent)
        assertEquals("Claude", pill.provider)
        assertNull(pill.window)
        // The rendered strings, not just the fields: "Claude 38%" is what the
        // maintainer asked to see, and `attribution` is what the pill paints.
        assertEquals("Claude", pill.attribution)
        assertEquals("Claude 38%", pill.label)
        assertEquals("Usage Claude 38%", pill.contentDescription)
    }

    /** Without the focus the SAME snapshot is the pre-#2579 pill, unchanged. */
    @Test
    fun `the same snapshot without a focus still reports the worst provider anywhere`() {
        val snapshots = mapOf(
            HOST to records(
                claude(fiveHourPercent = 40.0, sevenDayPercent = 38.0),
                grok(sevenDayPercent = 83.0),
            ),
        )

        val pill = pill(snapshots = snapshots, focus = null)

        assertNotNull(pill)
        assertEquals(83, pill!!.percent)
        assertEquals("Grok", pill.provider)
        assertEquals("7d", pill.window)
        assertEquals("Grok 7d 83%", pill.label)
    }

    /** A provider whose only long window is monthly reports the monthly one. */
    @Test
    fun `a monthly-only provider focuses on monthly, not on its 5h bucket`() {
        val pill = pill(
            snapshots = mapOf(
                HOST to records(
                    record(
                        provider = "copilot",
                        windows = listOf(window("5h", 0.0), window("monthly", 88.0)),
                    ),
                ),
            ),
            focus = GlanceFocus(HOST, "copilot"),
        )

        assertEquals(88, pill?.percent)
        assertEquals("Copilot", pill?.provider)
        assertNull(pill?.window)
    }

    /** The host's vocabulary is lowercase; a differently-cased focus still matches. */
    @Test
    fun `the provider match is case-insensitive`() {
        val pill = pill(
            snapshots = mapOf(HOST to records(claude(40.0, 38.0))),
            focus = GlanceFocus(HOST, "Claude"),
        )

        assertEquals(38, pill?.percent)
    }

    /**
     * The focus changes WHICH window is displayed, never whether the reading is
     * honest about its age: a stale focused pill still says so and still
     * carries the fetch clock.
     */
    @Test
    fun `a focused pill keeps the staleness and the fetch clock`() {
        val fetchedAt = Instant.parse("2026-09-06T10:15:00Z")
        val pill = usageGlancePillState(
            snapshots = mapOf(HOST to records(claude(40.0, 38.0), fetchedAt = fetchedAt)),
            warnPercent = WARN,
            focus = GlanceFocus(HOST, "claude"),
            now = fetchedAt.plus(Duration.ofHours(1)),
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals(true, pill?.stale)
        assertEquals("10:15", pill?.fetchedClock)
        assertEquals("Usage Claude 38%, read at 10:15", pill?.contentDescription)
    }

    // --- severity ----------------------------------------------------------

    /**
     * A hard-blocked provider stays Blocked even when the window the pill
     * DISPLAYS is comfortable. Claude's 5h bucket is exhausted, so the session
     * cannot run; a green dot over "Claude 38%" would say the opposite.
     */
    @Test
    fun `a blocked record is a Blocked pill regardless of the displayed percent`() {
        val pill = pill(
            snapshots = mapOf(
                HOST to records(
                    record(
                        provider = "claude",
                        status = UsageStatus.Blocked,
                        windows = listOf(window("5h", 98.0), window("7d", 38.0)),
                    ),
                ),
            ),
            focus = GlanceFocus(HOST, "claude"),
        )

        assertEquals(38, pill?.percent)
        assertEquals(PillKind.Blocked, pill?.kind)
    }

    /** A blocked provider that reports NO window at all still surfaces, at 100%. */
    @Test
    fun `a blocked record with no windows focuses at 100 percent`() {
        val pill = pill(
            snapshots = mapOf(
                HOST to records(
                    record(provider = "claude", status = UsageStatus.Blocked, windows = emptyList()),
                ),
            ),
            focus = GlanceFocus(HOST, "claude"),
        )

        assertEquals(100, pill?.percent)
        assertEquals(PillKind.Blocked, pill?.kind)
        assertEquals("Claude 100%", pill?.label)
    }

    /**
     * Severity otherwise describes the number NEXT TO IT. Claude's 5h bucket is
     * at 90% (Approaching on its own) while the displayed 7d window is at 12%;
     * an amber dot over "Claude 12%" would be just as misleading in reverse.
     */
    @Test
    fun `an un-blocked record takes its severity from the displayed window`() {
        val calm = pill(
            snapshots = mapOf(
                HOST to records(
                    record(
                        provider = "claude",
                        windows = listOf(window("5h", 90.0), window("7d", 12.0)),
                    ),
                ),
            ),
            focus = GlanceFocus(HOST, "claude"),
        )

        assertEquals(12, calm?.percent)
        assertEquals(PillKind.Ok, calm?.kind)

        val warned = pill(
            snapshots = mapOf(
                HOST to records(
                    record(
                        provider = "claude",
                        windows = listOf(window("5h", 5.0), window("7d", 87.0)),
                    ),
                ),
            ),
            focus = GlanceFocus(HOST, "claude"),
        )

        assertEquals(87, warned?.percent)
        assertEquals(PillKind.Warn, warned?.kind)
    }

    // --- fallbacks ---------------------------------------------------------

    /**
     * The provider the session's agent maps to has no reading on this host —
     * the user never bought that quota, or `quse` could not probe it. The pill
     * must not vanish; it falls back to the cross-provider answer.
     */
    @Test
    fun `a focus on a provider with no record on that host falls back`() {
        val snapshots = mapOf(HOST to records(grok(sevenDayPercent = 83.0)))

        val focused = pill(snapshots = snapshots, focus = GlanceFocus(HOST, "claude"))

        assertEquals(pill(snapshots = snapshots, focus = null), focused)
        assertEquals("Grok 7d 83%", focused?.label)
    }

    /**
     * A record that exists but carries neither a window nor a block has no
     * number to show. Painting a fake 0% would be worse than the honest
     * cross-provider answer.
     */
    @Test
    fun `a focus on a record with no windows and no block falls back`() {
        val snapshots = mapOf(
            HOST to records(
                record(provider = "claude", status = UsageStatus.Unsupported, windows = emptyList()),
                grok(sevenDayPercent = 83.0),
            ),
        )

        assertEquals("Grok 7d 83%", pill(snapshots, GlanceFocus(HOST, "claude"))?.label)
    }

    /** A focus naming a host that produced no snapshot falls back. */
    @Test
    fun `a focus on an unknown host falls back`() {
        val snapshots = mapOf(HOST to records(claude(40.0, 38.0), grok(83.0)))

        assertEquals(
            pill(snapshots, focus = null),
            pill(snapshots, GlanceFocus(OTHER_HOST, "claude")),
        )
    }

    /** A host that FAILED its usage read carries no records to focus on. */
    @Test
    fun `a focus on a host whose read failed falls back`() {
        val snapshots = mapOf(
            HOST to UsageSnapshot.Failed(HOST, "box", "not connected", FETCHED_AT),
            OTHER_HOST to records(grok(83.0), hostId = OTHER_HOST),
        )

        assertEquals("Grok 7d 83%", pill(snapshots, GlanceFocus(HOST, "claude"))?.label)
    }

    /** A blank provider name is "the host said nothing", never a match attempt. */
    @Test
    fun `a focus with a blank provider falls back`() {
        val snapshots = mapOf(HOST to records(grok(83.0)))

        assertEquals(pill(snapshots, focus = null), pill(snapshots, GlanceFocus(HOST, "   ")))
    }

    /** With nothing readable at all, a focus cannot conjure a pill. */
    @Test
    fun `a focus over an empty snapshot map still hides the pill`() {
        assertNull(pill(snapshots = emptyMap(), focus = GlanceFocus(HOST, "claude")))
    }

    // --- window spans ------------------------------------------------------

    @Test
    fun `window spans follow the producer's own vocabulary`() {
        assertEquals(5, windowSpanHours("5h"))
        assertEquals(24, windowSpanHours("1d"))
        assertEquals(168, windowSpanHours("7d"))
        assertEquals(168, windowSpanHours("weekly"))
        assertEquals(720, windowSpanHours("monthly"))
        assertEquals(720, windowSpanHours(" MONTHLY "))
        // Unknown names score 0: they still compete, they just never outrank a
        // span that is actually known.
        assertEquals(0, windowSpanHours("short_term"))
        assertEquals(0, windowSpanHours(""))
        assertEquals(0, windowSpanHours(null))
    }

    /** `7d` and `weekly` are the same span; the producer's order breaks the tie. */
    @Test
    fun `equal spans keep the first window the host listed`() {
        val pill = pill(
            snapshots = mapOf(
                HOST to records(
                    record(
                        provider = "claude",
                        windows = listOf(window("7d", 38.0), window("weekly", 71.0)),
                    ),
                ),
            ),
            focus = GlanceFocus(HOST, "claude"),
        )

        assertEquals(38, pill?.percent)
    }

    /**
     * A provider whose ONLY window has a name this build has never seen still
     * gets a focused pill — scoring 0 means "unranked", not "ignored".
     */
    @Test
    fun `a provider with only an unrecognised window still focuses on it`() {
        val pill = pill(
            snapshots = mapOf(
                HOST to records(
                    record(provider = "claude", windows = listOf(window("short_term", 44.0))),
                ),
            ),
            focus = GlanceFocus(HOST, "claude"),
        )

        assertEquals(44, pill?.percent)
        assertNull(pill?.window)
    }

    // --- helpers -----------------------------------------------------------

    private fun pill(
        snapshots: Map<Long, UsageSnapshot>,
        focus: GlanceFocus?,
    ): UsageGlancePillState? = usageGlancePillState(
        snapshots = snapshots,
        warnPercent = WARN,
        focus = focus,
        now = FETCHED_AT,
        zoneId = ZoneId.of("UTC"),
    )

    private fun records(
        vararg records: UsageProviderRecord,
        hostId: Long = HOST,
        fetchedAt: Instant = FETCHED_AT,
    ) = UsageSnapshot.Records(hostId, "box-$hostId", records.toList(), fetchedAt)

    private fun record(
        provider: String,
        windows: List<UsageWindow>,
        status: UsageStatus = UsageStatus.Ok,
    ) = UsageProviderRecord(
        provider = provider,
        status = status,
        windows = windows,
        rawStatus = status.name.lowercase(),
    )

    /** `used` is already in percent units, exactly as the usage parser emits. */
    private fun window(name: String, percent: Double) =
        UsageWindow(name = name, used = percent, limit = 100.0, unit = "percent", resetAt = null)

    private fun claude(fiveHourPercent: Double, sevenDayPercent: Double) = record(
        provider = "claude",
        windows = listOf(window("5h", fiveHourPercent), window("7d", sevenDayPercent)),
    )

    private fun grok(sevenDayPercent: Double) =
        record(provider = "grok", windows = listOf(window("7d", sevenDayPercent)))

    private companion object {
        const val HOST = 7L
        const val OTHER_HOST = 8L
        const val WARN = UsageProviderRecord.DEFAULT_WARN_PERCENT
        val FETCHED_AT: Instant = Instant.parse("2026-09-06T10:15:00Z")
    }
}
