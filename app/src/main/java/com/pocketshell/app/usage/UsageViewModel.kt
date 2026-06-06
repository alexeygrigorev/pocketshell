package com.pocketshell.app.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.usage.UsageProviderRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import javax.inject.Inject

/**
 * Per-host usage fetcher. Injected into [UsageViewModel] so tests can
 * stub the SSH/pocketshell path without needing a real transport. The
 * default implementation opens a short-lived SSH session via
 * [SshConnection], runs [UsageRemoteSource.detectPocketshell], and — when
 * present — [UsageRemoteSource.fetchUsage].
 *
 * The result is one of the four [HostUsageFetch] cases the view model
 * already partitions on (Records / ToolMissing / Skipped). Failures land
 * in `Skipped`; a future Fix C iteration can surface them as
 * `UsageScreenState.errors` so the user sees why a host is missing.
 */
public interface HostUsageFetcher {
    public suspend fun fetch(host: HostEntity): HostUsageFetch
}

public sealed interface HostUsageFetch {
    public data class Records(
        val records: List<UsageProviderRecord>,
        val syncedAt: Instant,
    ) : HostUsageFetch

    public data object ToolMissing : HostUsageFetch
    public data object Skipped : HostUsageFetch
}

/**
 * Production [HostUsageFetcher] backed by sshj. Resolves the host's SSH
 * key from disk and opens a short-lived session for the detect+fetch
 * exchange.
 *
 * Skips passphrase-protected keys: the Usage screen does not own a
 * passphrase prompt today, and silently unlocking a key is not the right
 * call. Fix C may pass a passphrase through from an open session in
 * memory, but Fix A simply leaves the host out of the panel.
 */
internal fun interface SshHostUsageConnector {
    suspend fun connect(host: HostEntity, keyFile: File): Result<SshSession>
}

public class SshHostUsageFetcher : HostUsageFetcher {
    private val sshKeyDao: SshKeyDao
    private val remoteSource: UsageRemoteSource
    private val connector: SshHostUsageConnector

    // `UsageRemoteSource` carries a default-arg constructor that, combined
    // with its `@Inject` annotation, generates two constructors at the
    // bytecode level — Hilt refuses to bind it directly. Instantiating it
    // here keeps the source unmodified (out of scope for this issue) while
    // still letting Hilt own the `SshHostUsageFetcher` graph.
    @Inject
    public constructor(sshKeyDao: SshKeyDao) : this(
        sshKeyDao = sshKeyDao,
        remoteSource = UsageRemoteSource(),
        connector = SshHostUsageConnector { host, keyFile ->
            SshConnection.connect(
                host = host.hostname,
                port = host.port,
                user = host.username,
                key = SshKey.Path(keyFile),
                knownHosts = KnownHostsPolicy.AcceptAll,
            )
        },
    )

    internal constructor(
        sshKeyDao: SshKeyDao,
        remoteSource: UsageRemoteSource,
        connector: SshHostUsageConnector,
    ) {
        this.sshKeyDao = sshKeyDao
        this.remoteSource = remoteSource
        this.connector = connector
    }

    override suspend fun fetch(host: HostEntity): HostUsageFetch {
        val key = sshKeyDao.getById(host.keyId) ?: return HostUsageFetch.Skipped
        val keyFile = File(key.privateKeyPath)
        if (!keyFile.exists()) return HostUsageFetch.Skipped
        if (key.hasPassphrase) return HostUsageFetch.Skipped

        val session: SshSession = try {
            connector.connect(host, keyFile).getOrNull() ?: return HostUsageFetch.Skipped
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return HostUsageFetch.Skipped
        }

        return try {
            // Issue #490: the Usage DETAIL screen and the host-list SUMMARY
            // strip must agree. The summary's [UsageScheduler.fetchHostOverSsh]
            // calls [UsageRemoteSource.fetchUsage] DIRECTLY and infers
            // "tool missing" only from a genuine exit-127. The detail used to
            // pre-gate on a SEPARATE `detectPocketshell` probe, so a host where
            // the binary resolves for `fetchUsage` but the bare `command -v`
            // probe failed (the #484 PATH bug) showed live data on the summary
            // yet "not installed" on the detail. We now run the SAME single
            // path: fetch usage directly and derive the state from its result,
            // so both surfaces are consistent by construction.
            when (val fetch = remoteSource.fetchUsage(session, commandOverride = host.usageCommandOverride)) {
                is UsageFetchResult.Success -> HostUsageFetch.Records(
                    records = fetch.records,
                    syncedAt = Instant.now(),
                )

                UsageFetchResult.ToolMissing -> HostUsageFetch.ToolMissing
                is UsageFetchResult.Failed -> HostUsageFetch.Skipped
            }
        } finally {
            runCatching { session.close() }
        }
    }
}

/**
 * Backs [UsageScreen] for issue #114 Fix A.
 *
 * Iterates over every saved host and asks [HostUsageFetcher] for its
 * pocketshell/quota state. Results are aggregated into a single
 * [UsageScreenState] that the screen renders. Hosts whose key file is
 * missing, who fail to connect, or whose pocketshell status is unknown are
 * silently skipped so the panel only shows actionable rows. Hosts where
 * pocketshell is confirmed absent populate the "missing tool" empty-state
 * list.
 *
 * The view model is deliberately stateless across visits: every
 * navigation to [com.pocketshell.app.nav.AppDestination.Usage] spins up a
 * new instance (via `hiltViewModel()`), which calls [refresh] from
 * `init {}`. Pull-to-refresh (wired through `UsageScreen.onRefresh`)
 * re-runs the same routine.
 *
 * Per-host command override (Fix C) and bootstrap-aware periodic polling
 * (Fix C) are out of scope for this issue; the `commandOverride` field
 * on [UsageRemoteSource.fetchUsage] is still threaded through
 * [SshHostUsageFetcher] so a follow-up can plug it into a settings UI
 * without re-shaping the data model.
 */
