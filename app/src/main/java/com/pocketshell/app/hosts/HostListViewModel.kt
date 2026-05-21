package com.pocketshell.app.hosts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.bootstrap.HostBootstrapSheetState
import com.pocketshell.app.bootstrap.HostBootstrapper
import com.pocketshell.app.bootstrap.InstallResult
import com.pocketshell.app.bootstrap.TmuxStatus
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
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
import java.io.File
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
 * Issue #49 adds the host-bootstrap flow. On tap-to-connect we open a
 * short-lived SSH session, probe for `tmux`, and either:
 *
 *  - persist `tmuxInstalled = true` and continue to the session, OR
 *  - persist `tmuxInstalled = false` and surface the bootstrap sheet so
 *    the user can install in one tap.
 *
 * The probe result is cached per-host with [BOOTSTRAP_CACHE_MS] (24h):
 * subsequent connects within the window skip the probe entirely. The
 * sheet state lives here so a rotation across the dialog doesn't lose
 * the in-flight install. See [bootstrapState], [bootstrapHost],
 * [installTmuxOnPendingHost], [dismissBootstrapAndOpen].
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
    private val bootstrapper: HostBootstrapper,
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

    /**
     * Bootstrap sheet state. `null` means the sheet is hidden. The
     * sheet's lifecycle is bound to a `pendingNavigation`: when the user
     * skips or completes install, the screen calls
     * [dismissBootstrapAndOpen] to flush the pending navigation and clear
     * the state.
     */
    private val _bootstrapState = MutableStateFlow<HostBootstrapSheetState?>(null)
    val bootstrapState: StateFlow<HostBootstrapSheetState?> = _bootstrapState.asStateFlow()

    private val _bootstrapHostName = MutableStateFlow<String>("")
    val bootstrapHostName: StateFlow<String> = _bootstrapHostName.asStateFlow()

    /**
     * The host + key path we'd navigate to once the bootstrap flow
     * resolves. The screen consumes this via [consumePendingNavigation].
     */
    private val _pendingNavigation = MutableStateFlow<PendingNavigation?>(null)
    val pendingNavigation: StateFlow<PendingNavigation?> = _pendingNavigation.asStateFlow()

    /** Session held open while the bootstrap sheet is visible so install reuses the connection. */
    private var bootstrapSession: SshSession? = null

    /** Cached host so install can update the right row. */
    private var bootstrapTargetHost: HostEntity? = null

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
     * Kick the bootstrap flow for [host] using the key stored at
     * [keyPath]. Cache-aware: when the host was probed within the last
     * 24h and tmux is present, navigates straight through without
     * connecting; when tmux was previously missing within 24h, the user
     * is prompted again so they can choose to retry the install.
     *
     * Caller is the host-list screen's tap handler. The screen observes
     * [pendingNavigation] for the "open the session" signal — it fires
     * once the bootstrap resolves (immediately on cache hit, or after
     * the user taps Skip / Continue on the sheet).
     */
    fun bootstrapHost(host: HostEntity, keyPath: String) {
        // Cache: hit within 24h with tmux present → fast path. The
        // navigation collector only fires `onOpenSession` once
        // `ready == true`, so the cache hit must mark itself ready
        // immediately.
        if (host.tmuxInstalled == true && host.isBootstrapFresh()) {
            _pendingNavigation.value = PendingNavigation(host, keyPath, ready = true)
            return
        }

        // Probe (re-probe if stale or unknown). For a previously-missing
        // host we still want to re-check because the user may have fixed
        // it via SSH on their own — but we cap the probe at the existing
        // connect timeout so a flaky network doesn't hang the tap.
        _bootstrapHostName.value = host.name
        // Pending stays at ready=false until the probe (and any sheet
        // interaction) resolves.
        _pendingNavigation.value = PendingNavigation(host, keyPath, ready = false)
        bootstrapTargetHost = host

        viewModelScope.launch {
            val session = openSession(host, keyPath)
            if (session == null) {
                // Couldn't connect → don't block navigation. The session
                // screen will surface the same connect failure with its
                // own retry UX, which is the right place to handle it.
                _pendingNavigation.value = PendingNavigation(host, keyPath, ready = true)
                return@launch
            }
            bootstrapSession = session

            when (val status = bootstrapper.checkTmux(session)) {
                TmuxStatus.Installed -> {
                    persistResult(host, installed = true)
                    closeBootstrapSession()
                    _pendingNavigation.value = PendingNavigation(host, keyPath, ready = true)
                }

                TmuxStatus.Missing -> {
                    persistResult(host, installed = false)
                    _bootstrapState.value = HostBootstrapSheetState.Prompt
                }

                is TmuxStatus.Unknown -> {
                    // Can't prove missing → continue, don't pester the user.
                    closeBootstrapSession()
                    _pendingNavigation.value = PendingNavigation(host, keyPath, ready = true)
                    @Suppress("UNUSED_VARIABLE") val reason = status.reason
                }
            }
        }
    }

    /**
     * Force a re-probe regardless of cache. The host detail screen / a
     * future "check tmux" affordance can call this. Not currently wired
     * into the UI (issue #49 acceptance only requires the
     * cache-on-connect behaviour) but here so the cache invalidation
     * acceptance criterion is testable.
     */
    fun refreshBootstrap(host: HostEntity, keyPath: String) {
        val cleared = host.copy(tmuxInstalled = null, lastBootstrapAt = null)
        bootstrapHost(cleared, keyPath)
    }

    /**
     * Called when the user taps **Install** on the sheet. Re-uses the
     * already-open [bootstrapSession]; if for some reason it's gone
     * (process death, GC) the install is treated as a transport error.
     */
    fun installTmuxOnPendingHost() {
        val session = bootstrapSession
        val host = bootstrapTargetHost
        if (session == null || host == null) {
            _bootstrapState.value = HostBootstrapSheetState.Failed(
                message = "Connection closed before install could start. Tap the host again to retry.",
            )
            return
        }
        _bootstrapState.value = HostBootstrapSheetState.Installing
        viewModelScope.launch {
            when (val result = bootstrapper.installTmux(session)) {
                InstallResult.Success -> {
                    persistResult(host, installed = true)
                    _bootstrapState.value = HostBootstrapSheetState.Success
                }

                is InstallResult.Failed -> {
                    _bootstrapState.value = HostBootstrapSheetState.Failed(
                        message = result.stderr.ifBlank { "exit ${result.exitCode}" },
                    )
                }

                is InstallResult.UnsupportedOs -> {
                    val osPart = result.osId?.let { " (detected: $it)" } ?: ""
                    _bootstrapState.value = HostBootstrapSheetState.Failed(
                        message = "PocketShell doesn't have a tmux installer for this host's OS$osPart. Install tmux manually and reconnect.",
                    )
                }

                is InstallResult.Error -> {
                    _bootstrapState.value = HostBootstrapSheetState.Failed(message = result.reason)
                }
            }
        }
    }

    /**
     * User tapped Skip / Continue / Close on the sheet (or swiped it
     * down). Close the probe session and flush the pending navigation so
     * the screen routes to the session.
     */
    fun dismissBootstrapAndOpen() {
        closeBootstrapSession()
        _bootstrapState.value = null
        val pending = _pendingNavigation.value ?: return
        _pendingNavigation.value = pending.copy(ready = true)
    }

    /**
     * Called by the screen once it has consumed the pending navigation
     * (i.e. invoked `onOpenSession`). Clears the flag so a subsequent
     * tap re-triggers the flow.
     */
    fun consumePendingNavigation() {
        _pendingNavigation.value = null
        bootstrapTargetHost = null
        _bootstrapHostName.value = ""
    }

    private suspend fun openSession(host: HostEntity, keyPath: String): SshSession? {
        val file = File(keyPath)
        if (!file.exists()) return null
        return SshConnection.connect(
            host = host.hostname,
            port = host.port,
            user = host.username,
            key = SshKey.Path(file),
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrNull()
    }

    private suspend fun persistResult(host: HostEntity, installed: Boolean) {
        val now = System.currentTimeMillis()
        // Reload from DB in case the row changed underneath us (e.g. an
        // edit). Fall back to the in-memory snapshot if it's gone.
        val current = hostDao.getById(host.id) ?: host
        hostDao.update(
            current.copy(
                tmuxInstalled = installed,
                lastBootstrapAt = now,
            ),
        )
    }

    private fun closeBootstrapSession() {
        bootstrapSession?.let { runCatching { it.close() } }
        bootstrapSession = null
    }

    override fun onCleared() {
        super.onCleared()
        closeBootstrapSession()
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

    /**
     * Pending-navigation tuple: the host + key path the screen would
     * route to once the bootstrap flow resolves. [ready] flips to `true`
     * when the screen is allowed to fire `onOpenSession`.
     */
    public data class PendingNavigation(
        val host: HostEntity,
        val keyPath: String,
        val ready: Boolean = false,
    )

    private companion object {
        /** 24-hour cache window — re-probe after this. */
        const val BOOTSTRAP_CACHE_MS: Long = 24L * 60L * 60L * 1000L
    }

    /**
     * Visible extension on the receiver so test code (and
     * [bootstrapHost]) can ask the question without leaking the constant
     * through the public surface. Returns `true` when the last bootstrap
     * stamp is within [BOOTSTRAP_CACHE_MS] of `now`.
     */
    private fun HostEntity.isBootstrapFresh(now: Long = System.currentTimeMillis()): Boolean {
        val last = lastBootstrapAt ?: return false
        return now - last < BOOTSTRAP_CACHE_MS
    }
}
