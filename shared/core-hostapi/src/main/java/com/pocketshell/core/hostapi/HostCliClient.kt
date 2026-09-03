package com.pocketshell.core.hostapi

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * Result of `pocketshell sessions create --json`.
 *
 * [created] is `false` when the session already existed: the host CLI's create
 * is idempotent, and "already there" is a SUCCESS, not an error. A caller that
 * treats `created == false` as a failure re-breaks the reconnect story the
 * idempotency exists for.
 *
 * [id] is the aplexer session id, and is `null` for a tmux-managed session
 * (tmux has no id beyond the name).
 */
data class CreatedSession(
    val name: String,
    val manager: Backend,
    val id: String?,
    val created: Boolean,
)

/**
 * Quotes [s] as a single shell word using single quotes.
 *
 * Single quotes are the only shell quoting with no escape processing at all
 * inside them, so this is total: `$`, backticks, backslashes, newlines,
 * spaces and unicode all survive byte-for-byte. The one character that cannot
 * appear inside a single-quoted string is `'` itself, handled the standard
 * way — close the quote, emit an escaped `'`, reopen: `'\''`.
 *
 * An empty string becomes `''`, which is a real empty argument rather than
 * nothing at all (dropping it would silently shift every later argument).
 *
 * Public because it is the load-bearing half of [HostCliClient.attachCommand]
 * and is tested directly.
 */
fun shellSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Every host-CLI verb the app needs, over one [RemoteExec].
 *
 * Two kinds of method live here, and the difference is deliberate:
 *
 * - `listX` / `createSession` **run** a command and parse its JSON. One call
 *   is one exec — no retry, no polling, no caching. The caller owns cadence
 *   and owns what to do with a failure.
 * - `attachCommand` **builds** a command string and runs nothing. It takes
 *   over its channel (attach becomes the session), so it belongs on a PTY
 *   channel the caller opens, not on the request/response [RemoteExec] seam.
 *   It is prefixed with `exec ` so the wrapping shell is REPLACED by the
 *   process: signals and window-size changes then land on the session itself
 *   rather than on a middleman that would eat them.
 *
 * The commands are plain strings rather than argv lists because that is what
 * an SSH channel takes — the remote end runs them through a shell. Every
 * user-controlled argument therefore goes through [shellSingleQuote], and the
 * option list is terminated with `--` so a session literally named `--help`
 * is still a session name.
 *
 * Failure is always a `Result.failure(`[HostCliError]`)`, never a thrown
 * exception: [HostCliError.Failed] for a non-zero exit / timeout / dead
 * transport, [HostCliError.TooOld] for a host CLI below schema
 * [SessionsJson.REQUIRED_SCHEMA], [HostCliError.Malformed] for anything
 * unreadable.
 */
