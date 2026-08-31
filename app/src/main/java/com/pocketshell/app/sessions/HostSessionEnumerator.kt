package com.pocketshell.app.sessions

import android.util.Log
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.core.ssh.ExecResult
import kotlinx.coroutines.CancellationException

/**
 * The host's session ENUMERATOR: `pocketshell sessions list --json` (tmuxctl +
 * aplexer across every socket), with a human-table fallback for older hosts
 * that reject `--json`.
 *
 * Issue #2377: this used to live only inside
 * [com.pocketshell.app.projects.FolderListPocketshellEnumerator], so the folder
 * list was the only surface that knew the difference between "the host has no
 * other sessions" and "we could not read the host's session list". The session
 * PICKER — the app's other session list — had its own narrower copy and
 * short-circuited on a live `tmux -CC` client, which is attached to exactly ONE
 * tmux server. Both surfaces now share this one state machine so a fix to the
 * undercount class cannot land on one list and miss the other;
 * `FolderListPocketshellEnumerator` is a thin row-type adapter over it.
 *
 * Issue #2348: the enumerator is bounded on the 12s mobile reconcile path. A
 * hung or timed-out JSON exec must NOT spend a second human exec; JSON
 * empty-success must not fall through to human either. Exit 0 with a non-JSON
 * body (the 0.4.45 fixture prints the human table for `--json`) is parsed
 * in-process from that same stdout — never a second `humanCommand` hop. Stdin
 * is closed on the JSON body so a wrap()-style first-statement `read()` cannot
 * park the hop until the 12s bound. Unknown `--json` (nonzero exit) may still
 * fall back to one human exec.
 *
 * Issue #2377: a transport throw/timeout is [Fetch.Unavailable], NOT
 * [Fetch.Empty]. The two used to be the same value, and that conflation is the
 * silent-undercount hazard: `Empty` means "the host authoritatively reports
 * these zero extra sessions", so the caller happily publishes whatever narrower
 * enumeration it already has (default-socket `tmux list-sessions`, or a live
 * `-CC` client bound to ONE tmuxctl socket). `Unavailable` means "we do not
 * know", and the caller must surface a retryable error instead of a
 * confidently-wrong count. Do not merge these two states again.
 *
 * Issue #2444: #2377's fail-closed design (any single exec throw/timeout ->
 * [Fetch.Unavailable] -> the caller's hard `FolderListResult.Failed`) was, on
 * its own, a REGRESSION on a lossy mobile link — worse than the undercount it
 * fixed. `Issue1876FolderReconcileMobileRttIntegrationTest`'s simulated
 * 200ms+-40ms-RTT/5%-loss profile hit [Fetch.Unavailable] far more often than
 * the nominal 5% loss rate alone predicts, because ONE lost segment anywhere
 * in the JSON exec's round trip (channel open, command, stdout drain) is
 * enough to blow the caller's single per-attempt bounded-exec timeout (the
 * ~3.5s `execBounded`/`EXEC_READ_TIMEOUT_MS` class), and a single un-retried
 * attempt has no way to tell "this link just hiccupped" from "the enumerator
 * is genuinely gone".
 *
 * So each hop below (JSON, then human) now gets up to [MAX_EXEC_ATTEMPTS]
 * bounded attempts, all on the SAME already-open lease/transport, before it is
 * classified as [Fetch.Unavailable] — the same "one more attempt on the warm
 * transport, not a fresh dial" shape #2422's `execRequiredLandingBounded`
 * already uses for the required landing batch, applied here to the enumerator
 * hop (and extended past a single retry — see the constant's doc for why).
 * [MAX_EXEC_ATTEMPTS] consecutive losses on the same short exec is a
 * legitimately unavailable enumerator (or a dead transport, where each retry
 * fails fast rather than waiting out its full bound); this generous a run of
 * back-to-back losses is exactly what a single-retry design still let through
 * often enough to fail the reported mobile-RTT reproduction — see the
 * constant's doc for the measured evidence. Every attempt still costs at most
 * the caller's unchanged per-attempt timeout (never a wider one), and every
 * hop still runs on the SAME warm lease the caller already dialled — never a
 * second SSH handshake.
 */
internal object HostSessionEnumerator {

