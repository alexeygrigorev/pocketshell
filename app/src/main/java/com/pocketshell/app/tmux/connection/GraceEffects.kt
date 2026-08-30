package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.tmux.TmuxClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Stable identity of the control client a within-grace recovery was claimed for. */
@JvmInline
value class GraceClientId(val value: Int)

fun TmuxClient.graceClientId(): GraceClientId = GraceClientId(System.identityHashCode(this))

/**
 * Typed ownership token for the bounded within-grace recovery window.
 *
 * The target and client are both captured at the foreground decision point. Liveness may only
 * defer when the token still names its current target/client; a later replacement client is not
 * accidentally hidden behind stale grace ownership.
 */
data class WithinGraceRecoveryClaim(
    val targetId: SessionId,
    val clientId: GraceClientId?,
)

/** The single typed owner state shared by grace dispatch, liveness, status, and reveal. */
sealed interface GraceRecoveryOwnership {
    data object Idle : GraceRecoveryOwnership

    data class WithinGrace(val claim: WithinGraceRecoveryClaim) : GraceRecoveryOwnership
}

/**
 * EPIC #792 Slice B — the GRACE IO owner (background detach + within-grace foreground
 * reattach/heal).
 *
 * Before Slice B the background-grace IO bodies lived inline on
 * [com.pocketshell.app.tmux.TmuxSessionViewModel] and were each invoked through TWO
 * paths: the [ConnectionEffectDriver]'s effect seam (`backgroundedEffect` /
 * `foregroundReattachEffect`) AND a direct inline call from `onAppBackgrounded()` /
 * `onAppForegrounded()` (with a `?.invoke() ?:` test twin). That dual-write is exactly
 * the D28(4) half-migration condition — two writers of the same effect.
 *
 * Slice B collapses it to ONE owner. [GraceEffects] is the SOLE dispatcher of the grace
 * IO triggers; the deeply-coupled IO bodies stay as [GraceIo] capability methods the VM
 * implements (the established seam pattern from slice B1/#738 and 1c-iv-c/#754 — the
 * effect class owns the *trigger/decision*, the VM owns the coupled primitive). Both the
 * driver seam and the lifecycle entrypoints now route through this one object, so the
 * inline twin invocation is DELETED (D22 hard-cut — no dual-write, single path).
 *
 * Dispatch behaviour remains direct: each method calls the same [GraceIo] body the inline
 * path called. Issue #1954 additionally keeps the bounded recovery claim here so liveness,
 * status, and reveal consult the same controller-adjacent owner.
 *
 * @param io the VM-implemented capability that performs the actual (deeply VM-coupled)
 *   grace teardown/reseed/heal IO. [GraceEffects] never touches transport/lease/client
 *   state directly; it owns effect dispatch plus the typed bounded recovery claim.
 */
class GraceEffects(private val io: GraceIo) {

    var recoveryOwnership: GraceRecoveryOwnership = GraceRecoveryOwnership.Idle
        private set

    /**
     * The coroutines the CURRENT bounded owner runs (its hold-release timer and its heal
     * retry loop), so a superseding owner can retire them instead of racing them. Reset by
     * [beginWithinGraceRecovery] so a fresh claim never inherits the previous claim's jobs.
     */
    private var recoveryJobs: List<Job> = emptyList()

    /**
     * Issue #2415: the claim most recently taken away by [retireForSupersedingOwner], so a stale
     * coroutine belonging to it can be told "you were superseded" even though the window is now
     * [GraceRecoveryOwnership.Idle] rather than owned by a visible successor.
     *
     * Without it [recoveryWasSuperseded] answers `false` for a RETIRED claim (Idle is not "someone
     * else owns it"), and the abandoned heal's grace-expiry handoff would be safe only by
     * construction — it relies on [retireForSupersedingOwner] having cancelled the tracked job and
     * on both operations sharing `Main.immediate`. That is true today, but it makes a
     * *cancellation* detail load-bearing for a *policy* decision ("may this claim install a
     * reconnect ladder?"). Recording the retirement makes the answer explicit.
     *
     * Deliberately NOT set by [endWithinGraceRecovery]: a claim whose own bounded timer expired was
     * not superseded, and it MUST still hand off to the loud auto-reconnect ladder — the hold-release
     * timer and the heal retry loop both end at `passiveDisconnectGraceMs`, so at legitimate
     * exhaustion the window is Idle too, and conflating the two states would silently delete the
     * beyond-grace reconnect.
     */
    private var retiredClaim: WithinGraceRecoveryClaim? = null

