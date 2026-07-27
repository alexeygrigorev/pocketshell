package com.pocketshell.core.connection

import java.util.concurrent.atomic.AtomicLong

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
 * A pure, single-authority reducer mapping an exact [RuntimeHealthBinding] to
 * [RuntimeHealth]. It holds NO IO, NO coroutines, NO android imports and NO
 * time — the app-side `ParkedRuntimeHealthEffects` owns the edge subscriptions
 * and feeds this reducer [RuntimeHealthEvent]s, then the switch path consults
 * it. It is confined to the ViewModel's single main dispatcher (same discipline
 * as [ConnectionController]); it is deliberately not internally synchronised.
 *
 * The opaque [RuntimeInstanceToken] is load-bearing: host + session is only a
 * logical address and can be reused immediately by a replacement runtime. An
 * old client's late EOF must never overwrite, clear, or evict the replacement.
 * Every event therefore carries the exact binding allocated with that runtime;
 * there is no host/session-only mutation fallback.
 */
public class RuntimeHealthLedger {
    private val states = linkedMapOf<RuntimeHealthBinding, RuntimeHealth>()

    /**
     * Apply one [event] and return the resulting [RuntimeHealth] for its key
     * (or `null` when the key is no longer tracked).
     */
    public fun reduce(event: RuntimeHealthEvent): RuntimeHealth? = when (event) {
        is RuntimeHealthEvent.Parked -> {
            states[event.binding] = RuntimeHealth.Healthy
            RuntimeHealth.Healthy
        }
        is RuntimeHealthEvent.Died -> {
            val dead = RuntimeHealth.Dead(event.cause)
            states[event.binding] = dead
            dead
        }
        is RuntimeHealthEvent.Cleared -> {
            states.remove(event.binding)
            null
        }
    }

    public fun health(binding: RuntimeHealthBinding): RuntimeHealth? = states[binding]

    public fun isHealthy(binding: RuntimeHealthBinding): Boolean =
        states[binding] is RuntimeHealth.Healthy

    public fun isDead(binding: RuntimeHealthBinding): Boolean =
        states[binding] is RuntimeHealth.Dead

    public fun deadCause(binding: RuntimeHealthBinding): RuntimeDeathCause? =
        (states[binding] as? RuntimeHealth.Dead)?.cause

    /**
     * One-shot exact-binding consult retained for reducer consumers and tests.
     * App-side death handling normally clears the binding after its synchronous
     * exact compare-and-remove callback.
     */
    public fun consumeDead(binding: RuntimeHealthBinding): RuntimeDeathCause? {
        val dead = states[binding] as? RuntimeHealth.Dead ?: return null
        states.remove(binding)
        return dead.cause
    }

    public fun trackedBindings(): Set<RuntimeHealthBinding> = states.keys.toSet()

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

/**
 * Opaque identity of one concrete cached-runtime lifetime.
 *
 * It deliberately exposes equality and a diagnostic string only — callers
 * cannot derive it from host/session or choose a generation. This makes every
 * ledger/binding/cache mutation prove it owns the exact runtime instance.
 */
public class RuntimeInstanceToken private constructor(
    private val sequence: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is RuntimeInstanceToken && sequence == other.sequence

    override fun hashCode(): Int = sequence.hashCode()

    override fun toString(): String = sequence.toString()

    public companion object {
        private val nextSequence = AtomicLong(0L)

        public fun create(): RuntimeInstanceToken =
            RuntimeInstanceToken(nextSequence.incrementAndGet())
    }
}

/** Exact health ownership: logical session address plus one opaque runtime life. */
public data class RuntimeHealthBinding(
    val key: RuntimeHealthKey,
    val token: RuntimeInstanceToken,
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
     * The parked client's typed disconnect event reports a reader EOF.
     */
    ReaderEof,

    /** The parked control-channel reader failed with an exception. */
    ReaderException,

    /** The remote tmux server announced its own exit in-band. */
    ServerExited,

    /** A fatal tmux command timeout closed the parked control channel. */
    CommandTimeout,

    /** A typed control-channel close whose reason remains unattributable. */
    UnknownControlChannel,

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
    public val binding: RuntimeHealthBinding

    /** A runtime was parked into the cache; begin tracking it as Healthy. */
    public data class Parked(
        override val binding: RuntimeHealthBinding,
    ) : RuntimeHealthEvent

    /** A liveness edge declared the parked runtime dead. */
    public data class Died(
        override val binding: RuntimeHealthBinding,
        val cause: RuntimeDeathCause,
    ) : RuntimeHealthEvent

    /**
     * The runtime left the cache without a detected death (activated, expired,
     * overflowed, or explicitly evicted); stop tracking it. A sticky [Died]
     * marker is preserved by the effects layer rather than sending this.
     */
    public data class Cleared(
        override val binding: RuntimeHealthBinding,
    ) : RuntimeHealthEvent
}
