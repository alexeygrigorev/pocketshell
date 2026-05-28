package com.pocketshell.app.sessions

import com.pocketshell.core.tmux.TmuxClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
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
     * Issue #235: per-host hooks installed by each
     * [com.pocketshell.app.tmux.TmuxSessionViewModel] when it brings a
     * tmux `-CC` client up. The application-scoped
     * [androidx.lifecycle.ProcessLifecycleOwner] observer (wired in
     * [com.pocketshell.app.App.onCreate]) drives these hooks on every
     * process foreground/background transition so the tmux server-side
     * client list does not pin the window size to the phone's viewport
     * while the user has switched away from PocketShell.
     *
     * Keyed by host id (same key as [_clients]) so a fresh
     * registration for the same host replaces a stale hook left by the
     * previous registrant. Held in a [ConcurrentHashMap] so the
     * lifecycle observer can iterate while a tmux teardown is
     * concurrently unregistering a hook from another thread.
     */
    private val lifecycleHooks: MutableMap<Long, LifecycleHooks> = ConcurrentHashMap()

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
     *
     * Issue #235: this method DOES NOT clear lifecycle hooks. The
     * client entry is created/destroyed on every attach/detach cycle
     * (including the lifecycle-driven background detach this issue
     * adds), but the lifecycle hooks must survive across cycles —
     * otherwise the very first auto-detach would unregister the
     * foreground hook and the app would never reattach on
     * `ON_START`. Hooks are removed via [unregisterLifecycleHooks]
     * (called from `TmuxSessionViewModel.onCleared` only).
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
     * Issue #235: install per-host hooks driven by the application
     * lifecycle observer.
     *
     * Each [com.pocketshell.app.tmux.TmuxSessionViewModel] that brings
     * a tmux `-CC` client up calls this immediately after [register] so
     * the [App][com.pocketshell.app.App]-installed
     * [androidx.lifecycle.ProcessLifecycleOwner] observer can drive a
     * "detach this -CC client on background, reattach on foreground"
     * journey without each ViewModel having to observe the process
     * lifecycle itself. Reattach uses the view model's existing
     * `reconnect()` machinery; detach is bounded by
     * [TmuxClient.detachCleanly]'s built-in timeout.
     *
     * Replacing an existing hook for the same [hostId] is intentional —
     * the same ViewModel instance re-registers when it swaps SSH
     * sessions (fast-switch path) and the new hook supersedes the
     * previous one.
     */
    fun registerLifecycleHooks(
        hostId: Long,
        hooks: LifecycleHooks,
    ) {
        lifecycleHooks[hostId] = hooks
    }

    /**
     * Issue #235: explicitly remove the lifecycle hooks installed for
     * [hostId] by an earlier [registerLifecycleHooks] call. Called
     * from `TmuxSessionViewModel.onCleared` so a destroyed VM does
     * not leak a callback into the singleton fanout.
     */
    fun unregisterLifecycleHooks(hostId: Long) {
        lifecycleHooks.remove(hostId)
    }

    /**
     * Issue #235: snapshot of all installed lifecycle hooks. Returns a
     * shallow copy so the caller can iterate without holding the map's
     * iterator across hook invocations (each hook hops to the view
     * model's `viewModelScope`, which may itself unregister mid-walk).
     */
    fun lifecycleHooksSnapshot(): List<LifecycleHooks> =
        lifecycleHooks.values.toList()

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

    /**
     * Issue #235: pair of suspend callbacks each registrant supplies
     * so the application-scoped lifecycle observer can drive the
     * "detach `-CC` on background, reattach on foreground" journey
     * without owning a strong reference to the ViewModel.
     *
     * Both callbacks are `suspend` so the implementation can perform a
     * bounded tmux round-trip (`detach-client` + reader-EOF wait) or
     * re-run the existing connect machinery without blocking the
     * lifecycle observer's caller thread.
     */
    class LifecycleHooks(
        val onBackground: suspend () -> Unit,
        val onForeground: suspend () -> Unit,
    )
}
