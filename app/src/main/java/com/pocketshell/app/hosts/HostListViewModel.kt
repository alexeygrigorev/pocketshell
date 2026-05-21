package com.pocketshell.app.hosts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
 *
 * Issue #40 adds the GitHub-Releases update-availability check. The
 * checker fires once at construction time and can be re-fired by the UI
 * (e.g. pull-to-refresh) via [checkForUpdates]. Any failure leaves
 * [updateAvailable] at `null` — the banner is a courtesy, not a hard
 * requirement, so we never surface network errors to the user.
 *
 * `@ApplicationContext` is the project's standard for injecting a Context
 * into a `ViewModel` (see [SessionViewModel][com.pocketshell.app.session.SessionViewModel]).
 * It avoids the `AndroidViewModel` subclass dependency while still giving
 * us access to `PackageManager` for reading the installed `versionName`.
 */
@HiltViewModel
class HostListViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
    private val releaseChecker: ReleaseChecker,
) : ViewModel() {

    /** Live list of saved hosts, sorted by name (DAO query). */
    val hosts: StateFlow<List<HostEntity>> = hostDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Non-null when a newer GitHub Release is available than the
     * installed APK. Consumed by [HostListScreen] to render the update
     * banner / chip with a tap-to-download action.
     */
    private val _updateAvailable = MutableStateFlow<ReleaseInfo?>(null)
    val updateAvailable: StateFlow<ReleaseInfo?> = _updateAvailable.asStateFlow()

    init {
        checkForUpdates()
    }

    /**
     * Look up the key entity for the given key id. Returns `null` if the
     * key has been deleted (the foreign-key CASCADE on `hosts.keyId` should
     * keep this from happening — but the suspending lookup means the call
     * site has a defined behaviour for the race anyway).
     */
    suspend fun keyFor(keyId: Long): SshKeyEntity? = sshKeyDao.getById(keyId)

    /**
     * Re-run the GitHub-Releases check. Called from `init {}` on
     * construction, and intended to be re-invoked from the UI (e.g. a
     * pull-to-refresh handler on [HostListScreen]). Safe to call
     * repeatedly — the underlying [ReleaseChecker] is stateless and
     * each call is a fresh HTTP request.
     */
    fun checkForUpdates() {
        viewModelScope.launch {
            val currentVersion = currentVersionName()
            _updateAvailable.value = releaseChecker.check(currentVersion)
        }
    }

    /**
     * Resolve the installed `versionName` from `PackageManager`. Falls
     * back to a sentinel string if the read fails — the [ReleaseChecker]
     * will then treat any real release as "newer than 0.0.0" and surface
     * the banner, which is the right default for a debug-build edge case.
     */
    private fun currentVersionName(): String = try {
        applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0)
            .versionName
            ?: "0.0.0"
    } catch (_: Exception) {
        "0.0.0"
    }
}
