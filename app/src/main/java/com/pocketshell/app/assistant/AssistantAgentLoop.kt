package com.pocketshell.app.assistant

import com.pocketshell.core.assistant.AssistantLlmClient
import com.pocketshell.core.assistant.LlmMessage
import com.pocketshell.core.assistant.LlmToolCall
import com.pocketshell.core.assistant.LlmToolResult
import com.pocketshell.core.assistant.StopReason
import org.json.JSONException
import org.json.JSONObject

/**
 * The provider-agnostic agent loop for the in-app action assistant
 * (issue #266).
 *
 * Given a transcript, it drives a multi-turn tool-calling conversation over
 * an [AssistantLlmClient]:
 *
 *  1. Seed the conversation with a system prompt + the user transcript.
 *  2. Call the model with the full [AssistantTools.ALL] catalog.
 *  3. For each tool call the model makes:
 *     - Inspect / navigation tools auto-run (D25) via [AssistantActions].
 *     - Mutating tools ([AssistantTools.MUTATING_TOOLS]) generate a
 *       candidate, run the [CommandSafety] gate (for `run_command`), then
 *       route through the **confirm-or-correct** [confirmGate]:
 *         * Confirm  → execute the candidate as-is.
 *         * Correct  → the user's correction text is fed back into the loop
 *           as additional context; the model produces a revised candidate
 *           and we re-prompt. Loops until confirmed or cancelled.
 *  4. Feed every tool result back and iterate until the model returns a
 *     final text answer or the step cap is hit.
 *
 * The loop owns no Android / SSH / Hilt types — it talks to [AssistantActions]
 * (action seam), [AssistantTraceSink] (logging seam), and [confirmGate]
 * (UX seam) — so it is fully unit-testable with a fake [AssistantLlmClient]
 * scripting tool calls, including a reject → correct → confirm sequence.
 *
 * @property client the provider-agnostic LLM client (#265).
 * @property actions live action surfaces (nav, FolderListGateway, HostDao, SSH).
 * @property traceSink #270 trace emission (no-op on absent binary).
 * @property installId stable per-install UUID for the trace `install_id`.
 * @property maxSteps hard cap on model turns to avoid runaway loops.
 */