    /** Cause-trail vocabulary for the issue #2444 enumerator-hop retry. */
    const val TRAIL_STAGE_ENUMERATOR_EXEC_RETRY: String = "host_session_enumerator_exec_retry"
    const val TRAIL_OUTCOME_RETRIED_ON_WARM_TRANSPORT: String = "retried_on_warm_transport"
    const val TRAIL_OUTCOME_RETRY_ALSO_FAILED: String = "retry_also_failed"
    private const val LOG_TAG: String = "HostSessionEnumerator"

    /**
     * Issue #2444 — up to this many bounded attempts per hop before giving up.
     *
     * A single retry (2 attempts) measurably reduced, but did not eliminate,
     * the un-retried #2377 design's failure rate: reproduced red->green
     * against `Issue1876FolderReconcileMobileRttIntegrationTest`'s real
     * 200ms+-40ms-RTT/5%-loss Docker profile, a 2-attempt cap still hit
     * `FolderListResult.Failed("... did not respond ...")` in 2 of 4 repeated
     * runs of `aSlowButAliveHostCliDoesNotCostAFreshDialOrTheWholeTree` (the
     * arm with the least timing headroom of the class — its own KDoc already
     * flagged this). That arm's own per-exec effective loss rate runs well
     * above the nominal 5% (consistent with the issue's own finding that a
     * multi-round-trip exec is disproportionately exposed to a flat per-packet
     * loss rate), so two-in-a-row losses are not the rare tail a single retry
     * assumed. 3 attempts (2 retries) reproduced GREEN across the same repeated
     * runs while staying inside the #2348 12s reconcile bound (measured
     * 7.9-10.5s per sample with 0-2 retries actually spent, safely under both
     * the 9s structural-cost budget most samples hit and the 12s bound the
     * rare retried sample can still afford) — see
     * `Issue1876FolderReconcileMobileRttIntegrationTest` for the reviewer-
     * reproducible evidence. Every attempt beyond the first still runs on the
     * SAME warm lease; the worst case ([MAX_EXEC_ATTEMPTS] times the caller's
     * per-attempt bound) is one bounded probe among several that already run
     * CONCURRENTLY with the rest of the reconcile, not a serial addition to
     * the whole chain.
     */
    private const val MAX_EXEC_ATTEMPTS: Int = 3
    /**
     * Wrap the JSON enumerator so stdin is closed for the whole pipeline. The
     * caller still applies its own `pathAware`/`/bin/sh -lc` wrapping, which is
     * what makes the redirect apply to the pocketshell process.
     */
    fun jsonExecBody(jsonCommand: String): String = "{ $jsonCommand ; } </dev/null"

    sealed class Fetch {
        abstract val rows: List<HostTmuxSessionRow>

        data class Json(override val rows: List<HostTmuxSessionRow>) : Fetch()
        data class Human(override val rows: List<HostTmuxSessionRow>) : Fetch()

        /** The host ran the enumerator and authoritatively reported no rows. */
        data object Empty : Fetch() {
            override val rows: List<HostTmuxSessionRow> get() = emptyList()
        }

        /** Human fallback ran and failed (missing binary / tmux error). */
        data object Failed : Fetch() {
            override val rows: List<HostTmuxSessionRow> get() = emptyList()
        }

        /**
         * Issue #2377: the enumerator could not be RUN (bounded-exec timeout or
         * transport throw). Rows are empty because nothing was read, not because
         * the host has no sessions — the caller must NOT treat this as "no extra
         * sessions" and publish a narrower list.
         */
        data object Unavailable : Fetch() {
            override val rows: List<HostTmuxSessionRow> get() = emptyList()
        }
    }

