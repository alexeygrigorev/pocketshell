package com.pocketshell.next.hosts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.next.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What [HostQrShareScreen] renders. */
data class HostQrShareUiState(
    val hostName: String = "",
    val hostSubtitle: String = "",
    /** One entry per QR to display, in order. Empty until the host is read. */
    val parts: List<String> = emptyList(),
    /** Which part is on screen; only meaningful when [parts] has more than one. */
    val index: Int = 0,
    val error: String? = null,
) {
    val current: String? get() = parts.getOrNull(index)
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < parts.lastIndex
}

/**
 * Builds the QR payload for one host (rewrite task P-6).
 *
 * ## What goes on the QR — and what deliberately does not
 *
 * The payload names the host's key by name ([SshImportAuth.KeyReference]); it
 * never contains private key material. This preserves the shipping client's
 * stance and it is the right one for the direction this export runs in: the QR
 * is rendered on a phone screen, in whatever room the phone happens to be in,
 * and anything on it is readable by any camera pointed at it. The desktop
 * emitter (`pocketshell qr-share`) is the path that legitimately carries a key,
 * because bootstrapping a phone that has no key yet is its whole purpose, and
 * `docs/ssh-qr-import.md` tells the user to treat that QR as the secret it is.
 *
 * The payload is always wrapped in the [QrChunkCodec] envelope, even at
 * `part=1/1`, so the scanner has exactly one decoder path.
 */
@HiltViewModel
class HostQrShareViewModel @Inject constructor(
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val hostId: Long = requireNotNull(
        savedStateHandle.get<Long>(Destination.ARG_HOST_ID),
    ) { "HostQrShareViewModel needs a ${Destination.ARG_HOST_ID} argument" }

    private val _state = MutableStateFlow(HostQrShareUiState())
    val state: StateFlow<HostQrShareUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val host = hostDao.getById(hostId)
            if (host == null) {
                _state.value = HostQrShareUiState(error = "That host no longer exists")
                return@launch
            }
            val key = sshKeyDao.getById(host.keyId)
            if (key == null) {
                _state.value = HostQrShareUiState(
                    hostName = host.name,
                    hostSubtitle = "${host.username}@${host.hostname}:${host.port}",
                    error = "Cannot share ${host.name}: its SSH key is missing",
                )
                return@launch
            }
            val payload = SshImportPayloadCodec.encode(
                SshImportConfig(
                    name = host.name,
                    host = host.hostname,
                    port = host.port,
                    username = host.username,
                    auth = SshImportAuth.KeyReference(name = key.name),
                ),
            )
            _state.value = HostQrShareUiState(
                hostName = host.name,
                hostSubtitle = "${host.username}@${host.hostname}:${host.port}",
                parts = QrChunkCodec.encode(payload),
            )
        }
    }

    fun next() {
        val current = _state.value
        if (current.hasNext) _state.value = current.copy(index = current.index + 1)
    }

    fun previous() {
        val current = _state.value
        if (current.hasPrevious) _state.value = current.copy(index = current.index - 1)
    }
}