internal class AssistantAgentLoop(
    private val client: AssistantLlmClient,
    private val actions: AssistantActions,
    private val traceSink: AssistantTraceSink = NoOpAssistantTraceSink,
    private val installId: String = "unknown",
    private val sessionId: String? = null,
    private val maxSteps: Int = 12,
) {

    /**
     * The user's response to a confirm-or-correct prompt for a mutating tool.
     */
    sealed interface Decision {
        /** Execute the candidate as-is. */
        data object Confirm : Decision

        /** Re-plan with the supplied [correction] (voice or typed). */
        data class Correct(val correction: String) : Decision

        /** Abandon this tool dispatch (the loop relays a cancellation to the model). */
        data object Cancel : Decision
    }

    /** A candidate mutating action awaiting the user's decision. */
    data class Candidate(
        val toolName: String,
        /** Human-readable one-line summary, e.g. the exact command or path. */
        val summary: String,
    )

    /** Final loop outcome surfaced to the UI. */
    sealed interface Outcome {
        data class Answer(val text: String) : Outcome
        data class Cancelled(val text: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * The confirm-or-correct UX seam. Given a [Candidate], return the user's
     * [Decision]. The implementation surfaces the candidate, asks "Is this
     * what you want me to execute?", and resumes with Confirm / Correct /
     * Cancel. In tests this is a scripted function.
     */
    fun interface ConfirmGate {
        suspend fun decide(candidate: Candidate): Decision
    }

    suspend fun run(transcript: String, confirmGate: ConfirmGate): Outcome {
        val messages = mutableListOf(
            LlmMessage.system(SYSTEM_PROMPT),
            LlmMessage.user(transcript),
        )

        var step = 0
        while (step < maxSteps) {
            step++
            val response = client.complete(
                messages = messages,
                tools = AssistantTools.ALL,
            ).getOrElse { error ->
                return Outcome.Failed(error.message ?: "The assistant request failed.")
            }

            if (!response.hasToolCalls) {
                val text = response.text?.trim().orEmpty()
                return when (response.stopReason) {
                    StopReason.EndTurn, StopReason.Other ->
                        Outcome.Answer(text.ifEmpty { "Done." })
                    StopReason.MaxTokens ->
                        Outcome.Answer(text.ifEmpty { "(response truncated)" })
                    StopReason.ToolUse ->
                        // Provider said tool_use but gave no calls — treat as done.
                        Outcome.Answer(text.ifEmpty { "Done." })
                }
            }

            // Record the assistant's tool-call turn verbatim so the provider
            // can pair each result to its call id on the next request.
            messages += LlmMessage(
                role = LlmMessage.Role.Assistant,
                text = response.text,
                toolCalls = response.toolCalls,
            )

            val results = mutableListOf<LlmToolResult>()
            for (call in response.toolCalls) {
                val outcome = dispatch(call, confirmGate)
                if (outcome is DispatchOutcome.CancelLoop) {
                    return Outcome.Cancelled(outcome.message)
                }
                results += (outcome as DispatchOutcome.Result).toolResult
            }
            messages += LlmMessage.toolResults(results)
        }
        return Outcome.Failed("The assistant reached its step limit before finishing.")
    }

    private sealed interface DispatchOutcome {
        data class Result(val toolResult: LlmToolResult) : DispatchOutcome
        data class CancelLoop(val message: String) : DispatchOutcome
    }

    private suspend fun dispatch(
        call: LlmToolCall,
        confirmGate: ConfirmGate,
    ): DispatchOutcome {
        val args = parseArgs(call.argumentsJson)
        return if (AssistantTools.isMutating(call.name)) {
            dispatchMutating(call, args, confirmGate)
        } else {
            DispatchOutcome.Result(LlmToolResult(call.id, dispatchInspectOrNav(call.name, args)))
        }
    }

    private suspend fun dispatchInspectOrNav(name: String, args: JSONObject): String =
        when (name) {
            AssistantTools.GET_CONTEXT -> trace(name, null, null, emptyMap()) { actions.getContext() }
            AssistantTools.LIST_HOSTS -> trace(name, null, null, emptyMap()) { actions.listHosts() }
            AssistantTools.LIST_FOLDERS -> {
                val host = args.optString("host")
                trace(name, host, null, mapOf("host" to host)) { actions.listFolders(host) }
            }
            AssistantTools.LIST_SESSIONS -> {
                val host = args.optString("host")
                trace(name, host, null, mapOf("host" to host)) { actions.listSessions(host) }
            }
            AssistantTools.LIST_DIRECTORY -> {
                val path = args.optString("path")
                trace(name, null, path, mapOf("path" to path)) { actions.listDirectory(path) }
            }
            AssistantTools.READ_FILE -> {
                val path = args.optString("path")
                trace(name, null, path, mapOf("path" to path)) { actions.readFile(path) }
            }
            AssistantTools.LIST_REPOS -> trace(name, null, null, emptyMap()) { actions.listRepos() }
            AssistantTools.OPEN_FOLDER -> {
                val host = args.optString("host")
                val path = args.optString("path")
                trace(name, host, path, mapOf("host" to host, "path" to path)) {
                    actions.openFolder(host, path)
                }
            }
            AssistantTools.OPEN_SESSION -> {
                val s = args.optString("session_name")
                trace(name, null, null, mapOf("session_name" to s)) { actions.openSession(s) }
            }
            AssistantTools.OPEN_SCREEN -> {
                val d = args.optString("destination")
                trace(name, null, null, mapOf("destination" to d)) { actions.openScreen(d) }
            }
            else -> "Unknown tool: $name"
        }

    private suspend fun dispatchMutating(
        call: LlmToolCall,
        args: JSONObject,
        confirmGate: ConfirmGate,
    ): DispatchOutcome {
        // run_command safety gate runs BEFORE the user ever sees the
        // candidate, so a blocked command is relayed to the model (which can
        // revise) rather than offered for confirmation.
        if (call.name == AssistantTools.RUN_COMMAND) {
            val command = args.optString("command")
            CommandSafety.reject(command)?.let { reason ->
                return DispatchOutcome.Result(
                    LlmToolResult(call.id, reason, isError = true),
                )
            }
        }

        val candidate = candidateFor(call.name, args)
        return when (val decision = confirmGate.decide(candidate)) {
            is Decision.Confirm -> {
                val result = executeMutating(call.name, args)
                val relayed = if (result.ok) result.message else result.message
                DispatchOutcome.Result(
                    LlmToolResult(call.id, relayed, isError = !result.ok),
                )
            }
            is Decision.Correct ->
                // The correction is relayed back to the model as the tool
                // result so the next turn produces a revised candidate. The
                // model re-issues the (corrected) mutating call and we
                // re-prompt — the confirm-or-correct loop, by construction.
                DispatchOutcome.Result(
                    LlmToolResult(
                        call.id,
                        "The user did not confirm. Their correction: ${decision.correction}",
                        isError = false,
                    ),
                )
            is Decision.Cancel ->
                DispatchOutcome.CancelLoop("Cancelled.")
        }
    }

    private suspend fun executeMutating(name: String, args: JSONObject): ActionResult =
        when (name) {
            AssistantTools.RUN_COMMAND -> {
                val command = args.optString("command")
                traceAction(name, null, null, mapOf("command" to command)) {
                    actions.runCommand(command)
                }
            }
            AssistantTools.CREATE_FILE -> {
                val path = args.optString("path")
                val content = args.optString("content")
                // Secret hygiene: never put raw file contents in the trace args.
                traceAction(name, null, path, mapOf("path" to path, "content" to REDACTED)) {
                    actions.createFile(path, content)
                }
            }
            AssistantTools.START_SESSION -> {
                val host = args.optString("host")
                val cwd = args.optString("cwd")
                val agent = args.optString("agent")
                traceAction(name, host, cwd, mapOf("host" to host, "cwd" to cwd, "agent" to agent)) {
                    actions.startSession(host, cwd, agent)
                }
            }
            AssistantTools.CLONE_REPO -> {
                val fullName = args.optString("full_name")
                val folder = args.optString("folder").takeIf { it.isNotBlank() }
                traceAction(
                    name,
                    null,
                    folder,
                    mapOf("full_name" to fullName, "folder" to (folder ?: "")),
                ) {
                    actions.cloneRepo(fullName, folder)
                }
            }
            else -> ActionResult.error("Unknown mutating tool: $name")
        }

    private fun candidateFor(name: String, args: JSONObject): Candidate = when (name) {
        AssistantTools.RUN_COMMAND -> Candidate(name, args.optString("command"))
        AssistantTools.CREATE_FILE -> Candidate(name, "Create file ${args.optString("path")}")
        AssistantTools.START_SESSION -> Candidate(
            name,
            "Start ${args.optString("agent")} session in ${args.optString("cwd")} " +
                "on ${args.optString("host")}",
        )
        AssistantTools.CLONE_REPO -> Candidate(name, "Clone ${args.optString("full_name")}")
        else -> Candidate(name, name)
    }

    /** Trace an inspect/nav tool, returning its text result. */
    private suspend fun trace(
        action: String,
        host: String?,
        cwd: String?,
        args: Map<String, String>,
        block: suspend () -> String,
    ): String {
        val out = block()
        emit(action, host, cwd, args, result = "ok")
        return out
    }

    /** Trace a mutating action, recording ok/error from its [ActionResult]. */
    private suspend fun traceAction(
        action: String,
        host: String?,
        cwd: String?,
        args: Map<String, String>,
        block: suspend () -> ActionResult,
    ): ActionResult {
        val result = block()
        emit(action, host, cwd, args, result = if (result.ok) "ok" else "error")
        return result
    }

    private suspend fun emit(
        action: String,
        host: String?,
        cwd: String?,
        args: Map<String, String>,
        result: String,
    ) {
        traceSink.emit(
            AssistantTraceEvent(
                action = action,
                targetHost = host,
                cwd = cwd,
                args = args,
                result = result,
                installId = installId,
                sessionId = sessionId,
            ),
        )
    }

    private fun parseArgs(json: String): JSONObject = try {
        if (json.isBlank()) JSONObject() else JSONObject(json)
    } catch (_: JSONException) {
        JSONObject()
    }

    companion object {
        const val REDACTED = "<redacted>"

        const val SYSTEM_PROMPT: String =
            "You are PocketShell's in-app action assistant. The user dictates or types a " +
                "request; you inspect app state and perform actions through the provided tools. " +
                "Resolve references like \"this folder\", \"here\", or \"it\" by calling " +
                "get_context FIRST. Prefer inspect tools before acting. Mutating tools " +
                "(run_command, create_file, start_session, clone_repo) are confirmed by the user " +
                "before they run; if the user corrects you, revise the candidate and try again. " +
                "Keep shell commands short and non-interactive. When the task is complete, reply " +
                "with a brief confirmation and stop calling tools."
    }
}
