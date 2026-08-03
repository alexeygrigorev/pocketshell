package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.tmux.TmuxClient
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

    /** Claim the bounded recovery window before either the reseed or dead-socket arm starts. */
    fun beginWithinGraceRecovery(claim: WithinGraceRecoveryClaim) {
        recoveryOwnership = GraceRecoveryOwnership.WithinGrace(claim)
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

    fun recoveryWasSuperseded(claim: WithinGraceRecoveryClaim): Boolean =
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
