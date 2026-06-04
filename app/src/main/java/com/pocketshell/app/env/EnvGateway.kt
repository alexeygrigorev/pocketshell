package com.pocketshell.app.env

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * One key surfaced by `pocketshell env list --json` — issue #264.
 *
 * Values are deliberately absent. `list` is write-only by default (D24);
 * the UI shows `••••` and only calls [EnvGateway.getValue] when the user
 * explicitly taps "Reveal".
 *
 * @property key the environment variable name.
 * @property file the env file the key lives in (`.env` or `.envrc`).
 * @property hasValue whether the parsed assignment had a non-empty
 *   right-hand side (so the UI can mask `••••` vs render an "(empty)"
 *   hint).
 */
data class EnvKeyRow(
    val key: String,
    val file: String,
    val hasValue: Boolean,
)

/** The two env-file targets the CLI manages (D24). */
enum class EnvFileTarget(val fileName: String) {
    Env(".env"),
    Envrc(".envrc"),
    ;

    companion object {
        fun fromFileName(name: String): EnvFileTarget =
            entries.firstOrNull { it.fileName == name } ?: Env
    }
}

/** Result of an `env list` probe against one folder. */
sealed interface EnvListResult {
    data class Keys(val keys: List<EnvKeyRow>) : EnvListResult
    data object ToolUnavailable : EnvListResult
    data class Failed(val message: String) : EnvListResult
    data class ConnectFailed(val cause: Throwable) : EnvListResult
}

/** Result of a single mutating / reveal call. */
sealed interface EnvOpResult {
    data object Success : EnvOpResult

    /** `env get` returned the requested value(s). */
    data class Values(val values: Map<String, String>) : EnvOpResult
    data object ToolUnavailable : EnvOpResult
    data class Failed(val message: String) : EnvOpResult
    data class ConnectFailed(val cause: Throwable) : EnvOpResult
}

/**
 * Thin SSH gateway for the env-file management screen — issue #264.
 *
 * Backed entirely by the server-side `pocketshell env ...` CLI (#262).
 * The phone holds no secrets; all file I/O happens on the dev box.
 *
 * Kept deliberately separate from
 * [com.pocketshell.app.projects.FolderListGateway] (which #263 is
 * concurrently editing): this gateway opens its OWN short-lived
 * [SshConnection] per operation, mirroring the
 * [com.pocketshell.app.projects.SshFolderListGateway] /
 * [com.pocketshell.app.jobs.PocketshellJobsRemoteSource] exec pattern
 * (`session.exec(pathAware(...))`).
 *
 * ## D24 secret-safety contract
 *
 * `env set` / `env copy` write secret VALUES, and those must never appear
 * in any argv (`ps`, shell history, tmux scrollback) or in logcat /
 * command logs. So:
 *
 *  - `list`, `get`, and `copy` go through plain `exec` — they pass only
 *    key NAMES and directory PATHS on the command line, never values.
 *  - `set` uploads the `{"KEY":"value"}` JSON payload to a temporary file
 *    on the remote via the SCP-style `cat > file` upload primitive
 *    ([SshSession.uploadStream] — the JSON bytes travel through the
 *    channel's stdin, never argv), then runs
 *    `pocketshell env set ... < tmpfile` and removes the temp file. The
 *    value bytes therefore never touch any command line, and this
 *    gateway never logs them.
 */
interface EnvGateway {
    suspend fun listKeys(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        directory: String,
    ): EnvListResult

    suspend fun getValue(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        directory: String,
        key: String,
    ): EnvOpResult

    /**
     * Create/update keys from [updates] in [directory]/[file]. Values are
     * delivered via stdin (a remote temp file written over SCP), never
     * argv (D24).
     */
    suspend fun setKeys(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        directory: String,
        file: EnvFileTarget,
        updates: Map<String, String>,
    ): EnvOpResult

    /**
     * Copy [keys] from [sourceDirectory] into [destinationDirectory]/[file].
     * Values are read and written entirely server-side; only key names and
     * paths cross the command line.
     */
    suspend fun copyKeys(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sourceDirectory: String,
        destinationDirectory: String,
        file: EnvFileTarget,
        keys: List<String>,
    ): EnvOpResult
}

