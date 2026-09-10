package com.pocketshell.next.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.next.di.IoDispatcher
import com.pocketshell.next.di.LiveHostIds
import com.pocketshell.next.usage.UsageGlanceCache
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.next.usage.toPillState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One rendered row of the host list.
 *
 * Deliberately three primitive fields. The old client's row model carried a
 * bootstrap state, a live connection status, a session count, a "resume last
 * session" descriptor and an update-available flag, which is why its
 * ViewModel needed probe scheduling, cache-staleness rules and a connection
 * observer to keep them honest. app2's list is a read-only projection of the
 * `hosts` table: what Room emits is what the screen paints, so there is no
 * second source of truth to reconcile. [connected] is the one derived field,
 * and it stays honest the same way — it is `ConnectionsRegistry`'s own live
 * feed folded in, not a status this ViewModel caches and has to re-check.
 */
data class HostRow(
    val id: Long,
    val name: String,
    /** `username@hostname` — the muted mono subtitle line on the row. */
    val subtitle: String,
    /**
     * Whether this host currently holds a live connection (#2635 2a).
     *
     * Read from [com.pocketshell.next.connect.ConnectionsRegistry.liveHostIds],
     * which observes the transports themselves — this is not a cached flag the
     * list has to keep honest, which is what the comment above meant by "no
     * second source of truth to reconnect". Never dials: the list is a
     * pre-connection screen (D21).
     */
    val connected: Boolean = false,
)

/**
 * What [com.pocketshell.next.hosts.HostListScreen] renders.
 *
 * [loaded] exists only to separate "Room has not emitted yet" from "there
 * genuinely are no hosts" — without it a cold launch flashes the empty state
 * for a frame before the first query result arrives.
 */
data class HostListUiState(
    val hosts: List<HostRow> = emptyList(),
    val loaded: Boolean = false,
    /**
     * The last usage reading this device saw, or null on an install that has
     * never read one (issue #2632).
     *
     * Cached, never live: the host list is a pre-connection screen and usage
     * never dials (D21). It is rendered by the same
     * [com.pocketshell.next.usage.UsageGlancePill] the session screen uses, so
     * a reading older than
     * [com.pocketshell.next.usage.USAGE_GLANCE_STALE_AFTER] shows its muted
     * "read at HH:mm" form rather than passing itself off as live.
     */
    val usagePill: UsageGlancePillState? = null,
)

/**
 * Host-list screen state, derived from `core-storage`'s [HostDao].
 *
 * app2 reads the same `hosts` table the shipping client writes (plan §U-1);
 * it does not add, edit or delete rows yet. The whole ViewModel is therefore
 * one `Flow` mapping: `getAll()` → UI rows. Room owns the invalidation, so an
 * edit made elsewhere in the process re-emits here with no refresh plumbing.
 *
 * [dispatcher] is injected rather than hard-coded so a unit test can run the
 * mapping on its own scheduler and stay deterministic; it is where the row
 * projection runs, not where the query runs (Room already dispatches its own
 * queries off the main thread).
 */
@HiltViewModel
class HostListViewModel @Inject constructor(
    private val hostDao: HostDao,
    usageGlanceCache: UsageGlanceCache,
    @LiveHostIds liveHostIds: @JvmSuppressWildcards Flow<Set<Long>>,
    @IoDispatcher dispatcher: CoroutineDispatcher,
) : ViewModel() {

    /**
     * Remove a host (task P-6). Deliberately the only write on this ViewModel:
     * the row's other management actions are navigations, and add/edit belongs
     * to [AddEditHostViewModel] where the form state lives.
     *
     * No optimistic update — Room's invalidation re-emits the list, so the row
     * disappearing IS the confirmation, and there is no local copy that could
     * disagree with the table.
     */
    fun delete(hostId: Long) {
        viewModelScope.launch { hostDao.deleteById(hostId) }
    }

    val state: StateFlow<HostListUiState> =
        combine(
            hostDao.getAll(),
            usageGlanceCache.last,
            liveHostIds,
        ) { hosts, usage, liveHostIds ->
            HostListUiState(
                hosts = hosts.map { toRow(it, connected = it.id in liveHostIds) },
                loaded = true,
                // Re-derived per emission, not stored: staleness is a function
                // of when the reading was taken and when it is being looked at.
                usagePill = usage?.toPillState(),
            )
        }
            .flowOn(dispatcher)
            .stateIn(
                scope = viewModelScope,
                // Keeps the Room query alive across a configuration change /
                // brief backgrounding, and cancels it when the screen is gone
                // for good — the list must not hold a cursor open forever.
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = HostListUiState(),
            )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * `HostEntity` → row. The display name falls back to the hostname when
         * the stored label is blank, so a row imported without a name is still
         * tappable and identifiable rather than rendering as an empty line.
         */
        fun toRow(host: HostEntity, connected: Boolean): HostRow = HostRow(
            id = host.id,
            name = host.name.ifBlank { host.hostname },
            subtitle = "${host.username}@${host.hostname}",
            connected = connected,
        )
    }
}
