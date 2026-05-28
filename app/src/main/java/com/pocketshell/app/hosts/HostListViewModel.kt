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
import com.pocketshell.app.bootstrap.TmuxStatus
import com.pocketshell.app.bootstrap.ToolStatus
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.SessionSummary
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.usage.UsageDashboardRow
import com.pocketshell.app.usage.UsageScheduler
import com.pocketshell.app.usage.UsageSnapshot
import com.pocketshell.app.usage.worstBadgeRecord
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) : ViewModel() {

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

    /**
     * `true` when at least one persisted host has `pocketshellInstalled == true`,
     * i.e. there is something for the usage panel to surface. Drives the
     * "render strip / hide strip" gate on [HostListScreen] (issue #116:
     * "When no host has pocketshell installed, the dashboard strip is not
     * rendered — no empty rail.").
     */
    val hasUsageInstalledHost: StateFlow<Boolean> = hostDao.getAll()
        .map { rows -> rows.any { it.pocketshellInstalled == true } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /**
     * Rows for the cross-host [com.pocketshell.app.usage.UsageDashboardStrip]
     * derived from [UsageScheduler.snapshots]. Empty when no pocketshell host has
     * reported yet — the screen also hides the strip via
     * [hasUsageInstalledHost] until a record exists, so the strip never
     * renders an empty rail.
     *
     * Issue #214: each row's `thresholdState` is computed against the
     * user-configurable `usageWarnThresholdPercent` so a user
     * who pulled the slider down to 50 % sees the amber tint earlier.
     */
    val usageDashboardRows: StateFlow<List<UsageDashboardRow>> = combine(
        usageScheduler.snapshots,
        settingsRepository.settings,
    ) { snapshots, settings ->
        val records = snapshots.values
            .filterIsInstance<UsageSnapshot.Records>()
            .flatMap { it.records }
        buildDashboardRows(
            records = records,
            warnPercent = settings.usageWarnThresholdPercent.toDouble(),
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

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

    /** Session held open while the bootstrap sheet is visible so install reuses the connection. */
    private var bootstrapSession: SshSession? = null

    /** Cached host so install can update the right row. */
    private var bootstrapTargetHost: HostEntity? = null

    /** Guard so the cold-launch reprobe only fires once per ViewModel. */
    private var autoReprobeKicked = false

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
                )
                hostDao.update(updated)
                _shareMessage.value = "Overwrote ${conflict.existing.name}"
            }
            ImportConflictResolution.Skip -> {
                _shareMessage.value = "Skipped ${conflict.incoming.name}"
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
        viewModelScope.launch {
            val currentVersion = currentVersionName()
            if (currentVersion == null) {
                _updateAvailable.value = null
                return@launch
            }
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
    fun bootstrapHost(host: HostEntity, keyPath: String, passphrase: CharArray? = null) {
        // Cache: a fresh tmux result skips the bootstrap SSH probe. The
        // session picker can discover pocketshell/tmux on its own and fall back
        // cleanly, so blocking every host tap on server-tool checks makes the
        // phone path pay for setup work before the user has even picked a
        // session.
        val skipTmuxProbe = host.tmuxInstalled == true && host.isBootstrapFresh()

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
            _pendingNavigation.value = PendingNavigation(host, keyPath, passphrase, ready = true)
            return
        }

        viewModelScope.launch {
            val session = openSession(host, keyPath, passphrase)
            if (session == null) {
                // Couldn't connect → don't block navigation. The session
                // screen will surface the same connect failure with its
                // own retry UX, which is the right place to handle it.
                _pendingNavigation.value = PendingNavigation(host, keyPath, passphrase, ready = true)
                return@launch
            }
            bootstrapSession = session

            // Issue #41: the bootstrap probe runs `/bin/sh -lc`, which
            // sources `~/.profile` but not `~/.bashrc`. Users whose tool
            // PATH lives in `.bashrc` (e.g. cloned-repo venv installs)
            // surface that fact via the Add/Edit Host "Extra PATH
            // directories" field, which we forward to every probe call.
            val pathOverride = host.pathOverride
            when (val status = bootstrapper.checkTmux(session, pathOverride)) {
                TmuxStatus.Installed -> {
                    persistResult(host, installed = true)
                    val report = bootstrapper.checkServerSetup(session, pathOverride)
                    persistPocketshellResult(host, report)
                    if (report.isReady) {
                        closeBootstrapSession()
                        _pendingNavigation.value = PendingNavigation(host, keyPath, passphrase, ready = true)
                    } else {
                        _bootstrapState.value = HostBootstrapSheetState.Prompt(
                            needsTmux = false,
                            report = report,
                        )
                    }
                }

                TmuxStatus.Missing -> {
                    persistResult(host, installed = false)
                    val report = bootstrapper.checkServerSetup(session, pathOverride)
                    persistPocketshellResult(host, report)
                    _bootstrapState.value = HostBootstrapSheetState.Prompt(
                        needsTmux = true,
                        report = report,
                    )
                }

                is TmuxStatus.Unknown -> {
                    // Can't prove missing → continue, don't pester the user.
                    closeBootstrapSession()
                    _pendingNavigation.value = PendingNavigation(host, keyPath, passphrase, ready = true)
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
        viewModelScope.launch {
            val session = openSession(host, keyPath, passphrase = null) ?: return@launch
            val pathOverride = host.pathOverride
            try {
                when (bootstrapper.checkTmux(session, pathOverride)) {
                    TmuxStatus.Installed -> {
                        persistResult(host, installed = true)
                        val report = bootstrapper.checkServerSetup(session, pathOverride)
                        persistPocketshellResult(host, report)
                    }
                    TmuxStatus.Missing -> {
                        persistResult(host, installed = false)
                        val report = bootstrapper.checkServerSetup(session, pathOverride)
                        persistPocketshellResult(host, report)
                    }
                    is TmuxStatus.Unknown -> {
                        // Cannot prove either way — leave the persisted
                        // flags untouched. Badge stays Unknown.
                    }
                }
            } finally {
                runCatching { session.close() }
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
            val pathOverride = host.pathOverride
            when (val result = bootstrapper.installServerSetup(
                session,
                prompt?.report ?: bootstrapper.checkServerSetup(session, pathOverride),
                pathOverride,
            )) {
                InstallResult.Success -> {
                    // Re-probe so the persisted pocketshell flag reflects the
                    // post-install reality, then flip to the success
                    // state so the sheet can offer the Open Usage CTA.
                    val finalReport = bootstrapper.checkServerSetup(session, pathOverride)
                    persistPocketshellResult(host, finalReport)
                    _bootstrapState.value = HostBootstrapSheetState.Success
                }

                is InstallResult.Failed -> {
                    _bootstrapState.value = HostBootstrapSheetState.Failed(
                        message = result.stderr.ifBlank { "exit ${result.exitCode}" },
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
                message = "Install uv or pipx on the host, then reconnect. PocketShell uses one of them to install pocketshell.",
            )
            return
        }
        val pathOverride = bootstrapTargetHost?.pathOverride
        _bootstrapState.value = HostBootstrapSheetState.Installing
        viewModelScope.launch {
            when (val result = bootstrapper.installServerTool(session, installer, tool, pathOverride)) {
                InstallResult.Success -> refreshServerSetupPrompt(session, needsTmux = prompt.needsTmux, pathOverride = pathOverride)
                is InstallResult.Failed -> _bootstrapState.value = HostBootstrapSheetState.Failed(
                    message = result.stderr.ifBlank { "exit ${result.exitCode}" },
                )

                is InstallResult.UnsupportedOs -> _bootstrapState.value = HostBootstrapSheetState.Failed(
                    message = "PocketShell doesn't have a server-tool installer for this host. Install uv or pipx and reconnect.",
                )

                is InstallResult.Error -> _bootstrapState.value = HostBootstrapSheetState.Failed(message = result.reason)
            }
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
        val pathOverride = bootstrapTargetHost?.pathOverride
        _bootstrapState.value = HostBootstrapSheetState.Installing
        viewModelScope.launch {
            when (val result = bootstrapper.installPocketshellDaemon(session, pathOverride)) {
                InstallResult.Success -> refreshServerSetupPrompt(session, needsTmux = prompt.needsTmux, pathOverride = pathOverride)
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
        _pendingNavigation.value = pending.copy(ready = true)
    }

    private suspend fun refreshServerSetupPrompt(
        session: SshSession,
        needsTmux: Boolean,
        pathOverride: String? = bootstrapTargetHost?.pathOverride,
    ) {
        val report = bootstrapper.checkServerSetup(session, pathOverride)
        bootstrapTargetHost?.let { persistPocketshellResult(it, report) }
        _bootstrapState.value = if (!needsTmux && report.isReady) {
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
        _pendingNavigation.value = null
        bootstrapTargetHost = null
        _bootstrapHostName.value = ""
    }

    private suspend fun openSession(host: HostEntity, keyPath: String, passphrase: CharArray?): SshSession? {
        val file = File(keyPath)
        if (!file.exists()) return null
        return SshConnection.connect(
            host = host.hostname,
            port = host.port,
            user = host.username,
            key = SshKey.Path(file),
            passphrase = passphrase?.copyOf(),
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
        val pocketshellInstalled = report.tools[BootstrapTool.Pocketshell] is ToolStatus.Installed
        val now = System.currentTimeMillis()
        val current = hostDao.getById(host.id) ?: host
        // Only write when the cached value would change; avoids a churn
        // on every connect for a row that has not moved.
        if (current.pocketshellInstalled == pocketshellInstalled && current.pocketshellLastDetectedAt != null) {
            return
        }
        hostDao.update(
            current.copy(
                pocketshellInstalled = pocketshellInstalled,
                pocketshellLastDetectedAt = now,
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

    data class HostSharePayload(
        val hostName: String,
        val payload: String,
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
 * - `NeedsSetup`  — `tmuxInstalled == false`, OR `pocketshellInstalled ==
 *                   false`. At least one required tool is missing.
 * - `Ready`       — `tmuxInstalled == true` AND `pocketshellInstalled == true`.
 */
internal fun deriveSetupState(host: HostEntity): HostSetupState {
    val tmux = host.tmuxInstalled
    val pocketshell = host.pocketshellInstalled
    return when {
        tmux == null -> HostSetupState.Unknown
        tmux == false -> HostSetupState.NeedsSetup
        // tmux == true past here.
        pocketshell == null -> HostSetupState.Unknown
        pocketshell == false -> HostSetupState.NeedsSetup
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
    setupState == HostSetupState.NeedsSetup -> HostStatus.NeedsSetup
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

/**
 * Build [UsageDashboardRow] entries from the union of every host's
 * fetched [UsageProviderRecord]s. Mirrors the existing extension on
 * [com.pocketshell.app.usage.UsageScreenState] but operates on a flat
 * record list so the host list can render the same strip without
 * shaping a full [com.pocketshell.app.usage.UsageScreenState] from the
 * scheduler snapshots (issue #116, usage-panel Fix B).
 *
 * Rows are sorted by provider so a Claude row consistently appears
 * above Codex regardless of which host the record came from, and the
 * most-constrained window is what's shown — the same rule the panel
 * itself uses for the at-a-glance percent.
 */
internal fun buildDashboardRows(
    records: List<UsageProviderRecord>,
    warnPercent: Double = UsageProviderRecord.DEFAULT_WARN_PERCENT,
): List<UsageDashboardRow> =
    records
        .sortedWith(compareBy<UsageProviderRecord> { it.provider })
        .mapNotNull { record ->
            val window = record.mostConstrainedWindow ?: return@mapNotNull null
            UsageDashboardRow(
                provider = record.displayName,
                status = record.status,
                percent = window.percent,
                blocked = record.isBlocked,
                nearLimit = record.isNearLimit,
                thresholdState = record.thresholdState(warnPercent = warnPercent),
            )
        }
