package com.pocketshell.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.regex.PatternSyntaxException
import java.util.concurrent.TimeUnit

/**
 * Converts a voice Command-mode transcript into reviewable shell commands.
 *
 * The planner deliberately returns data only: callers decide whether to show a
 * review sheet, insert the commands at the prompt, or send them with Enter.
 */
public interface CommandPlannerClient {
    public suspend fun plan(request: CommandPlannerRequest): Result<CommandPlan>
}

public data class CommandPlannerConfig(
    val apiKey: CharArray,
    val model: String = DEFAULT_COMMAND_PLANNER_MODEL,
    val baseUrl: String = DEFAULT_OPENAI_BASE_URL,
)

public data class CommandPlannerRequest(
    val transcript: String,
    val session: CommandPlannerSessionMetadata,
    val safety: CommandPlannerSafetyConstraints = CommandPlannerSafetyConstraints(),
)

public data class CommandPlannerSessionMetadata(
    val hostLabel: String,
    val username: String,
    val currentDirectory: String? = null,
    val projectRoots: List<String> = emptyList(),
    val shellType: String? = null,
)

public data class CommandPlannerSafetyConstraints(
    val requireReviewBeforeExecution: Boolean = true,
    val allowAutoSend: Boolean = false,
    val maxCommands: Int = 5,
    val forbiddenPatterns: List<String> = DEFAULT_FORBIDDEN_COMMAND_PATTERNS,
)

public data class CommandPlan(
    val commands: List<PlannedCommand>,
) {
    public fun asTerminalPayload(sendWithEnter: Boolean): String {
        val body = commands.joinToString("\n") { it.command }
        return if (sendWithEnter) body + "\n" else body
    }
}

public data class PlannedCommand(
    val command: String,
    val rationale: String? = null,
)

public sealed class CommandPlannerException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    public class Rejected(message: String) : CommandPlannerException(message)
    public class Auth(message: String) : CommandPlannerException(message)
    public class RateLimited(message: String, val retryAfterSeconds: Long? = null) :
        CommandPlannerException(message)
    public class Server(message: String, val statusCode: Int) : CommandPlannerException(message)
    public class Transport(message: String, cause: Throwable? = null) : CommandPlannerException(message, cause)
    public class Parse(message: String, cause: Throwable? = null) : CommandPlannerException(message, cause)
}

