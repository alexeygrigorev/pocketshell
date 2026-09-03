package com.pocketshell.core.hostapi

/**
 * Outcome of one [RemoteExec.exec] call.
 *
 * Mirrors `core-transport`'s `ExecResult` on purpose but is a separate type:
 * `core-hostapi` must not depend on the transport module (nor the transport on
 * this one), so the host-CLI layer can be unit-tested with a lambda and no SSH
 * stack at all. The adapter that bridges the two is app-level wiring.
 *
 * A non-zero [exitCode] is a normal result, not an error condition — callers
 * branch on it. When [timedOut] is true the budget elapsed first, [stdout] /
 * [stderr] hold whatever was captured, and [exitCode] is meaningless.
 */
data class ExecOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)
