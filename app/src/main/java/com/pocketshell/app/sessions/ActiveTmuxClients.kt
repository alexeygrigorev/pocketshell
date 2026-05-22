package com.pocketshell.app.sessions

import com.pocketshell.core.tmux.TmuxClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped registry of every live `tmux -CC` [TmuxClient].
 *
 * Each entry pairs the host connection parameters with the live
 * [TmuxClient] and the resolved key path used to open it.
 * The dashboard's cross-host session aggregator
 * ([SessionsDashboardViewModel]) observes [clients] to discover which
 * hosts to poll for `list-sessions` data, and to find the key path /
 * host parameters needed to navigate to
 * [com.pocketshell.app.nav.AppDestination.TmuxSession] when the user
 * taps a session row.
 *
 * Lifecycle (per issue #46 brief — Hilt scoping concern):
 *
 *  - A long-lived consumer (today, the per-host
 *    [com.pocketshell.app.tmux.TmuxSessionViewModel] when #48 lifecycle
 *    UI lands; pre-#48, manually wired by tests) calls [register] when
 *    it brings a [TmuxClient] up, and [unregister] when it tears it
 *    down.
 *  - This class does NOT own client lifetimes — closing the registry
 *    entry does not close the underlying [TmuxClient]. The owner of the
 *    client (the consumer that registered it) closes the client when its
 *    own scope ends.
 *  - [@Singleton] so all consumers see the same snapshot.
 *
 * ## Concurrency
 *
 * The internal map is held inside a [MutableStateFlow]; updates copy the
 * map and `value =` the new snapshot. Concurrent registrations from
 * different ViewModels are safe because the assignment is atomic on the
 * StateFlow, but the read-modify-write of the inner map is guarded with
 * `synchronized(this)` so two concurrent [register] calls cannot lose an
 * entry.
 *
 * ## Why a separate registry instead of extending [com.pocketshell.app.tmux.TmuxModule]
 *
 * The brief for #46 offers either option. A separate `:sessions` registry
 * fits the layering cleanest:
 *
 *  - `:tmux` knows about a single client at a time
 *    ([com.pocketshell.app.tmux.TmuxSessionViewModel] owns one); it has
 *    no concept of "all clients" today.
 *  - The dashboard ([SessionsDashboardViewModel]) is the only consumer
 *    of "all clients" and lives under `:sessions` already.
 *  - Adding the registry to `:sessions` keeps the cross-host aggregation
 *    self-contained — Hilt still injects it everywhere, but the type
 *    lives next to the only thing that reads from it.
 *
 * Hilt discovers this through the `@Inject constructor` and shares it
 * app-wide through [@Singleton].
 */
@Singleton
class ActiveTmuxClients @Inject constructor() {

    /**
     * Snapshot of all currently-registered clients, keyed by
     * host id. Updated atomically on register / unregister via
     * [MutableStateFlow.value]; collectors get the latest map plus a
     * fresh emission on every change.
     *
     * Exposed as immutable [Map] so subscribers don't accidentally
     * mutate it.
     */
    private val _clients: MutableStateFlow<Map<Long, Entry>> = MutableStateFlow(emptyMap())

    val clients: StateFlow<Map<Long, Entry>> = _clients.asStateFlow()

    /**
     * Add (or replace) the entry for [hostId]. The caller still owns
     * [client]'s lifetime — see the class-level docs.
     *
     * Replacing an existing entry is intentional: if a host is
     * disconnected and reconnected within the same process lifetime,
     * the new client supersedes the old. The previous entry's
     * [TmuxClient] is NOT closed here — the previous owner is
     * responsible for that. In practice the previous owner is the same
     * ViewModel instance issuing the new [register] call, and it has
     * already closed the old client before re-registering.
     */
    fun register(
        hostId: Long,
        hostName: String,
        hostname: String,
        port: Int,
        username: String,
        keyPath: String,
        client: TmuxClient,
    ) {
        synchronized(this) {
            val next = _clients.value.toMutableMap()
            next[hostId] = Entry(
                hostId = hostId,
                hostName = hostName,
                hostname = hostname,
                port = port,
                username = username,
                keyPath = keyPath,
                client = client,
            )
            _clients.value = next
        }
    }

    /**
     * Remove the entry for [hostId] if present. No-op if the host was
     * never registered, or if it was overwritten by a later
     * [register] call for a different host id (the map is keyed on
     * host id so we use that as the unique handle).
     */
    fun unregister(hostId: Long) {
        synchronized(this) {
            val current = _clients.value
            if (hostId !in current) return
            val next = current.toMutableMap()
            next.remove(hostId)
            _clients.value = next
        }
    }

    /**
     * One entry in the registry — a live tmux control channel against a
     * known host, with enough metadata for the dashboard to build a
     * navigation destination without a DB round-trip.
     */
    data class Entry(
        val hostId: Long,
        val hostName: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val client: TmuxClient,
    )
}
