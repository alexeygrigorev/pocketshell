package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.selection.detectLocalhostPortReferences

/**
 * Pure, Android-free helper that scans decoded terminal output for
 * "a server just started listening on port N" signals and decides
 * whether the tmux session should surface the forward overlay (issue
 * #448, epic #432 slice C).
 *
 * Detection is deliberately two-staged:
 *
 *  1. **Regex trigger (this class).** A cheap, event-driven scan over the
 *     same per-pane byte flow the terminal already consumes. Zero extra
 *     SSH, zero polling — it only runs while the pane flow is being
 *     collected, i.e. while the session is foregrounded. This makes it
 *     trivially compatible with the no-background-work principle (D21):
 *     there is no timer and no poll loop here.
 *  2. **`ss` confirm (caller).** The regex alone over-fires: an agent can
 *     *echo* a URL it read from a file, or a log can replay an old port
 *     that is no longer listening. So a regex hit is only a *candidate*;
 *     the caller MUST confirm the candidate is actually in `LISTEN`
 *     (via `PortScanner.scan` over the live SSH session) before showing
 *     the overlay. [confirmed] records that contract so the same
 *     candidate is never re-confirmed.
 *
 * The class owns all session-scoped de-duplication so the view model
 * cannot accidentally re-prompt:
 *
 *  - [offer] returns null for a port that is already pending confirm,
 *    already confirmed/offered, already dismissed, or already forwarded
 *    this session.
 *  - [confirmed] / [dismissed] / [forwarded] move a port into a terminal
 *    state so it is never offered again for the lifetime of this
 *    detector instance (one per tmux session runtime).
 *
 * Output arrives as a byte stream split across `%output` chunks, so a
 * port string ("Listening on http://127.0.0.1:5173") can be torn across
 * two emissions. [scan] keeps a small rolling tail of the most recent
 * decoded text per detector so a match that straddles a chunk boundary
 * is still found on the next chunk.
 *
 * All state is single-threaded by contract: the view model drives [scan]
 * from one coroutine on its bridge scope.
 */
