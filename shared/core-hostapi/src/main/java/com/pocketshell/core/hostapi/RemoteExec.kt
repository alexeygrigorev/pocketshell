package com.pocketshell.core.hostapi

/**
 * Runs one command on the host and returns its whole outcome.
 *
 * This is the ONLY way `core-hostapi` reaches the host: every verb of the host
 * CLI is "run a string, read stdout". The module deliberately knows nothing
 * about SSH, sessions, or channels — `shared/core-transport` owns those, and
 * the app wires a transport-backed implementation in at composition time.
 *
 * Nothing implements this interface yet: task K-1 is contract-only. The
 * transport-backed implementation and the `HostCliClient` verbs arrive in K-2.
 *
 * Implementations must not throw for a non-zero exit or a timeout — those are
 * ordinary outcomes, reported through [ExecOutcome.exitCode] /
 * [ExecOutcome.timedOut]. Only a genuinely broken transport should throw.
 */
fun interface RemoteExec {
    suspend fun exec(command: String, timeoutMs: Long): ExecOutcome
}
