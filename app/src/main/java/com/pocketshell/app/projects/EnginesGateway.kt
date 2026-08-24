package com.pocketshell.app.projects

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.app.sessions.LeaseSessionExec
import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.app.ssh.BoundedSessionExec
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.model.sessionAgentKindFromOption
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Host-side profile metadata used to launch one registered engine. */
data class RemoteEngineProfile(
    val environmentVariable: String? = null,
    val defaultDirectoryName: String? = null,
    val markers: List<String> = emptyList(),
    val nameHints: List<String> = emptyList(),
    val defaultLabel: String? = null,
)

/** Host-side launch metadata for one registered engine. */
data class RemoteEngineLaunch(
    val argv: List<String> = emptyList(),
    val skipPermissionsArgv: List<String> = emptyList(),
    val supportsSkipPermissions: Boolean = false,
    val environmentSet: Map<String, String> = emptyMap(),
    val environmentUnset: List<String> = emptyList(),
    val profileEnvironment: String? = null,
    val profile: RemoteEngineProfile? = null,
)

/**
 * One row from `pocketshell engines list --json`.
 *
 * The host registry intentionally keeps [id] open-ended. [family] is the
 * closed app projection used by existing session rendering and detection.
 */
data class RemoteEngine(
    val id: String,
    val familyId: String,
    val family: SessionAgentKind,
    val label: String,
    val providerMark: String? = null,
    val usageProvider: String? = null,
    val enabled: Boolean = true,
    val available: Boolean = true,
    val availableForCreate: Boolean = enabled && available,
    val unavailableReason: String? = null,
    val launch: RemoteEngineLaunch = RemoteEngineLaunch(),
) {
    /** Explicit name for the open-ended value recorded in tmux. */
    val rawId: String get() = id
}

/** Result of one bounded host engine-registry read. */
sealed interface EnginesResult {
    data class Engines(
        val engines: List<RemoteEngine>,
        val fromCache: Boolean = false,
    ) : EnginesResult

    data object ToolUnavailable : EnginesResult
    data class Failed(val message: String) : EnginesResult
    data class ConnectFailed(val cause: Throwable) : EnginesResult
}

/** App-side seam for host engine-registry reads and family resolution. */
interface EnginesGateway {
    suspend fun listEngines(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): EnginesResult

    /** Last valid rows for [hostId], or empty before the first valid read. */
    fun cachedEngines(hostId: Long): List<RemoteEngine> = emptyList()

    /** Resolve a recorded raw id through the last valid registry read. */
    fun familyForRawId(hostId: Long, rawId: String?): SessionAgentKind? = null
}

/**
 * Bounded SSH gateway for `pocketshell engines list --json`.
 *
 * The command borrows the shared per-host lease. A malformed, unavailable,
 * timed-out, or connect-failed read serves the last valid host registry when
 * one exists, preserving existing-session renderability without hiding a
 * valid empty registry.
 */