    /** Claim the bounded recovery window before either the reseed or dead-socket arm starts. */
    fun beginWithinGraceRecovery(claim: WithinGraceRecoveryClaim) {
        recoveryOwnership = GraceRecoveryOwnership.WithinGrace(claim)
        recoveryJobs = emptyList()
        retiredClaim = null
    }

    /** Register a coroutine owned by the current bounded claim. */
    fun trackRecoveryJob(job: Job) {
        recoveryJobs = recoveryJobs + job
    }

    /**
     * Issue #2415: an accepted connect to a DIFFERENT session retires the bounded within-grace
     * recovery owner. Returns true when a claim for another session was actually retired (so the
     * caller can record the breadcrumb), false when the window is idle or already owned by
     * [targetId].
     *
     * `TmuxSessionViewModel.connect()` already cancels every OTHER competing recovery owner
     * before accepting a new target — the passive-grace job, the paused ladder, the
     * auto-reconnect ladder, the lifecycle/network coalesce. This claim was the one hole, and it
     * is the loudest one because it owns a 60 s retry loop over the SHARED per-host transport.
     * Left running for a session the user has left, it
     *
     *  1. re-dials and then `ExplicitDisconnect`-tears-down that shared transport every 250 ms
     *     (~190 iterations in the reported logcat), which closes the newly-opened session's `-CC`
     *     channel between its `panes-ready` and its reveal — `control channel closed before the
     *     connect could reveal session `codex` as live`, the reveal guard correctly refusing to
     *     publish `Live` over the corpse — and
     *  2. on grace expiry hands off `scheduleAutoReconnect(target = <the abandoned session>)`,
     *     so the abandoned session's terminal "Session “…” has ended." lands on the screen the
     *     user opened for a DIFFERENT, live session and drops them to the folder list.
     *
     * Guarded on session identity, never unconditional: a SAME-session re-entry must keep its own
     * claim or the #1538/#754/#1954 within-grace ride-through regresses. No new recovery writer —
     * [retireForSupersedingOwner] simply gains its second, symmetric caller (D28).
     */
    fun retireIfOwnedByOtherSession(targetId: SessionId): Boolean {
        val owned = (recoveryOwnership as? GraceRecoveryOwnership.WithinGrace)?.claim ?: return false
        if (owned.targetId == targetId) return false
        retireForSupersedingOwner()
        return true
    }

    /**
     * Issue #822: hand the channel over to a USER-initiated in-place reconnect.
     *
     * The bounded within-grace owner and the manual reconnect were two writers on the same
     * transport, and that is why tapping the in-place action did NOT recover while a
     * switch-away-and-back did — a switch retires this owner, a Retry did not. On device the
     * manual reconnect acquired a healthy fresh lease and the still-running grace loop then
     * failed its own re-dial with `proven-dead SSH lease was no longer current` every 250 ms,
     * tearing the successor down until the session settled on `Unreachable`. Retiring the
     * claim AND cancelling its coroutines makes the manual reconnect the single owner (D28);
     * the hold-release job's own guarded completion handler restores the reveal projection,
     * so the honest "Attaching…" for the user's explicit retry is not suppressed.
     *
     * Issue #2415: an accepted connect to a DIFFERENT session is the second superseding owner,
     * and #822 only wired the first. Both now route here — see [retireIfOwnedByOtherSession].
     */
    fun retireForSupersedingOwner() {
        retiredClaim = (recoveryOwnership as? GraceRecoveryOwnership.WithinGrace)?.claim ?: retiredClaim
        recoveryOwnership = GraceRecoveryOwnership.Idle
        val jobs = recoveryJobs
        recoveryJobs = emptyList()
        jobs.forEach { it.cancel() }
    }

    /** Release only the claim whose bounded owner timer completed; stale timers are inert. */
    fun endWithinGraceRecovery(claim: WithinGraceRecoveryClaim) {
        if (recoveryOwnership == GraceRecoveryOwnership.WithinGrace(claim)) {
            recoveryOwnership = GraceRecoveryOwnership.Idle
        }
    }

