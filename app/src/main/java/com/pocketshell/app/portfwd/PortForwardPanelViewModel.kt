package com.pocketshell.app.portfwd

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.core.portfwd.AutoForwarderSupervisor
import com.pocketshell.core.portfwd.PortScanner
import com.pocketshell.core.portfwd.RemotePort
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.PortRemappingDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PortForwardPanelState(
    val host: HostEntity? = null,
    val autoForwardEnabled: Boolean = false,
    val connectionState: PortForwardConnectionState = PortForwardConnectionState.Idle,
    val tunnels: List<TunnelInfo> = emptyList(),
    val error: String? = null,
    // Issue #492: "Show all ports" — when false (default), the discovery
    // table only shows ports in InterestingPortFilter.DEFAULT_RANGE
    // (10000+). When true, every discovered port is shown.
    val showAllPorts: Boolean = false,
    // Issue #492: number of discovered ports hidden by the default filter,
    // i.e. how many extra rows "Show all ports" would reveal. Drives the
    // checkbox label's "(N hidden)" hint. Always reflects the latest scan
    // regardless of the current showAllPorts value.
    val hiddenPortCount: Int = 0,
)

enum class PortForwardConnectionState {
    Idle,
    Connecting,
    Connected,
    Reconnecting,
    Error,
}