@Singleton
class SshEnginesGateway @Inject constructor(
    private val sshLeaseManager: SshLeaseManager,
) : EnginesGateway {

    private var execReadTimeoutMs: Long = EXEC_READ_TIMEOUT_MS
    private var execDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val cacheByHost = ConcurrentHashMap<Long, List<RemoteEngine>>()

    @androidx.annotation.VisibleForTesting
    internal constructor(
        sshLeaseManager: SshLeaseManager,
        execReadTimeoutMs: Long,
        execDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(sshLeaseManager) {
        this.execReadTimeoutMs = execReadTimeoutMs
        this.execDispatcher = execDispatcher
    }

    override suspend fun listEngines(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): EnginesResult {
        val fresh = withSession(host, keyPath, passphrase) { session ->
            val result = BoundedSessionExec.execBounded(
                session = session,
                command = pathAware("engines list --json"),
                timeoutMs = execReadTimeoutMs,
                dispatcher = execDispatcher,
                callerSite = "engines_registry",
            )
            when {
                result == null -> EnginesResult.Failed(
                    "pocketshell engines list timed out after ${execReadTimeoutMs}ms",
                )
                isToolMissing(result.exitCode, result.stderr) -> EnginesResult.ToolUnavailable
                result.exitCode != 0 -> EnginesResult.Failed(
                    errorMessage(result.stderr, result.stdout, result.exitCode),
                )
                else -> parseEnginesDocument(result.stdout)?.let { EnginesResult.Engines(it) }
                    ?: EnginesResult.Failed("Malformed pocketshell engines payload")
            }
        }.getOrElse { EnginesResult.ConnectFailed(it) }

        if (fresh is EnginesResult.Engines) {
            cacheByHost[host.id] = fresh.engines
            return fresh
        }
        return cacheByHost[host.id]?.let { EnginesResult.Engines(it, fromCache = true) } ?: fresh
    }

    override fun cachedEngines(hostId: Long): List<RemoteEngine> = cacheByHost[hostId].orEmpty()

    override fun familyForRawId(hostId: Long, rawId: String?): SessionAgentKind? {
        val id = rawId.cleanRawId() ?: return null
        return cacheByHost[hostId]
            ?.firstOrNull { it.id == id }
            ?.family
            ?: sessionAgentKindFromOption(id)
    }

    private suspend fun <T> withSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        block: suspend (SshSession) -> T,
    ): Result<T> = LeaseSessionExec.withSession(
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
    )

    private fun pathAware(command: String): String = PocketshellCommand.wrap(command)

    companion object {
        private const val EXEC_READ_TIMEOUT_MS = 3_500L

        private fun isToolMissing(exitCode: Int, stderr: String): Boolean =
            exitCode == 127 || stderr.contains("not found", ignoreCase = true)

        private fun errorMessage(stderr: String, stdout: String, exitCode: Int): String =
            stderr.ifBlank { stdout }
                .ifBlank { "pocketshell engines exited $exitCode" }
                .trim()

        /** Null means malformed/missing root; an empty list is valid. */
        internal fun parseEnginesDocument(stdout: String): List<RemoteEngine>? {
            val trimmed = stdout.trim()
            if (trimmed.isEmpty()) return null
            val root = try {
                JSONObject(trimmed)
            } catch (_: Throwable) {
                return null
            }
            val array = root.optJSONArray("engines") ?: return null
            val rows = ArrayList<RemoteEngine>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.stringOrNull("id") ?: continue
                val familyId = obj.stringOrNull("family").orEmpty()
                val enabled = obj.optBoolean("enabled", true)
                val available = obj.optBoolean("available", true)
                rows += RemoteEngine(
                    id = id,
                    familyId = familyId,
                    family = sessionAgentKindForFamily(familyId),
                    label = obj.stringOrNull("label") ?: id,
                    providerMark = obj.stringOrNull("provider_mark"),
                    usageProvider = obj.stringOrNull("usage_provider"),
                    // Wire convenience cannot override safety flags.
                    availableForCreate = enabled && available && obj.optBoolean(
                        "available_for_create",
                        true,
                    ),
                    enabled = enabled,
                    available = available,
                    unavailableReason = obj.stringOrNull("unavailable_reason"),
                    launch = obj.optJSONObject("launch").toLaunch(),
                )
            }
            return rows
        }

        internal fun parseEngines(stdout: String): List<RemoteEngine> =
            parseEnginesDocument(stdout).orEmpty()

        internal fun parseEnginesPayload(stdout: String): List<RemoteEngine> = parseEngines(stdout)

        internal fun sessionAgentKindForFamily(rawFamily: String?): SessionAgentKind =
            when (rawFamily?.trim()?.lowercase(Locale.ROOT)) {
                "claude" -> SessionAgentKind.Claude
                "codex" -> SessionAgentKind.Codex
                "opencode" -> SessionAgentKind.OpenCode
                "grok" -> SessionAgentKind.Grok
                "shell" -> SessionAgentKind.Shell
                else -> SessionAgentKind.Unknown
            }

        private fun JSONObject?.stringOrNull(name: String): String? {
            if (this == null || isNull(name)) return null
            return optString(name, "").trim().ifEmpty { null }
        }

        private fun JSONObject?.optStringArray(name: String): List<String> {
            val array = this?.optJSONArray(name) ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i, "").trim()
                    if (value.isNotEmpty()) add(value)
                }
            }
        }

        private fun JSONObject?.toStringMap(): Map<String, String> {
            if (this == null) return emptyMap()
            val result = LinkedHashMap<String, String>()
            val names = keys()
            while (names.hasNext()) {
                val name = names.next()
                val value = stringOrNull(name) ?: continue
                result[name] = value
            }
            return result
        }

        private fun JSONObject?.toLaunch(): RemoteEngineLaunch {
            if (this == null) return RemoteEngineLaunch()
            val skipPermissionsArgv = optStringArray("skip_permissions_argv")
            val environment = optJSONObject("env")
            val profile = optJSONObject("profile")?.let { profileJson ->
                RemoteEngineProfile(
                    environmentVariable = profileJson.stringOrNull("env_var"),
                    defaultDirectoryName = profileJson.stringOrNull("default_dirname"),
                    markers = profileJson.optStringArray("markers"),
                    nameHints = profileJson.optStringArray("name_hints"),
                    defaultLabel = profileJson.stringOrNull("default_label"),
                )
            }
            return RemoteEngineLaunch(
                argv = optStringArray("argv"),
                skipPermissionsArgv = skipPermissionsArgv,
                supportsSkipPermissions = optBoolean(
                    "supports_skip_permissions",
                    skipPermissionsArgv.isNotEmpty(),
                ),
                environmentSet = environment?.optJSONObject("set").toStringMap(),
                environmentUnset = environment?.optStringArray("unset").orEmpty(),
                profileEnvironment = stringOrNull("profile_env"),
                profile = profile,
            )
        }
    }
}

private fun String?.cleanRawId(): String? = this?.trim()?.ifBlank { null }

@Module
@InstallIn(SingletonComponent::class)
abstract class EnginesGatewayModule {
    @Binds
    @Singleton
    abstract fun bindEnginesGateway(gateway: SshEnginesGateway): EnginesGateway
}
