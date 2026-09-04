package com.pocketshell.next.connect

import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.core.transport.HostConnectionFactory
import com.pocketshell.core.transport.HostTarget
import com.pocketshell.core.transport.TrustDecision
import com.pocketshell.core.transport.TrustStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/**
 * Scripted [HostConnectionFactory] for [ConnectionsRegistry] tests: hands out
 * `core-transport`'s scripted [FakeHostConnection] instead of dialing sshj, and
 * records enough about each dial to prove the registry never dialed twice.
 *
 * - [dialCount] — total dials.
 * - [peakConcurrentDials] — how many dials were ever in flight at once. The
 *   registry's mutex contract is that this stays 1 even under concurrent
 *   `getOrConnect` calls.
 * - [dialStarts] — one element per dial, sent as the dial ENTERS (before it
 *   parks on [gate]), so a test can wait for "the dial is in flight and holding
 *   the registry mutex" without sleeping.
 * - [gate] — when set, every dial suspends on it, which is how a test pins a
 *   dial open while a second caller arrives.
 * - [presentedFingerprint] — when non-null the factory consults the injected
 *   [TrustStore] the way the real dial site does, so an untrusted key comes
 *   back as [ConnectResult.NeedsTrust] with a retry that re-dials from scratch.
 */
class FakeHostConnectionFactory(
    private val presentedFingerprint: String? = null,
) : HostConnectionFactory {

    private val lock = Any()
    private val dialedTargets = mutableListOf<HostTarget>()
    private val producedConnections = mutableListOf<FakeHostConnection>()
    private var inFlight = 0
    private var peak = 0

    /** Signals each dial's entry. Unlimited so a test never has to be listening. */
    val dialStarts = Channel<HostTarget>(Channel.UNLIMITED)

    /** When non-null, every dial suspends until it completes. */
    var gate: CompletableDeferred<Unit>? = null

    /** When non-null, the next dial returns [ConnectResult.Failed] with this message. */
    var failWith: String? = null

    /**
     * Applied to every [FakeHostConnection] as it is produced, before it is
     * handed to the caller.
     *
     * The registry dials LAZILY — the connection a screen will run commands on
     * does not exist until that screen asks for it — so a test cannot script
     * exec replies up front by holding the object. This hook is where "when the
     * dial happens, this is what the host answers" is expressed (task U-3's
     * session-tree tests script `pocketshell sessions list --json` through it).
     */
    var script: (FakeHostConnection) -> Unit = {}

    val dialCount: Int get() = synchronized(lock) { dialedTargets.size }

    val targets: List<HostTarget> get() = synchronized(lock) { dialedTargets.toList() }

    val connections: List<FakeHostConnection>
        get() = synchronized(lock) { producedConnections.toList() }

    val peakConcurrentDials: Int get() = synchronized(lock) { peak }

    override suspend fun connect(target: HostTarget, trust: TrustStore): ConnectResult {
        synchronized(lock) {
            dialedTargets += target
            inFlight += 1
            peak = maxOf(peak, inFlight)
        }
        dialStarts.trySend(target)
        try {
            gate?.await()

            if (presentedFingerprint != null) {
                val decision = trust.evaluate(target, presentedFingerprint)
                if (decision !is TrustDecision.Trusted) {
                    // Mirrors RealHostConnectionFactory: not a failure, and the
                    // retry re-runs the FULL dial so a recordTrusted() in
                    // between is re-evaluated.
                    return ConnectResult.NeedsTrust(decision) { connect(target, trust) }
                }
            }

            failWith?.let { return ConnectResult.Failed(it, null) }

            val connection = FakeHostConnection(target)
            script(connection)
            synchronized(lock) { producedConnections += connection }
            return ConnectResult.Connected(connection)
        } finally {
            synchronized(lock) { inFlight -= 1 }
        }
    }
}
