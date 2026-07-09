package com.pocketshell.core.tmux

private const val BEST_EFFORT_LATE_RESPONSE_DRAIN_MS = 1_000L

internal class TmuxClientDisconnectDiagnostics(
    private val sessionName: String,
    private val commandTimeoutMs: Long,
    private val clientId: Long,
    private val clientHash: Int,
) {
    @Volatile
    private var readerExitIntent: ReaderExitIntent = ReaderExitIntent.Unknown

    // Issue #998: latched when the reader parses a `%exit server exited` before
    // channel EOF, so reader-exit classification reports confirmed server death
    // instead of a generic EOF.
    @Volatile
    private var serverExitedInBand: Boolean = false

    @Volatile
    private var readerExitCommandKind: String? = null

    @Volatile
    private var readerExitTimeoutMode: CommandTimeoutMode? = null

    fun markReaderExitIntent(
        intent: ReaderExitIntent,
        commandKind: String? = null,
        timeoutMode: CommandTimeoutMode? = null,
    ) {
        if (readerExitIntent.priority > intent.priority) return
        readerExitIntent = intent
        readerExitCommandKind = commandKind
        readerExitTimeoutMode = timeoutMode
    }

    fun markServerExitedInBand() {
        serverExitedInBand = true
    }

    fun recordCommandTimeout(
        kind: String,
        timeoutMode: CommandTimeoutMode,
        writeCompleted: Boolean,
    ) {
        TmuxClientDiagnostics.record(
            "tmux_client_command_timeout",
            commonDiagnosticFields() + mapOf(
                "session" to sessionName,
                "commandKind" to kind,
                "timeoutMode" to timeoutMode.logName,
                "timeoutMs" to commandTimeoutMs,
                "writeCompleted" to writeCompleted,
            ),
        )
    }

    fun bestEffortLateResponseDrainMs(): Long =
        BEST_EFFORT_LATE_RESPONSE_DRAIN_MS.coerceAtMost(commandTimeoutMs).coerceAtLeast(1L)

    /**
     * Issue #979: a `FatalClose` command timed out, but the transport-liveness
     * oracle (#986/#964) proves the SSH link is still alive, so we did NOT close
     * the SSH shell — the slow reply rode through.
     */
    fun recordFatalTimeoutRodeThrough(kind: String) {
        TmuxClientDiagnostics.record(
            "tmux_client_fatal_timeout_rode_through",
            commonDiagnosticFields() + mapOf(
                "session" to sessionName,
                "commandKind" to kind,
                "timeoutMs" to commandTimeoutMs,
                "transportProvenAlive" to true,
            ),
        )
    }

    fun classifyReaderExit(source: String, closed: Boolean): ReaderDisconnectCause =
        when {
            readerExitIntent == ReaderExitIntent.CommandTimeout -> ReaderDisconnectCause.CommandTimeout
            readerExitIntent == ReaderExitIntent.DetachOrReplace -> ReaderDisconnectCause.DetachOrReplace
            closed && readerExitIntent == ReaderExitIntent.LocalClose -> ReaderDisconnectCause.LocalClose
            serverExitedInBand -> ReaderDisconnectCause.ServerExited
            source == "read_failure" -> ReaderDisconnectCause.ReadFailure
            source == "eof" -> ReaderDisconnectCause.ReadEof
            closed -> ReaderDisconnectCause.LocalClose
            else -> ReaderDisconnectCause.Unknown
        }

    fun disconnectEventFor(
        cause: ReaderDisconnectCause,
        source: String,
        exceptionClass: String? = null,
        message: String? = null,
    ): TmuxDisconnectEvent =
        TmuxDisconnectEvent(
            reason = disconnectReasonFor(cause),
            source = source,
            intent = readerExitIntent.logValue,
            commandKind = readerExitCommandKind,
            timeoutMode = readerExitTimeoutMode?.logName,
            exceptionClass = exceptionClass,
            message = message,
        )

    fun disconnectReasonFor(cause: ReaderDisconnectCause): TmuxDisconnectReason =
        when (cause) {
            ReaderDisconnectCause.LocalClose -> TmuxDisconnectReason.ExplicitClose
            ReaderDisconnectCause.DetachOrReplace -> TmuxDisconnectReason.ExplicitDetach
            ReaderDisconnectCause.CommandTimeout -> TmuxDisconnectReason.CommandTimeout
            ReaderDisconnectCause.ServerExited -> TmuxDisconnectReason.ServerExited
            ReaderDisconnectCause.ReadEof -> TmuxDisconnectReason.ReaderEof
            ReaderDisconnectCause.ReadFailure -> TmuxDisconnectReason.ReaderException
            ReaderDisconnectCause.Unknown -> TmuxDisconnectReason.Unknown
        }

    fun disconnectPriority(reason: TmuxDisconnectReason): Int =
        when (reason) {
            TmuxDisconnectReason.Unknown -> 0
            TmuxDisconnectReason.ReaderEof -> 1
            TmuxDisconnectReason.ReaderException -> 2
            TmuxDisconnectReason.ExplicitClose -> 3
            TmuxDisconnectReason.ExplicitDetach -> 4
            TmuxDisconnectReason.CommandTimeout -> 5
            // Issue #998: confirmed in-band server death is the most
            // authoritative drop cause.
            TmuxDisconnectReason.ServerExited -> 6
        }

    fun commonDiagnosticFields(): Map<String, Any?> =
        mapOf(
            "clientId" to clientId,
            "clientHash" to clientHash,
        )

    fun readerExitDiagnosticFields(
        source: String,
        cause: ReaderDisconnectCause,
        closed: Boolean,
        connected: Boolean,
        eventBusDroppedEvents: Int,
        exceptionClass: String? = null,
        message: String? = null,
    ): Map<String, Any?> =
        buildMap {
            put("session", sessionName)
            put("source", source)
            put("disconnectCause", cause.logValue)
            put("disconnectReason", disconnectReasonFor(cause).logValue)
            put("intent", readerExitIntent.logValue)
            putAll(commonDiagnosticFields())
            put("closed", closed)
            put("connected", connected)
            put("eventBusDroppedEvents", eventBusDroppedEvents)
            readerExitCommandKind?.let { put("commandKind", it) }
            readerExitTimeoutMode?.let { put("timeoutMode", it.logName) }
            exceptionClass?.let { put("cause", it) }
            message?.let { put("message", it) }
        }
}

internal enum class CommandTimeoutMode {
    FatalClose,
    BestEffortDrain,
    FailOpenDrain,
    ;

    val logName: String
        get() = when (this) {
            FatalClose -> "fatal"
            BestEffortDrain -> "best-effort"
            FailOpenDrain -> "fail-open"
        }
}

internal enum class ReaderExitIntent(
    val logValue: String,
    val priority: Int,
) {
    Unknown("unknown", 0),
    LocalClose("local_close", 1),
    DetachOrReplace("detach_or_replace", 2),
    CommandTimeout("command_timeout", 3),
}

internal enum class ReaderDisconnectCause(val logValue: String) {
    LocalClose("local_close"),
    DetachOrReplace("detach_or_replace"),
    CommandTimeout("command_timeout"),
    // Issue #998: in-band `%exit server exited` arrived before EOF.
    ServerExited("server_exited"),
    ReadEof("read_eof"),
    ReadFailure("read_failure"),
    Unknown("unknown"),
}
