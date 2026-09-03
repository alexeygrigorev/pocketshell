package com.pocketshell.next.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.next.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One rendered row of the host list.
 *
 * Deliberately three primitive fields. The old client's row model carried a
 * bootstrap state, a live connection status, a session count, a "resume last
 * session" descriptor and an update-available flag, which is why its
 * ViewModel needed probe scheduling, cache-staleness rules and a connection
 * observer to keep them honest. app2's list is a read-only projection of the
 * `hosts` table: what Room emits is what the screen paints, so there is no
 * second source of truth to reconcile. Status indicators come back in a later
 * plan task (P-6) on top of the connections registry, not from here.
 */
data class HostRow(
    val id: Long,
    val name: String,
    /** `username@hostname` — the muted mono subtitle line on the row. */
    val subtitle: String,
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
    hostDao: HostDao,
    @IoDispatcher dispatcher: CoroutineDispatcher,
) : ViewModel() {

    val state: StateFlow<HostListUiState> =
        hostDao.getAll()
            .map { hosts -> HostListUiState(hosts = hosts.map { toRow(it) }, loaded = true) }
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
        fun toRow(host: HostEntity): HostRow = HostRow(
            id = host.id,
            name = host.name.ifBlank { host.hostname },
            subtitle = "${host.username}@${host.hostname}",
        )
    }
}
