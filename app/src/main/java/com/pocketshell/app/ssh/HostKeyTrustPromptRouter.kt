package com.pocketshell.app.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process owner for host-key failures raised below individual feature screens.
 *
 * The authoritative lease connector reports only typed verification failures.
 * The app navigator consumes the retained host id and opens the shared explicit
 * Trust/Replace screen, so tmux, folders, forwarding, usage, and other pooled
 * callers cannot degrade a changed key into a generic retry-only error.
 */
@Singleton
internal class HostKeyTrustPromptRouter @Inject constructor() {
    private val _pendingHostId = MutableStateFlow<Long?>(null)
    val pendingHostId: StateFlow<Long?> = _pendingHostId.asStateFlow()

    fun report(hostId: Long?, failure: Throwable) {
        if (hostId == null || failure.findHostKeyVerificationFailure() == null) return
        _pendingHostId.value = hostId
    }

    fun consume(hostId: Long) {
        _pendingHostId.compareAndSet(hostId, null)
    }
}