@HiltViewModel
class PortForwardPanelViewModel @Inject constructor(
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
    private val connector: PortForwardConnector,
    // Issue #203 expanded scope: user-defined per-host port remappings.
    // The DAO is read once per connect inside `setAutoForwardEnabled` so
    // the new `AutoForwarder.initialRemappings` map reflects the latest
    // saved state. Persistence + edit affordances are deferred to the
    // panel UI in a follow-up round.
    private val portRemappingDao: PortRemappingDao,
    // Issue #329: active auto-forward ownership lives in this
    // app-scoped controller, not in the panel ViewModel. The panel
    // performs credential/generation checks, hands the verified first
    // SSH session to the controller, and then observes controller
    // flows. Panel disposal only detaches those observers; explicit
    // toggle-off or the service Stop action tears the forwarding down.
    private val forwardingController: ForwardingController,
    // Issue #492: persists the "Show all ports" checkbox across panel
    // navigation and app restarts. Global (not per-host) — see store doc.
    private val showAllPortsStore: ShowAllPortsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(PortForwardPanelState(showAllPorts = showAllPortsStore.isShowAll()))
    val state: StateFlow<PortForwardPanelState> = _state.asStateFlow()

    /**
     * Issue #492: the most recent raw discovery result, kept so toggling
     * "Show all ports" can re-filter the table without a new SSH scan. Null
     * until the first discovery completes; reset to null when the panel is
     * left or a different host is loaded.
     */
    private var lastDiscoveredPorts: List<RemotePort> = emptyList()

    /**
     * Issue #492: the remote->local remappings snapshot used to build the
     * last discovery table, kept so a "Show all ports" re-filter rebuilds the
     * rows with the same local-port mapping it scanned with.
     */
    private var currentRemappings: Map<Int, Int> = emptyMap()

    private var currentHostId: Long? = null
    private var requestedKeyPath: String? = null
    private var keyPath: String? = null
    private var passphrase: CharArray? = null
    private var tunnelCollection: Job? = null
    private var discoveryJob: Job? = null
    private var discoveryGeneration: Long = 0
    private var discoverPortsOnIdle: Boolean = false
    private var loadJob: Job? = null
    private var loadGeneration: Long = 0
    private var connectJob: Job? = null
    private var connectGeneration: Long = 0

    /**
     * Host id this panel is observing. Non-null does not imply ownership:
     * active forwarding is owned by [ForwardingController] so panel
     * disposal does not tear down foreground-service tunnels.
     */
    private var observedForwardingHostId: Long? = null
    private var pendingStartPort: Int? = null

    /**
     * Reference to the attached lifecycle owner + observer so
     * [onCleared] can detach cleanly. Only the Compose screen calls
     * [observeProcessLifecycle]; unit tests can opt in to a controlled
     * [LifecycleOwner] (typically backed by `LifecycleRegistry`) for
     * deterministic ON_STOP / ON_START sequences.
     */
    private var attachedLifecycle: Lifecycle? = null
    private val lifecycleObserver = LifecycleEventObserver { _: LifecycleOwner, event ->
        when (event) {
            Lifecycle.Event.ON_STOP -> handleLifecycleStop()
            Lifecycle.Event.ON_START -> handleLifecycleStart()
            else -> Unit
        }
    }

    /**
     * Attach the process-wide lifecycle. Auto-forward itself is the D21
     * foreground-service carve-out, so active tunnels stay supervised
     * through `ON_STOP`; the observer exists to keep that decision
     * explicit and testable.
     *
     * Idempotent: a second call with the same owner is a no-op; passing
     * a different owner detaches the previous one first so tests can
     * re-attach to a fresh `LifecycleRegistry`.
     *
     * Pattern mirrored after [com.pocketshell.app.usage.UsageScheduler.observeProcessLifecycle]
     * — the only behavioural difference is that the panel-scoped
     * ViewModel attaches lazily from the Compose screen rather than at
     * `App.onCreate`, because the panel is the only D21 surface that
     * actually opens phone-side `ServerSocket`s and an SSH transport
     * for the user's hosts.
     */
    fun observeProcessLifecycle(owner: LifecycleOwner = ProcessLifecycleOwner.get()) {
        val lifecycle = owner.lifecycle
        if (attachedLifecycle === lifecycle) return
        attachedLifecycle?.removeObserver(lifecycleObserver)
        attachedLifecycle = lifecycle
        // `Lifecycle.addObserver` requires the main thread; hop there
        // explicitly so a call from a coroutine on `Dispatchers.IO` is
        // safe. The observer itself runs on whatever thread the
        // lifecycle dispatches events on (always Main in production).
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                lifecycle.addObserver(lifecycleObserver)
            }
        }
    }

    private fun handleLifecycleStop() {
        // Auto-forward is the D21 foreground-service carve-out: once
        // the user has enabled it, [ForwardingController] owns the
        // supervisor and [ForwardingService] keeps the process in
        // foreground-service mode. ON_STOP should not unregister the
        // active host or close the tunnels.
    }

    private fun handleLifecycleStart() {
        // Active tunnels were never torn down on ON_STOP, and idle
        // panels must not auto-start when the app returns foreground.
    }

    fun load(
        hostId: Long,
        initialKeyPath: String? = null,
        initialPassphrase: CharArray? = null,
        discoverPorts: Boolean = false,
        // Slice B (#447): when a caller opens the panel for a specific
        // remote port (the 432c detection overlay, a "known port" entry
        // point, or a discovered-row tap that pre-resolved the port),
        // the panel comes up with that port already being forwarded in
        // one step instead of waiting for a discovery scan + a manual
        // toggle. Prefill takes priority over the idle discovery scan so
        // we don't open two SSH sessions for the same host on entry.
        prefillRemotePort: Int? = null,
    ) {
        if (
            currentHostId == hostId &&
            requestedKeyPath == initialKeyPath &&
            passphrase contentEquals initialPassphrase &&
            _state.value.host != null
        ) {
            // Same panel re-composition (no credential change). If a
            // prefill port was requested and we are not already
            // forwarding it, start it now without tearing down the
            // existing connection/discovery state.
            if (prefillRemotePort != null) {
                startPort(prefillRemotePort)
            }
            return
        }
        val requestGeneration = ++loadGeneration
        loadJob?.cancel()
        stopDiscovery()
        detachForwardingObservers()
        clearPassphrase()
        pendingStartPort = null
        currentHostId = hostId
        requestedKeyPath = initialKeyPath
        keyPath = initialKeyPath
        discoverPortsOnIdle = discoverPorts
        passphrase = initialPassphrase?.copyOf()
        lastDiscoveredPorts = emptyList()
        _state.value = PortForwardPanelState(showAllPorts = showAllPortsStore.isShowAll())
        loadJob = viewModelScope.launch {
            val host = hostDao.getById(hostId)
            if (requestGeneration != loadGeneration || !currentCoroutineContext().isActive) {
                return@launch
            }
            if (host == null) {
                _state.value = PortForwardPanelState(
                    connectionState = PortForwardConnectionState.Error,
                    error = "Host not found.",
                )
                return@launch
            }
            val resolvedKeyPath = initialKeyPath ?: sshKeyDao.getById(host.keyId)?.privateKeyPath
            if (requestGeneration != loadGeneration || !currentCoroutineContext().isActive) {
                return@launch
            }
            keyPath = resolvedKeyPath
            _state.value = PortForwardPanelState(
                host = host,
                showAllPorts = showAllPortsStore.isShowAll(),
            )
            if (forwardingController.isHostActive(host.id)) {
                attachForwardingObservers(host.id)
                _state.value = _state.value.copy(
                    autoForwardEnabled = true,
                    connectionState = PortForwardConnectionState.Connecting,
                    error = null,
                )
                return@launch
            }
            if (prefillRemotePort != null) {
                // One-step forward of the requested port. `startPort`
                // routes through `setAutoForwardEnabled(true)` which
                // opens the SSH session, so the idle discovery scan is
                // intentionally skipped to avoid a second connection.
                startPort(prefillRemotePort)
            } else if (discoverPorts && resolvedKeyPath != null) {
                startDiscovery(host, resolvedKeyPath)
            }
            if (requestGeneration == loadGeneration) {
                loadJob = null
            }
        }
    }

    fun leavePanel() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        stopDiscovery()
        detachForwardingObservers()
        clearPassphrase()
        pendingStartPort = null
        currentHostId = null
        requestedKeyPath = null
        keyPath = null
        discoverPortsOnIdle = false
        lastDiscoveredPorts = emptyList()
        _state.value = PortForwardPanelState(showAllPorts = showAllPortsStore.isShowAll())
    }

    /**
     * Issue #492: toggle the "Show all ports" checkbox. Persists the choice
     * and re-filters the currently discovered ports without a new SSH scan.
     *
     * Only the discovery (auto-forward off) table is re-filtered: when
     * auto-forward is active the rows are the user's chosen tunnels, not a
     * scan, so the filter doesn't apply. The persisted flag still updates so
     * the next discovery honours it.
     */
    fun setShowAllPorts(showAll: Boolean) {
        if (showAll == _state.value.showAllPorts) return
        showAllPortsStore.setShowAll(showAll)
        DiagnosticEvents.record(
            "action",
            "port_forward_show_all_toggle",
            "enabled" to showAll,
            "cachedPortCount" to lastDiscoveredPorts.size,
            "hiddenPortCount" to InterestingPortFilter.hiddenCount(lastDiscoveredPorts),
        )
        _state.value = if (!_state.value.autoForwardEnabled && lastDiscoveredPorts.isNotEmpty()) {
            val remappings = currentRemappings
            _state.value.copy(
                showAllPorts = showAll,
                tunnels = lastDiscoveredPorts.toAvailableTunnels(remappings, showAll),
            )
        } else {
            _state.value.copy(showAllPorts = showAll)
        }
    }

    fun setAutoForwardEnabled(enabled: Boolean) {
        val host = _state.value.host ?: return
        if (enabled == _state.value.autoForwardEnabled) return
        DiagnosticEvents.record(
            "action",
            "port_forward_auto_toggle",
            "enabled" to enabled,
            "hostId" to host.id,
            "activeTunnelCount" to _state.value.tunnels.count {
                it.status == TunnelInfo.Status.FORWARDING
            },
        )
        // User-driven disable: persist immediately. We don't wait for
        // anything else because there is no async success-or-fail path
        // on disable.
        if (!enabled && host.enabled) {
            val updated = host.copy(enabled = false)
            _state.value = _state.value.copy(host = updated)
            viewModelScope.launch {
                runCatching { hostDao.update(updated) }
            }
        }
        if (!enabled) {
            stopForwarding()
            _state.value = _state.value.copy(
                autoForwardEnabled = false,
                connectionState = PortForwardConnectionState.Idle,
                tunnels = emptyList(),
                error = null,
            )
            if (discoverPortsOnIdle) {
                keyPath?.let { startDiscovery(host, it) }
            }
            return
        }

        val resolvedKeyPath = keyPath
        if (resolvedKeyPath == null) {
            _state.value = _state.value.copy(
                autoForwardEnabled = false,
                connectionState = PortForwardConnectionState.Error,
                error = "SSH key not found.",
            )
            return
        }

        stopDiscovery()
        _state.value = _state.value.copy(
            autoForwardEnabled = true,
            connectionState = PortForwardConnectionState.Connecting,
            error = null,
        )
        val requestGeneration = ++connectGeneration
        val requestLoadGeneration = loadGeneration
        val requestPassphrase = passphrase?.copyOf()
        val shouldPersistOnSuccess = !host.enabled
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val connected = connector.connect(host, resolvedKeyPath, requestPassphrase)
            if (
                requestGeneration != connectGeneration ||
                requestLoadGeneration != loadGeneration ||
                _state.value.host?.id != host.id ||
                !currentCoroutineContext().isActive
            ) {
                connected.getOrNull()?.close()
                return@launch
            }
            val sshSession = connected.getOrElse { failure ->
                pendingStartPort = null
                DiagnosticEvents.record(
                    "action",
                    "port_forward_auto_start_result",
                    "status" to "failure",
                    "hostId" to host.id,
                    "cause" to failure.javaClass.simpleName,
                )
                _state.value = _state.value.copy(
                    autoForwardEnabled = false,
                    connectionState = PortForwardConnectionState.Error,
                    error = failure.message ?: "SSH connection failed.",
                )
                return@launch
            }
            if (!_state.value.autoForwardEnabled || _state.value.host?.id != host.id) {
                sshSession.close()
                return@launch
            }
            val remappings = runCatching {
                portRemappingDao.getByHostId(host.id).first()
                    .associate { it.remotePort to it.localPort }
            }.getOrElse { emptyMap() }
            forwardingController.adoptForwardingSession(
                host = host,
                keyPath = resolvedKeyPath,
                passphrase = requestPassphrase,
                firstSession = sshSession,
                initialRemappings = remappings,
            )
            DiagnosticEvents.record(
                "action",
                "port_forward_auto_start_result",
                "status" to "success",
                "hostId" to host.id,
                "pendingStartPort" to (pendingStartPort != null),
            )
            attachForwardingObservers(host.id)
            pendingStartPort?.let { remotePort ->
                pendingStartPort = null
                forwardingController.togglePort(host.id, remotePort)
            }
            // User-driven enable: persist only after the SSH connect +
            // forwarder bring-up succeeded. A failed enable leaves the
            // persisted `host.enabled` flag untouched so the next load
            // does not autostart a still-broken connection.
            if (shouldPersistOnSuccess) {
                val current = _state.value.host
                if (current != null && current.id == host.id && !current.enabled) {
                    val updated = current.copy(enabled = true)
                    _state.value = _state.value.copy(host = updated)
                    runCatching { hostDao.update(updated) }
                }
            }
            if (requestGeneration == connectGeneration) {
                connectJob = null
            }
        }
    }

    fun togglePort(remotePort: Int) {
        val hostId = _state.value.host?.id ?: return
        DiagnosticEvents.record(
            "action",
            "port_forward_port_toggle",
            "hostId" to hostId,
            "remotePort" to remotePort,
            "currentlyForwarding" to (
                _state.value.tunnels.firstOrNull { it.remotePort == remotePort }?.status ==
                    TunnelInfo.Status.FORWARDING
                ),
        )
        forwardingController.togglePort(hostId, remotePort)
    }

    fun startPort(remotePort: Int) {
        val hostId = _state.value.host?.id
        DiagnosticEvents.record(
            "action",
            "port_forward_port_open",
            "hostId" to hostId,
            "remotePort" to remotePort,
            "hostActive" to (hostId?.let { forwardingController.isHostActive(it) } == true),
        )
        if (hostId == null || !forwardingController.isHostActive(hostId)) {
            pendingStartPort = remotePort
            setAutoForwardEnabled(true)
            return
        }
        val current = _state.value.tunnels.firstOrNull { it.remotePort == remotePort }
        if (current?.status != TunnelInfo.Status.FORWARDING) {
            forwardingController.togglePort(hostId, remotePort)
        }
    }

    override fun onCleared() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        stopDiscovery()
        detachForwardingObservers()
        clearPassphrase()
        // Detach the lifecycle observer so the ViewModel is GC-eligible
        // once its scope is cleared. `ProcessLifecycleOwner.get()` is a
        // process-singleton and outlives this ViewModel, so without
        // detach it would hold a reference indefinitely.
        attachedLifecycle?.removeObserver(lifecycleObserver)
        attachedLifecycle = null
        super.onCleared()
    }

    private fun stopForwarding() {
        connectGeneration++
        connectJob?.cancel()
        connectJob = null
        detachForwardingObservers()
        val hostId = _state.value.host?.id ?: currentHostId
        if (hostId != null) {
            DiagnosticEvents.record(
                "action",
                "port_forward_stop",
                "hostId" to hostId,
                "activeTunnelCount" to _state.value.tunnels.count {
                    it.status == TunnelInfo.Status.FORWARDING
                },
            )
            forwardingController.stopForwarding(hostId)
        }
        pendingStartPort = null
    }

    private fun attachForwardingObservers(hostId: Long) {
        if (observedForwardingHostId == hostId && tunnelCollection?.isActive == true) return
        detachForwardingObservers()
        observedForwardingHostId = hostId
        tunnelCollection = viewModelScope.launch {
            launch {
                forwardingController.flowOfHostTunnels(hostId).collect { tunnels ->
                    _state.value = _state.value.copy(tunnels = tunnels)
                }
            }
            launch {
                forwardingController.flowOfHostConnectionState(hostId).collect { supervisorState ->
                    _state.value = _state.value.copy(
                        connectionState = supervisorState.toPanelConnectionState(),
                    )
                }
            }
            launch {
                forwardingController.flowOfHostError(hostId).collect { error ->
                    _state.value = _state.value.copy(error = error)
                }
            }
        }
    }

    private fun detachForwardingObservers() {
        tunnelCollection?.cancel()
        tunnelCollection = null
        observedForwardingHostId = null
    }

    private fun startDiscovery(host: HostEntity, resolvedKeyPath: String) {
        val requestGeneration = ++discoveryGeneration
        val requestLoadGeneration = loadGeneration
        val requestPassphrase = passphrase?.copyOf()
        discoveryJob?.cancel()
        _state.value = _state.value.copy(
            connectionState = PortForwardConnectionState.Connecting,
            error = null,
        )
        discoveryJob = viewModelScope.launch {
            val connected = connector.connect(host, resolvedKeyPath, requestPassphrase)
            if (
                requestGeneration != discoveryGeneration ||
                requestLoadGeneration != loadGeneration ||
                _state.value.host?.id != host.id ||
                _state.value.autoForwardEnabled ||
                !currentCoroutineContext().isActive
            ) {
                connected.getOrNull()?.close()
                return@launch
            }
            val sshSession = connected.getOrElse { failure ->
                _state.value = _state.value.copy(
                    connectionState = PortForwardConnectionState.Error,
                    error = failure.message ?: "SSH connection failed.",
                )
                return@launch
            }
            try {
                val remappings = runCatching {
                    portRemappingDao.getByHostId(host.id).first()
                        .associate { it.remotePort to it.localPort }
                }.getOrElse { emptyMap() }
                val rawPorts = PortScanner.scan(sshSession)
                val showAll = _state.value.showAllPorts
                val discovered = rawPorts.toAvailableTunnels(remappings, showAll)
                if (
                    requestGeneration == discoveryGeneration &&
                    requestLoadGeneration == loadGeneration &&
                    _state.value.host?.id == host.id &&
                    !_state.value.autoForwardEnabled
                ) {
                    // Issue #492: cache the raw scan + remappings so toggling
                    // "Show all ports" can re-filter without a new SSH scan.
                    lastDiscoveredPorts = rawPorts
                    currentRemappings = remappings
                    _state.value = _state.value.copy(
                        connectionState = PortForwardConnectionState.Connected,
                        tunnels = discovered,
                        hiddenPortCount = InterestingPortFilter.hiddenCount(rawPorts),
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                if (requestGeneration == discoveryGeneration && !_state.value.autoForwardEnabled) {
                    _state.value = _state.value.copy(
                        connectionState = PortForwardConnectionState.Error,
                        error = t.message ?: "Port discovery failed.",
                    )
                }
            } finally {
                sshSession.close()
                if (requestGeneration == discoveryGeneration) {
                    discoveryJob = null
                }
            }
        }
    }

    private fun stopDiscovery() {
        discoveryGeneration++
        discoveryJob?.cancel()
        discoveryJob = null
    }

    private fun clearPassphrase() {
        passphrase?.fill('\u0000')
        passphrase = null
    }

    private fun AutoForwarderSupervisor.ConnectionState.toPanelConnectionState(): PortForwardConnectionState =
        when (this) {
            AutoForwarderSupervisor.ConnectionState.Idle -> PortForwardConnectionState.Idle
            AutoForwarderSupervisor.ConnectionState.Connecting -> PortForwardConnectionState.Connecting
            AutoForwarderSupervisor.ConnectionState.Connected -> PortForwardConnectionState.Connected
            AutoForwarderSupervisor.ConnectionState.Reconnecting -> PortForwardConnectionState.Reconnecting
            AutoForwarderSupervisor.ConnectionState.Lost -> PortForwardConnectionState.Error
        }
}

private fun List<RemotePort>.toAvailableTunnels(
    remappings: Map<Int, Int>,
    showAll: Boolean = false,
): List<TunnelInfo> =
    // Issue #456/#492: de-dupe per port and, by default, keep only the useful
    // high-port range (`10000+`) so the panel table is readable instead of
    // an ~80-row dump. When [showAll] is true the low noisy ports are included
    // too. The filter already orders in-range-first and
    // de-duplicates, so we keep its order rather than re-sorting by port number.
    InterestingPortFilter.filter(this, showAll).map { remotePort ->
        TunnelInfo(
            remotePort = remotePort.port,
            localPort = remappings[remotePort.port] ?: remotePort.port,
            process = remotePort.processName,
            status = TunnelInfo.Status.AVAILABLE,
        )
    }

private infix fun CharArray?.contentEquals(other: CharArray?): Boolean =
    when {
        this === null && other === null -> true
        this === null || other === null -> false
        size != other.size -> false
        else -> indices.all { index -> this[index] == other[index] }
    }
