package com.pocketshell.app.assistant

import com.pocketshell.core.assistant.AssistantLlmClient
import com.pocketshell.core.assistant.AssistantLlmException
import com.pocketshell.core.assistant.LlmMessage
import com.pocketshell.core.assistant.LlmResponse
import com.pocketshell.core.assistant.LlmToolCall
import com.pocketshell.core.assistant.LlmToolResult
import com.pocketshell.core.assistant.StopReason
import com.pocketshell.core.assistant.ToolSpec
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
 * @property modelTurnTimeoutMs per-model-call timeout; a hung provider must
 *   fail this run instead of leaving the UI in Thinking forever.
 */
internal class AssistantAgentLoop(
    private val client: AssistantLlmClient,
    private val actions: AssistantActions,
    private val traceSink: AssistantTraceSink = NoOpAssistantTraceSink,
    private val installId: String = "unknown",
    private val sessionId: String? = null,
    private val maxSteps: Int = 12,
    private val modelTurnTimeoutMs: Long = DEFAULT_MODEL_TURN_TIMEOUT_MS,
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

    /**
     * The user's response to a folder-disambiguation prompt: either they
     * picked one of the offered folders, or they backed out.
     */
    sealed interface ChoiceDecision {
        /** The user picked [candidate]; its cwd is fed back to the model. */
        data class Pick(val candidate: FolderCandidate) : ChoiceDecision

        /** The user dismissed the chooser; the loop relays a cancellation. */
        data object Cancel : ChoiceDecision
    }

    /**
     * The "which folder?" UX seam, sibling to [ConfirmGate]. Given the fuzzy
     * [query] and the ambiguous [candidates] (all real, drawn from the known
     * folder set), surface a chooser and return the user's [ChoiceDecision].
     * Deterministic: the picked cwd is relayed straight back to the model with
     * no extra round-trip, so the model cannot re-guess the wrong folder.
     */
    fun interface ChoiceGate {
        suspend fun choose(query: String, candidates: List<FolderCandidate>): ChoiceDecision
    }

    /** Final loop outcome surfaced to the UI. */
    sealed interface Outcome {
        data class Answer(val text: String) : Outcome
        data class Cancelled(val text: String) : Outcome
        data class Failed(val message: String) : Outcome
        data class RetryableError(
            val reason: AssistantFailureReason,
            val message: String,
        ) : Outcome
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

    suspend fun run(
        transcript: String,
        choiceGate: ChoiceGate = ChoiceGate { _, _ -> ChoiceDecision.Cancel },
        confirmGate: ConfirmGate,
    ): Outcome {
        val messages = mutableListOf(
            LlmMessage.system(SYSTEM_PROMPT),
            LlmMessage.user(transcript),
        )

        var step = 0
        while (step < maxSteps) {
            step++
            val response = runModelTurn(
                messages = messages,
                tools = AssistantTools.ALL,
            ).getOrElse { error ->
                return when (val reason = error.retryableReason()) {
                    null -> Outcome.Failed(error.message ?: "The assistant request failed.")
                    else -> Outcome.RetryableError(reason, error.retryableMessage(reason))
                }
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
            var corrected = false
            for ((index, call) in response.toolCalls.withIndex()) {
                val outcome = dispatch(call, confirmGate, choiceGate)
                if (outcome is DispatchOutcome.CancelLoop) {
                    return Outcome.Cancelled(outcome.message)
                }
                results += (outcome as DispatchOutcome.Result).toolResult
                if (outcome.replan) {
                    corrected = true
                    response.toolCalls.drop(index + 1).forEach { skipped ->
                        results += LlmToolResult(
                            skipped.id,
                            "Not executed because the user corrected an earlier tool call. " +
                                "Revise the plan and issue fresh tool calls.",
                            isError = true,
                        )
                    }
                    break
                }
            }
            messages += LlmMessage.toolResults(results)
            if (corrected) {
                continue
            }
        }
        return Outcome.Failed("The assistant reached its step limit before finishing.")
    }

    private suspend fun runModelTurn(
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
    ): Result<LlmResponse> = try {
        withTimeout(modelTurnTimeoutMs) {
            client.complete(
                messages = messages,
                tools = tools,
            )
        }
    } catch (timeout: TimeoutCancellationException) {
        Result.failure(AssistantModelTurnException(AssistantFailureReason.ModelTimeout, timeout))
    }

    private sealed interface DispatchOutcome {
        data class Result(
            val toolResult: LlmToolResult,
            val replan: Boolean = false,
        ) : DispatchOutcome
        data class CancelLoop(val message: String) : DispatchOutcome
    }

    private suspend fun dispatch(
        call: LlmToolCall,
        confirmGate: ConfirmGate,
        choiceGate: ChoiceGate,
    ): DispatchOutcome {
        val args = parseArgs(call.argumentsJson)
        return when {
            AssistantTools.isMutating(call.name) -> dispatchMutating(call, args, confirmGate)
            call.name == AssistantTools.RESOLVE_FOLDER -> dispatchResolveFolder(call, args, choiceGate)
            else -> DispatchOutcome.Result(LlmToolResult(call.id, dispatchInspectOrNav(call.name, args)))
        }
    }

    /**
     * `resolve_folder` is read-only (it never mutates remote state), but it
     * gets bespoke dispatch because the AMBIGUOUS band triggers a clarifying
     * turn: we suspend on the [ChoiceGate], and the user's pick is relayed
     * deterministically back to the model as the tool result so the model can
     * immediately call start_session with the real cwd. Confident and no-match
     * resolutions are summarised as text the same way other inspect tools are.
     */
    private suspend fun dispatchResolveFolder(
        call: LlmToolCall,
        args: JSONObject,
        choiceGate: ChoiceGate,
    ): DispatchOutcome {
        val host = args.optString("host")
        val query = args.optString("query")
        val result = actions.resolveFolder(host, query)
        emit(call.name, host, null, mapOf("host" to host, "query" to query), result = "ok")
        return when (result) {
            is FolderResolutionResult.Unavailable ->
                DispatchOutcome.Result(LlmToolResult(call.id, result.message))
            is FolderResolutionResult.Resolved -> when (val resolution = result.resolution) {
                is FolderResolution.Confident ->
                    DispatchOutcome.Result(
                        LlmToolResult(
                            call.id,
                            "Confident match: ${resolution.candidate.label} at " +
                                "${resolution.candidate.path}. Use this cwd in start_session.",
                        ),
                    )
                is FolderResolution.NoMatch -> {
                    val nearest = resolution.nearest
                        .joinToString(", ") { "${it.label} (${it.path})" }
                        .ifBlank { "none" }
                    DispatchOutcome.Result(
                        LlmToolResult(
                            call.id,
                            "No folder matched \"$query\". Nearest folders: $nearest. " +
                                "Tell the user it wasn't found and stop unless they clarify.",
                        ),
                    )
                }
                is FolderResolution.Ambiguous -> dispatchAmbiguous(call, query, resolution, choiceGate)
            }
        }
    }

    private suspend fun dispatchAmbiguous(
        call: LlmToolCall,
        query: String,
        resolution: FolderResolution.Ambiguous,
        choiceGate: ChoiceGate,
    ): DispatchOutcome = when (val decision = choiceGate.choose(query, resolution.candidates)) {
        is ChoiceDecision.Pick ->
            DispatchOutcome.Result(
                LlmToolResult(
                    call.id,
                    "The user chose ${decision.candidate.label} at ${decision.candidate.path}. " +
                        "Use this cwd in start_session.",
                ),
            )
        is ChoiceDecision.Cancel -> DispatchOutcome.CancelLoop("Cancelled.")
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
        if (call.name == AssistantTools.SEND_PROMPT_TO_SESSION) {
            args.put("prompt", normalizeAgentPrompt(args.optString("prompt")))
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
                    replan = true,
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
            AssistantTools.SEND_PROMPT_TO_SESSION -> {
                val sessionName = args.optString("session_name")
                val prompt = args.optString("prompt")
                traceAction(
                    name,
                    null,
                    null,
                    mapOf("session_name" to sessionName, "prompt" to REDACTED),
                ) {
                    actions.sendPromptToSession(sessionName, prompt)
                }
            }
            AssistantTools.CREATE_PROJECT -> {
                val host = args.optString("host")
                val parentPath = args.optString("parent_path")
                val folderName = args.optString("folder_name")
                traceAction(
                    name,
                    host,
                    parentPath,
                    mapOf("host" to host, "parent_path" to parentPath, "folder_name" to folderName),
                ) {
                    actions.createProject(host, parentPath, folderName)
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
        AssistantTools.SEND_PROMPT_TO_SESSION -> Candidate(
            name,
            "Send prompt to ${args.optString("session_name")}: ${args.optString("prompt")}",
        )
        AssistantTools.CREATE_PROJECT -> Candidate(
            name,
            "Create ${args.optString("folder_name")} in ${args.optString("parent_path")} " +
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

    private fun normalizeAgentPrompt(prompt: String): String =
        prompt.replace("эможди", "эмоджи", ignoreCase = true)

    companion object {
        const val DEFAULT_MODEL_TURN_TIMEOUT_MS: Long = 60_000L

        const val REDACTED = "<redacted>"

        const val SYSTEM_PROMPT: String =
            "You are PocketShell's in-app action assistant. The user dictates or types a " +
                "request; you inspect app state and perform actions through the provided tools. " +
                "Resolve references like \"this folder\", \"here\", or \"it\" by calling " +
                "get_context FIRST. When the user names a folder loosely instead of giving an " +
                "absolute path (e.g. \"open Claude in the workshops folder\"), call resolve_folder " +
                "to turn it into an exact cwd before using that project path in any other tool, " +
                "even if get_context lists a likely matching path — never invent or copy a path " +
                "for a spoken project name. If resolve_folder reports a confident match or the " +
                "user has picked one, call start_session with that cwd; if it finds no match, " +
                "tell the user and stop. " +
                "Prefer inspect tools before acting. Mutating tools " +
                "(run_command, create_file, start_session, send_prompt_to_session, " +
                "create_project, clone_repo) are confirmed by the user " +
                "before they run; if the user corrects you, revise the candidate and try again. " +
                "For requests to start an agent in a project and give it a task, produce the " +
                "structured sequence: inspect/resolve the project, start_session with the chosen " +
                "cwd and agent, then send_prompt_to_session with the user's task prompt. " +
                "Treat code-editing tasks that name a project the same way: do not use " +
                "list_directory, read_file, run_command, or create_file to perform the edit " +
                "yourself. Resolve the project, start a coding agent there, and send the user's " +
                "task prompt to that agent. Default to the codex agent when the user does not " +
                "specify an agent. Normalize obvious speech " +
                "recognition typos in the prompt sent to the agent without changing the task. " +
                "Preserve the user's language in prompts sent to agent sessions; do not translate " +
                "or paraphrase them. " +
                "Keep shell commands short and non-interactive. When the task is complete, reply " +
                "with a brief confirmation and stop calling tools."
    }
}

internal enum class AssistantFailureReason(val retryable: Boolean) {
    ModelTimeout(retryable = true),
    ModelTransport(retryable = true),
}

private class AssistantModelTurnException(
    val reason: AssistantFailureReason,
    cause: Throwable,
) : RuntimeException("Assistant model call timed out.", cause)

private fun Throwable.retryableReason(): AssistantFailureReason? = when (this) {
    is AssistantModelTurnException -> reason
    is AssistantLlmException.Transport -> AssistantFailureReason.ModelTransport
    else -> null
}

private fun Throwable.retryableMessage(reason: AssistantFailureReason): String = when (reason) {
    AssistantFailureReason.ModelTimeout ->
        "The assistant model call timed out. Check the network or try again."
    AssistantFailureReason.ModelTransport ->
        message?.takeIf { it.isNotBlank() } ?: "The assistant model transport failed. Try again."
}
