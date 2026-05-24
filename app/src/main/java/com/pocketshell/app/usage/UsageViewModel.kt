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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import javax.inject.Inject

/**
 * Per-host usage fetcher. Injected into [UsageViewModel] so tests can
 * stub the SSH/heru path without needing a real transport. The default
 * implementation opens a short-lived SSH session via [SshConnection],
 * runs [UsageRemoteSource.detectHeru], and — when present —
 * [UsageRemoteSource.fetchUsage].
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
public class SshHostUsageFetcher @Inject constructor(
    private val sshKeyDao: SshKeyDao,
) : HostUsageFetcher {
    // `UsageRemoteSource` carries a default-arg constructor that, combined
    // with its `@Inject` annotation, generates two constructors at the
    // bytecode level — Hilt refuses to bind it directly. Instantiating it
    // here keeps the source unmodified (out of scope for this issue) while
    // still letting Hilt own the `SshHostUsageFetcher` graph.
    private val remoteSource: UsageRemoteSource = UsageRemoteSource()

    override suspend fun fetch(host: HostEntity): HostUsageFetch {
        val key = sshKeyDao.getById(host.keyId) ?: return HostUsageFetch.Skipped
        val keyFile = File(key.privateKeyPath)
        if (!keyFile.exists()) return HostUsageFetch.Skipped
        if (key.hasPassphrase) return HostUsageFetch.Skipped

        val session: SshSession = try {
            SshConnection.connect(
                host = host.hostname,
                port = host.port,
                user = host.username,
                key = SshKey.Path(keyFile),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrNull() ?: return HostUsageFetch.Skipped
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return HostUsageFetch.Skipped
        }

        return try {
            when (remoteSource.detectHeru(session)) {
                UsageToolStatus.Installed -> {
                    when (val fetch = remoteSource.fetchUsage(session)) {
                        is UsageFetchResult.Success -> HostUsageFetch.Records(
                            records = fetch.records,
                            syncedAt = Instant.now(),
                        )

                        UsageFetchResult.ToolMissing -> HostUsageFetch.ToolMissing
                        is UsageFetchResult.Failed -> HostUsageFetch.Skipped
                    }
                }

                UsageToolStatus.Missing -> HostUsageFetch.ToolMissing
                is UsageToolStatus.Unknown -> HostUsageFetch.Skipped
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
 * heru/quota state. Results are aggregated into a single
 * [UsageScreenState] that the screen renders. Hosts whose key file is
 * missing, who fail to connect, or whose heru status is unknown are
 * silently skipped so the panel only shows actionable rows. Hosts where
 * heru is confirmed absent populate the "missing tool" empty-state list.
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
open class UsageViewModel @Inject constructor(
    private val hostDao: HostDao,
    private val fetcher: HostUsageFetcher,
) : ViewModel() {

    private val _state = MutableStateFlow(UsageScreenState())
    val state: StateFlow<UsageScreenState> = _state.asStateFlow()

    private var inFlight: Job? = null

    init {
        refresh()
    }

    /**
     * Re-fetch usage for every host. Cancels any in-flight refresh first
     * so rapid pull-to-refresh taps don't stack overlapping probes.
     */
    fun refresh() {
        inFlight?.cancel()
        inFlight = viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true)
            val hosts = hostDao.getAll().first()
            val snapshots = mutableListOf<UsageHostSnapshot>()
            val missing = mutableListOf<UsageMissingToolHost>()

            hosts.forEach { host ->
                when (val result = fetcher.fetch(host)) {
                    is HostUsageFetch.Records -> snapshots += UsageHostSnapshot(
                        hostId = host.id,
                        hostName = host.name,
                        records = result.records,
                        lastSyncedAt = result.syncedAt,
                    )

                    HostUsageFetch.ToolMissing -> missing += UsageMissingToolHost(
                        hostId = host.id,
                        hostName = host.name,
                    )

                    HostUsageFetch.Skipped -> Unit
                }
            }

            _state.value = UsageScreenState(
                hosts = snapshots,
                missingToolHosts = missing,
                isRefreshing = false,
            )
        }
    }
}
