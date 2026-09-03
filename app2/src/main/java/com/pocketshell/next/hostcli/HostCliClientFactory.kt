package com.pocketshell.next.hostcli

import com.pocketshell.core.hostapi.ExecOutcome
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.hostapi.RemoteExec
import com.pocketshell.core.transport.HostConnection

/**
 * Builds a [HostCliClient] over one LIVE [HostConnection].
 *
 * Deliberately a factory rather than a singleton client: `core-hostapi` has no
 * notion of a connection lifetime, and a [HostConnection] is spent the moment
 * its transport is lost. A singleton `HostCliClient` would therefore capture a
 * connection that can die under it, and every screen would need its own
 * liveness check before each call. Asking
 * [com.pocketshell.next.connect.ConnectionsRegistry] for the current connection
 * and wrapping THAT — per call site, per refresh — keeps "which connection is
 * this command running on" answerable at every point.
 *
 * A `fun interface` so the production binding is one lambda in
 * [com.pocketshell.next.di.AppModule] and a test can substitute a client over a
 * scripted connection without a DI graph.
 */
fun interface HostCliClientFactory {
    fun create(connection: HostConnection): HostCliClient
}

/**
 * Adapts a transport [HostConnection] to `core-hostapi`'s [RemoteExec].
 *
 * The two modules deliberately do not depend on each other, so their outcome
 * types are structurally identical and nominally distinct
 * (`core-transport.ExecResult` / `core-hostapi.ExecOutcome`). This is the ONE
 * place the two are bridged; because both are four-field records of the same
 * shapes, a swapped `stdout`/`stderr` here would compile silently and turn every
 * host-CLI parse into "response was not valid JSON". It is therefore mapped by
 * NAME, not positionally, and covered by its own unit test.
 *
 * Nothing is caught here: [RemoteExec]'s contract says a non-zero exit and a
 * timeout are ordinary outcomes (they arrive on the result), and a genuinely
 * broken transport should throw — [HostCliClient] already turns that throw into
 * a `HostCliError.Failed`.
 */
fun HostConnection.asRemoteExec(): RemoteExec = RemoteExec { command, timeoutMs ->
    val result = exec(command = command, timeoutMs = timeoutMs)
    ExecOutcome(
        exitCode = result.exitCode,
        stdout = result.stdout,
        stderr = result.stderr,
        timedOut = result.timedOut,
    )
}