class SshEnvGateway @Inject constructor() : EnvGateway {

    override suspend fun listKeys(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        directory: String,
    ): EnvListResult = withSession(host, keyPath, passphrase, onConnectFail = {
        EnvListResult.ConnectFailed(it)
    }) { session ->
        val command = buildString {
            append("pocketshell env list --dir ")
            append(shellQuote(directory))
            append(" --json")
        }
        val result = session.exec(pathAware(command))
        when {
            isToolMissing(result.exitCode, result.stderr) -> EnvListResult.ToolUnavailable
            result.exitCode != 0 -> EnvListResult.Failed(errorMessage(result.stderr, result.stdout, result.exitCode))
            else -> EnvListResult.Keys(parseKeyList(result.stdout))
        }
    }

    override suspend fun getValue(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        directory: String,
        key: String,
    ): EnvOpResult = withSession(host, keyPath, passphrase, onConnectFail = {
        EnvOpResult.ConnectFailed(it)
    }) { session ->
        val command = buildString {
            append("pocketshell env get --dir ")
            append(shellQuote(directory))
            append(" --key ")
            append(shellQuote(key))
            append(" --json")
        }
        val result = session.exec(pathAware(command))
        when {
            isToolMissing(result.exitCode, result.stderr) -> EnvOpResult.ToolUnavailable
            result.exitCode != 0 -> EnvOpResult.Failed(errorMessage(result.stderr, result.stdout, result.exitCode))
            else -> EnvOpResult.Values(parseValueMap(result.stdout))
        }
    }

    override suspend fun setKeys(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        directory: String,
        file: EnvFileTarget,
        updates: Map<String, String>,
    ): EnvOpResult {
        if (updates.isEmpty()) return EnvOpResult.Success
        return withSession(host, keyPath, passphrase, onConnectFail = {
            EnvOpResult.ConnectFailed(it)
        }) { session ->
            // D24: the secret VALUES go to a remote temp file over the
            // SCP-style `cat > file` upload (stdin transport), so they
            // never appear in any argv / scrollback. The `env set`
            // command line carries only the directory and file flag.
            val payloadBytes = buildSetPayload(updates).toByteArray(StandardCharsets.UTF_8)
            val tmpPath = remoteTempPath()
            session.uploadStream(
                input = ByteArrayInputStream(payloadBytes),
                length = payloadBytes.size.toLong(),
                name = "pocketshell-env.json",
                remotePath = tmpPath,
            )
            val command = buildString {
                append("pocketshell env set --dir ")
                append(shellQuote(directory))
                append(" --file ")
                append(shellQuote(file.fileName))
                append(" < ")
                append(shellQuote(tmpPath))
                // Always remove the temp file, even on a set failure, so
                // no secret bytes linger on disk.
                append("; status=$?; rm -f ")
                append(shellQuote(tmpPath))
                append("; exit \$status")
            }
            val result = session.exec(pathAware(command))
            when {
                isToolMissing(result.exitCode, result.stderr) -> EnvOpResult.ToolUnavailable
                result.exitCode != 0 -> EnvOpResult.Failed(errorMessage(result.stderr, result.stdout, result.exitCode))
                else -> EnvOpResult.Success
            }
        }
    }

    override suspend fun copyKeys(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sourceDirectory: String,
        destinationDirectory: String,
        file: EnvFileTarget,
        keys: List<String>,
    ): EnvOpResult {
        if (keys.isEmpty()) return EnvOpResult.Success
        return withSession(host, keyPath, passphrase, onConnectFail = {
            EnvOpResult.ConnectFailed(it)
        }) { session ->
            val command = buildString {
                append("pocketshell env copy --from ")
                append(shellQuote(sourceDirectory))
                append(" --to ")
                append(shellQuote(destinationDirectory))
                append(" --file ")
                append(shellQuote(file.fileName))
                keys.forEach { key ->
                    append(" --key ")
                    append(shellQuote(key))
                }
            }
            val result = session.exec(pathAware(command))
            when {
                isToolMissing(result.exitCode, result.stderr) -> EnvOpResult.ToolUnavailable
                result.exitCode != 0 -> EnvOpResult.Failed(errorMessage(result.stderr, result.stdout, result.exitCode))
                else -> EnvOpResult.Success
            }
        }
    }

