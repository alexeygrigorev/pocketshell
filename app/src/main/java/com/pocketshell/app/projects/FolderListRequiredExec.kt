package com.pocketshell.app.projects

import android.util.Log
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.ssh.BoundedSessionExec
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.Dispatchers

/**
 * Cause-trail vocabulary for the issue #2422 required-landing-batch retry.
 *
 * Lives beside the behaviour rather than in `SshFolderListGateway`'s companion so
 * the hot gateway file stays under its size ratchet.
 */
internal object FolderListRequiredExec {
    /**
     * The REQUIRED landing batch over-ran its bound on a still-connected
     * transport and was retried in place, instead of evicting the warm lease and
     * re-running the whole reconcile on a fresh dial.
     */
    const val TRAIL_STAGE_REQUIRED_EXEC_RETRY: String = "folder_list_required_exec_retry"

    /** Retried in place; the warm transport was kept. */
    const val TRAIL_OUTCOME_RETRIED_ON_WARM_TRANSPORT: String = "retried_on_warm_transport"
}

/**
 * Issue #2422 — run the REQUIRED folder-reconcile landing batch, retrying ONCE on
 * the SAME warm transport when it over-runs [timeoutMs].
 *
 * ## Why this exists
 *
 * The required landing batch ([FolderListLandingProbeOwner.executeRequired]) is
 * the ONLY exec in a reconcile whose failure escapes: every other probe (the
 * tmuxctl/aplexer enumerator, the foreign-kind guess, the optional host-CLI
 * decoration, the port scan) is fail-soft and degrades in place. When the required
 * batch throws [FolderListExecTimeoutException], `isStaleChannelSymptom`
 * classifies it as a POISONED TRANSPORT, so the lease attempt evicts the warm
 * lease and the whole chain re-runs on a brand-new SSH dial.
 *
 * On a mobile link that response is expensive, and it fires on a link that is
 * working. Measured on the repo's own netem shaper at ~400 ms RTT / 5 % loss
 * (`Issue1876FolderReconcileMobileRttIntegrationTest`, three samples each, with
 * one required-batch over-run forced per sample):
 *
 * ```text
 * no over-run                     5881-8530 ms,  0 extra SSH logins
 * over-run, evict + fresh dial   15189-17018 ms, 1 extra SSH login   (before)
 * over-run, retry in place       12322-13223 ms, 0 extra SSH logins  (after)
 * ```
 *
 * At that profile 1-3 execs per reconcile already run into their bound, so which
 * one loses is luck; the required batch losing used to add ~8 s and a fresh
 * handshake. That is the scheduled-CI failure this issue was filed for
 * (`elapsedMs=12545 bound=12000`, on a run that still returned a COMPLETE tree)
 * and, on a phone, part of #1870's server-side evidence: four successful publickey
 * authentications in two minutes while the user saw nothing load.
 *
 * ## Why a retry, and why on the SAME transport
 *
 * [BoundedSessionExec] abandons a slow exec WITHOUT touching the transport, and
 * records `transportAlive` in the cause trail, precisely because a starved exec is
 * not evidence of a dead link — its own doc states that "callers get the retryable
 * timeout and retry on the SAME warm transport". That retry never existed for this
 * caller; the only recovery on offer was the lease-level heal, which pays a fresh
 * TCP+SSH handshake and redoes every probe. So a host command that is merely SLOW
 * (a congested mobile link, a cold host Python CLI) is answered here by one more
 * attempt on the warm transport.
 *
 * ## The load-bearing negative
 *
 * Only a SECOND over-run — or a transport sshj already knows is down — falls
 * through to the UNCHANGED evict-and-re-dial heal, via [retryBoundedExec]'s own
 * abandon-and-throw. Over-guarding that away would strand a genuinely dead
 * transport and stop the tree recovering at all, which is strictly worse than the
 * doubled reconcile this removes; `FolderListGatewayExecTimeoutTest`'s persistent
 * and transient wedge pair pins both halves.
 *
 * The worst case is bounded and no worse than before: two abandoned execs
 * (2 x [timeoutMs]) instead of one abandoned exec plus a fresh dial and a full
 * second chain.
 *
 * @param retryBoundedExec the caller's ordinary bounded exec — it keeps the
 *   unchanged abandon + log + [FolderListExecTimeoutException] behaviour, so this
 *   function never has to re-implement the failure path.
 */
internal suspend fun SshSession.execRequiredLandingBounded(
    command: String,
    timeoutMs: Long,
    retryBoundedExec: suspend (String) -> ExecResult,
): ExecResult {
    val first = BoundedSessionExec.execBounded(
        session = this,
        command = command,
        timeoutMs = timeoutMs,
        dispatcher = Dispatchers.IO,
        callerSite = SshFolderListGateway.TRAIL_CALLER_SITE,
    )
    if (first != null) return first
    if (!isConnected) {
        // sshj already knows the transport is gone: this is the DEAD case the
        // lease-level evict-and-re-dial heal owns. Retrying here would only burn
        // another bound before reaching it.
        Log.w(
            SshFolderListGateway.PROBE_LOG_TAG,
            "required folder-list landing exec over-ran ${timeoutMs}ms on a DISCONNECTED " +
                "transport; deferring to the lease heal (issue #2422)",
        )
        throw FolderListExecTimeoutException(command, timeoutMs)
    }
    Log.w(
        SshFolderListGateway.PROBE_LOG_TAG,
        "required folder-list landing exec over-ran ${timeoutMs}ms on a still-CONNECTED " +
            "transport; retrying ONCE on the same warm lease instead of evicting it and " +
            "re-running the whole reconcile on a fresh dial (issue #2422)",
    )
    // The retry must not be invisible: without a breadcrumb an exported diagnostics
    // report cannot tell "the landing batch over-ran once and recovered in place"
    // from "the reconcile was simply slow" — the distinction that took a netem
    // reproduction to establish in the first place.
    ReconnectCauseTrail.record(
        stage = FolderListRequiredExec.TRAIL_STAGE_REQUIRED_EXEC_RETRY,
        outcome = FolderListRequiredExec.TRAIL_OUTCOME_RETRIED_ON_WARM_TRANSPORT,
        cause = "required_exec_no_result_within_bound",
        "callerSite" to SshFolderListGateway.TRAIL_CALLER_SITE,
        "timeoutMs" to timeoutMs,
        "transportAlive" to true,
    )
    return retryBoundedExec(command)
}
