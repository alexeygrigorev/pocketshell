package com.pocketshell.app.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs [HostListScreen]. Streams the saved hosts via [HostDao.getAll]
 * and resolves the selected host's key by id when the user taps a row.
 *
 * The flow is `stateIn`-ed with `WhileSubscribed(5000)` so it survives
 * brief configuration changes (rotation) without losing the upstream
 * subscription. Once the screen is gone for 5 s the DB subscription is
 * dropped — Room will re-emit the cached snapshot on the next subscribe.
 *
 * Issue #18 keeps the list view-model focused on read + key-lookup. Host
 * mutation lives in [AddEditHostViewModel]; key mutation lives in
 * [SshKeysViewModel].
 */
@HiltViewModel
class HostListViewModel @Inject constructor(
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
) : ViewModel() {

    /** Live list of saved hosts, sorted by name (DAO query). */
    val hosts: StateFlow<List<HostEntity>> = hostDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Look up the key entity for the given key id. Returns `null` if the
     * key has been deleted (the foreign-key CASCADE on `hosts.keyId` should
     * keep this from happening — but the suspending lookup means the call
     * site has a defined behaviour for the race anyway).
     */
    suspend fun keyFor(keyId: Long): SshKeyEntity? = sshKeyDao.getById(keyId)
}