    private suspend fun <T> withSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        onConnectFail: (Throwable) -> T,
        block: suspend (SshSession) -> T,
    ): T {
        val session = SshConnection.connect(
            host = host.hostname,
            port = host.port,
            user = host.username,
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrElse { return onConnectFail(it) }
        return try {
            block(session)
        } finally {
            session.close()
        }
    }

    // Route every `pocketshell env ...` invocation through the centralised
    // PATH-robust resolver (issue #484) so the CLI is found even when the
    // non-interactive SSH `PATH` lacks `~/.local/bin`. The command strings
    // built above always start with the literal `pocketshell ` prefix; it is
    // stripped and the remaining args (including any `< file` redirection and
    // cleanup tail) are handed to the wrapper, which re-resolves and runs the
    // binary in a single non-interactive shell.
    private fun pathAware(command: String): String =
        com.pocketshell.app.pocketshell.PocketshellCommand.wrap(
            command.removePrefix("pocketshell ").trim(),
        )

    companion object {
        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"

        private fun isToolMissing(exitCode: Int, stderr: String): Boolean =
            exitCode == 127 || stderr.contains("not found", ignoreCase = true)

        private fun errorMessage(stderr: String, stdout: String, exitCode: Int): String =
            stderr.ifBlank { stdout }.ifBlank { "pocketshell env exited $exitCode" }.trim()

        /**
         * Build the `{"KEY":"value"}` JSON object the CLI reads on stdin.
         * Uses [JSONObject] so any value (newlines, quotes, `$`, `#`) is
         * escaped correctly and round-trips through the CLI's surgical
         * writer.
         */
        internal fun buildSetPayload(updates: Map<String, String>): String {
            val obj = JSONObject()
            for ((key, value) in updates) {
                obj.put(key, value)
            }
            return obj.toString()
        }

        /**
         * Parse `env list --json` output — a JSON array of
         * `{key, file, has_value}` objects. Tolerates leading/trailing
         * whitespace and an empty document.
         */
        internal fun parseKeyList(stdout: String): List<EnvKeyRow> {
            val trimmed = stdout.trim()
            if (trimmed.isEmpty()) return emptyList()
            val array = JSONArray(trimmed)
            val rows = ArrayList<EnvKeyRow>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val key = obj.optString("key", "")
                if (key.isEmpty()) continue
                rows += EnvKeyRow(
                    key = key,
                    file = obj.optString("file", EnvFileTarget.Env.fileName),
                    hasValue = obj.optBoolean("has_value", false),
                )
            }
            return rows
        }

        /**
         * Parse `env get --json` output — a JSON object mapping
         * `key -> value` for keys that exist. Missing keys are simply
         * absent.
         */
        internal fun parseValueMap(stdout: String): Map<String, String> {
            val trimmed = stdout.trim()
            if (trimmed.isEmpty()) return emptyMap()
            val obj = JSONObject(trimmed)
            val result = LinkedHashMap<String, String>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = obj.optString(key, "")
            }
            return result
        }

        /**
         * A collision-resistant temp path on the remote. The basename
         * carries no secret material; the JSON bytes arrive over stdin.
         *
         * `/tmp` is used because the SCP-style upload primitive runs
         * `cat > '<path>'` with the path SINGLE-QUOTED — `$HOME` would
         * NOT expand inside single quotes, so an absolute, universally
         * writable directory is required. `/tmp` is POSIX-standard and
         * present on the Docker `agents` fixture and real dev boxes.
         */
        private fun remoteTempPath(): String {
            val suffix = System.nanoTime().toString(36) +
                "-" + (Math.random() * 1_000_000).toInt().toString(36)
            return "/tmp/.pocketshell-env-$suffix.json"
        }
    }
}

/**
 * Hilt binding for [EnvGateway] — issue #264. Mirrors
 * [com.pocketshell.app.projects.FolderListGatewayModule] so the `env`
 * package owns its gateway wiring.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EnvGatewayModule {
    @Binds
    abstract fun bindEnvGateway(gateway: SshEnvGateway): EnvGateway
}
