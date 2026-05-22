package com.pocketshell.app.portfwd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.portfwd.AutoForwardConfig
import com.pocketshell.core.portfwd.AutoForwarder
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
) : ViewModel() {

    private val _state = MutableStateFlow(PortForwardPanelState())
    val state: StateFlow<PortForwardPanelState> = _state.asStateFlow()

    private var currentHostId: Long? = null
    private var requestedKeyPath: String? = null
    private var keyPath: String? = null
    private var session: SshSession? = null
    private var forwarder: AutoForwarder? = null
    private var tunnelCollection: Job? = null

    fun load(hostId: Long, initialKeyPath: String? = null) {
        if (currentHostId == hostId && requestedKeyPath == initialKeyPath && _state.value.host != null) return
        stopForwarding()
        currentHostId = hostId
        requestedKeyPath = initialKeyPath
        keyPath = initialKeyPath
        viewModelScope.launch {
            val host = hostDao.getById(hostId)
            if (host == null) {
                _state.value = PortForwardPanelState(
                    connectionState = PortForwardConnectionState.Error,
                    error = "Host not found.",
                )
                return@launch
            }
            val resolvedKeyPath = initialKeyPath ?: sshKeyDao.getById(host.keyId)?.privateKeyPath
            keyPath = resolvedKeyPath
            _state.value = PortForwardPanelState(host = host)
            if (host.enabled && resolvedKeyPath != null) {
                setAutoForwardEnabled(true)
            }
        }
    }

    fun leavePanel() {
        stopForwarding()
        _state.value = _state.value.copy(
            autoForwardEnabled = false,
            connectionState = PortForwardConnectionState.Idle,
            tunnels = emptyList(),
            error = null,
        )
    }

    fun setAutoForwardEnabled(enabled: Boolean) {
        val host = _state.value.host ?: return
        if (enabled == _state.value.autoForwardEnabled) return
        if (!enabled) {
            stopForwarding()
            _state.value = _state.value.copy(
                autoForwardEnabled = false,
                connectionState = PortForwardConnectionState.Idle,
                tunnels = emptyList(),
                error = null,
            )
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

        _state.value = _state.value.copy(
            autoForwardEnabled = true,
            connectionState = PortForwardConnectionState.Connecting,
            error = null,
        )
        viewModelScope.launch {
            val connected = connector.connect(host, resolvedKeyPath)
            val sshSession = connected.getOrElse { failure ->
                _state.value = _state.value.copy(
                    autoForwardEnabled = false,
                    connectionState = PortForwardConnectionState.Error,
                    error = failure.message ?: "SSH connection failed.",
                )
                return@launch
            }
            if (!_state.value.autoForwardEnabled) {
                sshSession.close()
                return@launch
            }

            session = sshSession
            val autoForwarder = AutoForwarder(sshSession, host.toAutoForwardConfig())
            forwarder = autoForwarder
            tunnelCollection?.cancel()
            tunnelCollection = viewModelScope.launch {
                autoForwarder.flowOfTunnels().collect { tunnels ->
                    _state.value = _state.value.copy(tunnels = tunnels)
                }
            }
            autoForwarder.start(viewModelScope)
            _state.value = _state.value.copy(connectionState = PortForwardConnectionState.Connected)
        }
    }

    fun togglePort(remotePort: Int) {
        val autoForwarder = forwarder ?: return
        viewModelScope.launch {
            autoForwarder.togglePort(remotePort)
        }
    }

    override fun onCleared() {
        stopForwarding()
        super.onCleared()
    }

    private fun stopForwarding() {
        tunnelCollection?.cancel()
        tunnelCollection = null
        forwarder?.stop()
        forwarder = null
        session?.close()
        session = null
    }

    private fun HostEntity.toAutoForwardConfig(): AutoForwardConfig =
        AutoForwardConfig(
            scanIntervalSec = scanIntervalSec,
            maxAutoPort = maxAutoPort,
            skipPortsBelow = skipPortsBelow,
        )
}
