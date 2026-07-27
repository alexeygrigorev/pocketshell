package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.CachedTmuxRuntime
import com.pocketshell.core.connection.RuntimeDeathCause
import com.pocketshell.core.connection.RuntimeHealthEvent
import com.pocketshell.core.connection.RuntimeHealthBinding
import com.pocketshell.core.connection.RuntimeHealthLedger
import com.pocketshell.core.ssh.SshLeaseCloseReason
import com.pocketshell.core.ssh.SshLeaseConnectionState
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseStateEvent
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Issue #1537 (option b): the parked-runtime health subscriber — the ONE piece
 * the single [com.pocketshell.core.connection.ConnectionController] authority was
 * missing.
 *
 * When a same-host session is parked into the runtime cache while another
 * session is foreground, its liveness edges keep firing but nobody listens:
 *
 *  - the parked [TmuxClient]'s typed `disconnectEvent` latches the exact
 *    reason/source/intent of a `-CC` reader exit, and
 *  - the pool broadcasts a per-key `Closed(KeepaliveDead)`/`Closed` edge on
 *    [leaseStateEvents] when the always-on keepalive declares the transport dead.
 *
 * This class binds those edges at park time and, on the first death edge,
 * records the verdict in the single [ledger] and invokes [onDeath] so the VM can
 * evict the corpse from the cache and release its lease ref BEFORE the user
 * switches back — turning a switch-back attach EOF (the visible drop) into a
 * proactive, calm heal.
 *
 * ## Termination (the #1517 virtual-time rule)
 *
 * Each binding is a [scope]-owned job whose two collectors use `first { … }`, so
 * each has a natural terminal condition (the edge, or the binding cancel at
 * activate/evict/death). There is NO unbounded re-arm loop — under virtual time a
 * bound-but-unfired binding is idle (a suspended `first` on a quiet flow), and a
 * fired death cancels its own binding.
 *
 * All state is confined to the ViewModel's single main dispatcher ([scope]); the
 * ledger reduce + [onDeath] fire on that dispatcher, same discipline as the
 * controller.
 */
