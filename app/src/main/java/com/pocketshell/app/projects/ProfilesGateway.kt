package com.pocketshell.app.projects

import com.pocketshell.app.sessions.LeaseSessionExec
import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * One agent profile discovered ON THE HOST (issue #718) — a row from
 * `pocketshell profiles list --json`.
 *
 * Profiles are defined once on the dev box (auto-discovered from
 * conventional config dirs like `~/.claude` / `~/.zlaude` / `~/.codex`, plus
 * an optional `~/.config/pocketshell/profiles.yaml`) and the phone FETCHES
 * them instead of storing/hand-editing a per-host JSON blob (the #627/#631
 * client-stored model was hard-cut per D22).
 *
 * @property name display label, e.g. "Claude" / "Claude (Z.AI)" / "Codex".
 * @property engine the agent engine: `"claude"` or `"codex"`.
 * @property configDir remote config dir the profile maps to
 *   (`CLAUDE_CONFIG_DIR` / `CODEX_HOME`). `null` for the engine's built-in
 *   default profile (no override).
 * @property default whether this is the engine's default profile (sorted
 *   first, pre-selected in the picker).
 */
data class RemoteProfile(
    val name: String,
    val engine: String,
    val configDir: String? = null,
    val default: Boolean = false,
) {
    companion object {
        const val ENGINE_CLAUDE = "claude"
        const val ENGINE_CODEX = "codex"
    }
}

/** Result of a `profiles list` probe against one host. */
sealed interface ProfilesResult {
    /** The host's profiles for the requested engine(s). */
    data class Profiles(val profiles: List<RemoteProfile>) : ProfilesResult

    /**
     * The host has no `pocketshell` CLI (or it predates the `profiles`
     * subcommand). The picker falls back to the default-only profile set,
     * so the caller treats this exactly like an empty list.
     */
    data object ToolUnavailable : ProfilesResult
    data class Failed(val message: String) : ProfilesResult
    data class ConnectFailed(val cause: Throwable) : ProfilesResult
}

/**
 * Thin SSH gateway for host-discovered agent profiles — issue #718 (S2).
 *
 * Mirrors [com.pocketshell.app.env.SshEnvGateway]: it borrows a
 * reference-counted lease on the app-wide `@Singleton` [SshLeaseManager]
 * (keyed identically to the session screens / folder discovery) and runs the
 * server-side `pocketshell profiles list --json` over the host's WARM
 * transport, so opening the new-session picker reuses the same pooled SSH
 * connection rather than dialing a fresh handshake.
 *
 * The phone only parses JSON here — `--json` is requested so the client
 * reuses `org.json` (the server defaults to YAML for human/agent use).
 */
interface ProfilesGateway {
    /**
     * Fetch the host's agent profiles. When [engine] is non-null the result
     * is limited to that engine (`claude` / `codex`); `null` returns all.
     */
    suspend fun listProfiles(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        engine: String? = null,
    ): ProfilesResult
}

class SshProfilesGateway @Inject constructor(
    private val sshLeaseManager: SshLeaseManager,
) : ProfilesGateway {

    override suspend fun listProfiles(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        engine: String?,
    ): ProfilesResult = withSession(host, keyPath, passphrase, onConnectFail = {
        ProfilesResult.ConnectFailed(it)
    }) { session ->
        val command = buildString {
            append("pocketshell profiles list")
            if (engine != null) {
                append(" --engine ")
                append(shellQuote(engine))
            }
            append(" --json")
        }
        val result = session.exec(pathAware(command))
        when {
            isToolMissing(result.exitCode, result.stderr) -> ProfilesResult.ToolUnavailable
            result.exitCode != 0 -> ProfilesResult.Failed(errorMessage(result.stderr, result.stdout, result.exitCode))
            else -> ProfilesResult.Profiles(parseProfiles(result.stdout))
        }
    }

    /**
     * Borrow the host's WARM transport from the app-wide `@Singleton`
     * [SshLeaseManager] (reference-counted, released — not closed — when
     * [block] returns), identical to [com.pocketshell.app.env.SshEnvGateway].
     */
    private suspend fun <T> withSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        onConnectFail: (Throwable) -> T,
        block: suspend (SshSession) -> T,
    ): T =
        LeaseSessionExec.withSession(
            leaseManager = sshLeaseManager,
            target = LeaseSessionTarget(
                hostId = host.id,
                hostname = host.hostname,
                port = host.port,
                username = host.username,
                keyPath = keyPath,
                passphrase = passphrase,
            ),
            block = block,
        ).getOrElse { onConnectFail(it) }

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
            stderr.ifBlank { stdout }.ifBlank { "pocketshell profiles exited $exitCode" }.trim()

        /**
         * Parse `profiles list --json` output:
         * `{"profiles":[{"name","engine","config_dir","default"}, …]}`.
         * Tolerates leading/trailing whitespace and an empty/missing array.
         * A `null` / empty `config_dir` maps to [RemoteProfile.configDir] =
         * `null` (the engine's built-in default).
         */
        internal fun parseProfiles(stdout: String): List<RemoteProfile> {
            val trimmed = stdout.trim()
            if (trimmed.isEmpty()) return emptyList()
            val root = try { JSONObject(trimmed) } catch (_: Throwable) { return emptyList() }
            val array = root.optJSONArray("profiles") ?: return emptyList()
            val rows = ArrayList<RemoteProfile>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val engine = obj.optString("engine", "").trim()
                if (name.isEmpty() || engine.isEmpty()) continue
                val configDir = if (obj.isNull("config_dir")) {
                    null
                } else {
                    obj.optString("config_dir", "").trim().ifEmpty { null }
                }
                rows += RemoteProfile(
                    name = name,
                    engine = engine,
                    configDir = configDir,
                    default = obj.optBoolean("default", false),
                )
            }
            return rows
        }
    }
}

/**
 * Hilt binding for [ProfilesGateway] — issue #718. Mirrors
 * [com.pocketshell.app.env.EnvGatewayModule].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProfilesGatewayModule {
    @Binds
    abstract fun bindProfilesGateway(gateway: SshProfilesGateway): ProfilesGateway
}
