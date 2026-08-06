package com.pocketshell.app.portfwd.service

/**
 * Sole ordering authority for [ForwardingService]'s notification mutations.
 *
 * Snapshot rendering happens off the service thread, while lifecycle commands
 * arrive on the service thread. Every mutation is therefore serialized on one
 * monitor and tagged with the service generation that requested it. Closing a
 * generation fences snapshots that were already computed but had not yet
 * entered the mutation boundary: they are rejected instead of reposting the
 * notification after foreground removal (issue #1933).
 *
 * ## Issue #2006 — a close is fenceable too
 *
 * [close] takes an optional `observerGeneration`. A lifecycle command from the
 * service thread (`ACTION_STOP`, `onDestroy`, a rejected foreground promotion)
 * passes `null` and always closes: the user (or the service itself) asked for
 * the teardown, so it happens.
 *
 * The notification OBSERVER passes the generation it was launched for, and its
 * teardown request is re-validated against BOTH authorities under this monitor:
 *
 *  - the observer's generation must still be the open one (an observer left
 *    over from an already invalidated generation cannot tear down the
 *    generation that superseded it — the mirror of the #1933 stale-publish
 *    fence); AND
 *  - the [ForwardingController] must still be empty. The observer's "no hosts
 *    left" conclusion is computed on the observe dispatcher and can be minutes
 *    — or microseconds — old by the time it reaches this monitor. A forward
 *    that started in between makes that conclusion FALSE, and acting on it
 *    removes the ongoing notification and stops the service while a tunnel is
 *    live (issue #2006). Re-reading the live count here is what lets the
 *    service recover on its own, with no follow-up start required.
 */
internal class ForwardingNotificationMutationAuthority(
    private val record: (ForwardingNotificationOperation) -> Unit,
) {
    private val lock = Any()
    private var generation = 0L
    private var acceptingPublishes = false

    fun open(controllerCount: Int, bootstrap: (Long) -> Unit): Long = synchronized(lock) {
        if (!acceptingPublishes) {
            generation += 1
            acceptingPublishes = true
        }
        record(
            ForwardingNotificationOperation(
                kind = ForwardingNotificationOperation.Kind.OPEN,
                generation = generation,
                controllerCount = controllerCount,
            ),
        )
        bootstrap(generation)
        generation
    }

    fun publish(
        expectedGeneration: Long,
        controllerCount: Int,
        mutation: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (
            !acceptingPublishes ||
            expectedGeneration != generation ||
            controllerCount <= 0
        ) {
            record(
                ForwardingNotificationOperation(
                    kind = ForwardingNotificationOperation.Kind.DROP_STALE,
                    generation = generation,
                    expectedGeneration = expectedGeneration,
                    controllerCount = controllerCount,
                ),
            )
            return@synchronized false
        }
        record(
            ForwardingNotificationOperation(
                kind = ForwardingNotificationOperation.Kind.PUBLISH,
                generation = generation,
                expectedGeneration = expectedGeneration,
                controllerCount = controllerCount,
            ),
        )
        mutation()
        true
    }

    /**
     * Invalidate the current generation and run [removal] under the same
     * monitor.
     *
     * @param controllerCount reads the LIVE active-host count. It is a supplier,
     *   not a value, because it must be sampled inside the monitor: a count
     *   sampled before the close boundary is exactly the stale input that made
     *   #2006 tear a live forward's notification down (and it also made the
     *   recorded diagnostics lie about what the close saw).
     * @param observerGeneration non-null ONLY for the notification observer's
     *   own zero-snapshot teardown, carrying the generation it was launched
     *   for. Such a teardown is rejected when its generation has been
     *   superseded, or when the controller is no longer empty. Lifecycle
     *   commands pass null and always tear down.
     * @return the generation that was invalidated, or null when the close was
     *   rejected. The caller uses it to fence its own follow-up cleanup on the
     *   same generation.
     */
    fun close(
        controllerCount: () -> Int,
        reason: String,
        observerGeneration: Long? = null,
        removal: (Long) -> Unit,
    ): Long? = synchronized(lock) {
        val liveControllerCount = controllerCount()
        if (observerGeneration != null) {
            val rejection = when {
                !acceptingPublishes || observerGeneration != generation -> "stale_generation"
                liveControllerCount > 0 -> "live_forward"
                else -> null
            }
            if (rejection != null) {
                record(
                    ForwardingNotificationOperation(
                        kind = ForwardingNotificationOperation.Kind.DROP_STALE,
                        generation = generation,
                        expectedGeneration = observerGeneration,
                        controllerCount = liveControllerCount,
                        reason = "$reason:$rejection",
                    ),
                )
                return@synchronized null
            }
        }
        val closedGeneration = generation
        generation += 1
        acceptingPublishes = false
        record(
            ForwardingNotificationOperation(
                kind = ForwardingNotificationOperation.Kind.INVALIDATE,
                generation = generation,
                controllerCount = liveControllerCount,
                reason = reason,
            ),
        )
        removal(generation)
        closedGeneration
    }
}

internal data class ForwardingNotificationOperation(
    val kind: Kind,
    val generation: Long,
    val expectedGeneration: Long? = null,
    val controllerCount: Int,
    val reason: String? = null,
) {
    enum class Kind {
        OPEN,
        PUBLISH,
        DROP_STALE,
        INVALIDATE,
        REMOVE,
        SERVICE_STOP,
    }
}
