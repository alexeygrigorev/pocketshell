package com.pocketshell.core.transport

/**
 * Outcome of a one-shot remote command.
 *
 * A non-zero [exitCode] is a normal result, never an exception — callers branch
 * on the code. [timedOut] is true when the wall-clock budget elapsed before the
 * command finished; [stdout]/[stderr] then hold whatever was captured so far
 * and [exitCode] is meaningless.
 */
data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)