class HostCliClient(
    private val exec: RemoteExec,
    private val binary: String = "pocketshell",
) {

    // --- verbs that run ---------------------------------------------------

    /** `pocketshell sessions list --json` (schema 2). */
    suspend fun listSessions(): Result<SessionsListing> {
        val command = "$binary sessions list --json"
        val stdout = captureJson(command, LIST_TIMEOUT_MS).getOrElse { return Result.failure(it) }
        return SessionsJson.parseSessionsList(stdout)
    }

    /**
     * `pocketshell sessions create --json` — creates a DETACHED session.
     *
     * [name] is required, matching the host CLI (`sessions create NAME`): the
     * host derives nothing from [cwd], so a phone that passed no name would
     * get a usage error, not a generated one. [cwd] is optional for the same
     * reason — `--cwd` is an option there, and omitting it lets the host's own
     * default working directory apply.
     *
     * [engine] additionally asks the HOST to start that agent in the new
     * session (server-side `send-keys`), so the phone never types a launch
     * line; [profile] selects a named host profile for it (see
     * [listProfiles]).
     */
    suspend fun createSession(
        name: String,
        cwd: String? = null,
        engine: String? = null,
        profile: String? = null,
    ): Result<CreatedSession> {
        val command = buildString {
            append(binary).append(" sessions create --json")
            if (cwd != null) append(" --cwd ").append(shellSingleQuote(cwd))
            if (engine != null) append(" --engine ").append(shellSingleQuote(engine))
            if (profile != null) append(" --profile ").append(shellSingleQuote(profile))
            append(" -- ").append(shellSingleQuote(name))
        }
        val outcome = capture(command, CREATE_TIMEOUT_MS).getOrElse { return Result.failure(it) }
        return parseCreate(command, outcome)
    }

    /** `pocketshell engines list --json`. */
    suspend fun listEngines(): Result<List<EngineInfo>> {
        val command = "$binary engines list --json"
        val stdout = captureJson(command, LIST_TIMEOUT_MS).getOrElse { return Result.failure(it) }
        return EnginesJson.parseEnginesList(stdout)
    }

    /** `pocketshell profiles list --json`. */
    suspend fun listProfiles(): Result<List<ProfileInfo>> {
        val command = "$binary profiles list --json"
        val stdout = captureJson(command, LIST_TIMEOUT_MS).getOrElse { return Result.failure(it) }
        return ProfilesJson.parseProfilesList(stdout)
    }

    // --- verbs that only build a command ----------------------------------

    /**
     * The command that BECOMES the session [name], to run on a PTY channel.
     *
     * `--hide-status` turns the tmux status bar off for that session before
     * attaching (the app draws its own chrome); it is ignored by the host for
     * aplexer sessions, which have no tmux status bar. Defaults to `true`
     * because every in-app attach wants it — a caller that wants the host's
     * own bar passes `false` explicitly.
     */
    fun attachCommand(name: String, hideStatus: Boolean = true): String = buildString {
        append("exec ").append(binary).append(" sessions attach")
        if (hideStatus) append(" --hide-status")
        append(" -- ").append(shellSingleQuote(name))
    }

    // --- plumbing ---------------------------------------------------------

    /**
     * Runs [command], turning "no answer" into a [HostCliError.Failed].
     *
     * A [CancellationException] is rethrown rather than swallowed: a cancelled
     * screen must not look like a host that failed.
     */
    private suspend fun capture(command: String, timeoutMs: Long): Result<ExecOutcome> {
        val outcome = try {
            exec.exec(command, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(
                HostCliError.Failed(
                    command = command,
                    exitCode = null,
                    stderr = "",
                    timedOut = false,
                    userMessage = "Could not run `$command` on the host: " +
                        (e.message ?: e::class.simpleName ?: "unknown error"),
                    cause = e,
                ),
            )
        }
        if (outcome.timedOut) {
            return Result.failure(
                HostCliError.Failed(
                    command = command,
                    exitCode = null,
                    stderr = outcome.stderr,
                    timedOut = true,
                    userMessage = "`$command` did not finish within ${timeoutMs}ms on the host.",
                ),
            )
        }
        return Result.success(outcome)
    }

    /** [capture] plus "exit 0 and something on stdout", for the read verbs. */
    private suspend fun captureJson(command: String, timeoutMs: Long): Result<String> {
        val outcome = capture(command, timeoutMs).getOrElse { return Result.failure(it) }
        if (outcome.exitCode != 0) return Result.failure(nonZeroExit(command, outcome))
        if (outcome.stdout.isBlank()) return Result.failure(blankStdout(command))
        return Result.success(outcome.stdout)
    }

    /**
     * Reads the create envelope, which is the one verb whose FAILURE is also
     * JSON: the host prints `{"schema":2,"error":"…"}` on stdout and exits
     * non-zero. That message is the host's own explanation of what went wrong
     * (missing `tmuxctl`, bad backend, agent launch failed), so it is
     * preferred over the generic "exited N" text whenever it is present.
     */
    private fun parseCreate(command: String, outcome: ExecOutcome): Result<CreatedSession> {
        if (outcome.stdout.isBlank()) {
            return Result.failure(
                if (outcome.exitCode != 0) nonZeroExit(command, outcome) else blankStdout(command),
            )
        }

        val obj = jsonObjectOrFailure(createJson, outcome.stdout).getOrElse {
            // A non-zero exit with unreadable stdout is a command failure, not
            // a parser problem — report the exit, which is the actionable half.
            return Result.failure(
                if (outcome.exitCode != 0) nonZeroExit(command, outcome) else it,
            )
        }

        val schemaField = obj[FIELD_SCHEMA]
        val schema = (schemaField as? JsonPrimitive)?.intOrNull
            ?: return Result.failure(
                HostCliError.Malformed(
                    if (schemaField == null) {
                        "the create response is missing the `$FIELD_SCHEMA` field"
                    } else {
                        "the create response's `$FIELD_SCHEMA` was not an integer"
                    },
                ),
            )
        if (schema < SessionsJson.REQUIRED_SCHEMA) {
            return Result.failure(
                HostCliError.TooOld(schema, SessionsJson.REQUIRED_SCHEMA),
            )
        }

        val hostError = (obj[FIELD_ERROR] as? JsonPrimitive)?.contentOrNull
        if (hostError != null) {
            return Result.failure(
                HostCliError.Failed(
                    command = command,
                    exitCode = outcome.exitCode,
                    stderr = outcome.stderr,
                    timedOut = false,
                    userMessage = hostError,
                ),
            )
        }
        if (outcome.exitCode != 0) return Result.failure(nonZeroExit(command, outcome))

        val wire = try {
            createJson.decodeFromJsonElement<CreatedSessionWire>(obj)
        } catch (e: Exception) {
            return Result.failure(
                HostCliError.Malformed(
                    "schema $schema create response did not match the expected shape " +
                        "(${e.message ?: e::class.simpleName})",
                    e,
                ),
            )
        }

        return Result.success(
            CreatedSession(
                name = wire.name,
                manager = Backend.fromWire(wire.manager),
                id = wire.id,
                created = wire.created,
            ),
        )
    }

    private fun nonZeroExit(command: String, outcome: ExecOutcome): HostCliError.Failed {
        val detail = firstLine(outcome.stderr)
        return HostCliError.Failed(
            command = command,
            exitCode = outcome.exitCode,
            stderr = outcome.stderr,
            timedOut = false,
            userMessage = "`$command` failed on the host (exit ${outcome.exitCode})" +
                if (detail.isEmpty()) "." else ": $detail",
        )
    }

    private fun blankStdout(command: String): HostCliError.Malformed =
        HostCliError.Malformed("`$command` printed nothing on stdout")

    /**
     * First non-blank stderr line, capped.
     *
     * Whole stderr can be a Python traceback; the head line is the part a user
     * can act on, and the untruncated text is still on
     * [HostCliError.Failed.stderr] for the log.
     */
    private fun firstLine(stderr: String): String {
        val line = stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return ""
        return if (line.length <= MAX_DETAIL_CHARS) line else line.take(MAX_DETAIL_CHARS) + "…"
    }

    companion object {
        /**
         * Budget for the read verbs. Generous because `sessions list` sweeps
         * a socket directory that accumulates hundreds of dead sockets, and
         * `engines list` probes harness binaries through a login shell.
         */
        const val LIST_TIMEOUT_MS: Long = 20_000

        /**
         * Budget for `sessions create`: it starts a systemd scope and may
         * send an agent launch line, both slower than a read.
         */
        const val CREATE_TIMEOUT_MS: Long = 60_000

        private const val FIELD_SCHEMA = "schema"
        private const val FIELD_ERROR = "error"
        private const val MAX_DETAIL_CHARS = 200

        private val createJson = Json { ignoreUnknownKeys = true }
    }

    /**
     * `schema` is read by [parseCreate] before this decode and ignored here;
     * `id` is null for tmux. `name`/`manager`/`created` are required: a create
     * response missing any of them cannot tell the caller what it now has.
     */
    @Serializable
    private data class CreatedSessionWire(
        val name: String,
        val manager: String,
        val created: Boolean,
        val id: String? = null,
    )
}
