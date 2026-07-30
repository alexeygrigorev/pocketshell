package com.pocketshell.app.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.fileviewer.RemotePathResolver
import com.pocketshell.app.projects.conventionalRemoteHome
import com.pocketshell.app.sessions.LeaseSessionBlockTimeoutException
import com.pocketshell.app.sessions.LeaseSessionExec
import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.shellSingleQuote
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

internal sealed interface ConversationPathTapState {
    data object Idle : ConversationPathTapState
    data class Checking(val requestId: Long, val resolvedPath: String) : ConversationPathTapState
    data class OpenFile(val requestId: Long, val resolvedPath: String) : ConversationPathTapState
    data class BrowseDirectory(val requestId: Long, val resolvedPath: String) : ConversationPathTapState
    data class Failed(
        val requestId: Long,
        val resolvedPath: String,
        val reason: String,
    ) : ConversationPathTapState
}

internal data class ConversationPathTapRequest(
    val scopeKey: String,
    val rawPath: String,
    val cwd: String?,
    val target: LeaseSessionTarget,
)

internal sealed interface ConversationRemotePathFact {
    data object RegularFile : ConversationRemotePathFact
    data object Directory : ConversationRemotePathFact
    data class Failure(val reason: String) : ConversationRemotePathFact
}

internal data class ConversationRemotePathProbeResult(
    val resolvedPath: String,
    val fact: ConversationRemotePathFact,
)

/**
 * Owns the one-at-a-time Conversation path-tap operation (issue #1890).
 *
 * The target is resolved synchronously from the pane cwd captured at tap time.
 * A pane/window switch invalidates [scopeKey], cancels the borrow, and prevents
 * a late result from navigating the newly-visible conversation. While a result
 * is checking or awaiting consumption, further taps are ignored so a double tap
 * cannot launch duplicate reads or duplicate destinations.
 */
internal class ConversationPathTapController(
    private val scope: CoroutineScope,
    private val probeTimeoutMs: Long = ConversationPathTapViewModel.PROBE_TIMEOUT_MS,
    private val probe:
        suspend (ConversationPathTapRequest, String) -> ConversationRemotePathProbeResult,
) {
    private val _state = MutableStateFlow<ConversationPathTapState>(ConversationPathTapState.Idle)
    val state: StateFlow<ConversationPathTapState> = _state.asStateFlow()

    private var currentScopeKey: String? = null
    private var nextRequestId = 0L
    private var activeRequestId: Long? = null
    private var job: Job? = null

    fun bindScope(scopeKey: String?) {
        if (currentScopeKey == scopeKey) return
        currentScopeKey = scopeKey
        cancel()
    }

    fun open(request: ConversationPathTapRequest) {
        if (request.scopeKey != currentScopeKey) return
        if (activeRequestId != null) return

        val resolved = ConversationPathTapViewModel.resolvePath(
            rawPath = request.rawPath,
            cwd = request.cwd,
            remoteHome = conventionalRemoteHome(request.target.username),
        )
        val requestId = ++nextRequestId
        if (resolved.isBlank()) {
            activeRequestId = requestId
            _state.value = ConversationPathTapState.Failed(
                requestId = requestId,
                resolvedPath = resolved,
                reason = "The path is empty and could not be resolved.",
            )
            return
        }

        activeRequestId = requestId
        _state.value = ConversationPathTapState.Checking(requestId, resolved)
        job = scope.launch {
            val result = withTimeoutOrNull(probeTimeoutMs) {
                try {
                    probe(request, resolved)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    ConversationRemotePathProbeResult(
                        resolvedPath = resolved,
                        fact = ConversationRemotePathFact.Failure(
                            t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName,
                        ),
                    )
                }
            } ?: ConversationRemotePathProbeResult(
                resolvedPath = resolved,
                fact = ConversationRemotePathFact.Failure("Timed out while checking the path."),
            )
            if (activeRequestId != requestId || currentScopeKey != request.scopeKey) return@launch
            _state.value = when (val fact = result.fact) {
                ConversationRemotePathFact.RegularFile ->
                    ConversationPathTapState.OpenFile(requestId, result.resolvedPath)
                ConversationRemotePathFact.Directory ->
                    ConversationPathTapState.BrowseDirectory(requestId, result.resolvedPath)
                is ConversationRemotePathFact.Failure ->
                    ConversationPathTapState.Failed(requestId, result.resolvedPath, fact.reason)
            }
        }
    }

    fun consume(requestId: Long) {
        if (activeRequestId != requestId) return
        activeRequestId = null
        job = null
        _state.value = ConversationPathTapState.Idle
    }

    fun cancel() {
        job?.cancel()
        job = null
        activeRequestId = null
        _state.value = ConversationPathTapState.Idle
    }
}

