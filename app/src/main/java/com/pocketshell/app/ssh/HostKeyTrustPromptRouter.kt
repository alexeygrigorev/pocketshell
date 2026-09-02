package com.pocketshell.app.ssh

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process owner for host-key failures raised below individual feature screens.
 *
 * The authoritative lease connector and the port-forward connector report only
 * typed verification failures. This router decides what a report is allowed to
 * DO, and the two answers are deliberately different (issue #2463):
 *
 *  * **Always** annotate: [hostsNeedingTrust] carries every host whose most
 *    recent connect attempt failed host-key verification, so the host list can
 *    mark the card and let the user act when *they* are ready. A background
 *    cold-launch reprobe, a port-forward resume-on-launch, a pooled reconnect —
 *    none of those may take the screen away from whatever the user is doing.
 *  * **Only for a foreground, user-initiated connect**: emit on [trustPrompts]
 *    so `MainActivity`'s navigator opens the shared explicit Trust/Replace
 *    screen. A call site opts in either by [armUserInitiatedConnect] — a bounded
 *    window, used when the connect happens on a screen the tap navigates INTO
 *    (the host-list card tap / setup badge / "Re-check setup", and the
 *    "Resume last session" row) — or by wrapping the attempt itself in
 *    [withUserInitiatedConnect] — a strict bracket, used when the connect is
 *    in-scope (the port-forward panel's start/discover, the watched-folders
 *    "Discover from remote" probe). Everything else is background by
 *    construction; there is no "background" flag to forget.
 *
 *    The complete set of production opt-ins is deliberately small and is
 *    covered end to end by `Issue2463BackgroundHostKeyTrustNoNavJourneyE2eTest`.
 *
 * ### The bug this shape replaces
 *
 * v0.4.47's Room v19 migration leaves `trustedHostKeySha256` null for every
 * pre-existing host, so on the first launch after the update the host-list
 * cold-launch reprobe fails host-key verification for hosts it has never
 * probed. The old router reported that into a retained `MutableStateFlow<Long?>`
 * which `MainActivity`'s navigator consumed **unconditionally**, so ~1 s after
 * launch — with no tap — the user was thrown off the Hosts list onto the
 * Trust/Test-connect screen for whichever host lost the race.
 *
 * ### Why [trustPrompts] is a non-replaying event, not retained state
 *
 * Being a `StateFlow` on a process-wide singleton, the old field also *replayed*:
 * a freshly created `MainActivity` — after process death, a configuration
 * change, a second launch — collected the current value and navigated with no
 * user action behind it. `replay = 0` means only a collector subscribed **at
 * emission time** ever sees a prompt, so a new Activity starts from nothing.
 * That holds independently of the foreground gate above; neither mechanism is
 * load-bearing alone.
 */
@Singleton
class HostKeyTrustPromptRouter internal constructor(
    private val nowMs: () -> Long,
) {
    @Inject constructor() : this(nowMs = System::currentTimeMillis)

    // replay = 0: a late/second collector (a new Activity) inherits nothing.
    // extraBufferCapacity keeps a burst from suspending the reporting connector;
    // DROP_OLDEST is correct because the newest failure is the actionable one.
    private val _trustPrompts = MutableSharedFlow<Long>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Host ids the user should be asked to trust *right now*, because they just
     * asked for that host. One-shot events; never replayed to a new collector.
     */
    val trustPrompts: SharedFlow<Long> = _trustPrompts.asSharedFlow()

    private val _hostsNeedingTrust = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Host ids whose last connect attempt failed host-key verification. Drives
     * the host-card status indicator so a background failure stays *visible*
     * without hijacking navigation.
     */
    val hostsNeedingTrust: StateFlow<Set<Long>> = _hostsNeedingTrust.asStateFlow()

    /** hostId -> deadline (wall clock ms) of the user's foreground intent window. */
    private val armedUntilMs = ConcurrentHashMap<Long, Long>()

    /** hostId -> number of explicitly bracketed foreground attempts in flight. */
    private val inFlightUserConnects = ConcurrentHashMap<Long, Int>()

    /**
     * hostId -> monotonic count of host-key verification failures reported.
     *
     * Read only through [withHostKeyFailureWatch]. This is deliberately NOT
     * [hostsNeedingTrust]: that set is sticky (cleared only by a successful
     * connect) and answers "should this card be marked?", never "did the
     * attempt I just ran fail on host-key trust?".
     */
    private val hostKeyFailureCounts = ConcurrentHashMap<Long, Long>()

    fun report(hostId: Long?, failure: Throwable) {
        if (hostId == null || failure.findHostKeyVerificationFailure() == null) return
        hostKeyFailureCounts.merge(hostId, 1L) { existing, _ -> existing + 1L }
        _hostsNeedingTrust.update { it + hostId }
        if (isUserInitiated(hostId)) {
            _trustPrompts.tryEmit(hostId)
        }
    }

    /**
     * Run [block] — a SINGLE connect attempt for [hostId] — and report whether a
     * host-key verification failure was raised for that host **while it ran**.
     *
     * Issue #2463 (round-2 reviewer finding 2): a caller that needs to know
     * "did THIS attempt fail on host-key trust?" must never read
     * [hostsNeedingTrust] to answer it. That set is process-wide and survives
     * until a *successful* connect, so once a background cold-launch reprobe has
     * annotated a host, every later attempt that fails for some OTHER reason
     * (server down, network drop, auth) would read as a host-key failure. In
     * `HostListViewModel` that turned such a tap into a silent no-op: no folder
     * screen with its retry UX, no Trust screen (nothing emitted on
     * [trustPrompts] this time), no message at all.
     */
    suspend fun <T> withHostKeyFailureWatch(
        hostId: Long,
        block: suspend () -> T,
    ): AttemptOutcome<T> {
        val before = hostKeyFailureCounts[hostId] ?: 0L
        val value = block()
        val after = hostKeyFailureCounts[hostId] ?: 0L
        return AttemptOutcome(value = value, failedHostKeyVerification = after != before)
    }

    /** Result of one watched connect attempt — see [withHostKeyFailureWatch]. */
    class AttemptOutcome<T>(
        val value: T,
        val failedHostKeyVerification: Boolean,
    )

    /**
     * The host connected (or its trust was confirmed), so the card annotation is
     * stale. Called on every successful connect through the reporting
     * connectors, so a confirmed fingerprint clears the marker on its own.
     */
    fun clearTrustAttention(hostId: Long?) {
        hostId ?: return
        _hostsNeedingTrust.update { if (hostId in it) it - hostId else it }
    }

    /**
     * The user just asked to open/re-check [hostId] and is watching the result.
     * Opens a bounded window in which a host-key failure for that host may take
     * the screen to the Trust/Replace prompt.
     *
     * A window rather than a strict bracket because the tap's connect is not
     * always the tapped screen's own: a host whose bootstrap cache is still
     * fresh navigates straight on, and the connect that discovers the
     * unconfirmed key belongs to the folder/session screen the tap opened. The
     * window is short enough that a later background reprobe never inherits it.
     */
    fun armUserInitiatedConnect(hostId: Long, windowMs: Long = USER_INTENT_WINDOW_MS) {
        val deadline = nowMs() + windowMs
        armedUntilMs.merge(hostId, deadline) { existing, fresh -> maxOf(existing, fresh) }
    }

    /**
     * Drop the foreground window for [hostId]. Called by `MainActivity`'s
     * navigator the moment a prompt has actually been delivered to the
     * Trust/Replace screen: the user's ask has been answered, so the remaining
     * window must not let an unrelated background failure for the same host
     * navigate again on the back of it.
     */
    fun disarmUserInitiatedConnect(hostId: Long) {
        armedUntilMs.remove(hostId)
    }

    /**
     * Test-only reset of this process-scoped router.
     *
     * The annotation set, the failure counters and the foreground windows are
     * all process-wide by design (the same singleton is shared by every
     * Activity). In an instrumentation run one process hosts many test classes
     * and Room host ids repeat after `clearAllTables()`, so a class that
     * annotated host id 1 would otherwise leave a red status dot on an unrelated
     * later class's freshly seeded host id 1. Connected tests reach this through
     * [com.pocketshell.app.testaccess.TestAccessEntryPoint], the same way they
     * already reset `StaleSessionPromptController` (#2249).
     */
    @VisibleForTesting
    fun resetForTest() {
        _hostsNeedingTrust.value = emptySet()
        armedUntilMs.clear()
        inFlightUserConnects.clear()
        hostKeyFailureCounts.clear()
    }

    /**
     * Bracket a connect the user explicitly asked for. Unlike
     * [armUserInitiatedConnect] this cannot time out under a slow handshake:
     * the attempt counts as foreground for as long as it actually runs.
     */
    suspend fun <T> withUserInitiatedConnect(hostId: Long, block: suspend () -> T): T {
        inFlightUserConnects.merge(hostId, 1) { existing, _ -> existing + 1 }
        return try {
            block()
        } finally {
            inFlightUserConnects.computeIfPresent(hostId) { _, depth ->
                (depth - 1).takeIf { it > 0 }
            }
        }
    }

    @VisibleForTesting
    internal fun isUserInitiated(hostId: Long): Boolean =
        inFlightUserConnects.containsKey(hostId) ||
            (armedUntilMs[hostId]?.let { it > nowMs() } == true)

    internal companion object {
        /**
         * How long a host-card tap keeps counting as "the user is waiting for
         * this host". Long enough to cover the tap's bootstrap probe plus the
         * folder/session connect it navigates into on a cache hit; far shorter
         * than the cold-launch reprobe cadence, which is once per ViewModel.
         */
        internal const val USER_INTENT_WINDOW_MS: Long = 20_000L
    }
}
