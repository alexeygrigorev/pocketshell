package com.pocketshell.next.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.next.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One tickable row in the Account & sync host picker. */
data class SyncHostRow(
    val name: String,
    val subtitle: String,
    val checked: Boolean,
    /** True for an alias that exists only in the account, not on this device. */
    val accountOnly: Boolean = false,
)

/** The last thing a sync did, as one line the screen can show. */
sealed interface SyncOutcome {
    data object None : SyncOutcome
    data object Running : SyncOutcome
    data class Pushed(val uploaded: Int, val version: Int) : SyncOutcome
    data class Pulled(val hosts: Int) : SyncOutcome
    data object AccountEmpty : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

/**
 * Everything the Account & sync screen renders.
 *
 * Note what is NOT here: an ID token, a refresh token, or the passphrase. The
 * tokens never leave [GoogleAuth]/the Keystore-backed store, and the
 * passphrase lives in the text field's own state and is handed to a call as a
 * parameter. `AccountSyncViewModelTest` asserts that containment rather than
 * trusting this comment.
 */
data class AccountSyncUiState(
    val clientConfigured: Boolean = SyncConfig.isGoogleClientConfigured,
    val signedIn: Boolean = false,
    val email: String? = null,
    val signInPhase: SyncSignInCoordinator.State = SyncSignInCoordinator.State.Idle,
    val hosts: List<SyncHostRow> = emptyList(),
    val outcome: SyncOutcome = SyncOutcome.None,
)

@HiltViewModel
class AccountSyncViewModel @Inject constructor(
    private val coordinator: SyncSignInCoordinator,
    private val auth: GoogleAuth,
    private val repository: SyncRepository,
    private val selection: SyncSelectionStore,
    hostDao: HostDao,
    @IoDispatcher dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val outcome = MutableStateFlow<SyncOutcome>(SyncOutcome.None)

    /** Account aliases seen on the last pull, so the picker can show them. */
    private val accountHosts = MutableStateFlow<List<SyncHostEntry>>(emptyList())

    private val localHosts: StateFlow<List<HostEntity>> = hostDao.getAll()
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val state: StateFlow<AccountSyncUiState> = combine(
        auth.status,
        coordinator.state,
        selection.selected,
        localHosts,
        combine(accountHosts, outcome, ::Pair),
    ) { status, phase, checked, hosts, (account, lastOutcome) ->
        AccountSyncUiState(
            clientConfigured = status.clientConfigured,
            signedIn = status.signedIn,
            email = status.email,
            signInPhase = phase,
            hosts = buildRows(hosts, account, checked),
            outcome = lastOutcome,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountSyncUiState())

    init {
        auth.refreshStatus()
    }

    fun signIn(context: Context) = coordinator.startSignIn(context)

    fun acknowledgeSignIn() = coordinator.acknowledge()

    fun signOut() {
        viewModelScope.launch {
            auth.signOut()
            accountHosts.value = emptyList()
            outcome.value = SyncOutcome.None
        }
    }

    fun setHostChecked(alias: String, checked: Boolean) = selection.setChecked(alias, checked)

    /** Fetch the account's blob and absorb its aliases into the selection. */
    fun pull(passphrase: String) {
        if (!guard(passphrase)) return
        outcome.value = SyncOutcome.Running
        viewModelScope.launch {
            outcome.value = when (val result = repository.pull(passphrase, localHosts.value)) {
                SyncRepository.PullResult.Absent -> {
                    accountHosts.value = emptyList()
                    SyncOutcome.AccountEmpty
                }
                is SyncRepository.PullResult.Ok -> {
                    accountHosts.value = result.hosts
                    SyncOutcome.Pulled(result.hosts.size)
                }
                is SyncRepository.PullResult.Failed -> SyncOutcome.Failed(result.message)
            }
        }
    }

    /** Upload the ticked hosts, replacing the account's content with them. */
    fun push(passphrase: String) {
        if (!guard(passphrase)) return
        outcome.value = SyncOutcome.Running
        viewModelScope.launch {
            outcome.value = when (val result = repository.push(passphrase, localHosts.value)) {
                is SyncRepository.PushResult.Ok -> SyncOutcome.Pushed(result.uploaded, result.version)
                is SyncRepository.PushResult.Conflict -> SyncOutcome.Failed(
                    "Another device is syncing right now (version ${result.currentVersion}). Try again.",
                )
                is SyncRepository.PushResult.Failed -> SyncOutcome.Failed(result.message)
            }
        }
    }

    private fun guard(passphrase: String): Boolean {
        if (passphrase.isEmpty()) {
            outcome.value = SyncOutcome.Failed("Enter your sync passphrase first.")
            return false
        }
        return true
    }

    private fun buildRows(
        local: List<HostEntity>,
        account: List<SyncHostEntry>,
        checked: List<String>,
    ): List<SyncHostRow> {
        val rows = local.map { host ->
            val alias = host.name.ifBlank { host.hostname }
            SyncHostRow(
                name = alias,
                subtitle = "${host.username}@${host.hostname}:${host.port}",
                checked = alias in checked,
            )
        }
        val localAliases = rows.map { it.name }.toSet()
        // Aliases the account holds that this device does not: shown so an
        // untick is a decision the user can actually see and make, instead of
        // an invisible entry that a push would silently drop.
        val accountOnly = account
            .filter { it.name !in localAliases }
            .map { entry ->
                SyncHostRow(
                    name = entry.name,
                    subtitle = "${entry.user}@${entry.hostname}:${entry.port} · in your account",
                    checked = entry.name in checked,
                    accountOnly = true,
                )
            }
        return rows + accountOnly
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
