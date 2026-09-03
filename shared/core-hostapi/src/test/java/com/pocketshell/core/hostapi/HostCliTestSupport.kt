package com.pocketshell.core.hostapi

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Drives a `suspend` call to completion on the calling thread.
 *
 * Deliberately not `kotlinx.coroutines.runBlocking`: this module has no
 * coroutines dependency (suspension itself lives in kotlin-stdlib), and adding
 * one just to call a fake that never suspends would put a runtime artifact in
 * the build for a test-harness convenience.
 *
 * If a fake ever DOES suspend, the continuation never resumes and this fails
 * loudly instead of returning a half-built value — the assertion below is the
 * whole reason this is safe.
 */
internal fun <T> runSuspending(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) {
        "the coroutine suspended and never resumed; these tests only drive " +
            "non-suspending fakes"
    }.getOrThrow()
}

/**
 * A [RemoteExec] that records what it was asked to run and answers from a
 * script.
 *
 * Recording matters as much as answering: half of this suite's job is
 * asserting the exact command line the client builds, and the only place that
 * string is observable is here.
 */
internal class RecordingExec(
    private val respond: (String) -> ExecOutcome,
) : RemoteExec {

    val commands: MutableList<String> = mutableListOf()
    val timeouts: MutableList<Long> = mutableListOf()

    /** The single command run, failing if there was not exactly one. */
    val command: String
        get() {
            check(commands.size == 1) { "expected exactly one exec, got ${commands.size}" }
            return commands.single()
        }

    override suspend fun exec(command: String, timeoutMs: Long): ExecOutcome {
        commands += command
        timeouts += timeoutMs
        return respond(command)
    }

    companion object {
        /** Exit 0 with [stdout]. */
        fun ok(stdout: String): RecordingExec =
            RecordingExec { ExecOutcome(exitCode = 0, stdout = stdout, stderr = "", timedOut = false) }

        /** A non-zero exit, optionally with output on either stream. */
        fun exit(code: Int, stdout: String = "", stderr: String = ""): RecordingExec =
            RecordingExec { ExecOutcome(exitCode = code, stdout = stdout, stderr = stderr, timedOut = false) }

        /**
         * The budget elapsed. [ExecOutcome.exitCode] is meaningless in this
         * state, so it is set to a value no shell returns to prove the client
         * never reports it.
         */
        fun timedOut(stdout: String = "", stderr: String = ""): RecordingExec =
            RecordingExec { ExecOutcome(exitCode = -999, stdout = stdout, stderr = stderr, timedOut = true) }

        /** A transport too broken to run anything. */
        fun throwing(error: Exception): RecordingExec = RecordingExec { throw error }
    }
}

/** The [HostCliError] a failed [Result] carries, failing the test if it is not one. */
internal fun Result<*>.hostCliError(): HostCliError {
    val error = exceptionOrNull() ?: error("expected a failure, got success: ${getOrNull()}")
    return error as? HostCliError
        ?: error("expected a HostCliError, got ${error::class.java.name}: ${error.message}")
}
