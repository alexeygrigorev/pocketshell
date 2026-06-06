package com.pocketshell.app.hosts

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.bootstrap.HostBootstrapReport
import com.pocketshell.app.bootstrap.HostBootstrapSheetState
import com.pocketshell.app.bootstrap.HostBootstrapper
import com.pocketshell.app.bootstrap.InstallResult
import com.pocketshell.app.bootstrap.BootstrapTool
import com.pocketshell.app.bootstrap.PocketshellDaemonStatus
import com.pocketshell.app.bootstrap.TmuxStatus
import com.pocketshell.app.bootstrap.ToolStatus
import com.pocketshell.app.bootstrap.cliUpdateFailureMessage
import com.pocketshell.app.notifications.DefaultUpdateNotifier
import com.pocketshell.app.notifications.UpdateNotifier
import com.pocketshell.app.release.ReleaseCheckResult
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.SessionSummary
import com.pocketshell.app.sessions.SSH_SOURCE_WARM_HOST_CONNECT
import com.pocketshell.app.sessions.SshOpenTelemetry
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.app.usage.UsageScheduler
import com.pocketshell.app.usage.UsageSnapshot
import com.pocketshell.app.usage.worstBadgeRecord
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.uikit.model.HostSetupState
import com.pocketshell.uikit.model.HostStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IdentityHashMap
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
class HostListViewModel internal constructor(
    @ApplicationContext private val applicationContext: Context,
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
    private val releaseChecker: ReleaseChecker,
    private val bootstrapper: HostBootstrapper,
    // Issue #116 (usage-panel Fix B): the host list mounts the cross-host
    // [com.pocketshell.app.usage.UsageDashboardStrip] above its section
    // header, and each card row may render a blocked/near-limit chip.
    // Both surfaces read off [UsageScheduler.snapshots] — the singleton
    // scheduler from #117 already polls every pocketshell-installed host on the
    // 60s / 5m cadence and caches the result here. Hilt always supplies
    // the real singleton in production because [UsageScheduler] is
    // `@Singleton @Inject`; the Robolectric tests construct one with the
    // same in-memory DAOs they already use.
    private val usageScheduler: UsageScheduler,
    // Issue #201: the per-host status chip needs to know which hosts
    // the app is currently attached to. The same singleton registry
    // already drives the cross-host session dashboard
    // ([SessionsDashboardViewModel]); we inject it here read-only so
    // the derived [HostStatus] can flip to `Attached` the moment a
    // `tmux -CC` client registers.
    private val activeClients: ActiveTmuxClients,
    // Issue #214: the cross-host strip + per-host badge + dismissible
    // banner all consult the user-configurable "approaching limit"
    // threshold so the user can decide whether 80 % or 90 % is
    // the right place to start surfacing warnings. The repository is
    // hot-cached and reading `.value` from it costs no I/O.
    private val settingsRepository: SettingsRepository,
    private val sshLeaseManager: SshLeaseManager = SshLeaseManager(
        connector = SshLeaseConnector { target ->
            com.pocketshell.core.ssh.DefaultSshLeaseConnector().connect(target)
        },
    ),
    private val sessionOpener: HostSessionOpener = HostSessionOpener { _, _, _ -> null },
    // Issue #502: posts a local "new version available" notification when
    // the foreground [releaseChecker] detects a strictly-newer release.
    // De-dupes per version so the user isn't re-notified on every cold
    // launch / pull-to-refresh. Defaulted to the production notifier so
    // unit tests can inject a recording fake.
    private val updateNotifier: UpdateNotifier = DefaultUpdateNotifier(applicationContext),
) : ViewModel() {

    @Inject
    constructor(
        @ApplicationContext applicationContext: Context,
        hostDao: HostDao,
        sshKeyDao: SshKeyDao,
        releaseChecker: ReleaseChecker,
        bootstrapper: HostBootstrapper,
        usageScheduler: UsageScheduler,
        activeClients: ActiveTmuxClients,
        settingsRepository: SettingsRepository,
        sshLeaseManager: SshLeaseManager,
    ) : this(
        applicationContext = applicationContext,
        hostDao = hostDao,
        sshKeyDao = sshKeyDao,
        releaseChecker = releaseChecker,
        bootstrapper = bootstrapper,
        usageScheduler = usageScheduler,
        activeClients = activeClients,
        settingsRepository = settingsRepository,
        sshLeaseManager = sshLeaseManager,
        sessionOpener = LeaseBackedHostSessionOpener(sshLeaseManager),
    )

    /** Live list of saved hosts, sorted by name (DAO query). */
    val hosts: StateFlow<List<HostEntity>> = hostDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Per-host setup-state derived from the persisted bootstrap columns
     * on [HostEntity]. Drives the badge on the host card surface (issue
     * #120). Keyed by `HostEntity.id` so the screen can look up the
     * state for a row independently of the host snapshot it currently
     * holds.
     *
     * Derivation rule (kept intentionally narrow):
     *
     * - `Ready`        — `tmuxInstalled == true` AND `pocketshellInstalled == true`.
     *                    Both required tools have been verified by the
     *                    most recent bootstrap probe.
     * - `NeedsSetup`   — `tmuxInstalled == false`, OR `pocketshellInstalled ==
     *                    false`. The probe reported at least one tool
     *                    missing.
     * - `Unknown`      — anything else (no probe yet, or a row that never
     *                    recorded `pocketshellInstalled`).
     *
     * The map only reflects what's persisted; the in-memory
     * [HostBootstrapReport] from the most recent connect lives in the
     * sheet state and is not durable across screens. The persisted flags
     * are the contract for the badge so the value survives navigation.
     */
    val setupStates: StateFlow<Map<Long, HostSetupState>> = hostDao.getAll()
        .map { rows -> rows.associate { it.id to deriveSetupState(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

    // Issue #483: the cross-host `usageDashboardRows` flow and the
    // strip-gating `hasUsageInstalledHost` flow that fed the global usage
    // strip at the top of the host list were removed with the strip
    // itself (D22 hard-cut). Issue #506 also removed the per-host usage
    // chip that briefly replaced the strip; usage is reachable per-host
    // via the kebab → "Usage" item. Settings keeps its own independent
    // `hasUsageInstalledHost` for the Settings → Usage entry gate.

    /**
     * Read-only projection of [ActiveTmuxClients.clients] used by the
     * host-card status chip (issue #201) to flip a row to the
     * `Attached` state when the app holds a live `tmux -CC` client
     * against the host. Keyed by [HostEntity.id]; emits the empty set
     * when no clients are registered. Exposed as a `Set` rather than
     * the full registry map so the screen layer only re-renders on
     * register / unregister, not on internal entry-value changes.
     */
    val attachedHostIds: StateFlow<Set<Long>> = activeClients.clients
        .map { it.keys.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())

    /**
     * Per-host worst-case provider record — populated only for hosts
     * whose latest scheduler snapshot warrants the in-app blocked /
     * near-limit chip (issue #116 AC). Keyed by [HostEntity.id]; absence
     * means "no badge".
     *
     * Issue #214: the worst-case derivation now consults the
     * user-configurable warn threshold so the in-app warning state
     * stays in sync with the cross-host strip + Settings → Usage list.
     */
    val usageBadges: StateFlow<Map<Long, UsageProviderRecord>> = combine(
        usageScheduler.snapshots,
        settingsRepository.settings,
    ) { snapshots, settings ->
        val warn = settings.usageWarnThresholdPercent.toDouble()
        snapshots.mapNotNull { (id, snap) ->
            snap.worstBadgeRecord(warnPercent = warn)?.let { id to it }
        }.toMap()
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

    // Issue #483 introduced a per-host usage summary chip (`usageSummaries`)
    // rendered under each host card; issue #506 dropped that chip because it
    // read as a cryptic floating row. Usage is reachable per-host via the
    // kebab → "Usage" item, so the dedicated summary flow is gone (D22
    // hard-cut — no dead state).

    /**
     * Records that should drive a dismissible in-app banner above the
     * host list (issue #214). One entry per provider keyed by the
     * provider string (lower-cased — `pocketshell usage` emits e.g. `"claude"`),
     * picking the worst-case record across all hosts so a 96 % Claude
     * on host A and a 50 % Claude on host B surface a single banner
     * representing the 96 % state. Empty when no provider warrants a
     * warning.
     *
     * Dismissals live on [dismissedBanners] and are intentionally
     * in-memory only (the issue spec: "Dismissible; survives until app
     * restart OR until percentage drops back below threshold").
     */
    val usageWarningProviders: StateFlow<Map<String, UsageProviderRecord>> = combine(
        usageScheduler.snapshots,
        settingsRepository.settings,
    ) { snapshots, settings ->
        val warn = settings.usageWarnThresholdPercent.toDouble()
        val recordsByProvider = mutableMapOf<String, Pair<UsageProviderRecord, Int>>()
        snapshots.values
            .filterIsInstance<UsageSnapshot.Records>()
            .flatMap { it.records }
            .forEach { record ->
                val state = record.thresholdState(warnPercent = warn)
                if (!state.warrantsWarning) return@forEach
                val key = record.provider.lowercase()
                val current = recordsByProvider[key]
                if (current == null || state.ordinal > current.second) {
                    recordsByProvider[key] = record to state.ordinal
                }
            }
        recordsByProvider.mapValues { it.value.first }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

    /**
     * Set of provider ids the user has dismissed for this app session.
     * Reset on every fresh `HostListViewModel` instance (i.e. cold
     * launch), matching the issue spec: "Dismissible; survives until
     * app restart OR until percentage drops back below threshold".
     * Also cleared whenever a provider's record drops back to
     * [com.pocketshell.core.usage.UsageThresholdState.Ok] — the latter
     * is handled implicitly by [usageWarningProviders] no longer
     * surfacing the provider, so the host list filters dismissed
     * providers against the live warning set and a re-cross-threshold
     * brings the banner back automatically.
     */
    private val _dismissedBanners = MutableStateFlow<Set<String>>(emptySet())
    val dismissedBanners: StateFlow<Set<String>> = _dismissedBanners.asStateFlow()

    /**
     * Mark [providerId] as dismissed for this app session so the
     * banner stops rendering. `providerId` is normalised to lowercase
     * to match the keying on [usageWarningProviders].
     */
    fun dismissUsageBanner(providerId: String) {
        val key = providerId.lowercase()
        if (_dismissedBanners.value.contains(key)) return
        _dismissedBanners.value = _dismissedBanners.value + key
    }

    /**
     * Transient one-shot acknowledgement message for the manual
     * "Re-check setup" affordance (issue #120). Surfaces in the existing
     * `shareMessage` banner slot — keeping the banner pattern means no
     * Scaffold-level Snackbar host is required. `null` hides the
     * banner; the screen consumes via [clearRecheckMessage].
     */
    private val _recheckMessage = MutableStateFlow<String?>(null)
    val recheckMessage: StateFlow<String?> = _recheckMessage.asStateFlow()

    /**
     * Non-null when a newer GitHub Release is available than the
     * installed APK. Consumed by [HostListScreen] to render the update
     * banner / chip with a tap-to-download action.
     */
    private val _updateAvailable = MutableStateFlow<ReleaseInfo?>(null)
    val updateAvailable: StateFlow<ReleaseInfo?> = _updateAvailable.asStateFlow()

    /**
     * Issue #515: non-null when the most recent GitHub-Releases poll did
     * not complete (non-200 / rate-limit / network error / unparseable
     * body), carrying the concrete failure reason. The old check
     * collapsed "no newer release" and "the check itself failed" into the
     * same silent `null`, so a single bad cold-launch moment produced no
     * banner and no trace until the next cold start. Surfacing the failure
     * gives the user a visible, dismissible "couldn't check for updates —
     * Retry" affordance instead of a silent no-op, and the reason is also
     * logged in [ReleaseChecker.checkForUpdate]. Cleared on the next
     * successful check (or a dismiss). `null` hides the banner.
     */
    private val _updateCheckFailed = MutableStateFlow<UpdateCheckFailure?>(null)
    val updateCheckFailed: StateFlow<UpdateCheckFailure?> = _updateCheckFailed.asStateFlow()

    /** Dismiss the "couldn't check for updates" banner for now. */
    fun dismissUpdateCheckFailure() {
        _updateCheckFailed.value = null
    }

    /**
     * Issue #476: transient feedback for the update-banner tap. The
     * download itself is launched from the screen (it needs an Android
     * `Context` to fire `ACTION_VIEW`), but the user-visible "what
     * happened" message lives here so it's testable and surfaces through
     * the same one-shot banner pattern as [shareMessage] /
     * [recheckMessage]. A working download used to look like a silent
     * failure; this gives every tap a clear started/failed result.
     * `null` hides the banner; the screen consumes via
     * [clearUpdateMessage].
     */
    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage.asStateFlow()

    /**
     * Records that the APK download intent was launched successfully. The
     * download lands in the system Downloads / a notification, so we point
     * the user there rather than implying the install is automatic.
     */
    fun onUpdateDownloadStarted(tagName: String) {
        _updateMessage.value =
            "Downloading $tagName — check your notifications / Downloads"
    }

    /**
     * Records that the primary APK-download intent could not be launched
     * (no handler / security denial). The screen falls back to opening the
     * release page in a browser, so the message names the reason and tells
     * the user we opened the release page instead.
     */
    fun onUpdateDownloadFailed(reason: String) {
        _updateMessage.value =
            "Couldn't start the download: $reason — opened the release page instead"
    }

    fun clearUpdateMessage() {
        _updateMessage.value = null
    }

    /**
     * Issue #514: small, non-blocking, dismissible note shown when the
     * remote pocketshell CLI is *newer* than this app build. A minor
     * version delta almost never breaks compatibility, so the host stays
     * fully set up and usable (usage panel, sessions, folders all work) —
     * this is the ONLY surfaced difference. NOT a takeover sheet, NOT a
     * modal, NOT a "setup needed" framing, and it never runs the host
     * installer. `null` hides the banner.
     *
     * In-memory only (resets on cold launch) like the usage / share /
     * re-check banners. Re-raised the next time we probe a remote-newer
     * host; dismissal lasts the app session via [dismissAppUpdateWarning].
     */
    private val _appUpdateWarning = MutableStateFlow<AppUpdateWarning?>(null)
    val appUpdateWarning: StateFlow<AppUpdateWarning?> = _appUpdateWarning.asStateFlow()

    /**
     * Set of "remote newer" host ids the user has dismissed this session,
     * so re-probing a dismissed host does not re-raise the same banner.
     */
    private val _dismissedAppUpdateWarnings = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Raise the soft "consider updating the app" banner when [report]
     * reports the remote pocketshell CLI is newer than this app build
     * (`pocketshellAppUpdateRequired != null`). No-op otherwise, and a
     * no-op when the user already dismissed it for this host this session.
     * The host itself is left fully functional — this only adds a banner.
     */
    private fun maybeRaiseAppUpdateWarning(host: HostEntity, report: HostBootstrapReport) {
        val appUpdate = report.pocketshellAppUpdateRequired ?: return
        if (_dismissedAppUpdateWarnings.value.contains(host.id)) return
        _appUpdateWarning.value = AppUpdateWarning(
            hostId = host.id,
            remoteVersion = appUpdate.currentVersion,
            appVersion = appUpdate.expectedVersion,
        )
        // Issue #515: the host probe just *proved* this app build is behind
        // the remote pocketshell CLI — the strongest possible "you should
        // update" signal. Try to resolve a real downloadable GitHub release
        // so the banner can offer an actionable, OPTIONAL "Update" (the same
        // ACTION_VIEW → APK path the standalone UpdateBanner uses) instead of
        // a dead passive line. This is best-effort and non-blocking: the host
        // is already fully usable, and if the GitHub lookup finds nothing
        // newer (or fails) the banner stays a passive note that still
        // dismisses cleanly.
        resolveAppUpdateRelease(host.id)
    }

    /**
     * Best-effort GitHub-release lookup that populates the [AppUpdateWarning]
     * banner's actionable [AppUpdateWarning.releaseInfo] so the user can tap
     * "Update" and get the APK. Runs in [viewModelScope]; on any failure or
     * "no newer release" the banner simply keeps its passive form. Guards
     * against a stale write by re-checking the live warning still targets the
     * same host before applying the resolved release.
     */
    private fun resolveAppUpdateRelease(hostId: Long) {
        viewModelScope.launch {
            val currentVersion = currentVersionName() ?: return@launch
            val info = releaseChecker.checkForUpdate(currentVersion).infoOrNull() ?: return@launch
            val current = _appUpdateWarning.value ?: return@launch
            if (current.hostId != hostId || current.releaseInfo != null) return@launch
            _appUpdateWarning.value = current.copy(releaseInfo = info)
        }
    }

    /**
     * Dismiss the app-update warning for this app session. Records the
     * host id so a later re-probe of the same remote-newer host does not
     * pop the banner again until the next cold launch.
     */
    fun dismissAppUpdateWarning() {
        val current = _appUpdateWarning.value ?: return
        _dismissedAppUpdateWarnings.value = _dismissedAppUpdateWarnings.value + current.hostId
        _appUpdateWarning.value = null
    }

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

    private val _sharePayload = MutableStateFlow<HostSharePayload?>(null)
    val sharePayload: StateFlow<HostSharePayload?> = _sharePayload.asStateFlow()

    private val _shareMessage = MutableStateFlow<String?>(null)
    val shareMessage: StateFlow<String?> = _shareMessage.asStateFlow()

    /**
     * Pending import-conflict prompt. Issue #157 polish item 2: when an
     * incoming import (QR scan, file pick, clipboard paste) resolves to
     * a `(hostname, port)` that already exists in the local database,
     * we pause the write and surface this state so the UI can prompt
     * "Host X:Y already exists — Overwrite, Skip, or Add as new?"
     * before mutating storage. `null` means there is no pending
     * conflict to resolve.
     */
    private val _importConflict = MutableStateFlow<ImportConflict?>(null)
    val importConflict: StateFlow<ImportConflict?> = _importConflict.asStateFlow()

    /**
     * The host + key path we'd navigate to once the bootstrap flow
     * resolves. The screen consumes this via [consumePendingNavigation].
     */
    private val _pendingNavigation = MutableStateFlow<PendingNavigation?>(null)
    val pendingNavigation: StateFlow<PendingNavigation?> = _pendingNavigation.asStateFlow()

    /**
     * Foreground host-open progress shown on the tapped host card.
     * This is intentionally separate from background setup reprobes so
     * cold-launch maintenance never makes the host list look blocked.
     */
    private val _hostOpenProgress = MutableStateFlow<HostOpenProgress?>(null)
    val hostOpenProgress: StateFlow<HostOpenProgress?> = _hostOpenProgress.asStateFlow()

    /** Session held open while the bootstrap sheet is visible so install reuses the connection. */
    private var bootstrapSession: SshSession? = null

    /** Cached host so install can update the right row. */
    private var bootstrapTargetHost: HostEntity? = null

    /** Guard so the cold-launch reprobe only fires once per ViewModel. */
    private var autoReprobeKicked = false

    /** Per-host bootstrap probe coordination across silent reprobes and foreground taps. */
    private val probeLocks = mutableMapOf<Long, Mutex>()

    /** Incremented by foreground taps so older silent probes cannot persist late results. */
    private val foregroundProbeGenerations = mutableMapOf<Long, Long>()

    init {
        checkForUpdates()
    }

    /**
     * Cold-launch background reprobe entry point (issue #120 AC: "If
     * the report has not run yet… show `unknown` and trigger a
     * background reprobe on first composition"). Invoked from the host
     * list screen via `LaunchedEffect(Unit) {}` so the trigger is
     * literally "first composition" — and unit tests that construct
     * the ViewModel without a UI don't get a surprise IO probe firing
     * in the background.
     *
     * Idempotent: subsequent calls within the same ViewModel lifetime
     * are no-ops. The guard intentionally lives on the ViewModel (not
     * the screen) so a configuration change that re-composes the
     * screen doesn't re-fire the probe.
     *
     * Only hosts whose key is NOT passphrase-protected are probed —
     * unlocking a passphrase requires user interaction (biometric
     * prompt / dialog), so a silent background probe cannot succeed
     * there. For passphrase-protected hosts the badge stays `Unknown`
     * until the user manually taps "Re-check setup".
     */
    fun reprobeUnknownHostsOnce() {
        if (autoReprobeKicked) return
        autoReprobeKicked = true
        StartupTiming.mark("hostlist-reprobe-kicked")
        viewModelScope.launch {
            // Defensive: probe failures stay silent for the user — the
            // badge just remains `Unknown` and the manual re-check
            // affordance is available.
            try {
                val firstRows = hosts.first { it.isNotEmpty() }
                firstRows
                    .filter { deriveSetupState(it) == HostSetupState.Unknown }
                    .forEach { host ->
                        val key = sshKeyDao.getById(host.keyId) ?: return@forEach
                        if (key.hasPassphrase) return@forEach
                        if (!File(key.privateKeyPath).exists()) return@forEach
                        StartupTiming.markOnce(
                            "hostlist-reprobe-ssh-start",
                            "hostId" to host.id,
                            "host" to host.hostname,
                            "port" to host.port,
                        )
                        recheckHostSilently(host, key.privateKeyPath)
                    }
            } catch (_: IllegalStateException) {
                // Most often: "Cannot perform this operation because the
                // connection pool has been closed" — the DB went away
                // mid-probe. Defensive swallow.
            }
        }
    }

    /**
     * Look up the key entity for the given key id. Returns `null` if the
     * key has been deleted (the foreign-key CASCADE on `hosts.keyId` should
     * keep this from happening — but the suspending lookup means the call
     * site has a defined behaviour for the race anyway).
     */
    suspend fun keyFor(keyId: Long): SshKeyEntity? = sshKeyDao.getById(keyId)

    fun createSharePayload(host: HostEntity) {
        viewModelScope.launch {
            val key = sshKeyDao.getById(host.keyId)
            if (key == null) {
                _shareMessage.value = "Cannot share ${host.name}: its SSH key is missing"
                return@launch
            }
            // Share-out uses the canonical SSH-import payload with a
            // [SshImportAuth.KeyReference] so private-key material never
            // leaves the device. Wrap it in the QR envelope because the
            // live scanner accepts envelope payloads only.
            val importPayload = SshImportPayloadCodec.encode(
                SshImportConfig(
                    name = host.name,
                    host = host.hostname,
                    port = host.port,
                    username = host.username,
                    auth = SshImportAuth.KeyReference(name = key.name),
                ),
            )
            val payload = QrChunkCodec.encode(importPayload).single()
            _sharePayload.value = HostSharePayload(
                hostName = host.name,
                payload = payload,
            )
            _shareMessage.value = null
        }
    }

    fun dismissSharePayload() {
        _sharePayload.value = null
    }

    fun clearShareMessage() {
        _shareMessage.value = null
    }

    fun importSharedHostPayload(payload: String): Job = viewModelScope.launch {
        importSharedHostPayloadInternal(payload)
    }

    private suspend fun importSharedHostPayloadInternal(payload: String) {
        // Issue #129: a multi-QR envelope wraps the SSH-import JSON.
        // Single-part envelopes also use the same prefix so a desktop
        // emitter can always emit envelopes regardless of payload size;
        // we unwrap once here and recurse with the inner payload.
        if (QrChunkCodec.isEnvelope(payload)) {
            val part = QrChunkCodec.decodePart(payload).getOrElse { error ->
                _shareMessage.value = error.message ?: "Could not decode QR envelope"
                return
            }
            if (part.total != 1) {
                _shareMessage.value =
                    "This is part ${part.part} of ${part.total}. Scan from the QR scanner so all parts can be combined."
                return
            }
            importSharedHostPayloadInternal(String(part.chunk, Charsets.UTF_8))
            return
        }
        val sshImport = SshImportPayloadCodec.decode(payload)
        if (sshImport.isSuccess) {
            importSshHost(sshImport.getOrThrow())
            return
        }
        _shareMessage.value = sshImport.exceptionOrNull()?.message ?: "Could not read shared host"
    }

    private suspend fun importSshHost(config: SshImportConfig) {
        val key = when (val auth = config.auth) {
            is SshImportAuth.PrivateKey -> {
                try {
                    SshKeyStorage.persistKey(
                        context = applicationContext,
                        sshKeyDao = sshKeyDao,
                        name = auth.name,
                        content = auth.privateKeyPem,
                    )
                } catch (t: Throwable) {
                    _shareMessage.value = "Could not import SSH key: ${t.message}"
                    return
                }
            }

            is SshImportAuth.KeyReference -> {
                sshKeyDao.getByName(auth.name) ?: run {
                    _shareMessage.value = "Import the SSH key named ${auth.name} before importing this host"
                    return
                }
            }
        }

        // Issue #157 polish item 2: surface a conflict prompt instead of
        // silently overwriting / duplicating when the inbound host
        // matches an existing row's `(hostname, port)`. The user resolves
        // it via [resolveImportConflict].
        val existing = findHostByEndpoint(config.host, config.port)
        if (existing != null) {
            _importConflict.value = ImportConflict(
                incoming = PendingHostImport(
                    name = config.name,
                    hostname = config.host,
                    port = config.port,
                    username = config.username,
                    keyId = key.id,
                ),
                existing = existing,
            )
            return
        }

        hostDao.insert(
            HostEntity(
                name = config.name,
                hostname = config.host,
                port = config.port,
                username = config.username,
                keyId = key.id,
                enabled = false,
            ),
        )
        _shareMessage.value = "Imported ${config.name}"
    }

    /**
     * Look up an existing host by `(hostname, port)`. Returns the first
     * match (the DAO orders by name) or `null` if no host matches.
     *
     * Implemented as a one-shot `first()` over the existing list flow
     * rather than a dedicated DAO query to keep this issue from
     * extending the Room schema / DAO surface — the host list is small
     * (single-digit rows is the norm during active development) so the in-memory scan
     * has no practical cost.
     */
    private suspend fun findHostByEndpoint(hostname: String, port: Int): HostEntity? {
        val rows = hostDao.getAll().first()
        return rows.firstOrNull { it.hostname == hostname && it.port == port }
    }

    /**
     * Resolve the pending [ImportConflict] surfaced by an in-flight
     * import. The three resolutions mirror the dialog buttons:
     *
     *  - [ImportConflictResolution.Overwrite] — update the existing row
     *    in place, preserving its `id` (so any sessions / bootstrap
     *    cache rows that reference the id stay valid) but replacing
     *    `name` / `username` / `keyId` with the incoming values.
     *    `tmuxInstalled` / `pocketshellInstalled` / `lastBootstrapAt` are
     *    cleared so the next connect re-probes — the inbound host may
     *    point at a freshly-rebuilt remote that no longer has the
     *    cached tooling.
     *  - [ImportConflictResolution.Skip] — drop the incoming payload
     *    silently; the existing row is untouched.
     *  - [ImportConflictResolution.AddAsNew] — insert anyway. The user
     *    explicitly accepts that two rows now point at the same
     *    `(hostname, port)`, which is occasionally what they want
     *    (e.g. two profiles with different usernames or PATH overrides
     *    for the same VM).
     */
    fun resolveImportConflict(resolution: ImportConflictResolution): Job = viewModelScope.launch {
        val conflict = _importConflict.value ?: return@launch
        when (resolution) {
            ImportConflictResolution.Overwrite -> {
                val updated = conflict.existing.copy(
                    name = conflict.incoming.name,
                    hostname = conflict.incoming.hostname,
                    port = conflict.incoming.port,
                    username = conflict.incoming.username,
                    keyId = conflict.incoming.keyId,
                    // The remote may have changed underneath us; force a
                    // re-probe on next connect.
                    tmuxInstalled = null,
                    pocketshellInstalled = null,
                    lastBootstrapAt = null,
                    pocketshellLastDetectedAt = null,
                    pocketshellCliVersion = null,
                    pocketshellExpectedCliVersion = null,
                    pocketshellVersionCompatible = null,
                    pocketshellDaemonRunning = null,
                    pocketshellDaemonEnabled = null,
                )
                hostDao.update(updated)
                _shareMessage.value = "Overwrote ${conflict.existing.name}"
            }
            ImportConflictResolution.Skip -> {
                _shareMessage.value = "Already added: ${conflict.existing.name}"
            }
            ImportConflictResolution.AddAsNew -> {
                hostDao.insert(
                    HostEntity(
                        name = conflict.incoming.name,
                        hostname = conflict.incoming.hostname,
                        port = conflict.incoming.port,
                        username = conflict.incoming.username,
                        keyId = conflict.incoming.keyId,
                        enabled = false,
                    ),
                )
                _shareMessage.value = "Imported ${conflict.incoming.name}"
            }
        }
        _importConflict.value = null
    }

    /** Dismiss the import-conflict prompt without writing anything. */
    fun dismissImportConflict() {
        _importConflict.value = null
        _shareMessage.value = null
    }

    fun importSharedHostUri(uri: Uri) {
        viewModelScope.launch {
            val payload = withContext(kotlinx.coroutines.Dispatchers.IO) {
                HostQrCode.decode(applicationContext, uri).getOrElse {
                    runCatching {
                        applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader(Charsets.UTF_8).readText()
                        }
                    }.getOrNull()
                }
            }
            if (payload == null) {
                _shareMessage.value = "Could not read shared host"
                return@launch
            }
            importSharedHostPayloadInternal(payload)
        }
    }

    /**
     * Re-run the GitHub-Releases check. Called from `init {}` on
     * construction, and intended to be re-invoked from the UI (e.g. a
     * pull-to-refresh handler on [HostListScreen]). Safe to call
     * repeatedly — the underlying [ReleaseChecker] is stateless and
     * each call is a fresh HTTP request.
     */
    fun checkForUpdates() {
        StartupTiming.markOnce("hostlist-update-check-start")
        viewModelScope.launch {
            val currentVersion = currentVersionName()
            if (currentVersion == null) {
                _updateAvailable.value = null
                _updateCheckFailed.value = null
                return@launch
            }
            // Issue #515: the check now classifies its outcome instead of
            // collapsing "no newer release" and "the check failed" into the
            // same silent null. A genuine failure (non-200 / rate-limit /
            // network blip / unparseable body) raises a visible, dismissible
            // "couldn't check — Retry" banner and is logged with its reason;
            // a successful "up to date" check clears any prior failure
            // without surfacing anything.
            when (val result = releaseChecker.checkForUpdate(currentVersion)) {
                is ReleaseCheckResult.UpdateAvailable -> {
                    _updateAvailable.value = result.info
                    _updateCheckFailed.value = null
                    StartupTiming.mark("hostlist-update-check-available", "tag" to result.info.tagName)
                    // Issue #502: surface the newer release as a local
                    // notification so the user notices it even when they've
                    // skipped the host list (the [UpdateBanner] home) and gone
                    // straight into a session. The notifier de-dupes per
                    // version, so re-running the check (cold launch /
                    // pull-to-refresh) never re-spams the same release.
                    updateNotifier.notifyUpdateAvailable(result.info)
                }

                ReleaseCheckResult.UpToDate -> {
                    _updateAvailable.value = null
                    _updateCheckFailed.value = null
                    StartupTiming.mark("hostlist-update-check-uptodate")
                }

                is ReleaseCheckResult.Failed -> {
                    // Keep any previously-found update visible; only surface
                    // the failure so the user can retry. The reason is also
                    // logged inside the checker.
                    _updateCheckFailed.value = UpdateCheckFailure(result.reason)
                    StartupTiming.mark("hostlist-update-check-failed", "reason" to result.reason)
                }
            }
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
    fun bootstrapHost(host: HostEntity, keyPath: String, passphrase: CharArray? = null): Job {
        if (!markHostOpenRequestStarted(host.id, host.name)) return completedJob()
        // Cache: a fresh tmux result skips the bootstrap SSH probe when
        // the matching server-setup probe proved the unified pocketshell
        // CLI is present and app-compatible. Optional jobs/daemon state
        // is not part of the normal host-open gate. NeedsSetup /
        // CliUpdateNeeded rows must not route
        // immediately off a partial cache; they need a chance to re-run
        // checkServerSetup so README-installed CLIs can be upgraded.
        val probeLock = probeLockFor(host.id)
        markForegroundProbe(host.id)
        val skipTmuxProbe = !probeLock.isLocked && host.canUseBootstrapCache()

        // Probe (re-probe if stale or unknown). For a previously-missing
        // host we still want to re-check because the user may have fixed
        // it via SSH on their own — but we cap the probe at the existing
        // connect timeout so a flaky network doesn't hang the tap.
        _bootstrapHostName.value = host.name
        // Pending stays at ready=false until the probe (and any sheet
        // interaction) resolves.
        _pendingNavigation.value = PendingNavigation(host, keyPath, passphrase, ready = false)
        bootstrapTargetHost = host

        if (skipTmuxProbe) {
            _bootstrapState.value = null
            setHostOpenPhase(host.id, HostOpenPhase.OpeningFolders)
            _pendingNavigation.value = PendingNavigation(host, keyPath, passphrase, ready = true)
            return completedJob()
        }

        return viewModelScope.launch {
            probeLock.withLock {
                val currentHost = hostDao.getById(host.id) ?: host
                if (currentHost.canUseBootstrapCache()) {
                    _bootstrapState.value = null
                    setHostOpenPhase(host.id, HostOpenPhase.OpeningFolders)
                    _pendingNavigation.value = PendingNavigation(currentHost, keyPath, passphrase, ready = true)
                    return@withLock
                }
                runForegroundBootstrapProbe(currentHost, keyPath, passphrase)
            }
        }
    }

    private suspend fun runForegroundBootstrapProbe(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ) {
        val session = openSession(host, keyPath, passphrase)
        if (session == null) {
            // Couldn't connect → don't block navigation. The session
            // screen will surface the same connect failure with its
            // own retry UX, which is the right place to handle it.
            setHostOpenPhase(host.id, HostOpenPhase.OpeningFolders)
            _pendingNavigation.value = PendingNavigation(host, keyPath, passphrase, ready = true)
            return
        }
        bootstrapSession = session
        setHostOpenPhase(host.id, HostOpenPhase.CheckingSetup)

        // Issue #294: bootstrap probes derive PATH from the remote
        // user's interactive shell rc, then prepend PocketShell's
        // default user-bin dirs. No per-host PATH override is needed.
        when (val status = bootstrapper.checkTmux(session)) {
            TmuxStatus.Installed -> {
                persistResult(host, installed = true)
                val report = bootstrapper.checkServerSetup(session, expectedPocketshellVersion())
                persistPocketshellResult(host, report)
                // Issue #514: when the remote pocketshell CLI is newer than
                // this app build the host is fully set up — isReady is true,
                // we navigate normally, and the "consider updating the app"
                // note is surfaced as a small dismissible banner on the host
                // list (see [pocketshellAppUpdateWarning]), NOT a takeover
                // sheet. No installer, no "setup needed" framing, no loop.
                if (report.isReady) {
                    maybeRaiseAppUpdateWarning(host, report)
                    closeBootstrapSession()
                    setHostOpenPhase(host.id, HostOpenPhase.OpeningFolders)
                    _pendingNavigation.value = PendingNavigation(host, keyPath, passphrase, ready = true)
                } else {
                    clearHostOpenProgress(host.id)
                    _bootstrapState.value = HostBootstrapSheetState.Prompt(
                        needsTmux = false,
                        report = report,
                    )
                }
            }

            TmuxStatus.Missing -> {
                persistResult(host, installed = false)
                val report = bootstrapper.checkServerSetup(session, expectedPocketshellVersion())
                persistPocketshellResult(host, report)
                clearHostOpenProgress(host.id)
                _bootstrapState.value = HostBootstrapSheetState.Prompt(
                    needsTmux = true,
                    report = report,
                )
            }

            is TmuxStatus.Unknown -> {
                // Can't prove missing → continue, don't pester the user.
                closeBootstrapSession()
                setHostOpenPhase(host.id, HostOpenPhase.OpeningFolders)
                _pendingNavigation.value = PendingNavigation(host, keyPath, passphrase, ready = true)
                @Suppress("UNUSED_VARIABLE") val reason = status.reason
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
    fun refreshBootstrap(host: HostEntity, keyPath: String): Job {
        val cleared = host.copy(tmuxInstalled = null, lastBootstrapAt = null)
        return bootstrapHost(cleared, keyPath)
    }

    /**
     * Issue #120: manual "Re-check setup" entry point invoked from the
     * host-card kebab. Reuses [refreshBootstrap] under the hood so the
     * 24h cache is invalidated and the next bootstrap run does a real
     * probe, and surfaces a small acknowledgement message in the same
     * banner slot used by host-share messages so the user knows the tap
     * registered.
     *
     * Calls [refreshBootstrap] (and therefore [bootstrapHost]) which
     * already kicks the probe on the IO dispatcher and persists the
     * result through [persistResult] / [persistPocketshellResult]. The badge
     * state is derived from the persisted columns, so it updates
     * automatically once the probe lands — no extra wiring required.
     */
    fun recheckSetup(host: HostEntity, keyPath: String) {
        _recheckMessage.value = "Re-checking setup for ${host.name}…"
        refreshBootstrap(host, keyPath)
    }

    /**
     * Internal counterpart to [recheckSetup] used by the cold-launch
     * background reprobe. No banner message; result lands in the
     * persisted columns and the badge derivation picks it up. Bypasses
     * the standard [bootstrapHost] flow so the user is NOT routed to a
     * session as a side effect of a silent probe.
     */
    private fun recheckHostSilently(host: HostEntity, keyPath: String) {
        val probeLock = probeLockFor(host.id)
        val scheduledGeneration = currentForegroundProbeGeneration(host.id)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            probeLock.withLock {
                val session = openSession(host, keyPath, passphrase = null) ?: return@withLock
                try {
                    when (bootstrapper.checkTmux(session)) {
                        TmuxStatus.Installed -> {
                            val report = bootstrapper.checkServerSetup(session, expectedPocketshellVersion())
                            if (!isSilentProbeStillCurrent(host.id, scheduledGeneration)) return@withLock
                            persistResult(host, installed = true)
                            persistPocketshellResult(host, report)
                        }
                        TmuxStatus.Missing -> {
                            val report = bootstrapper.checkServerSetup(session, expectedPocketshellVersion())
                            if (!isSilentProbeStillCurrent(host.id, scheduledGeneration)) return@withLock
                            persistResult(host, installed = false)
                            persistPocketshellResult(host, report)
                        }
                        is TmuxStatus.Unknown -> {
                            // Cannot prove either way — leave the persisted
                            // flags untouched. Badge stays Unknown.
                        }
                    }
                } finally {
                    closeSession(session)
                }
            }
        }
    }

    fun clearRecheckMessage() {
        _recheckMessage.value = null
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
        val prompt = _bootstrapState.value as? HostBootstrapSheetState.Prompt
        _bootstrapState.value = HostBootstrapSheetState.Installing
        viewModelScope.launch {
            val tmuxResult = if (prompt?.needsTmux == true) {
                bootstrapper.installTmux(session)
            } else {
                InstallResult.Success
            }

            when (tmuxResult) {
                InstallResult.Success -> Unit

                is InstallResult.SetupIncomplete -> Unit

                is InstallResult.Failed -> {
                    _bootstrapState.value = HostBootstrapSheetState.Failed(
                        message = tmuxResult.stderr.ifBlank { "exit ${tmuxResult.exitCode}" },
                    )
                    return@launch
                }

                is InstallResult.UnsupportedOs -> {
                    val osPart = tmuxResult.osId?.let { " (detected: $it)" } ?: ""
                    _bootstrapState.value = HostBootstrapSheetState.Failed(
                        message = "PocketShell doesn't have a tmux installer for this host's OS$osPart. Install tmux manually and reconnect.",
                    )
                    return@launch
                }

                is InstallResult.Error -> {
                    _bootstrapState.value = HostBootstrapSheetState.Failed(message = tmuxResult.reason)
                    return@launch
                }
            }

            persistResult(host, installed = true)
            when (val result = bootstrapper.installServerSetup(
                session,
                prompt?.report ?: bootstrapper.checkServerSetup(session, expectedPocketshellVersion()),
                expectedPocketshellVersion = expectedPocketshellVersion(),
            )) {
                InstallResult.Success -> {
                    // Re-probe so the persisted pocketshell flag reflects the
                    // post-install reality. Only show the success state when
                    // required setup is ready; optional jobs/daemon state is
                    // surfaced where those features are invoked.
                    val finalReport = bootstrapper.checkServerSetup(session, expectedPocketshellVersion())
                    persistPocketshellResult(host, finalReport)
                    // Issue #514: "remote newer than app" is a ready host —
                    // surface the soft update note via the banner, not the
                    // sheet.
                    maybeRaiseAppUpdateWarning(host, finalReport)
                    _bootstrapState.value = if (finalReport.isReady) {
                        HostBootstrapSheetState.Success
                    } else {
                        HostBootstrapSheetState.Prompt(
                            needsTmux = false,
                            report = finalReport,
                        )
                    }
                }

                is InstallResult.SetupIncomplete -> {
                    persistPocketshellResult(host, result.report)
                    _bootstrapState.value = HostBootstrapSheetState.Prompt(
                        needsTmux = false,
                        report = result.report,
                    )
                }

                is InstallResult.Failed -> {
                    _bootstrapState.value = HostBootstrapSheetState.Failed(
                        message = serverSetupFailureMessage(prompt?.report, result),
                    )
                }

                is InstallResult.UnsupportedOs -> {
                    _bootstrapState.value = HostBootstrapSheetState.Failed(
                        message = "PocketShell doesn't have a server-tool installer for this host. Install uv or pipx and reconnect.",
                    )
                }

                is InstallResult.Error -> {
                    _bootstrapState.value = HostBootstrapSheetState.Failed(message = result.reason)
                }
            }
        }
    }

    fun installBootstrapTool(tool: BootstrapTool) {
        val session = bootstrapSession
        val prompt = _bootstrapState.value as? HostBootstrapSheetState.Prompt
        if (session == null || prompt?.report == null) {
            _bootstrapState.value = HostBootstrapSheetState.Failed(
                message = "Connection closed before install could start. Tap the host again to retry.",
            )
            return
        }
        val installer = prompt.report.installer
        if (installer == null) {
            _bootstrapState.value = HostBootstrapSheetState.Failed(
                message = if (prompt.report.tools[tool] is ToolStatus.VersionMismatch) {
                    cliUpdateFailureMessage(
                        mismatch = prompt.report.tools[tool] as? ToolStatus.VersionMismatch,
                        installer = null,
                        stderr = "Automatic update needs uv or pipx on the host.",
                        exitCode = -1,
                    )
                } else {
                    bootstrapper.missingInstallerGuidance()
                },
            )
            return
        }
        _bootstrapState.value = HostBootstrapSheetState.Installing
        viewModelScope.launch {
            val result = if (prompt.report.tools[tool] is ToolStatus.VersionMismatch) {
                bootstrapper.upgradeServerTool(session, installer, tool, prompt.report.installerPath)
            } else {
                bootstrapper.installServerTool(session, installer, tool, prompt.report.installerPath)
            }
            when (result) {
                InstallResult.Success -> refreshServerSetupPrompt(session, needsTmux = prompt.needsTmux)
                is InstallResult.SetupIncomplete -> _bootstrapState.value = HostBootstrapSheetState.Prompt(
                    needsTmux = prompt.needsTmux,
                    report = result.report,
                )
                is InstallResult.Failed -> _bootstrapState.value = HostBootstrapSheetState.Failed(
                    message = if (prompt.report.tools[tool] is ToolStatus.VersionMismatch) {
                        cliUpdateFailureMessage(
                            mismatch = prompt.report.tools[tool] as? ToolStatus.VersionMismatch,
                            installer = installer,
                            stderr = result.stderr,
                            exitCode = result.exitCode,
                        )
                    } else {
                        result.stderr.ifBlank { "exit ${result.exitCode}" }
                    },
                )

                is InstallResult.UnsupportedOs -> _bootstrapState.value = HostBootstrapSheetState.Failed(
                    message = "PocketShell doesn't have a server-tool installer for this host. Install uv or pipx and reconnect.",
                )

                is InstallResult.Error -> _bootstrapState.value = HostBootstrapSheetState.Failed(message = result.reason)
            }
        }
    }

    private fun serverSetupFailureMessage(
        report: HostBootstrapReport?,
        result: InstallResult.Failed,
    ): String {
        val mismatch = report?.pocketshellVersionMismatch
        return if (mismatch != null) {
            cliUpdateFailureMessage(
                mismatch = mismatch,
                installer = report.installer,
                stderr = result.stderr,
                exitCode = result.exitCode,
            )
        } else {
            result.stderr.ifBlank { "exit ${result.exitCode}" }
        }
    }

    fun setupBootstrapDaemon() {
        val session = bootstrapSession
        val prompt = _bootstrapState.value as? HostBootstrapSheetState.Prompt
        if (session == null || prompt == null) {
            _bootstrapState.value = HostBootstrapSheetState.Failed(
                message = "Connection closed before setup could start. Tap the host again to retry.",
            )
            return
        }
        _bootstrapState.value = HostBootstrapSheetState.Installing
        viewModelScope.launch {
            when (val result = bootstrapper.installPocketshellDaemon(session)) {
                InstallResult.Success -> refreshServerSetupPrompt(session, needsTmux = prompt.needsTmux)
                is InstallResult.SetupIncomplete -> _bootstrapState.value = HostBootstrapSheetState.Prompt(
                    needsTmux = prompt.needsTmux,
                    report = result.report,
                )
                is InstallResult.Failed -> _bootstrapState.value = HostBootstrapSheetState.Failed(
                    message = result.stderr.ifBlank { "exit ${result.exitCode}" },
                )

                is InstallResult.UnsupportedOs -> _bootstrapState.value = HostBootstrapSheetState.Failed(
                    message = "PocketShell couldn't enable the pocketshell jobs daemon on this host.",
                )

                is InstallResult.Error -> _bootstrapState.value = HostBootstrapSheetState.Failed(message = result.reason)
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
        setHostOpenPhase(pending.host.id, HostOpenPhase.OpeningFolders)
        _pendingNavigation.value = pending.copy(ready = true)
    }

    private suspend fun refreshServerSetupPrompt(
        session: SshSession,
        needsTmux: Boolean,
    ) {
        val report = bootstrapper.checkServerSetup(session, expectedPocketshellVersion())
        bootstrapTargetHost?.let {
            persistPocketshellResult(it, report)
            // Issue #514: remote-newer is a ready host; show the soft note
            // via the banner, never the sheet.
            maybeRaiseAppUpdateWarning(it, report)
        }
        _bootstrapState.value = if (!needsTmux && report.isRequiredReady) {
            HostBootstrapSheetState.Success
        } else {
            HostBootstrapSheetState.Prompt(needsTmux = needsTmux, report = report)
        }
    }

    /**
     * Called by the screen once it has consumed the pending navigation
     * (i.e. invoked `onOpenSession`). Clears the flag so a subsequent
     * tap re-triggers the flow.
     */
    fun consumePendingNavigation() {
        _pendingNavigation.value?.let { clearHostOpenProgress(it.host.id) }
        _pendingNavigation.value = null
        bootstrapTargetHost = null
        _bootstrapHostName.value = ""
    }

    fun beginHostOpen(hostId: Long, hostName: String): Boolean {
        if (_hostOpenProgress.value != null) return false
        _hostOpenProgress.value = HostOpenProgress(
            hostId = hostId,
            hostName = hostName,
            phase = HostOpenPhase.ConnectingToHost,
            requestStarted = false,
        )
        return true
    }

    fun cancelHostOpen(hostId: Long) {
        clearHostOpenProgress(hostId)
    }

    private fun setHostOpenPhase(hostId: Long, phase: HostOpenPhase) {
        val current = _hostOpenProgress.value
        if (current == null || current.hostId != hostId) return
        _hostOpenProgress.value = current.copy(phase = phase)
    }

    private fun markHostOpenRequestStarted(hostId: Long, hostName: String): Boolean {
        val current = _hostOpenProgress.value
        if (current == null) {
            _hostOpenProgress.value = HostOpenProgress(
                hostId = hostId,
                hostName = hostName,
                phase = HostOpenPhase.ConnectingToHost,
                requestStarted = true,
            )
            return true
        }
        if (current.hostId != hostId || current.requestStarted) return false
        _hostOpenProgress.value = current.copy(requestStarted = true)
        return true
    }

    private fun clearHostOpenProgress(hostId: Long) {
        if (_hostOpenProgress.value?.hostId == hostId) {
            _hostOpenProgress.value = null
        }
    }

    private suspend fun openSession(host: HostEntity, keyPath: String, passphrase: CharArray?): SshSession? {
        return sessionOpener.open(host, keyPath, passphrase)
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

    /**
     * Persist the pocketshell-installed flag from a fresh
     * [HostBootstrapReport] (issue #117, usage-panel Fix C; unified onto
     * the single `pocketshell` CLI in #231, D22 hard cut). The check is
     * decoupled from [persistResult] because the pocketshell probe
     * arrives via the broader `checkServerSetup` call, not the
     * single-tool `checkTmux` probe.
     *
     * Records `pocketshellLastDetectedAt` whether pocketshell is present
     * or missing so the periodic usage scheduler can apply the same 24h
     * freshness heuristic the tmux probe uses — the scheduler only
     * re-detects when the cache is stale.
     */
    private suspend fun persistPocketshellResult(host: HostEntity, report: HostBootstrapReport) {
        val status = report.tools[BootstrapTool.Pocketshell]
        // Issue #514: AppUpdateRequired (remote CLI newer than this app
        // build) is still "installed and present" on the host — the host is
        // fine, only the app is behind.
        val pocketshellInstalled = status is ToolStatus.Installed ||
            status is ToolStatus.VersionMismatch ||
            status is ToolStatus.AppUpdateRequired
        val currentVersion = when (status) {
            is ToolStatus.Installed -> status.version
            is ToolStatus.VersionMismatch -> status.currentVersion
            is ToolStatus.AppUpdateRequired -> status.currentVersion
            else -> null
        }
        val expectedVersion = when (status) {
            is ToolStatus.Installed -> status.expectedVersion
            is ToolStatus.VersionMismatch -> status.expectedVersion
            is ToolStatus.AppUpdateRequired -> status.expectedVersion
            else -> expectedPocketshellVersion()
        }
        val compatible = when (status) {
            is ToolStatus.Installed -> if (status.expectedVersion != null) true else null
            is ToolStatus.VersionMismatch -> false
            // Host CLI works fine; the app needs updating, not the host. We
            // do not mark the host CLI itself as incompatible.
            is ToolStatus.AppUpdateRequired -> true
            else -> null
        }
        val daemonRunning = when (val daemon = report.daemon) {
            is PocketshellDaemonStatus.Running -> true
            is PocketshellDaemonStatus.InstalledStopped -> false
            PocketshellDaemonStatus.Missing -> false
            is PocketshellDaemonStatus.Unavailable -> null
            is PocketshellDaemonStatus.Unknown -> null
        }
        val daemonEnabled = when (val daemon = report.daemon) {
            is PocketshellDaemonStatus.Running -> daemon.enabled
            is PocketshellDaemonStatus.InstalledStopped -> daemon.enabled
            PocketshellDaemonStatus.Missing -> false
            is PocketshellDaemonStatus.Unavailable -> null
            is PocketshellDaemonStatus.Unknown -> null
        }
        val now = System.currentTimeMillis()
        val current = hostDao.getById(host.id) ?: host
        // Only write when the cached value would change; avoids a churn
        // on every connect for a row that has not moved.
        val existingDetectionIsFresh = current.pocketshellLastDetectedAt
            ?.let { now - it < BOOTSTRAP_CACHE_MS }
            ?: false
        if (
            current.pocketshellInstalled == pocketshellInstalled &&
            current.pocketshellCliVersion == currentVersion &&
            current.pocketshellExpectedCliVersion == expectedVersion &&
            current.pocketshellVersionCompatible == compatible &&
            current.pocketshellDaemonRunning == daemonRunning &&
            current.pocketshellDaemonEnabled == daemonEnabled &&
            existingDetectionIsFresh
        ) {
            return
        }
        hostDao.update(
            current.copy(
                pocketshellInstalled = pocketshellInstalled,
                pocketshellLastDetectedAt = now,
                pocketshellCliVersion = currentVersion,
                pocketshellExpectedCliVersion = expectedVersion,
                pocketshellVersionCompatible = compatible,
                pocketshellDaemonRunning = daemonRunning,
                pocketshellDaemonEnabled = daemonEnabled,
            ),
        )
    }

    private fun expectedPocketshellVersion(): String = currentVersionName().orEmpty()

    private fun closeBootstrapSession() {
        bootstrapSession?.let { closeSession(it) }
        bootstrapSession = null
    }

    private fun closeSession(session: SshSession) {
        runCatching {
            runBlocking { sessionOpener.close(session) }
        }
    }

    private fun completedJob(): Job {
        val job = Job()
        job.complete()
        return job
    }

    override fun onCleared() {
        super.onCleared()
        closeBootstrapSession()
    }

    /**
     * Resolve the installed `versionName` from `PackageManager`. Returns
     * `null` if the read fails so the app does not surface a misleading
     * update banner for an unknown local version.
     */
    private fun currentVersionName(): String? = try {
        applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0)
            .versionName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /**
     * Pending-navigation tuple: the host + key path the screen would
     * route to once the bootstrap flow resolves. [ready] flips to `true`
     * when the screen is allowed to fire `onOpenSession`.
     */
    public data class PendingNavigation(
        val host: HostEntity,
        val keyPath: String,
        val passphrase: CharArray?,
        val ready: Boolean = false,
    )

    data class HostOpenProgress(
        val hostId: Long,
        val hostName: String,
        val phase: HostOpenPhase,
        internal val requestStarted: Boolean = false,
    )

    enum class HostOpenPhase(val label: String) {
        ConnectingToHost("Connecting to host"),
        CheckingSetup("Checking setup"),
        OpeningFolders("Opening folders"),
    }

    data class HostSharePayload(
        val hostName: String,
        val payload: String,
    )

    /**
     * Issue #514: payload for the soft "remote pocketshell CLI is newer
     * than this app" banner. Carries the host id (so dismissal is scoped)
     * and the two versions for the user-facing copy. Surfacing this never
     * blocks the host — usage, sessions, and folders all keep working.
     */
    data class AppUpdateWarning(
        val hostId: Long,
        val remoteVersion: String,
        val appVersion: String,
        // Issue #515: when a real downloadable GitHub release has been
        // resolved (best-effort, after the probe proved the app is behind),
        // the banner offers an actionable "Update" that opens this APK via
        // ACTION_VIEW. `null` until/unless the lookup succeeds — the banner
        // then stays a passive, dismissible note.
        val releaseInfo: ReleaseInfo? = null,
    ) {
        val message: String
            get() = "Remote pocketshell CLI $remoteVersion is newer than this app " +
                "($appVersion) — consider updating the app."
    }

    /**
     * Issue #515: payload for the "couldn't check for updates" banner. The
     * GitHub-Releases poll failed (non-200 / rate-limit / network blip /
     * unparseable body) rather than finding no newer release. [reason] is a
     * short human-readable cause for the inline note; the same reason is
     * logged in [ReleaseChecker.checkForUpdate]. The banner offers a Retry
     * that re-runs [checkForUpdates].
     */
    data class UpdateCheckFailure(
        val reason: String,
    )

    /**
     * Decoded but not-yet-persisted host import. Captured at the point
     * the conflict was detected so the resolution flow can act on the
     * exact same values the codec produced, without re-parsing the
     * payload.
     */
    data class PendingHostImport(
        val name: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyId: Long,
    )

    /**
     * Live import-conflict prompt. [incoming] is the inbound host the
     * codec just decoded; [existing] is the database row that shares
     * its `(hostname, port)`. The dialog shows both so the user can
     * decide what to do with full context.
     */
    data class ImportConflict(
        val incoming: PendingHostImport,
        val existing: HostEntity,
    )

    private companion object {
        /** 24-hour cache window — re-probe after this. */
        const val BOOTSTRAP_CACHE_MS: Long = 24L * 60L * 60L * 1000L
    }

    private fun probeLockFor(hostId: Long): Mutex =
        synchronized(probeLocks) {
            probeLocks.getOrPut(hostId) { Mutex() }
        }

    private fun markForegroundProbe(hostId: Long) {
        synchronized(foregroundProbeGenerations) {
            foregroundProbeGenerations[hostId] = currentForegroundProbeGenerationLocked(hostId) + 1L
        }
    }

    private fun currentForegroundProbeGeneration(hostId: Long): Long =
        synchronized(foregroundProbeGenerations) {
            currentForegroundProbeGenerationLocked(hostId)
        }

    private fun currentForegroundProbeGenerationLocked(hostId: Long): Long =
        foregroundProbeGenerations[hostId] ?: 0L

    private fun isSilentProbeStillCurrent(hostId: Long, scheduledGeneration: Long): Boolean =
        currentForegroundProbeGeneration(hostId) == scheduledGeneration

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

    private fun HostEntity.hasFreshCompatiblePocketshellResult(now: Long = System.currentTimeMillis()): Boolean {
        val last = pocketshellLastDetectedAt ?: return false
        return pocketshellInstalled == true &&
            pocketshellVersionCompatible == true &&
            now - last < BOOTSTRAP_CACHE_MS
    }

    private fun HostEntity.canUseBootstrapCache(now: Long = System.currentTimeMillis()): Boolean =
        tmuxInstalled == true &&
            isBootstrapFresh(now) &&
            hasFreshCompatiblePocketshellResult(now)
}

internal fun interface HostSessionOpener {
    suspend fun open(host: HostEntity, keyPath: String, passphrase: CharArray?): SshSession?

    suspend fun close(session: SshSession) {
        session.close()
    }
}

internal class LeaseBackedHostSessionOpener(
    private val sshLeaseManager: SshLeaseManager,
) : HostSessionOpener {
    private val leasesBySession: MutableMap<SshSession, ArrayDeque<SshLease>> =
        java.util.Collections.synchronizedMap(IdentityHashMap())

    override suspend fun open(host: HostEntity, keyPath: String, passphrase: CharArray?): SshSession? {
        val file = File(keyPath)
        if (!file.exists()) return null
        val target = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = host.hostname,
                port = host.port,
                user = host.username,
                credentialId = "${host.id}:$keyPath",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(file),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )
        val lease = sshLeaseManager.acquire(target).getOrNull() ?: return null
        if (lease.isNewConnection) {
            SshOpenTelemetry.record(
                source = SSH_SOURCE_WARM_HOST_CONNECT,
                host = host.hostname,
                port = host.port,
                user = host.username,
            )
        }
        synchronized(leasesBySession) {
            leasesBySession.getOrPut(lease.session) { ArrayDeque() }.addLast(lease)
        }
        return lease.session
    }

    override suspend fun close(session: SshSession) {
        val lease = synchronized(leasesBySession) {
            val leases = leasesBySession[session] ?: return@synchronized null
            val lease = leases.removeLastOrNull()
            if (leases.isEmpty()) {
                leasesBySession.remove(session)
            }
            lease
        }
        if (lease != null) {
            lease.release()
        } else {
            session.close()
        }
    }
}

/**
 * The three user choices for resolving a duplicate-host import
 * conflict surfaced by [HostListViewModel.importConflict] (issue #157
 * polish item 2). Lives at the top level so the Compose dialog can
 * reference the enum without depending on the ViewModel's nested
 * types.
 */
enum class ImportConflictResolution {
    /** Update the existing row in place, preserving its `id`. */
    Overwrite,

    /** Drop the incoming payload; leave the database unchanged. */
    Skip,

    /** Insert anyway, accepting that two rows share the endpoint. */
    AddAsNew,
}

/**
 * Pure derivation of the host-card badge state (issue #120) from the
 * persisted bootstrap columns on [HostEntity]. Top-level rather than a
 * companion-object member so the unit tests can call it without
 * standing up an Android-runtime ViewModel, and so the value-side
 * derivation cannot accidentally close over instance state.
 *
 * - `Unknown`     — `tmuxInstalled == null` (never probed) OR
 *                   `pocketshellInstalled == null` (no usage-tool probe
 *                   recorded yet).
 * - `CliUpdateNeeded` — `pocketshellVersionCompatible == false`.
 * - `NeedsSetup`  — `tmuxInstalled == false`, OR `pocketshellInstalled ==
 *                   false`. At least one required tool is missing.
 * - `Ready`       — `tmuxInstalled == true`, `pocketshellInstalled == true`,
 *                   and no optional helper gap is known.
 * - `OptionalUnavailable` — required setup is ready, but optional helper
 *                   capability state is unavailable or unverified.
 * - `DaemonDisabled` — required setup is ready, but the optional jobs daemon
 *                   is known stopped or disabled.
 */
internal fun deriveSetupState(host: HostEntity): HostSetupState {
    val tmux = host.tmuxInstalled
    val pocketshell = host.pocketshellInstalled
    val daemonRunning = host.pocketshellDaemonRunning
    val daemonEnabled = host.pocketshellDaemonEnabled
    return when {
        tmux == null -> HostSetupState.Unknown
        tmux == false -> HostSetupState.NeedsSetup
        // tmux == true past here.
        host.pocketshellVersionCompatible == false -> HostSetupState.CliUpdateNeeded
        pocketshell == null -> HostSetupState.Unknown
        pocketshell == false -> HostSetupState.NeedsSetup
        daemonRunning == false || daemonEnabled == false -> HostSetupState.DaemonDisabled
        daemonRunning == null || daemonEnabled == null -> HostSetupState.OptionalUnavailable
        else -> HostSetupState.Ready
    }
}

/**
 * Pure derivation of the host-card trailing status chip (issue #201) —
 * the at-a-glance answer to "what's happening on this host right now?".
 * Each return value maps to exactly one trigger condition; the AC for
 * #201 explicitly forbids overlap between labels and bans the previous
 * ambiguous "idle" wording.
 *
 * Precedence (top-down — first match wins):
 *
 *  1. [HostStatus.NeedsSetup]      — `setupState == NeedsSetup`. Setup-
 *     required wins over everything else; the inline `HostSetupBadge`
 *     surfaces the actionable affordance and the trailing chip is
 *     hidden by the caller. We still emit `NeedsSetup` here (rather
 *     than collapsing to Unknown) so the caller can branch on it
 *     without re-computing the precedence rule.
 *  2. [HostStatus.ConnectionError] — `lastConnectError != null`. A
 *     known-failed connect means session counts are stale; surface the
 *     error instead.
 *  3. [HostStatus.Attached]        — `appAttached == true`. The app
 *     itself holds a live `tmux -CC` client against this host.
 *  4. [HostStatus.ActiveSessions]  — `setupState == Ready` AND
 *     `sessionCount >= 1`. The host has live tmux sessions and we know
 *     it does because the bootstrap probe verified the tooling.
 *  5. [HostStatus.NoActiveSessions] — `setupState == Ready` AND
 *     `sessionCount == 0`. We've verified the tooling AND we have a
 *     positive answer that there are no sessions; safe to say "no
 *     active sessions" rather than "unknown".
 *  6. [HostStatus.Unknown]         — anything else. Most often:
 *     `setupState == Unknown` (cold launch, probe in flight) — we have
 *     not verified the tooling so we cannot claim a session count of
 *     zero. The renderer surfaces a quiet spinner in this case so the
 *     user never sees a stale indicator.
 *
 * @param setupState bootstrap-probe readiness, derived elsewhere via
 *   [deriveSetupState]. Drives precedence + the Ready gate on
 *   session-count claims.
 * @param sessionCount number of tmux sessions reported by
 *   [com.pocketshell.app.sessions.SessionsDashboardViewModel] for this
 *   host. Pass `null` if no recent `list-sessions` data is available
 *   (e.g. the dashboard has not polled this host yet).
 * @param appAttached `true` when [com.pocketshell.app.sessions.ActiveTmuxClients]
 *   reports a live client registered for this host id.
 * @param lastConnectError opaque marker for "the most recent SSH
 *   attempt failed". The signal is currently best-expressed as
 *   `tmuxInstalled == false && pocketshellInstalled == false && there was a
 *   probe attempt`; for now we keep the parameter explicit so a future
 *   issue (when a richer connect-failure persistence lands) can hook
 *   in without re-shaping the derivation. Defaults to `false`.
 */
internal fun deriveHostStatus(
    setupState: HostSetupState,
    sessionCount: Int?,
    appAttached: Boolean,
    lastConnectError: Boolean = false,
): HostStatus = when {
    setupState == HostSetupState.NeedsSetup || setupState == HostSetupState.CliUpdateNeeded -> HostStatus.NeedsSetup
    lastConnectError -> HostStatus.ConnectionError
    appAttached -> HostStatus.Attached
    setupState == HostSetupState.Ready && sessionCount != null && sessionCount >= 1 ->
        HostStatus.ActiveSessions(count = sessionCount)
    setupState == HostSetupState.Ready && sessionCount == 0 -> HostStatus.NoActiveSessions
    else -> HostStatus.Unknown
}

/**
 * Convenience wrapper around [deriveHostStatus] that takes the raw
 * cross-host session list ([SessionSummary] from the dashboard) +
 * registered-client host-id set, looks up the per-host slice, and
 * returns the resolved [HostStatus]. Lives next to the pure derivation
 * so the screen layer doesn't repeat the grouping logic inline.
 *
 * `sessions` is expected to be the aggregate from
 * [com.pocketshell.app.sessions.SessionsDashboardViewModel.sessions];
 * `attachedHostIds` is the key-set of
 * [com.pocketshell.app.sessions.ActiveTmuxClients.clients].
 *
 * `sessionCount` is intentionally derived as
 * `sessions.count { it.hostId == hostId }` rather than counting unique
 * names — tmux session names are already unique on a server (tmux
 * enforces it), so the count agrees either way and the simpler
 * expression is easier to verify by inspection.
 */
internal fun resolveHostStatus(
    hostId: Long,
    setupState: HostSetupState,
    sessions: List<SessionSummary>,
    attachedHostIds: Set<Long>,
): HostStatus {
    val perHost = sessions.filter { it.hostId == hostId }
    // We treat "no session data for this host" (i.e. the dashboard
    // hasn't polled it yet) as `null`. Only when the host's tmux
    // client has been seen and reported a zero-row list do we surface
    // "no active sessions". The presence of any session row OR a
    // registered tmux client both imply the dashboard has data.
    val haveData = perHost.isNotEmpty() || hostId in attachedHostIds
    val sessionCount = if (haveData) perHost.size else null
    val appAttached = hostId in attachedHostIds
    return deriveHostStatus(
        setupState = setupState,
        sessionCount = sessionCount,
        appAttached = appAttached,
    )
}
