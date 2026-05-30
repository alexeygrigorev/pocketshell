package com.pocketshell.app.portfwd

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.portfwd.AutoForwardConfig
import com.pocketshell.core.portfwd.AutoForwarder
import com.pocketshell.core.portfwd.AutoForwarderSupervisor
import com.pocketshell.core.portfwd.PortScanner
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.ssh.SshSession
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
)

enum class PortForwardConnectionState {
    Idle,
    Connecting,
    Connected,
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
    // Issue #203 expanded scope (round 3, foreground-service slice):
    // rendezvous for starting / stopping the persistent foreground
    // service. The ViewModel calls `registerActiveHost` when
    // auto-forward succeeds and `unregisterActiveHost` on disable. The
    // controller starts the [com.pocketshell.app.portfwd.service.ForwardingService]
    // when the first host registers and stops it when the last
    // unregisters.
    private val forwardingController: ForwardingController,
) : ViewModel() {

    private val _state = MutableStateFlow(PortForwardPanelState())
    val state: StateFlow<PortForwardPanelState> = _state.asStateFlow()

    private var currentHostId: Long? = null
    private var requestedKeyPath: String? = null
    private var keyPath: String? = null
    private var passphrase: CharArray? = null
    private var session: SshSession? = null
    private var forwarder: AutoForwarder? = null
    private var tunnelCollection: Job? = null
    private var discoveryJob: Job? = null
    private var discoveryGeneration: Long = 0
    private var discoverPortsOnIdle: Boolean = false
    private var loadJob: Job? = null
    private var loadGeneration: Long = 0
    private var connectJob: Job? = null
    private var connectGeneration: Long = 0

    /**
     * Host id we have registered with [forwardingController]. Non-null
     * means the foreground service is being kept alive for us. We hold
     * this so [stopForwarding] / [leavePanel] / [onCleared] can call
     * `unregisterActiveHost` even when [_state.value.host] has already
     * been replaced (e.g. user navigated to a different host's panel).
     */
    private var registeredHostId: Long? = null
    private var pendingStartPort: Int? = null

    /**
     * Snapshot of whether auto-forward was active immediately before the
     * app went into `ON_STOP`. Restored on the next `ON_START` so the
     * user returns to the panel with the same tunnels they left behind
     * (subject to a fresh SSH connect + scan).
     *
     * Cleared once a restore attempt has been kicked off or the user
     * leaves the panel — we never want a stale "was-enabled" flag to
     * second-guess an explicit user toggle later.
     */
    private var wasAutoForwardEnabledBeforeBackground: Boolean = false

    /**
     * `true` while the most recent `setAutoForwardEnabled` call is being
     * driven by the lifecycle observer (background -> resume), not the
     * user toggling the Switch. Lifecycle-driven changes must NOT
     * persist `enabled` back to [HostEntity] — `enabled` is the user's
     * intent for autostart, and a `STOP` event is not "the user turned
     * it off".
     */
    private var lifecycleDriven: Boolean = false

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
     * Attach the process-wide lifecycle so the panel pauses its scan
     * loop + closes active tunnels on `ON_STOP` (D21, issue #161 — no
     * background work) and re-opens them on the next `ON_START`.
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
        // Only pause if forwarding is actually active. Otherwise this
        // is a no-op so the next ON_START doesn't auto-start an idle
        // panel.
        if (!_state.value.autoForwardEnabled) {
            wasAutoForwardEnabledBeforeBackground = false
            return
        }
        wasAutoForwardEnabledBeforeBackground = true
        lifecycleDriven = true
        try {
            setAutoForwardEnabled(false)
        } finally {
            lifecycleDriven = false
        }
    }

    private fun handleLifecycleStart() {
        // Re-open the tunnels the user had active before the app went
        // to the background. If nothing was active, do nothing — a
        // fresh ON_START should not auto-start an idle panel.
        if (!wasAutoForwardEnabledBeforeBackground) return
        wasAutoForwardEnabledBeforeBackground = false
        // Only attempt to restore if we still have the panel state
        // (host + key path) that the original enable used. If the user
        // left the panel while backgrounded, leavePanel() already
        // cleared this and the restore is silently skipped.
        if (_state.value.host == null || keyPath == null) return
        lifecycleDriven = true
        try {
            setAutoForwardEnabled(true)
        } finally {
            lifecycleDriven = false
        }
    }

    fun load(
        hostId: Long,
        initialKeyPath: String? = null,
        initialPassphrase: CharArray? = null,
        discoverPorts: Boolean = false,
    ) {
        if (
            currentHostId == hostId &&
            requestedKeyPath == initialKeyPath &&
            passphrase contentEquals initialPassphrase &&
            _state.value.host != null
        ) {
            return
        }
        val requestGeneration = ++loadGeneration
        loadJob?.cancel()
        stopDiscovery()
        stopForwarding()
        clearPassphrase()
        pendingStartPort = null
        currentHostId = hostId
        requestedKeyPath = initialKeyPath
        keyPath = initialKeyPath
        discoverPortsOnIdle = discoverPorts
        passphrase = initialPassphrase?.copyOf()
        _state.value = PortForwardPanelState()
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
            _state.value = PortForwardPanelState(host = host)
            if (discoverPorts && resolvedKeyPath != null) {
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
        stopForwarding()
        clearPassphrase()
        pendingStartPort = null
        currentHostId = null
        requestedKeyPath = null
        keyPath = null
        discoverPortsOnIdle = false
        // A user navigating away erases any pending "restore on
        // ON_START" intent — the panel will autostart on the next
        // re-entry only if [HostEntity.enabled] is persisted true.
        wasAutoForwardEnabledBeforeBackground = false
        _state.value = PortForwardPanelState()
    }

    fun setAutoForwardEnabled(enabled: Boolean) {
        val host = _state.value.host ?: return
        if (enabled == _state.value.autoForwardEnabled) return
        // User-driven disable: persist immediately. We don't wait for
        // anything else because there is no async success-or-fail path
        // on disable. A lifecycle-driven `ON_STOP` skips this branch
        // (see [observeProcessLifecycle]) — a backgrounded process is
        // not the user disabling forwarding.
        if (!lifecycleDriven && !enabled && host.enabled) {
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
            if (discoverPortsOnIdle && !lifecycleDriven) {
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
        val shouldPersistOnSuccess = !lifecycleDriven && !host.enabled
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

            session = sshSession
            // Load persisted remote->local port remappings before the
            // scan loop starts so the first round of forwards honours
            // them. `first()` snapshots the current set; the panel
            // re-runs `setAutoForwardEnabled` after edits so a runtime
            // remap isn't lost.
            val remappings = runCatching {
                portRemappingDao.getByHostId(host.id).first()
                    .associate { it.remotePort to it.localPort }
            }.getOrElse { emptyMap() }
            val autoForwarder = AutoForwarder(
                session = sshSession,
                config = host.toAutoForwardConfig(),
                initialRemappings = remappings,
            )
            forwarder = autoForwarder
            tunnelCollection?.cancel()
            tunnelCollection = viewModelScope.launch {
                autoForwarder.flowOfTunnels().collect { tunnels ->
                    _state.value = _state.value.copy(tunnels = tunnels)
                    // Issue #203 expanded scope (foreground-service
                    // slice): mirror the tunnel count out to the
                    // controller so the persistent notification can
                    // render `N tunnels active`. Count only FORWARDING
                    // tunnels — AVAILABLE / FAILED / STOPPED rows
                    // shouldn't inflate the notification count.
                    val activeRemotePorts = tunnels
                        .filter { tunnel -> tunnel.status == TunnelInfo.Status.FORWARDING }
                        .map { tunnel -> tunnel.remotePort }
                        .toSet()
                    forwardingController.updateActiveTunnels(host.id, activeRemotePorts)
                }
            }
            pendingStartPort?.let { remotePort ->
                pendingStartPort = null
                autoForwarder.togglePort(remotePort)
            }
            autoForwarder.start(viewModelScope)
            // Foreground-service rendezvous: tell the controller this
            // host is now actively forwarding. The controller starts
            // [ForwardingService] when this is the first registered
            // host. Reconnect-on-network-recovery is not yet wired
            // through to the bare [AutoForwarder] — a follow-up round
            // swaps the forwarder for [AutoForwarderSupervisor] and
            // passes the supervisor's `reconnectNow()` as the
            // reconnect hook below. For now we register without a
            // hook so the service still tracks the host (and keeps
            // the process alive), but the network callback's
            // `controller.reconnectNow()` becomes a no-op for this
            // host. The supervisor exists and is fully unit-tested
            // (see `AutoForwarderSupervisorTest`) for that follow-up.
            forwardingController.registerActiveHost(
                hostId = host.id,
                hostName = host.name,
                reconnectHook = null,
            )
            registeredHostId = host.id
            _state.value = _state.value.copy(connectionState = PortForwardConnectionState.Connected)
            // User-driven enable: persist only after the SSH connect +
            // forwarder bring-up succeeded. A failed enable leaves the
            // persisted `host.enabled` flag untouched so the next load
            // does not autostart a still-broken connection. A
            // lifecycle-driven restore (`ON_START` after `ON_STOP`)
            // skips this — the value was already true.
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
        val autoForwarder = forwarder ?: return
        viewModelScope.launch {
            autoForwarder.togglePort(remotePort)
        }
    }

    fun startPort(remotePort: Int) {
        val autoForwarder = forwarder
        if (autoForwarder == null) {
            pendingStartPort = remotePort
            setAutoForwardEnabled(true)
            return
        }
        viewModelScope.launch {
            val current = _state.value.tunnels.firstOrNull { it.remotePort == remotePort }
            if (current?.status != TunnelInfo.Status.FORWARDING) {
                autoForwarder.togglePort(remotePort)
            }
        }
    }

    override fun onCleared() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        stopDiscovery()
        stopForwarding()
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
        tunnelCollection?.cancel()
        tunnelCollection = null
        forwarder?.stop()
        forwarder = null
        session?.close()
        session = null
        // Foreground-service rendezvous: tell the controller this host
        // is no longer forwarding. The controller stops
        // [com.pocketshell.app.portfwd.service.ForwardingService] when
        // this was the last registered host. Idempotent on the
        // controller side — safe to call when nothing was registered
        // (e.g. teardown after a failed connect).
        registeredHostId?.let { hostId ->
            forwardingController.unregisterActiveHost(hostId)
        }
        registeredHostId = null
        pendingStartPort = null
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
                val discovered = PortScanner.scan(sshSession).map { port ->
                    TunnelInfo(
                        remotePort = port.port,
                        localPort = remappings[port.port] ?: port.port,
                        process = port.processName,
                        status = TunnelInfo.Status.AVAILABLE,
                    )
                }.sortedBy { it.remotePort }
                if (
                    requestGeneration == discoveryGeneration &&
                    requestLoadGeneration == loadGeneration &&
                    _state.value.host?.id == host.id &&
                    !_state.value.autoForwardEnabled
                ) {
                    _state.value = _state.value.copy(
                        connectionState = PortForwardConnectionState.Connected,
                        tunnels = discovered,
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

    private fun HostEntity.toAutoForwardConfig(): AutoForwardConfig =
        AutoForwardConfig(
            scanIntervalSec = scanIntervalSec,
            maxAutoPort = maxAutoPort,
            skipPortsBelow = skipPortsBelow,
        )
}

private infix fun CharArray?.contentEquals(other: CharArray?): Boolean =
    when {
        this === null && other === null -> true
        this === null || other === null -> false
        size != other.size -> false
        else -> indices.all { index -> this[index] == other[index] }
    }
