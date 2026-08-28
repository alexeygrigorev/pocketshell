package com.pocketshell.app.sessions

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
 */
internal object HostSessionEnumerator {
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
        val json = try {
            exec(jsonCommand)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Timed out / wrap-stdin hang / transport throw. A second human exec
            // here is the mutation that reddens #2348 (3.5s + 3.5s serial on the
            // 12s bound) — so we still do not retry. But #2377: this is
            // Unavailable, not Empty. Reporting Empty told the caller the host
            // has no tmuxctl/aplexer sessions, which silently published the
            // default-socket subset as if it were the whole truth.
            return Fetch.Unavailable
        }
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
        val human = try {
            exec(humanCommand)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Issue #2377: same rule as the JSON hop — a transport failure is
            // "unknown", never "the host has no sessions".
            return Fetch.Unavailable
        }
        if (human.exitCode == 0) {
            return Fetch.Human(parser.parsePocketshellSessionsList(human.stdout))
        }
        return Fetch.Failed
    }
}