public class OkHttpOpenAiCommandPlannerClient(
    config: CommandPlannerConfig,
    private val client: OkHttpClient = defaultCommandPlannerHttpClient(),
) : CommandPlannerClient {

    private val apiKey: CharArray = config.apiKey.copyOf()
    private val model: String = config.model
    private val baseUrl: String = config.baseUrl

    override suspend fun plan(request: CommandPlannerRequest): Result<CommandPlan> =
        withContext(Dispatchers.IO) {
            val body = buildOpenAiCommandPlannerRequest(request, model)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            val authHeader = StringBuilder("Bearer ").append(apiKey).toString()
            val httpRequest = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", authHeader)
                .post(body)
                .build()

            try {
                client.newCall(httpRequest).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            classifyCommandPlannerHttpFailure(
                                code = response.code,
                                retryAfter = response.header("Retry-After"),
                                body = responseBody,
                            ),
                        )
                    }
                    val content = extractChatCompletionContent(responseBody)
                        ?: return@withContext Result.failure(
                            CommandPlannerException.Parse(
                                "Command planner response did not contain message content",
                            ),
                        )
                    return@withContext parseCommandPlannerContent(content, request.safety)
                }
            } catch (io: IOException) {
                Result.failure(CommandPlannerException.Transport(io.message ?: "Transport failure", io))
            }
        }

    public companion object {
        public fun defaultCommandPlannerHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

public fun buildOpenAiCommandPlannerRequest(
    request: CommandPlannerRequest,
    model: String,
): JSONObject {
    val root = JSONObject()
        .put("model", model)
        .put("temperature", 0)
        .put("response_format", JSONObject().put("type", "json_object"))
    val messages = JSONArray()
        .put(
            JSONObject()
                .put("role", "system")
                .put("content", COMMAND_PLANNER_SYSTEM_PROMPT),
        )
        .put(
            JSONObject()
                .put("role", "user")
                .put("content", buildCommandPlannerUserContent(request).toString()),
        )
    return root.put("messages", messages)
}

public fun buildCommandPlannerUserContent(request: CommandPlannerRequest): JSONObject =
    JSONObject()
        .put("transcript", request.transcript)
        .put(
            "session",
            JSONObject()
                .put("host_label", request.session.hostLabel)
                .put("username", request.session.username)
                .put("current_directory", request.session.currentDirectory ?: JSONObject.NULL)
                .put("project_roots", JSONArray(request.session.projectRoots))
                .put("shell_type", request.session.shellType ?: JSONObject.NULL),
        )
        .put(
            "safety_constraints",
            JSONObject()
                .put("require_review_before_execution", request.safety.requireReviewBeforeExecution)
                .put("allow_auto_send", request.safety.allowAutoSend)
                .put("max_commands", request.safety.maxCommands)
                .put("forbidden_patterns", JSONArray(request.safety.forbiddenPatterns)),
        )

public fun parseCommandPlannerContent(
    content: String,
    safety: CommandPlannerSafetyConstraints = CommandPlannerSafetyConstraints(),
): Result<CommandPlan> {
    val root = try {
        JSONObject(content)
    } catch (json: JSONException) {
        return Result.failure(CommandPlannerException.Parse("Command planner returned malformed JSON", json))
    }

    val error = root.opt("error")
    if (error != null && error != JSONObject.NULL) {
        val message = when (error) {
            is JSONObject -> error.optString("message", "Planner rejected the transcript")
            else -> error.toString()
        }
        return Result.failure(CommandPlannerException.Rejected(message))
    }

    val commandsJson = root.optJSONArray("commands")
        ?: return Result.failure(CommandPlannerException.Parse("Command planner JSON did not contain commands"))
    if (commandsJson.length() == 0) {
        return Result.failure(CommandPlannerException.Rejected("Command planner returned no commands"))
    }
    if (commandsJson.length() > safety.maxCommands) {
        return Result.failure(
            CommandPlannerException.Rejected(
                "Command planner returned ${commandsJson.length()} commands; maximum is ${safety.maxCommands}",
            ),
        )
    }

    val compiledForbiddenPatterns = compileForbiddenPatterns(safety)
    if (compiledForbiddenPatterns.isFailure) return Result.failure(compiledForbiddenPatterns.exceptionOrNull()!!)
    val forbiddenPatterns = compiledForbiddenPatterns.getOrThrow()
    val commands = mutableListOf<PlannedCommand>()
    for (i in 0 until commandsJson.length()) {
        val item = commandsJson.optJSONObject(i)
            ?: return Result.failure(CommandPlannerException.Parse("Command at index $i was not an object"))
        val command = item.optString("command").trim()
        val rejection = validatePlannedCommand(command, forbiddenPatterns)
        if (rejection != null) return Result.failure(CommandPlannerException.Rejected(rejection))
        commands += PlannedCommand(
            command = command,
            rationale = item.optString("rationale").takeIf { it.isNotBlank() },
        )
    }
    return Result.success(CommandPlan(commands))
}

internal fun extractChatCompletionContent(responseBody: String): String? = try {
    JSONObject(responseBody)
        .optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
        ?.optString("content")
        ?.takeIf { it.isNotBlank() }
} catch (_: JSONException) {
    null
}

internal fun validatePlannedCommand(
    command: String,
    forbiddenPatterns: List<CompiledForbiddenPattern>,
): String? {
    if (command.isBlank()) return "Command planner returned an empty command"
    if (command.length > MAX_COMMAND_LENGTH) return "Command planner returned an overlong command"
    if (command.any { it == '\u0000' || it == '\r' || it == '\n' }) {
        return "Command planner returned a command containing control characters"
    }
    val normalized = command.lowercase()
    val matched = forbiddenPatterns.firstOrNull { pattern ->
        pattern.regex.containsMatchIn(normalized)
    }
    return matched?.let { "Command planner returned an unsafe command matching `${it.source}`" }
}

internal data class CompiledForbiddenPattern(
    val source: String,
    val regex: Regex,
)

internal fun compileForbiddenPatterns(
    safety: CommandPlannerSafetyConstraints,
): Result<List<CompiledForbiddenPattern>> {
    val compiled = mutableListOf<CompiledForbiddenPattern>()
    for (pattern in safety.forbiddenPatterns) {
        try {
            compiled += CompiledForbiddenPattern(
                source = pattern,
                regex = Regex(pattern, RegexOption.IGNORE_CASE),
            )
        } catch (syntax: PatternSyntaxException) {
            return Result.failure(
                CommandPlannerException.Rejected(
                    "Command planner safety configuration contains invalid forbidden pattern `$pattern`",
                ),
            )
        } catch (illegal: IllegalArgumentException) {
            return Result.failure(
                CommandPlannerException.Rejected(
                    "Command planner safety configuration contains invalid forbidden pattern `$pattern`",
                ),
            )
        }
    }
    return Result.success(compiled)
}

internal fun classifyCommandPlannerHttpFailure(
    code: Int,
    retryAfter: String?,
    body: String,
): CommandPlannerException = when (code) {
    401, 403 -> CommandPlannerException.Auth("Command planner rejected credentials (HTTP $code): ${body.take(200)}")
    429 -> CommandPlannerException.RateLimited(
        message = "Command planner rate limit hit (HTTP 429): ${body.take(200)}",
        retryAfterSeconds = retryAfter?.toLongOrNull(),
    )
    in 500..599 -> CommandPlannerException.Server(
        message = "Command planner server error (HTTP $code): ${body.take(200)}",
        statusCode = code,
    )
    else -> CommandPlannerException.Transport("Command planner returned unexpected HTTP $code: ${body.take(200)}")
}

private const val MAX_COMMAND_LENGTH: Int = 500
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

public const val DEFAULT_OPENAI_BASE_URL: String = "https://api.openai.com/v1"
public const val DEFAULT_COMMAND_PLANNER_MODEL: String = "gpt-5.4-mini"

public val DEFAULT_FORBIDDEN_COMMAND_PATTERNS: List<String> = listOf(
    """(^|[;&|]\s*)sudo\b""",
    """(^|[;&|]\s*)su\b""",
    """(^|[;&|]\s*)rm\s+-[^\n;]*[rf][^\n;]*[rf]""",
    """(^|[;&|]\s*)shutdown\b""",
    """(^|[;&|]\s*)reboot\b""",
    """(^|[;&|]\s*)halt\b""",
    """(^|[;&|]\s*)mkfs(\.|$|\s)""",
    """(^|[;&|]\s*)dd\s+""",
    """>\s*/dev/(sd|nvme|mapper/)""",
)

private const val COMMAND_PLANNER_SYSTEM_PROMPT: String =
    "You convert voice transcripts into a JSON command plan for a Unix shell. " +
        "Return only JSON with either {\"commands\":[{\"command\":\"...\",\"rationale\":\"...\"}]} " +
        "or {\"error\":{\"message\":\"...\"}}. Do not include prose. Produce short, non-interactive commands. " +
        "Respect the supplied session metadata and safety constraints. Never include newlines inside a command."
