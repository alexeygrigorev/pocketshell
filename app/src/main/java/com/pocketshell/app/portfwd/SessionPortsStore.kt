package com.pocketshell.app.portfwd

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Issue #2176: process-wide, session-attributed store of the ports each tmux
 * session was observed to open.
 *
 * ## Why a singleton and not view-model state
 *
 * The capture side lives in [com.pocketshell.app.tmux.TmuxSessionViewModel]
 * (that is where the pane output flow and the warm SSH session are), while the
 * reading side is the Ports panel, which has neither. A `@Singleton` is the
 * rendezvous: the session view model writes, the panel view model observes, and
 * neither dials a second connection. It also means the list is still there
 * after the panel is dismissed and re-opened without re-reading the host.
 *
 * ## Durability model
 *
 * Memory is a cache over the host-side `@ps_session_ports` option, never the
 * source of truth:
 *
 *  - [loadFromHost] is a read-through, run ONCE per session key per process. It
 *    is what makes the list survive an app restart (a cold process has an empty
 *    map until it reads) and a session switch away and back.
 *  - [recordAndPersist] updates memory synchronously — so a freshly-detected
 *    port is visible immediately, with no round trip — and returns the write
 *    command for the caller to fire and forget.
 *
 * There is deliberately **no polling and no timer**. Every entry point is driven
 * by an event that already happened: a confirmed port detection, or a session
 * attach. That keeps the feature inside D21 (foreground-only, no background
 * work) by construction rather than by discipline.
 *
 * ## Threading
 *
 * All state lives in one [MutableStateFlow] updated under [lock]. The capture
 * side calls in from the view model's port-detection dispatcher (off-Main, per
 * issue #877) and the panel side reads the flow on Main, so both sides touch the
 * map; the critical sections are map copies measured in microseconds and contain
 * no IO.
 */
@Singleton
public class SessionPortsStore @Inject constructor() {

    private val lock = Any()

    private val _mentions =
        MutableStateFlow<Map<SessionPortsKey, List<SessionPortMention>>>(emptyMap())

    /** Every known session's mentions, keyed by (host, session name). */
    public val mentions: StateFlow<Map<SessionPortsKey, List<SessionPortMention>>> =
        _mentions.asStateFlow()

    /** Session keys whose host option has already been read this process. */
    private val loaded: MutableSet<SessionPortsKey> = mutableSetOf()

    /** The mentions recorded for [key], oldest first. Empty when unknown. */
    public fun mentionsFor(key: SessionPortsKey): List<SessionPortMention> =
        _mentions.value[key].orEmpty()

    /**
     * True when [port] is already attributed to [key]. Lets the capture path
     * skip a redundant `LISTEN` scan for a port the session has already
     * contributed, so a dev server that reprints its banner costs nothing.
     */
    public fun hasRecorded(key: SessionPortsKey, port: Int): Boolean =
        _mentions.value[key].orEmpty().any { it.port == port }

    /**
     * True when [key] has not yet had its host option read this process. The
     * caller uses this to fire exactly one read per session; it flips before the
     * read runs so two concurrent attaches cannot both issue it.
     */
    public fun claimHostLoad(key: SessionPortsKey): Boolean = synchronized(lock) {
        loaded.add(key)
    }

    /**
     * Give the claim from [claimHostLoad] back after a read that never actually
     * happened (no live session, transport threw). Without this a transient
     * failure would burn the one read this process ever attempts and the panel
     * would stay empty until the app is restarted — the exact "empty when you
     * need it" failure the durable option exists to prevent.
     */
    public fun releaseHostLoad(key: SessionPortsKey) {
        synchronized(lock) { loaded.remove(key) }
    }

    /**
     * Merge a host-read option value into memory for [key].
     *
     * Merging (rather than replacing) matters on the reconnect path: ports
     * detected live in this process before the read completed must not be
     * dropped by an option value written before they existed.
     */
    public fun mergeHostValue(key: SessionPortsKey, rawOptionValue: String?) {
        val decoded = SessionPortMentionCodec.decode(rawOptionValue)
        if (decoded.isEmpty()) return
        synchronized(lock) {
            val merged = SessionPortMentionCodec.merge(mentionsFor(key), decoded)
            _mentions.value = _mentions.value + (key to merged)
        }
    }

    /**
     * Record [mention] against [key] and return the tmux command that persists
     * the session's whole (merged, capped) list host-side — or `null` when the
     * port was already attributed and nothing changed, so a repeated banner
     * costs zero SSH.
     *
     * The caller MUST fire the returned command **without waiting on it**. This
     * runs off the back of terminal output on a live session; a blocking exec
     * here would put an SSH round trip on a path that must stay free (#847), and
     * a failed write is not worth surfacing — the mention is already in memory
     * and the next confirmed port rewrites the whole list anyway.
     */
    public fun recordAndPersist(key: SessionPortsKey, mention: SessionPortMention): String? =
        synchronized(lock) {
            val existing = mentionsFor(key)
            if (existing.any { it.port == mention.port }) return@synchronized null
            val merged = SessionPortMentionCodec.merge(existing, listOf(mention))
            _mentions.value = _mentions.value + (key to merged)
            SessionPortsHostOptions.writeCommand(
                sessionName = key.sessionName,
                encoded = SessionPortMentionCodec.encode(merged),
            )
        }

    /** Test seam: drop all state so one test cannot leak into the next. */
    internal fun clearForTest() {
        synchronized(lock) {
            loaded.clear()
            _mentions.value = emptyMap()
        }
    }
}