@HiltViewModel
class ConversationPathTapViewModel @Inject constructor(
    sshLeaseManager: SshLeaseManager,
) : ViewModel() {
    private val controller = ConversationPathTapController(viewModelScope) { request, resolved ->
        probeRemotePath(sshLeaseManager, request, resolved)
    }

    internal val state: StateFlow<ConversationPathTapState> = controller.state

    internal fun bindScope(scopeKey: String?) = controller.bindScope(scopeKey)

    internal fun open(request: ConversationPathTapRequest) = controller.open(request)

    internal fun consume(requestId: Long) = controller.consume(requestId)

    internal fun cancel() = controller.cancel()

    override fun onCleared() {
        controller.cancel()
        super.onCleared()
    }

    companion object {
        internal const val PROBE_TIMEOUT_MS: Long = 8_000L

        /**
         * Resolve relative targets against the pane cwd when it is usable, or
         * against the remote login HOME when the pane has no usable cwd. The
         * latter makes the server target and any visible failure path absolute
         * instead of relying on an implicit SSH working directory.
         */
        internal fun resolvePath(
            rawPath: String,
            cwd: String?,
            remoteHome: String?,
        ): String {
            val usableCwd = cwd
                ?.trim()
                ?.takeIf { it.startsWith("/") || it == "~" || it.startsWith("~/") }
            return RemotePathResolver.resolve(
                input = rawPath,
                cwd = usableCwd ?: remoteHome,
                remoteHome = remoteHome,
            )
        }

        /**
         * ONE quoted remote command establishes existence, usable type and
         * access. The marker comes from remote fact, never from the parser's
         * extension heuristic. `stat` is reached only for a missing/inaccessible
         * target so its stderr preserves the server's concrete reason.
         */
        internal fun probeCommand(resolvedPath: String): String {
            val path = shellSingleQuote(resolvedPath)
            return "if [ -f $path ]; then " +
                "if [ -r $path ]; then printf '__PS_FILE__\\n'; else printf '__PS_DENIED__\\n'; fi; " +
                "elif [ -d $path ]; then " +
                "if [ -r $path ] && [ -x $path ]; then printf '__PS_DIRECTORY__\\n'; " +
                "else printf '__PS_DENIED__\\n'; fi; " +
                "elif [ -e $path ]; then printf '__PS_OTHER__\\n'; " +
                "else stat $path >/dev/null; fi"
        }

        internal fun parseProbe(
            stdout: String,
            stderr: String,
            exitCode: Int,
        ): ConversationRemotePathFact {
            return when (stdout.lineSequence().firstOrNull()?.trim()) {
                "__PS_FILE__" -> ConversationRemotePathFact.RegularFile
                "__PS_DIRECTORY__" -> ConversationRemotePathFact.Directory
                "__PS_DENIED__" -> ConversationRemotePathFact.Failure("Permission denied.")
                "__PS_OTHER__" -> ConversationRemotePathFact.Failure(
                    "The target is not a regular file or directory.",
                )
                else -> {
                    val detail = stderr.trim().lineSequence().firstOrNull().orEmpty()
                    val reason = when {
                        detail.contains("permission denied", ignoreCase = true) ->
                            "Permission denied."
                        detail.contains("no such", ignoreCase = true) ||
                            detail.contains("not found", ignoreCase = true) ->
                            "Path does not exist."
                        exitCode != 0 && detail.isNotBlank() -> detail
                        exitCode != 0 -> "Path does not exist."
                        else -> "The server returned an unknown path type."
                    }
                    ConversationRemotePathFact.Failure(reason)
                }
            }
        }

        private suspend fun probeRemotePath(
            leaseManager: SshLeaseManager,
            request: ConversationPathTapRequest,
            fallbackResolvedPath: String,
        ): ConversationRemotePathProbeResult = withContext(Dispatchers.IO) {
            LeaseSessionExec.withSession(
                leaseManager = leaseManager,
                target = request.target,
                blockTimeoutMs = PROBE_TIMEOUT_MS,
            ) { session ->
                // Match FileViewer's exact resolution order: ask the live login
                // shell for its authoritative HOME, then fall back to the same
                // conventional username-derived home only if that lookup fails.
                // This is resolution, not a second type check; the command below
                // remains the one and only existence/type probe.
                val homeResult = try {
                    session.exec("printf '%s\\n' \"\$HOME\"")
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                val remoteHome = homeResult
                    ?.takeIf { it.exitCode == 0 }
                    ?.stdout
                    ?.lineSequence()
                    ?.firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.let { it.trimEnd('/').ifEmpty { "/" } }
                    ?.takeIf { it.startsWith("/") }
                    ?: conventionalRemoteHome(request.target.username)
                val resolvedPath = resolvePath(
                    rawPath = request.rawPath,
                    cwd = request.cwd,
                    remoteHome = remoteHome,
                )
                val result = session.exec(probeCommand(resolvedPath))
                ConversationRemotePathProbeResult(
                    resolvedPath = resolvedPath,
                    fact = parseProbe(result.stdout, result.stderr, result.exitCode),
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                ConversationRemotePathProbeResult(
                    resolvedPath = fallbackResolvedPath,
                    fact = ConversationRemotePathFact.Failure(probeFailureReason(error)),
                )
            }
        }

        internal fun probeFailureReason(error: Throwable): String =
            if (error is LeaseSessionBlockTimeoutException) {
                "Timed out while checking the path."
            } else {
                "Couldn't check the path: " +
                    (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
            }
    }
}