    /** True only for the target/client whose grace recovery currently owns the channel. */
    fun ownsRecovery(targetId: SessionId, clientId: GraceClientId): Boolean {
        val owned = (recoveryOwnership as? GraceRecoveryOwnership.WithinGrace)?.claim ?: return false
        return owned.targetId == targetId && owned.clientId == clientId
    }

    fun isWithinGraceRecoveryActive(): Boolean =
        recoveryOwnership is GraceRecoveryOwnership.WithinGrace

    /**
     * True when [claim] no longer authorises the bounded owner's grace-expiry handoff to the loud
     * auto-reconnect ladder — either a DIFFERENT claim now owns the window, or (issue #2415) this
     * claim was explicitly retired by a superseding owner. False for a claim that simply ran its
     * bounded window out, which is the case that MUST still hand off.
     */
    fun recoveryWasSuperseded(claim: WithinGraceRecoveryClaim): Boolean =
        retiredClaim == claim ||
            (recoveryOwnership as? GraceRecoveryOwnership.WithinGrace)?.claim?.let { it != claim } == true

    /** Retry only while this exact claim remains the single bounded recovery owner. */
    suspend fun retryWithinGraceRecovery(
        claim: WithinGraceRecoveryClaim,
        timeoutMs: Long,
        retryDelayMs: Long,
        attempt: suspend (firstAttempt: Boolean) -> Boolean,
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        var firstAttempt = true
        while (recoveryOwnership == GraceRecoveryOwnership.WithinGrace(claim)) {
            if (attempt(firstAttempt)) return@withTimeoutOrNull true
            firstAttempt = false
            delay(retryDelayMs)
        }
        false
    } == true

    /**
     * The clean background-detach teardown. Fired by the [ConnectionEffectDriver] when
     * the controller transitions INTO `Backgrounded` — the SOLE trigger (the inline
     * `backgroundDetachJob` launch was already deleted in slice B1/#738; Slice B makes
     * this class the single owner of the dispatch). Delegates to [GraceIo.launchBackgroundDetachTeardown].
     */
    fun onBackgrounded() {
        io.launchBackgroundDetachTeardown()
    }

    /**
     * The within-grace FOREGROUND reseed-only reattach. The warm `-CC` control channel
     * was NEVER torn down across the brief background, so on return there is nothing to
     * reconnect: re-capture the active pane and let the existing SeedLanded promote the
     * controller back to Live. NO connect(), NO "Attaching…" overlay (the D21 within-grace
     * contract). Driven from BOTH the driver's `foregroundReattachEffect` seam (the
     * controller's Backgrounded→Reattaching edge) AND the synchronous
     * `onAppForegrounded(resumedWithinGrace=true)` reseed-eligible branch — both now route
     * here, so there is one reseed owner. Delegates to [GraceIo.launchForegroundReattachReseed].
     */
    fun onForegroundReattachReseed() {
        io.launchForegroundReattachReseed()
    }

    /**
     * The within-grace foreground SILENT heal of a `-CC` socket that DROPPED while
     * backgrounded (WiFi→cellular handoff / Doze). The reseed-only fast path declined
     * (the dropped socket killed the warm lease), so re-open a fresh `-CC` control client
     * over a freshly-acquired lease and reseed — SILENTLY, no band, no overlay. Driven
     * from the synchronous `onAppForegrounded(resumedWithinGrace=true)` heal branch.
     * Delegates to [GraceIo.launchForegroundHealWithinGrace].
     */
    fun onForegroundHealWithinGrace() {
        io.launchForegroundHealWithinGrace()
    }

    /**
     * The narrow capability the VM implements so [GraceEffects] can own the grace effect
     * dispatch without owning the deeply VM-coupled IO bodies (lease manager, runtime
     * cache, reveal machine, telemetry, ~dozen private fields). Each method is the SAME
     * body the inline path invoked; only the dispatch owner moved.
     */
    interface GraceIo {
        /** Launch the clean background-detach teardown (NonCancellable full teardown). */
        fun launchBackgroundDetachTeardown()

        /** Run the within-grace foreground reseed-only reattach over the warm client. */
        fun launchForegroundReattachReseed()

        /** Re-open a fresh `-CC` client over a fresh lease and reseed, silently. */
        fun launchForegroundHealWithinGrace()
    }
}
