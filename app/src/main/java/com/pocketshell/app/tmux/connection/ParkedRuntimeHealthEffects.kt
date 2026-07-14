package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.RuntimeDeathCause
import com.pocketshell.core.connection.RuntimeHealthEvent
import com.pocketshell.core.connection.RuntimeHealthKey
import com.pocketshell.core.connection.RuntimeHealthLedger
import com.pocketshell.core.ssh.SshLeaseCloseReason
import com.pocketshell.core.ssh.SshLeaseConnectionState
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseStateEvent
import com.pocketshell.core.tmux.TmuxClient
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
 *  - the parked [TmuxClient]'s own `disconnected` StateFlow latches `true` on
 *    the `-CC` reader EOF (control channel death AND tmux-server death), and
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
    private val onDeath: (RuntimeHealthKey, SshLeaseKey?, RuntimeDeathCause) -> Unit,
) {
    private val bindings = mutableMapOf<RuntimeHealthKey, Job>()

    /**
     * Bind the liveness edges for a runtime being parked into the cache. Idempotent
     * per key — a re-park cancels the prior binding and resets the ledger to Healthy.
     */
    fun bindParked(key: RuntimeHealthKey, client: TmuxClient, leaseKey: SshLeaseKey?) {
        cancelBinding(key)
        ledger.reduce(RuntimeHealthEvent.Parked(key))
        bindings[key] = scope.launch {
            coroutineScope {
                launch { awaitClientDeath(key, client) }
                launch { awaitLeaseDeath(key, leaseKey) }
            }
        }
    }

    /**
     * The parked runtime is being ACTIVATED — the live path owns its liveness
     * again. Cancel the binding and drop it from the ledger (a Dead entry would
     * not activate; it fell out via the health probe first).
     */
    fun onActivated(key: RuntimeHealthKey) {
        cancelBinding(key)
        ledger.reduce(RuntimeHealthEvent.Cleared(key))
    }

    /**
     * The parked runtime is leaving the cache WITHOUT a detected death (TTL,
     * host overflow, twin prune, or an explicit evict). Cancel the binding.
     * A sticky [RuntimeDeathCause] verdict is preserved so an imminent
     * switch-back can still consult it; a still-Healthy entry is dropped.
     */
    fun onEvicted(key: RuntimeHealthKey) {
        cancelBinding(key)
        if (!ledger.isDead(key)) ledger.reduce(RuntimeHealthEvent.Cleared(key))
    }

    /**
     * Consult-and-clear: if the parked runtime for [key] was proactively marked
     * dead, return the cause (one-shot) so the switch path can present the calm
     * hold; otherwise `null`.
     */
    fun consumeParkedDeath(key: RuntimeHealthKey): RuntimeDeathCause? = ledger.consumeDead(key)

    fun isTracked(key: RuntimeHealthKey): Boolean = ledger.health(key) != null

    /** Cancel every binding (VM teardown). */
    fun cancelAll() {
        bindings.values.forEach { it.cancel() }
        bindings.clear()
    }

    private fun cancelBinding(key: RuntimeHealthKey) {
        bindings.remove(key)?.cancel()
    }

    private suspend fun awaitClientDeath(key: RuntimeHealthKey, client: TmuxClient) {
        // Suspends until the parked reader latches disconnected (or returns
        // immediately if it already died before we bound). Terminal by nature.
        client.disconnected.first { it }
        fireDeath(key, leaseKeyHint = null, cause = RuntimeDeathCause.ClientDisconnected)
    }

    private suspend fun awaitLeaseDeath(key: RuntimeHealthKey, leaseKey: SshLeaseKey?) {
        if (leaseKey == null) return
        val event = leaseStateEvents.first {
            it.key == leaseKey && it.state == SshLeaseConnectionState.Closed
        }
        val cause = when (event.closeReason) {
            SshLeaseCloseReason.KeepaliveDead -> RuntimeDeathCause.KeepaliveDead
            else -> RuntimeDeathCause.LeaseClosed
        }
        fireDeath(key, leaseKeyHint = leaseKey, cause = cause)
    }

    private fun fireDeath(
        key: RuntimeHealthKey,
        leaseKeyHint: SshLeaseKey?,
        cause: RuntimeDeathCause,
    ) {
        // Idempotent: only the FIRST edge per binding wins.
        if (ledger.isDead(key)) return
        ledger.reduce(RuntimeHealthEvent.Died(key, cause))
        // Terminal: stop both collectors for this key (this cancels the job we
        // are running inside; the non-suspending [onDeath] below still runs).
        cancelBinding(key)
        onDeath(key, leaseKeyHint, cause)
    }
}
