package com.pocketshell.app.diagnostics

import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * UI state for the Settings one-shot full connection-journal pull (#1710).
 * The terminal states remain visible until the Settings surface consumes them.
 */
sealed interface ConnectionJournalHostPullState {
    data object Idle : ConnectionJournalHostPullState
    data object Mirroring : ConnectionJournalHostPullState
    data class Succeeded(val remotePath: String) : ConnectionJournalHostPullState
    data object Empty : ConnectionJournalHostPullState
    data object NoWarmSession : ConnectionJournalHostPullState
    data object Failed : ConnectionJournalHostPullState
}

/** Exact user-visible one-shot feedback for a terminal state. */
fun ConnectionJournalHostPullState.feedbackText(): String? = when (this) {
    ConnectionJournalHostPullState.Idle,
    ConnectionJournalHostPullState.Mirroring,
    -> null
    is ConnectionJournalHostPullState.Succeeded ->
        "Connection journal mirrored to `~/$remotePath`"
    ConnectionJournalHostPullState.Empty ->
        "No connection journal recorded yet."
    ConnectionJournalHostPullState.NoWarmSession ->
        "Open a connected session, then try again."
    ConnectionJournalHostPullState.Failed ->
        "Could not mirror connection journal to host."
}

/** Consume one-shot feedback without changing an in-progress operation. */
fun ConnectionJournalHostPullState.consume(): ConnectionJournalHostPullState = when (this) {
    ConnectionJournalHostPullState.Mirroring -> this
    else -> ConnectionJournalHostPullState.Idle
}

/**
 * Coordinates archive rendering, tap-time warm-session validation, and the
 * bounded fixed-path write. It never has a connector or lease manager, so a
 * missing/mismatched snapshot cannot acquire, cold-dial, retry, or retarget.
 */
object ConnectionJournalHostPull {
    const val DEFAULT_TIMEOUT_MS: Long = 20_000L

    suspend fun pull(
        recorder: DiagnosticRecorder,
        expectedLeaseKey: SshLeaseKey?,
        heldLease: SshLease?,
        heldSession: SshSession?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ConnectionJournalHostPullState {
        val jsonl = try {
            recorder.connectionJournalJsonl()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return ConnectionJournalHostPullState.Failed
        }
        if (jsonl.isBlank()) return ConnectionJournalHostPullState.Empty

        val session = heldSession
        if (
            expectedLeaseKey == null ||
            heldLease == null ||
            session == null ||
            heldLease.key != expectedLeaseKey ||
            heldLease.session !== session ||
            !session.isConnected ||
            session.isCloseInitiated
        ) {
            return ConnectionJournalHostPullState.NoWarmSession
        }

        val result = try {
            withTimeout(timeoutMs) {
                ConnectionLogHostMirror.mirrorConnectionJournal(session, jsonl)
            }
        } catch (_: TimeoutCancellationException) {
            return ConnectionJournalHostPullState.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return ConnectionJournalHostPullState.Failed
        }

        return result.fold(
            onSuccess = { remotePath ->
                remotePath?.let(ConnectionJournalHostPullState::Succeeded)
                    ?: ConnectionJournalHostPullState.Empty
            },
            onFailure = { ConnectionJournalHostPullState.Failed },
        )
    }
}
