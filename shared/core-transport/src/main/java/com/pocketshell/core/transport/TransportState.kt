package com.pocketshell.core.transport

/**
 * Lifecycle of one [HostConnection].
 *
 * [Lost] and [Closed] are terminal for a given connection instance: a
 * connection never self-heals. Reconnecting means the factory dials a *new*
 * [HostConnection]; the UI-level connection identity outlives the transport
 * object, the transport object does not resurrect itself.
 */
sealed interface TransportState {
    data object Connecting : TransportState

    data object Connected : TransportState

    /** The transport dropped unexpectedly. [cause] is a human-readable reason. */
    data class Lost(val cause: String) : TransportState

    /**
     * The transport was closed on purpose rather than lost — but WHY it was
     * closed is not one fact, it is two, and they mean opposite things to
     * anything watching a session over this connection. Hence [reason]: see
     * [CloseReason] (issue #2487).
     */
    data class Closed(val reason: CloseReason) : TransportState
}

/**
 * Why a [HostConnection] was closed deliberately (issue #2487).
 *
 * ## Why the transport carries this at all
 *
 * A closed transport tears its channels down exactly the way a dead socket
 * does — no clean exit-status on the PTY — so a screen watching a session
 * cannot tell "the app let go of the link" from "the session is over" by
 * looking at the channel. It used to try, by reading `Closed` alone as
 * "somebody ended this on purpose"; that conflated the two cases below and
 * produced a false "the connection was closed" error over a tmux session that
 * was still perfectly alive on the host, every time a phone spent more than 90
 * seconds in a pocket (issue #2487).
 *
 * The distinction is the transport's own — it knows which of its APIs started
 * the close — so it is recorded here rather than re-derived by guesswork
 * upstream. It is deliberately a property of [TransportState.Closed] and not a
 * separate flag: a consumer that reads the state cannot forget to also ask why.
 */
enum class CloseReason {

    /**
     * Someone asked for this connection to END: a user disconnect action, app
     * teardown, or a test's `ConnectionsRegistry.closeAll()` hygiene. Nothing
     * that was using it should redial — a fresh connection here is one nobody
     * asked for and nothing is watching (issue #2477).
     */
    Requested,

    /**
     * The D21 background grace window elapsed and
     * [HostConnection.scheduleGraceClose]'s deadline dropped the transport to
     * save the battery and the wake lock.
     *
     * The remote is untouched: the tmux session the user was attached to is
     * still running on the host, and the rewrite plan's foreground-return
     * contract says coming back reattaches to it. So this is a RECONNECT case,
     * exactly like a dropped link, and not the end of anything.
     */
    GraceExpired,
}