    suspend fun fetch(
        parser: HostTmuxSessionListParser,
        exec: suspend (String) -> ExecResult,
        jsonCommand: String,
        humanCommand: String,
    ): Fetch {
        // Issue #2444: up to MAX_EXEC_ATTEMPTS bounded attempts on the same warm
        // transport before this hop is classified Unavailable. Falling through
        // to a DIFFERENT command (the human hop, below) on a JSON miss is still
        // exactly what #2348 forbade — that is unchanged, and it is not what
        // this retry does: it repeats the SAME jsonCommand, never a second
        // distinct exec.
        val json = execWithBoundedRetries(jsonCommand, exec) ?: return Fetch.Unavailable
        if (json.exitCode == 0) {
            val parsed = parser.parsePocketshellSessionsJson(json.stdout)
            if (parsed != null) {
                return Fetch.Json(parsed)
            }
            // Exit 0 with a blank body is an empty enumerator, not a prompt
            // to fall through to the human table (that second exec was
            // stealing the next queued fake response and breaking lease-reuse
            // command accounting).
            if (json.stdout.isBlank()) {
                return Fetch.Empty
            }
            // Docker agents fixture 0.4.45 (and any host that accepts `--json`
            // then prints the human table): exit 0, stdout starts with IDX,
            // not `{`. parsePocketshellSessionsJson returns null. A second
            // `pocketshell sessions list --by activity` here is the extra
            // mobile-RTT hop that misses the 12s bound. Parse this same
            // stdout in-process; garbage that is not a table is Empty.
            val humanRows = parser.parsePocketshellSessionsList(json.stdout)
            return if (humanRows.isEmpty()) Fetch.Empty else Fetch.Human(humanRows)
        }
        // Unknown `--json` (nonzero exit, e.g. an old host CLI) falls back to
        // the human table — unchanged from #2348 — and that hop gets the same
        // #2444 bounded-retry treatment as the JSON hop.
        val human = execWithBoundedRetries(humanCommand, exec) ?: return Fetch.Unavailable
        if (human.exitCode == 0) {
            return Fetch.Human(parser.parsePocketshellSessionsList(human.stdout))
        }
        return Fetch.Failed
    }

    /**
     * Issue #2444 — run [command] via [exec], retrying on the same warm
     * transport (the caller's [exec] lambda is already bound to one lease/
     * session; this never re-dials) up to [MAX_EXEC_ATTEMPTS] times total when
     * an attempt times out or the transport throws. Returns `null` — the
     * caller's cue to report [Fetch.Unavailable] — only after EVERY attempt
     * fails.
     *
     * A single transient loss on a mobile link (one lost segment anywhere in
     * the exec's round trip) must not be indistinguishable from a genuinely
     * unreachable enumerator; [MAX_EXEC_ATTEMPTS] consecutive losses on the
     * same short exec is. This mirrors the shape (not the code — that one
     * needs the `SshSession` itself, which this generic `exec` lambda does not
     * expose) of `execRequiredLandingBounded` (issue #2422), already used for
     * the required folder-list landing batch: more attempts on the SAME
     * transport, never a fresh dial, and every retry is recorded so it is
     * visible in an exported diagnostics report rather than silently eating
     * the extra bounded wait.
     */
    private suspend fun execWithBoundedRetries(
        command: String,
        exec: suspend (String) -> ExecResult,
    ): ExecResult? {
        for (attempt in 1..MAX_EXEC_ATTEMPTS) {
            try {
                return exec(command)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val attemptsLeft = MAX_EXEC_ATTEMPTS - attempt
                if (attemptsLeft > 0) {
                    Log.w(
                        LOG_TAG,
                        "host session enumerator exec made no progress / threw on attempt " +
                            "$attempt/$MAX_EXEC_ATTEMPTS; retrying on the same warm transport " +
                            "before reporting Unavailable (issue #2444). " +
                            "cmd=${command.takeLast(48)} cause=${t.javaClass.simpleName}",
                    )
                    ReconnectCauseTrail.record(
                        stage = TRAIL_STAGE_ENUMERATOR_EXEC_RETRY,
                        outcome = TRAIL_OUTCOME_RETRIED_ON_WARM_TRANSPORT,
                        cause = "enumerator_exec_no_result_within_bound",
                        "commandTail" to command.takeLast(48),
                        "attempt" to attempt,
                    )
                } else {
                    // Every attempt failed: report it, this is the
                    // genuinely-unavailable case the caller must fail closed on.
                    ReconnectCauseTrail.record(
                        stage = TRAIL_STAGE_ENUMERATOR_EXEC_RETRY,
                        outcome = TRAIL_OUTCOME_RETRY_ALSO_FAILED,
                        cause = "enumerator_exec_no_result_within_bound",
                        "commandTail" to command.takeLast(48),
                        "attempt" to attempt,
                    )
                }
            }
        }
        return null
    }
}