internal class ParkedRuntimeHealthEffects(
    private val scope: CoroutineScope,
    private val ledger: RuntimeHealthLedger,
    private val leaseStateEvents: SharedFlow<SshLeaseStateEvent>,
    /**
     * Invoked on the FIRST death edge for a parked key: the VM evicts the corpse
     * runtime from the cache and releases its lease ref (and, when the lease key
     * is no longer shared by the active session or a sibling cached runtime,
     * force-disconnects the pooled transport so the switch-back dials fresh
     * instead of reusing a vouched corpse). Never disconnects a transport still
     * shared by the foreground session — that is the same-host residual race the
     * attach-EOF fallback covers.
     */
    private val onDeath: (ParkedRuntimeDeathSignal) -> Boolean,
) {
    private val bindings = mutableMapOf<RuntimeHealthBinding, Job>()

    fun bindParkedRuntime(runtime: CachedTmuxRuntime) {
        bindParked(
            binding = runtime.healthBinding,
            client = runtime.client,
            leaseKey = runtime.lease?.key ?: SshLeaseKey(
                host = runtime.key.hostname,
                port = runtime.key.port,
                user = runtime.key.username,
                credentialId = "${runtime.key.hostId}:${runtime.key.keyPath}",
            ),
        )
    }

    /**
     * Bind the liveness edges for a runtime being parked into the cache. Idempotent
     * per key — a re-park cancels the prior binding and resets the ledger to Healthy.
     */
    fun bindParked(
        binding: RuntimeHealthBinding,
        client: TmuxClient,
        leaseKey: SshLeaseKey?,
    ) {
        cancelBinding(binding)
        ledger.reduce(RuntimeHealthEvent.Parked(binding))
        bindings[binding] = scope.launch {
            coroutineScope {
                launch { awaitClientDeath(binding, client, leaseKey) }
                launch { awaitLeaseDeath(binding, client, leaseKey) }
            }
        }
    }

    /**
     * The parked runtime is being ACTIVATED — the live path owns its liveness
     * again. Cancel the binding and drop it from the ledger (a Dead entry would
     * not activate; it fell out via the health probe first).
     */
    fun onActivated(binding: RuntimeHealthBinding) {
        cancelBinding(binding)
        ledger.reduce(RuntimeHealthEvent.Cleared(binding))
    }

    /**
     * The parked runtime is leaving the cache WITHOUT a detected death (TTL,
     * host overflow, twin prune, or an explicit evict). Cancel the binding.
     * Exact death handling removes its ledger entry after the callback, so an
     * eviction only needs to clear a still-Healthy binding.
     */
    fun onEvicted(binding: RuntimeHealthBinding) {
        cancelBinding(binding)
        ledger.reduce(RuntimeHealthEvent.Cleared(binding))
    }

    fun isTracked(binding: RuntimeHealthBinding): Boolean = ledger.health(binding) != null

    /** Cancel every binding (VM teardown). */
    fun cancelAll() {
        bindings.values.forEach { it.cancel() }
        bindings.clear()
    }

    private fun cancelBinding(binding: RuntimeHealthBinding) {
        bindings.remove(binding)?.cancel()
    }

    private suspend fun awaitClientDeath(
        binding: RuntimeHealthBinding,
        client: TmuxClient,
        leaseKey: SshLeaseKey?,
    ) {
        // The typed event is published before the legacy Boolean latch. Waiting
        // on it preserves the emitter's reason/source/intent and lets the single
        // SelfInflictedClose authority reject our own detach/close.
        val event = client.disconnectEvent.first { it != null }!!
        if (SelfInflictedClose.isSelfInflictedControlChannelClose(event)) {
            ignoreSelfInflicted(binding)
            return
        }
        fireDeath(
            ParkedRuntimeDeathSignal(
                binding = binding,
                leaseKey = leaseKey,
                cause = event.toRuntimeDeathCause(),
                boundClientIdentity = System.identityHashCode(client),
                disconnectEvent = event,
                leaseCloseReason = null,
            ),
        )
    }

    private suspend fun awaitLeaseDeath(
        binding: RuntimeHealthBinding,
        client: TmuxClient,
        leaseKey: SshLeaseKey?,
    ) {
        if (leaseKey == null) return
        val event = leaseStateEvents.first {
            it.key == leaseKey && it.state == SshLeaseConnectionState.Closed
        }
        if (SelfInflictedClose.isSelfInflictedLeaseClose(event.closeReason)) {
            ignoreSelfInflicted(binding)
            return
        }
        val cause = when (event.closeReason) {
            SshLeaseCloseReason.KeepaliveDead -> RuntimeDeathCause.KeepaliveDead
            else -> RuntimeDeathCause.LeaseClosed
        }
        fireDeath(
            ParkedRuntimeDeathSignal(
                binding = binding,
                leaseKey = leaseKey,
                cause = cause,
                boundClientIdentity = System.identityHashCode(client),
                disconnectEvent = null,
                leaseCloseReason = event.closeReason,
            ),
        )
    }

    private fun fireDeath(signal: ParkedRuntimeDeathSignal) {
        // Idempotent: atomically claim/remove this exact binding before
        // publishing the verdict. A concurrently resumed sibling collector
        // then sees no binding and cannot double-dispatch after the ledger is
        // cleared.
        val bindingJob = bindings.remove(signal.binding) ?: return
        bindingJob.cancel()
        ledger.reduce(RuntimeHealthEvent.Died(signal.binding, signal.cause))
        onDeath(signal)
        // The exact corpse has now either been removed or identified as a stale
        // callback. No logical-key consult remains, so retain no tombstone.
        ledger.reduce(RuntimeHealthEvent.Cleared(signal.binding))
    }

    private fun ignoreSelfInflicted(binding: RuntimeHealthBinding) {
        val bindingJob = bindings.remove(binding) ?: return
        bindingJob.cancel()
        ledger.reduce(RuntimeHealthEvent.Cleared(binding))
    }

    private fun TmuxDisconnectEvent.toRuntimeDeathCause(): RuntimeDeathCause =
        when (reason) {
            TmuxDisconnectReason.ReaderEof -> RuntimeDeathCause.ReaderEof
            TmuxDisconnectReason.ReaderException -> RuntimeDeathCause.ReaderException
            TmuxDisconnectReason.ServerExited -> RuntimeDeathCause.ServerExited
            TmuxDisconnectReason.CommandTimeout -> RuntimeDeathCause.CommandTimeout
            TmuxDisconnectReason.Unknown -> RuntimeDeathCause.UnknownControlChannel
            // These are filtered through SelfInflictedClose above. Keeping the
            // exhaustive branches compiler-enforced prevents future enum drift.
            TmuxDisconnectReason.ExplicitClose,
            TmuxDisconnectReason.ExplicitDetach,
            -> error("self-inflicted disconnect reached parked death: $reason")
        }
}

/**
 * Fully attributed death of one exact parked runtime. The handler carries this
 * unchanged into diagnostics and atomic cache removal.
 */
internal data class ParkedRuntimeDeathSignal(
    val binding: RuntimeHealthBinding,
    val leaseKey: SshLeaseKey?,
    val cause: RuntimeDeathCause,
    val boundClientIdentity: Int,
    val disconnectEvent: TmuxDisconnectEvent?,
    val leaseCloseReason: SshLeaseCloseReason?,
)
