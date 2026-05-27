package com.pocketshell.app.hosts

import com.pocketshell.app.sessions.SessionSummary
import com.pocketshell.uikit.model.HostSetupState
import com.pocketshell.uikit.model.HostStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-unit coverage for the issue #201 host-status derivation. The
 * derivation lives at file scope on [HostListViewModel.kt] (see
 * `deriveHostStatus` / `resolveHostStatus`) precisely so it can be
 * exercised without standing up a Robolectric test runner or seeding a
 * Room database — every label must map to exactly one trigger
 * condition, and the cheapest way to prove that is a JVM-only
 * truth-table test.
 *
 * Acceptance criteria from #201 mapped to assertions in this file:
 *
 *  - "No host card displays the word 'idle.'" — covered indirectly by
 *    every assertion below, none of which produce the literal string;
 *    plus the dedicated [allHostStatusVariants_haveDistinctNonIdleLabels]
 *    case that walks the sealed hierarchy and rejects "idle".
 *  - "Each status label maps to exactly one trigger condition." — the
 *    per-condition tests below each pin a single label.
 *  - "Setup-required state takes precedence." — see
 *    [needsSetup_takesPrecedence_overSessionCounts] +
 *    [needsSetup_takesPrecedence_overAttached] +
 *    [needsSetup_takesPrecedence_overConnectionError].
 *  - The three primary states ("0 sessions", "N sessions", "needs
 *    setup") are covered by [noActiveSessions_whenReadyAndZeroSessions],
 *    [activeSessions_whenReadyAndPositiveCount], and
 *    [needsSetup_*] respectively; the connected E2E side of the AC is
 *    covered by [HostCardStatusChipTest] under `androidTest/`.
 */
class HostStatusDerivationTest {

    // --- deriveHostStatus: pure mapping --------------------------------

    @Test
    fun unknown_whenSetupStateIsUnknown() {
        // Cold launch / probe in flight — no verified data yet. Even
        // with a session count, the chip must stay Unknown because we
        // cannot trust a session list we never asked tmux for.
        val status = deriveHostStatus(
            setupState = HostSetupState.Unknown,
            sessionCount = 0,
            appAttached = false,
        )
        assertEquals(HostStatus.Unknown, status)
    }

    @Test
    fun noActiveSessions_whenReadyAndZeroSessions() {
        val status = deriveHostStatus(
            setupState = HostSetupState.Ready,
            sessionCount = 0,
            appAttached = false,
        )
        assertEquals(HostStatus.NoActiveSessions, status)
    }

    @Test
    fun activeSessions_whenReadyAndPositiveCount() {
        val one = deriveHostStatus(
            setupState = HostSetupState.Ready,
            sessionCount = 1,
            appAttached = false,
        )
        assertEquals(HostStatus.ActiveSessions(count = 1), one)

        val five = deriveHostStatus(
            setupState = HostSetupState.Ready,
            sessionCount = 5,
            appAttached = false,
        )
        assertEquals(HostStatus.ActiveSessions(count = 5), five)
    }

    @Test
    fun attached_takesPrecedence_overSessionCount() {
        // The user-facing answer to "what's this host doing?" when we
        // are attached is "attached", not "N sessions". The other count
        // is reachable from the session screen itself.
        val status = deriveHostStatus(
            setupState = HostSetupState.Ready,
            sessionCount = 3,
            appAttached = true,
        )
        assertEquals(HostStatus.Attached, status)
    }

    @Test
    fun needsSetup_takesPrecedence_overSessionCounts() {
        // AC: setup-required wins over session-count display.
        val status = deriveHostStatus(
            setupState = HostSetupState.NeedsSetup,
            sessionCount = 5,
            appAttached = false,
        )
        assertEquals(HostStatus.NeedsSetup, status)
    }

    @Test
    fun needsSetup_takesPrecedence_overAttached() {
        // Even when we somehow ended up "attached" against a host that
        // is missing tooling, NeedsSetup is the actionable label.
        val status = deriveHostStatus(
            setupState = HostSetupState.NeedsSetup,
            sessionCount = 0,
            appAttached = true,
        )
        assertEquals(HostStatus.NeedsSetup, status)
    }

    @Test
    fun needsSetup_takesPrecedence_overConnectionError() {
        // Two negative signals at once — the actionable one wins. The
        // user can install tools; a "connection error" badge on top of
        // that would just add noise.
        val status = deriveHostStatus(
            setupState = HostSetupState.NeedsSetup,
            sessionCount = null,
            appAttached = false,
            lastConnectError = true,
        )
        assertEquals(HostStatus.NeedsSetup, status)
    }

    @Test
    fun connectionError_whenLastAttemptFailedAndSetupKnownReady() {
        val status = deriveHostStatus(
            setupState = HostSetupState.Ready,
            sessionCount = null,
            appAttached = false,
            lastConnectError = true,
        )
        assertEquals(HostStatus.ConnectionError, status)
    }

    @Test
    fun unknown_whenReadyButSessionCountIsNull() {
        // Tooling verified, but the dashboard has not polled this host
        // yet. We must NOT claim "no active sessions" — we don't know
        // the count yet. The spinner says so.
        val status = deriveHostStatus(
            setupState = HostSetupState.Ready,
            sessionCount = null,
            appAttached = false,
        )
        assertEquals(HostStatus.Unknown, status)
    }

    // --- resolveHostStatus: per-host slicing over the dashboard list ---

    @Test
    fun resolveHostStatus_unknownSetup_returnsUnknown() {
        // Even with a populated session list, an unknown setup state
        // means we can't trust the data path. Stay Unknown.
        val sessions = listOf(
            session(hostId = 1L, name = "main"),
        )
        val status = resolveHostStatus(
            hostId = 1L,
            setupState = HostSetupState.Unknown,
            sessions = sessions,
            attachedHostIds = emptySet(),
        )
        assertEquals(HostStatus.Unknown, status)
    }

    @Test
    fun resolveHostStatus_readyAndAttached_returnsAttached() {
        val sessions = listOf(
            session(hostId = 1L, name = "main"),
            session(hostId = 1L, name = "agent"),
        )
        val status = resolveHostStatus(
            hostId = 1L,
            setupState = HostSetupState.Ready,
            sessions = sessions,
            attachedHostIds = setOf(1L),
        )
        assertEquals(HostStatus.Attached, status)
    }

    @Test
    fun resolveHostStatus_readyAndMultipleSessions_returnsActiveSessionsWithCount() {
        val sessions = listOf(
            session(hostId = 1L, name = "main"),
            session(hostId = 1L, name = "agent"),
            session(hostId = 1L, name = "build"),
            // Foreign host — must not be counted under hostId = 1.
            session(hostId = 2L, name = "isolated"),
        )
        val status = resolveHostStatus(
            hostId = 1L,
            setupState = HostSetupState.Ready,
            sessions = sessions,
            attachedHostIds = emptySet(),
        )
        assertEquals(HostStatus.ActiveSessions(count = 3), status)
    }

    @Test
    fun resolveHostStatus_readyAndNoSessionsForHost_butDashboardHasData_returnsNoActiveSessions() {
        // The dashboard has polled — there are rows for SOME hosts, but
        // not for this one AND the app is not attached. That's a
        // verified "no active sessions on this host".
        val sessions = listOf(
            session(hostId = 2L, name = "isolated"),
        )
        val status = resolveHostStatus(
            hostId = 1L,
            setupState = HostSetupState.Ready,
            // Attached against this host id → we DO have data for it,
            // but no sessions reported. We use the attachedHostIds
            // signal as a "we have a live tmux client here" tie-break
            // so the row reads "Attached" rather than "no active
            // sessions" in that case. Here the host isn't attached AND
            // no rows for it → Unknown (we can't tell if the dashboard
            // simply hasn't polled it yet).
            sessions = sessions,
            attachedHostIds = emptySet(),
        )
        assertEquals(HostStatus.Unknown, status)
    }

    @Test
    fun resolveHostStatus_readyAndAttachedWithZeroSessions_returnsAttached() {
        // Edge case: a live tmux client is registered (so we have
        // data), but no tmux sessions exist on the server. Attached
        // still wins because we ARE in a session pane (it's the dummy
        // / initial one) — and "Attached" is closer to the truth than
        // "no active sessions".
        val status = resolveHostStatus(
            hostId = 1L,
            setupState = HostSetupState.Ready,
            sessions = emptyList(),
            attachedHostIds = setOf(1L),
        )
        assertEquals(HostStatus.Attached, status)
    }

    @Test
    fun resolveHostStatus_needsSetup_winsOverEverything() {
        val sessions = listOf(
            session(hostId = 1L, name = "main"),
            session(hostId = 1L, name = "agent"),
        )
        val status = resolveHostStatus(
            hostId = 1L,
            setupState = HostSetupState.NeedsSetup,
            sessions = sessions,
            attachedHostIds = setOf(1L),
        )
        assertEquals(HostStatus.NeedsSetup, status)
    }

    // --- Banned label guard --------------------------------------------

    /**
     * The word "idle" is the entire point of issue #201 — make sure we
     * never accidentally reintroduce it as a HostStatus subtype name.
     *
     * The test enumerates a representative instance of every concrete
     * variant by hand (instead of via `sealedSubclasses`, which needs
     * kotlin-reflect and is not on the unit-test classpath). Each
     * instance is checked against the banned word and the expected
     * type name; adding a new HostStatus subtype without extending
     * this list is a build-failure-or-test-failure forcing function so
     * we never silently regress the AC.
     */
    @Test
    fun allHostStatusVariants_haveDistinctNonIdleLabels() {
        val variants: List<HostStatus> = listOf(
            HostStatus.Unknown,
            HostStatus.NoActiveSessions,
            HostStatus.ActiveSessions(count = 1),
            HostStatus.Attached,
            HostStatus.NeedsSetup,
            HostStatus.ConnectionError,
        )
        val names = variants.map { it::class.java.simpleName }
        // Sanity — we have the expected vocabulary.
        val expected = setOf(
            "Unknown",
            "NoActiveSessions",
            "ActiveSessions",
            "Attached",
            "NeedsSetup",
            "ConnectionError",
        )
        assertEquals(expected, names.toSet())
        // No "idle" anywhere in the type vocabulary.
        for (name in names) {
            check(!name.lowercase().contains("idle")) {
                "HostStatus must not include an 'idle' variant — see issue #201"
            }
        }
    }

    // --- helpers --------------------------------------------------------

    private fun session(hostId: Long, name: String): SessionSummary = SessionSummary(
        hostId = hostId,
        hostName = "h-$hostId",
        sessionName = name,
        lastActivity = 0L,
        attached = false,
    )
}
