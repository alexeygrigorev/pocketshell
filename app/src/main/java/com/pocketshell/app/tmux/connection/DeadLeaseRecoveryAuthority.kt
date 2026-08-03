package com.pocketshell.app.tmux.connection

import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionTarget
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.tmux.TmuxClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Coroutine-local authority to replace one exact manager-held dead lease. */
internal class DeadLeaseRecoveryAuthority private constructor(private var state: State) {
    private sealed interface State {
        data class Held(val lease: SshLease) : State

        data class Invalidated(
            val priorSession: SshSession,
            val recoveryRecorded: Boolean = false,
        ) : State
    }

    /**
     * Invalidates the exact corpse once, then acquires one identity-different manager-new
     * successor. Later attempts reuse that exact live successor without another refcount bump.
     */
    suspend fun acquireSuccessor(
        manager: SshLeaseManager,
        target: SshLeaseTarget,
        currentLease: () -> SshLease?,
        onDeadLeaseInvalidated: () -> Unit,
        diagnosticHostId: Long,
        diagnosticSessionName: String,
    ): SshLease {
        val invalidated = when (val current = state) {
            is State.Held -> {
                val removed = withContext(NonCancellable) { manager.invalidateDead(current.lease) }
                check(removed) { "proven-dead SSH lease was no longer current" }
                onDeadLeaseInvalidated()
                State.Invalidated(current.lease.session).also { state = it }
            }
            is State.Invalidated -> current
        }
        val pooledSuccessor = currentLease().takeIf { candidate ->
            candidate != null &&
                candidate.key == target.leaseKey &&
                candidate.session !== invalidated.priorSession &&
                manager.isCurrentLiveLease(candidate)
        }
        val acquired = pooledSuccessor ?: manager.acquire(target).getOrThrow()
        if (pooledSuccessor == null) {
            check(acquired.isNewConnection) { "proven-dead recovery reused a pooled SSH transport" }
            check(acquired.session !== invalidated.priorSession) {
                "proven-dead SSH lease was reused during fresh recovery"
            }
        }
        val recordRecovery = pooledSuccessor == null && !invalidated.recoveryRecorded
        if (recordRecovery) {
            state = invalidated.copy(recoveryRecorded = true)
            DiagnosticEvents.record(
                "connection",
                "dead_lease_recovery",
                "hostId" to diagnosticHostId,
                "session" to diagnosticSessionName,
                "invalidatedLease" to true,
                "freshTransport" to acquired.isNewConnection,
                "oldSshSessionHash" to System.identityHashCode(invalidated.priorSession),
                "newSshSessionHash" to System.identityHashCode(acquired.session),
            )
        }
        return acquired
    }

    companion object {
        fun held(lease: SshLease): DeadLeaseRecoveryAuthority =
            DeadLeaseRecoveryAuthority(State.Held(lease))

        /** A new drop owner superseded this cancelled owner but retained its exact corpse. */
        fun shouldHandOffSuccessorCancellation(
            cause: Throwable,
            replacement: TmuxClient?,
            currentClient: TmuxClient?,
            acquiredLease: SshLease?,
            currentLease: SshLease?,
            target: ConnectionTarget,
            activeTarget: ConnectionTarget?,
        ): Boolean =
            cause is CancellationException &&
                replacement != null &&
                currentClient === replacement &&
                replacement.disconnected.value &&
                acquiredLease != null &&
                currentLease === acquiredLease &&
                activeTarget == target
    }
}
