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

    fun close(
        controllerCount: Int,
        reason: String,
        removal: (Long) -> Unit,
    ) = synchronized(lock) {
        generation += 1
        acceptingPublishes = false
        record(
            ForwardingNotificationOperation(
                kind = ForwardingNotificationOperation.Kind.INVALIDATE,
                generation = generation,
                controllerCount = controllerCount,
                reason = reason,
            ),
        )
        removal(generation)
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
