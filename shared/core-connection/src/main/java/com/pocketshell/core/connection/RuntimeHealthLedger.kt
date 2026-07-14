package com.pocketshell.core.connection

/**
 * Issue #1537 (option b): the parked-runtime health ledger.
 *
 * ## Why this exists
 *
 * The last unfixed connection-flap class is the **fast-switch stale-lease
 * redial**: a session parked in the runtime cache while another session is
 * foreground is a blind spot — no machine watches its liveness, so a parked
 * runtime's death is only discovered at switch-back, as an attach EOF that
 * forces a visible fresh redial (`stage=stale_lease_auto_recover
 * cause=stale_lease_attach_eof outcome=fast_switch_fresh_redial`).
 *
 * Every liveness signal a parked runtime needs already fires while parked (the
 * parked `TmuxClient`'s `-CC` reader still latches its `disconnected` StateFlow
 * on EOF; the per-transport keepalive still runs; the lease pool still
 * broadcasts per-key `Closed(KeepaliveDead)` edges). What was missing is a
 * subscriber bound while parked plus a small health ledger consulted by the
 * switch path. This is that ledger.
 *
 * ## Shape
 *
 * A pure, single-authority reducer mapping [RuntimeHealthKey] to
 * [RuntimeHealth]. It holds NO IO, NO coroutines, NO android imports and NO
 * time — the app-side `ParkedRuntimeHealthEffects` owns the edge subscriptions
 * and feeds this reducer [RuntimeHealthEvent]s, then the switch path consults
 * it. It is confined to the ViewModel's single main dispatcher (same discipline
 * as [ConnectionController]); it is deliberately not internally synchronised.
 *
 * A [RuntimeHealthEvent.Died] verdict is **sticky** — it survives a plain
 * [RuntimeHealthEvent.Cleared] eviction so an imminent switch-back can still
 * consult it — and is cleared only by re-parking the key ([RuntimeHealthEvent.Parked])
 * or by the one-shot [consumeDead].
 */
public class RuntimeHealthLedger {
    private val states = linkedMapOf<RuntimeHealthKey, RuntimeHealth>()

    /**
     * Apply one [event] and return the resulting [RuntimeHealth] for its key
     * (or `null` when the key is no longer tracked).
     */
    public fun reduce(event: RuntimeHealthEvent): RuntimeHealth? = when (event) {
        is RuntimeHealthEvent.Parked -> {
            // A fresh park always resets to Healthy — even over a stale Dead
            // marker from a previous life of the same session.
            states[event.key] = RuntimeHealth.Healthy
            RuntimeHealth.Healthy
        }
        is RuntimeHealthEvent.Died -> {
            val dead = RuntimeHealth.Dead(event.cause)
            states[event.key] = dead
            dead
        }
        is RuntimeHealthEvent.Cleared -> {
            states.remove(event.key)
            null
        }
    }

    public fun health(key: RuntimeHealthKey): RuntimeHealth? = states[key]

    public fun isHealthy(key: RuntimeHealthKey): Boolean = states[key] is RuntimeHealth.Healthy

    public fun isDead(key: RuntimeHealthKey): Boolean = states[key] is RuntimeHealth.Dead

    public fun deadCause(key: RuntimeHealthKey): RuntimeDeathCause? =
        (states[key] as? RuntimeHealth.Dead)?.cause

    /**
     * One-shot consult: if [key] is currently [RuntimeHealth.Dead], remove the
     * marker and return its cause; otherwise return `null`. The switch path
     * uses this to decide, exactly once, that a parked runtime was known-dead
     * before the switch-back attach so it can present the calm hold instead of
     * discovering the death as an attach EOF.
     */
    public fun consumeDead(key: RuntimeHealthKey): RuntimeDeathCause? {
        val dead = states[key] as? RuntimeHealth.Dead ?: return null
        states.remove(key)
        return dead.cause
    }

    public fun trackedKeys(): Set<RuntimeHealthKey> = states.keys.toSet()

    public fun size(): Int = states.size
}

/**
 * Stable, transport-agnostic identity of a parked runtime the ledger tracks.
 * Keyed on host + tmux session name so it aligns with the runtime cache's
 * per-session eviction grain (a session has exactly one live runtime).
 */
public data class RuntimeHealthKey(
    val hostId: Long,
    val sessionName: String,
)

public sealed interface RuntimeHealth {
    public object Healthy : RuntimeHealth

    public data class Dead(val cause: RuntimeDeathCause) : RuntimeHealth
}

/**
 * How a parked runtime died. The switch path and diagnostics name the cause so
 * a proactive heal is attributable in the connection log instead of surfacing
 * only as an anonymous `stale_lease_attach_eof`.
 */
public enum class RuntimeDeathCause {
    /**
     * The parked client's own `-CC` reader latched `disconnected` — covers the
     * control channel EOF AND tmux-server death (the login-scope teardown
     * class), instantly and with zero new traffic.
     */
    ClientDisconnected,

    /**
     * The pool declared the parked lease's transport dead via the always-on
     * keepalive watchdog (`Closed(KeepaliveDead)`) — covers a silent transport
     * death within the keepalive's bound.
     */
    KeepaliveDead,

    /** The pool closed the parked lease for any other reason. */
    LeaseClosed,

    /**
     * The residual race (spike test v): a health-vouched parked lease was
     * actually a silent corpse and only revealed itself as an attach EOF at
     * switch-back. Recorded when the calm single-ladder fallback fires so the
     * silent-death window is observable.
     */
    AttachEof,
}

public sealed interface RuntimeHealthEvent {
    public val key: RuntimeHealthKey

    /** A runtime was parked into the cache; begin tracking it as Healthy. */
    public data class Parked(override val key: RuntimeHealthKey) : RuntimeHealthEvent

    /** A liveness edge declared the parked runtime dead. */
    public data class Died(
        override val key: RuntimeHealthKey,
        val cause: RuntimeDeathCause,
    ) : RuntimeHealthEvent

    /**
     * The runtime left the cache without a detected death (activated, expired,
     * overflowed, or explicitly evicted); stop tracking it. A sticky [Died]
     * marker is preserved by the effects layer rather than sending this.
     */
    public data class Cleared(override val key: RuntimeHealthKey) : RuntimeHealthEvent
}