internal class PortDetector(
    /**
     * Ports the host already has forwarded when this detector is created
     * (e.g. from a still-open `PortForwardPanelViewModel` for the same
     * host). These are seeded into the "handled" set so we never offer to
     * forward a port the user already forwarded.
     */
    alreadyForwarded: Set<Int> = emptySet(),
) {

    /**
     * A port matched by the regex that has NOT yet been resolved (not yet
     * confirmed, dismissed, or forwarded). Returned by [offer] so the
     * caller can run its single `ss` confirm scan.
     */
    data class Candidate(val port: Int)

    // Terminal de-dup set: ports we will never offer again this session,
    // for any reason (confirmed+offered, dismissed, or forwarded).
    private val handled: MutableSet<Int> = alreadyForwarded.toMutableSet()

    // Ports a regex hit produced that are mid-confirm. Kept separate from
    // [handled] so a confirm that comes back "not listening" can release
    // the port to be re-offered if the agent later actually binds it.
    private val pendingConfirm: MutableSet<Int> = mutableSetOf()

    // Rolling tail of recently decoded output so a port string split
    // across two byte chunks is still matched on the next chunk.
    private val tail = StringBuilder()

    /**
     * Feed a freshly decoded output chunk. Returns the set of ports that
     * are *new candidates* worth confirming — ports matched by the regex
     * that are not already handled or pending. Each returned port is moved
     * into the pending-confirm set; the caller is then responsible for
     * calling exactly one of [confirmed], [confirmFailed], [dismissed],
     * or [forwarded] for it.
     */
    fun scan(chunk: String): List<Candidate> {
        if (chunk.isEmpty()) return emptyList()
        tail.append(chunk)
        // Cap the rolling buffer; keep the last [TAIL_LIMIT] chars so a
        // boundary-straddling match still has both halves available.
        if (tail.length > TAIL_LIMIT) {
            tail.delete(0, tail.length - TAIL_LIMIT)
        }
        val text = tail.toString()
        val found = LinkedHashSet<Int>()
        for (regex in PORT_REGEXES) {
            for (match in regex.findAll(text)) {
                // Skip a match whose digits run to the very end of the
                // accumulated buffer: the port number may still be
                // truncated mid-chunk (e.g. ":51" that becomes ":5173"
                // on the next emission). Waiting for a following char
                // guarantees the number is complete before we offer it.
                if (match.range.last == text.length - 1) continue
                val port = match.groupValues.lastOrNull { it.toIntOrNull() != null }
                    ?.toIntOrNull()
                    ?: continue
                if (port !in VALID_PORT_RANGE) continue
                found += port
            }
        }
        for (reference in detectLocalhostPortReferences(text)) {
            // Same truncated-port guard as above, but using the shared parser's
            // source span. A chunk ending in `localhost:51` should wait for the
            // next bytes before offering what may become `localhost:5173`.
            if (reference.endExclusive == text.length) continue
            found += reference.localhostUrl.remotePort
        }
        val candidates = mutableListOf<Candidate>()
        for (port in found) {
            if (port in handled || port in pendingConfirm) continue
            pendingConfirm += port
            candidates += Candidate(port)
        }
        return candidates
    }

    /**
     * The caller's `ss` scan confirmed [port] is actually in `LISTEN`.
     * Marks it handled so it is never offered again this session and
     * returns true if the caller should surface the overlay (i.e. this is
     * the first confirm for the port). Returns false if the port was
     * already resolved by another path in the meantime.
     */
    fun confirmed(port: Int): Boolean {
        pendingConfirm.remove(port)
        if (port in handled) return false
        handled += port
        return true
    }

    /**
     * The caller's `ss` scan came back "not actually listening" (the
     * regex hit was an echoed/old URL). Releases the port from pending so
     * a *later* real bind of the same port can still be offered.
     */
    fun confirmFailed(port: Int) {
        pendingConfirm.remove(port)
    }

    /**
     * The user dismissed the overlay for [port] (or it auto-dismissed).
     * Permanently suppress re-prompting this port for the session.
     */
    fun dismissed(port: Int) {
        pendingConfirm.remove(port)
        handled += port
    }

    /**
     * The user accepted the overlay and was sent to forward [port].
     * Permanently suppress re-prompting this port for the session.
     */
    fun forwarded(port: Int) {
        pendingConfirm.remove(port)
        handled += port
    }

    companion object {
        /** Valid TCP user/server port range we are willing to offer. */
        private val VALID_PORT_RANGE = 1..65535

        /** Last N decoded chars retained to catch boundary-straddled hits. */
        private const val TAIL_LIMIT = 4096

        /**
         * Canonical "a server is now listening" lines printed by dev servers
         * and agents. Each regex captures the port as a numeric group; [scan]
         * takes the last numeric group of the match. A shared loopback
         * host/port parser is also applied in [scan] so bare
         * `localhost:5173` / `127.0.0.1:<port>` references use the same
         * false-positive guards as Conversation and terminal tap handling.
         * Regex matches use the same port-token boundary so `localhost:5173abc`
         * is not treated as a completed port.
         * Agent prose often drops the colon/URL shape ("localhost port 3000",
         * "port 5173 on 127.0.0.1"), so loopback-host + explicit "port N"
         * phrases are included too while unrelated bare port prose is not.
         *
         * Anchored to "listening"-style phrasing or to a localhost/
         * loopback/0.0.0.0 URL so a bare "port 8080" mention in prose is
         * not enough on its own — the `ss` confirm is the real guard, but
         * keeping the trigger reasonably specific avoids needless confirm
         * scans.
         */
        private val PORT_REGEXES: List<Regex> = listOf(
            // "Listening on 0.0.0.0:8000", "Listening on http://127.0.0.1:5000",
            // "Listening on port 3000", "listening at :4321"
            Regex(
                """(?i)listening\s+(?:on|at)\s+(?:https?://)?""" +
                    """(?:[\w.-]+)?:?(?:port\s+)?(\d{2,5})$PORT_TOKEN_BOUNDARY""",
            ),
            // Vite/Next style: "Local:   http://localhost:5173/"
            Regex("""(?i)local:\s+https?://[\w.-]+:(\d{2,5})$PORT_TOKEN_BOUNDARY"""),
            // "running on http://0.0.0.0:5000", "running on port 8080",
            // "Server running at http://localhost:4000"
            Regex(
                """(?i)running\s+(?:on|at)\s+(?:https?://)?""" +
                    """(?:[\w.-]+)?:?(?:port\s+)?(\d{2,5})$PORT_TOKEN_BOUNDARY""",
            ),
            // Agent prose without URL punctuation:
            // "localhost port 3000", "127.0.0.1 is running on port 5173",
            // "0.0.0.0 bound to port 8080".
            Regex(
                """(?i)$LOOPBACK_HOST_TOKEN$AGENT_LOOPBACK_PORT_PHRASE_WORDS""" +
                    """\s+port\s+(\d{2,5})$PORT_TOKEN_BOUNDARY""",
            ),
            // Same signal, reversed:
            // "port 3000 on localhost", "port 5173 is ready at 127.0.0.1".
            Regex(
                """(?i)\bport\s+(\d{2,5})$PORT_TOKEN_BOUNDARY""" +
                    """$AGENT_LOOPBACK_PORT_PHRASE_WORDS\s+$LOOPBACK_HOST_TOKEN""",
            ),
            // Bare loopback/any URLs the regexes above didn't anchor:
            // "http://localhost:8888", "http://127.0.0.1:5173",
            // "http://0.0.0.0:9000". Loopback/any-host only, so a remote
            // URL in prose isn't matched.
            Regex("""https?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0):(\d{2,5})$PORT_TOKEN_BOUNDARY"""),
        )

        private const val LOOPBACK_HOST_TOKEN =
            """(?<![\w.-])(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\])(?![\w.-])"""

        // Issue #877: this `(?:\s+(?:alt|alt|...))*` is a nested-quantifier
        // (star-of-alternation) shape — the classic catastrophic-backtracking
        // (ReDoS) trap. A 4 KB tail of whitespace-separated word tokens (very
        // common in agent prose / boxed TUI status frames, exactly the idle
        // agent's output) can drive a plain `*` into seconds of backtracking,
        // a hard main-thread stall even after the scan was moved off Main. The
        // per-iteration body is wrapped in an ATOMIC GROUP `(?>...)` so each
        // `\s+word` match commits and cannot be re-tried, collapsing the
        // worst case from exponential to linear. (Java/Kotlin regex supports
        // atomic groups; behaviour for real "host is listening on port N"
        // prose is unchanged.)
        private const val AGENT_LOOPBACK_PORT_PHRASE_WORDS =
            """(?>\s+(?:is|now|listening|running|serving|available|bound|reachable|ready|up|open|on|at|to|via|hosted|server|dev|preview))*"""

        private const val PORT_TOKEN_BOUNDARY =
            """(?=$|[/?#\s,;:)\]!?'"<>]|\.(?:$|\s|[)\]!?'"<>,;:]))"""
    }
}
