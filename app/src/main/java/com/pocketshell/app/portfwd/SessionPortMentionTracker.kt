package com.pocketshell.app.portfwd

import com.pocketshell.app.tmux.PortDetector
import com.pocketshell.core.portfwd.PortScanner
import com.pocketshell.core.portfwd.RemotePort
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Issue #2176 (extends issue #448): the second stage of new-port detection.
 *
 * [com.pocketshell.app.tmux.PortDetector] turns pane output into *candidates*.
 * This class turns a candidate into either nothing, a durable session-attributed
 * [SessionPortMention], the #448 forward overlay, or both. It was lifted out of
 * `TmuxSessionViewModel` verbatim and then extended, so the connection
 * god-object shrinks rather than grows (D28) and the logic becomes unit-testable
 * without standing up a view model.
 *
 * ## The `LISTEN` confirm is not optional
 *
 * A regex hit is a *candidate*, never a row. An agent can echo a URL it read out
 * of a file; a scrollback replay can resurrect a port that died an hour ago;
 * a README pasted into the terminal is full of `http://localhost:3000`. Every
 * one of those matches the regex and none of them is a running server. So
 * [confirmAndSurface] asks [PortScanner] whether the port is genuinely in
 * `LISTEN` on the host, and a candidate that is not listening produces **no
 * mention and no overlay** — it is merely released, so a *later* real bind of
 * the same port can still be detected.
 *
 * ## What changed versus #448, and what deliberately did not
 *
 * The overlay's user-visible behaviour is unchanged: still at most one overlay,
 * still never replaced while the user has not acted on it, still suppressed
 * while backgrounded, still never re-offered for a port already
 * confirmed/dismissed/forwarded.
 *
 * What is new is that the **recording happens before the overlay gate**. Under
 * #448 a port mentioned while an overlay was already up was dropped on the floor
 * without ever being scanned. For an overlay that is fine — there is one to show
 * already — but for attribution it is a hole: a dev server prints its URL once,
 * and if that once collided with an open overlay the port would never reach the
 * panel. The cost is bounded by [SessionPortsStore.hasRecorded]: once a port is
 * attributed, a repeated banner short-circuits before the scan.
 *
 * ## Foreground-only, event-driven (D21)
 *
 * There is no timer, no poll loop and no background work here. [confirmAndSurface]
 * runs only when a candidate arrives on the pane output flow the terminal is
 * already collecting, and [ensureHostLoad] runs once per session on attach. The
 * host write is fired and forgotten so no SSH round trip ever sits on the
 * detection path (#847).
 */
internal class SessionMentionedPortsTracker(
    private val detector: PortDetector,
    private val store: SessionPortsStore,
    private val scope: CoroutineScope,
    /** The live warm session, or null while nothing is attached. */
    private val sessionProvider: () -> SshSession?,
    /** Identity of the session on screen, or null before a target is bound. */
    private val sessionKeyProvider: () -> SessionPortsKey?,
    /** False while the app is backgrounded — gates the overlay only. */
    private val appActive: () -> Boolean,
    /** The port whose overlay is currently up, or null. */
    private val overlayPort: () -> Int?,
    /** Raise the #448 forward overlay for a port. */
    private val setOverlayPort: (Int) -> Unit,
    /** `LISTEN` scan seam; the real scanner in production. */
    private val scan: suspend (SshSession) -> List<RemotePort> = { PortScanner.scan(it) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Confirm one candidate and, if it is really listening, attribute it to the
     * current session before applying the unchanged #448 overlay rules.
     */
    suspend fun confirmAndSurface(candidate: PortDetector.Candidate) {
        val port = candidate.port
        val key = sessionKeyProvider()
        // Nothing left to learn: the port is already attributed AND an overlay
        // the user has not answered is up, so neither the panel nor the overlay
        // would change. Skip the scan entirely.
        if (key != null && overlayPort() != null && store.hasRecorded(key, port)) {
            detector.confirmFailed(port)
            return
        }
        val session = sessionProvider() ?: run {
            detector.confirmFailed(port)
            return
        }
        val listening = runCatching { scan(session) }
            .getOrDefault(emptyList())
            .firstOrNull { it.port == port }
        if (listening == null) {
            // Echoed / stale / documentation URL. Release so a real bind later
            // can still be detected.
            detector.confirmFailed(port)
            return
        }
        if (key != null) {
            record(key, listening, candidate.matchedText)
        }
        if (!appActive()) {
            // Went to background mid-confirm — don't pop an overlay the user
            // can't see; release so it can re-fire next foreground.
            detector.confirmFailed(port)
            return
        }
        // Don't replace an overlay the user hasn't acted on yet.
        if (overlayPort() != null) {
            detector.confirmFailed(port)
            return
        }
        if (detector.confirmed(port) && overlayPort() == null) {
            setOverlayPort(port)
        }
    }

    /**
     * Read the session's durable `@ps_session_ports` option once, in the
     * background, so a list captured before an app restart is present when the
     * user opens the panel. Called on attach; a no-op on every later call for
     * the same session.
     */
    fun ensureHostLoad() {
        val key = sessionKeyProvider() ?: return
        if (sessionProvider() == null) return
        if (!store.claimHostLoad(key)) return
        scope.launch {
            val session = sessionProvider()
            if (session == null) {
                store.releaseHostLoad(key)
                return@launch
            }
            val raw = runCatching {
                session.exec(SessionPortsHostOptions.readCommand(key.sessionName)).stdout
            }.getOrNull()
            if (raw == null) {
                store.releaseHostLoad(key)
                return@launch
            }
            store.mergeHostValue(key, raw)
        }
    }

    private fun record(key: SessionPortsKey, listening: RemotePort, matchedText: String) {
        val command = store.recordAndPersist(
            key = key,
            mention = SessionPortMention(
                port = listening.port,
                firstSeenAtEpochMs = nowMs(),
                process = listening.processName,
                matchedText = matchedText,
            ),
        ) ?: return
        // Fire and forget: the mention is already in memory, so the user sees
        // the row now; the durable write must never make the detection path
        // wait on SSH (#847), and a failed write is not actionable — the next
        // confirmed port rewrites the whole list.
        scope.launch {
            val session = sessionProvider() ?: return@launch
            runCatching { session.exec(command) }
        }
    }
}
