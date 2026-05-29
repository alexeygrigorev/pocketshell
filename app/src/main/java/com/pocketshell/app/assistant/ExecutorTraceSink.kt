package com.pocketshell.app.assistant

/**
 * [AssistantTraceSink] that opens a short-lived SSH session (via the same
 * [AssistantSshExecutor] the actions use) and ships the event through
 * [SshAssistantTraceSink] (issue #266 / #270).
 *
 * Resolves the active host's [AssistantSshParams] lazily on each emit so the
 * trace targets whatever host the assistant is currently acting against. If
 * no params are available (disconnected), the emit is a no-op. The underlying
 * `pocketshell logs ingest` call degrades to a silent no-op when the binary
 * is absent, so logging never fails an action.
 */
internal class ExecutorTraceSink(
    private val executor: AssistantSshExecutor,
    private val params: () -> AssistantSshParams?,
) : AssistantTraceSink {
    override suspend fun emit(event: AssistantTraceEvent) {
        val p = params() ?: return
        executor.withSession(p) { session ->
            SshAssistantTraceSink(session).emit(event)
        }
    }
}