@HiltViewModel
open class UsageViewModel internal constructor(
    private val hostDao: HostDao,
    private val fetcher: HostUsageFetcher,
    // Issue #116 (usage-panel Fix B): the scheduler is the single
    // source of truth for the per-host usage snapshots that the host
    // list strip + per-card badge + in-session chip all read. The view
    // model's pull-to-refresh path now feeds the scheduler too so the
    // badges in those surfaces update when the user taps refresh on
    // the Usage screen (AC #4: "both update on pull-to-refresh on
    // UsageScreen"). Nullable + defaulted on the Hilt constructor so
    // production injection can omit it when the scheduler is absent.
    private val usageScheduler: UsageScheduler?,
    private val refreshDispatcher: CoroutineDispatcher,
    private val refreshTimeoutMillis: Long,
) : ViewModel() {

    @Inject
    public constructor(
        hostDao: HostDao,
        fetcher: HostUsageFetcher,
        usageScheduler: UsageScheduler? = null,
    ) : this(
        hostDao = hostDao,
        fetcher = fetcher,
        usageScheduler = usageScheduler,
        refreshDispatcher = Dispatchers.IO,
        refreshTimeoutMillis = DEFAULT_REFRESH_TIMEOUT_MILLIS,
    )

    private val _state = MutableStateFlow(UsageScreenState())
    val state: StateFlow<UsageScreenState> = _state.asStateFlow()

    private var inFlight: Job? = null

    init {
        refresh()
    }

    /**
     * Re-fetch usage for every host. Cancels any in-flight refresh first
     * so rapid pull-to-refresh taps don't stack overlapping probes.
     *
     * Issue #116 also fans the same per-host result into
     * [UsageScheduler] (when injected) so the host-list strip and the
     * in-session chips stay in sync with what's on the usage panel.
     */
    fun refresh() {
        inFlight?.cancel()
        inFlight = viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true)
            val result = withContext(refreshDispatcher) {
                withTimeoutOrNull(refreshTimeoutMillis) {
                    loadUsageState()
                }
            }
            if (result == null) {
                _state.value = _state.value.copy(isRefreshing = false)
                return@launch
            }
            _state.value = result.state

            // Fan into the scheduler so cross-surface badges update.
            usageScheduler?.updateSnapshots(result.schedulerUpdates)
        }
    }

    private suspend fun loadUsageState(): UsageRefreshResult {
        // Issue #525: do NOT filter hosts by `pocketshellVersionCompatible`.
        // That flag is unreliable — a host whose remote CLI is NEWER than
        // the app (which #514 considers fully usable) can carry a STALE
        // `false` written before #514 replaced exact-`==` with semver
        // semantics, and that stale value silently dropped the host to
        // "0 hosts" → a blank panel. The fetcher already classifies every
        // host into Records / ToolMissing / Skipped from the SAME single
        // usage command the host-list summary runs, so attempting the fetch
        // for every saved host is both correct and consistent across
        // surfaces. A host that genuinely cannot serve usage lands in
        // ToolMissing (explicit empty-reason) or Skipped — never silently
        // hidden behind a stale compat flag.
        val hosts = hostDao.getAll().first()
        val snapshots = mutableListOf<UsageHostSnapshot>()
        val missing = mutableListOf<UsageMissingToolHost>()
        val schedulerUpdates = mutableMapOf<Long, UsageSnapshot>()

        hosts.forEach { host ->
            when (val result = fetcher.fetch(host)) {
                is HostUsageFetch.Records -> {
                    snapshots += UsageHostSnapshot(
                        hostId = host.id,
                        hostName = host.name,
                        records = result.records,
                        lastSyncedAt = result.syncedAt,
                    )
                    schedulerUpdates[host.id] = UsageSnapshot.Records(
                        hostId = host.id,
                        hostName = host.name,
                        records = result.records,
                        fetchedAt = result.syncedAt,
                        command = host.usageCommandOverride ?: UsageRemoteSource.defaultUsageCommand,
                    )
                }

                HostUsageFetch.ToolMissing -> {
                    missing += UsageMissingToolHost(
                        hostId = host.id,
                        hostName = host.name,
                    )
                    schedulerUpdates[host.id] = UsageSnapshot.ToolMissing(
                        hostId = host.id,
                        hostName = host.name,
                        fetchedAt = java.time.Instant.now(),
                    )
                }

                HostUsageFetch.Skipped -> Unit
            }
        }

        return UsageRefreshResult(
            state = UsageScreenState(
                hosts = snapshots,
                missingToolHosts = missing,
                isRefreshing = false,
            ),
            schedulerUpdates = schedulerUpdates,
        )
    }

    private data class UsageRefreshResult(
        val state: UsageScreenState,
        val schedulerUpdates: Map<Long, UsageSnapshot>,
    )

    public companion object {
        public const val DEFAULT_REFRESH_TIMEOUT_MILLIS: Long = 20_000L
    }
}
