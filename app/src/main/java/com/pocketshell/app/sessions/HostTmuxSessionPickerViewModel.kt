package com.pocketshell.app.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class HostTmuxSessionPickerViewModel @Inject constructor(
    private val gateway: HostTmuxSessionsGateway,
) : ViewModel() {
    private val _state: MutableStateFlow<HostTmuxSessionPickerState> =
        MutableStateFlow(HostTmuxSessionPickerState.Idle)
    val state: StateFlow<HostTmuxSessionPickerState> = _state.asStateFlow()

    /**
     * Issue #463: the in-session project switcher's sibling list. Sourced
     * ONLY from the warm live `-CC` client (never a fresh SSH connect) and
     * filtered to the current session's project path, so tapping the
     * header crumb and switching stays instant.
     */
    private val _projectSwitcher: MutableStateFlow<ProjectSwitcherState> =
        MutableStateFlow(ProjectSwitcherState())
    val projectSwitcher: StateFlow<ProjectSwitcherState> = _projectSwitcher.asStateFlow()

    private var loadJob: Job? = null
    private var projectSwitcherJob: Job? = null

    fun load(request: HostTmuxSessionPickerRequest) {
        loadJob?.cancel()
        _state.value = HostTmuxSessionPickerState.Loading(request)
        loadJob = viewModelScope.launch {
            val result = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                gateway.listSessions(request.host, request.keyPath, request.passphrase)
            }
            _state.value = if (result == null) {
                HostTmuxSessionPickerState.Fallback(
                    request = request,
                    message = "Timed out while loading tmux sessions. Please retry.",
                )
            } else when (result) {
                is HostTmuxSessionListResult.Sessions -> HostTmuxSessionPickerState.Ready(
                    request = request,
                    rows = result.rows.sortedWith(
                        compareByDescending<HostTmuxSessionRow> { it.lastActivity ?: it.createdAt ?: 0L }
                            .thenBy { it.name },
                    ),
                    message = if (result.rows.isEmpty()) "No tmux sessions found." else null,
                )
                HostTmuxSessionListResult.ToolUnavailable -> HostTmuxSessionPickerState.Fallback(
                    request = request,
                    message = "pocketshell/tmux is not available on this host.",
                )
                is HostTmuxSessionListResult.Failed -> HostTmuxSessionPickerState.Fallback(
                    request = request,
                    message = result.message,
                )
                is HostTmuxSessionListResult.ConnectFailed -> HostTmuxSessionPickerState.ConnectError(
                    request = request,
                    summary = summarizeConnectError(result.cause),
                )
            }
        }
    }

    /**
     * Issue #109: Retry button on the connect-error sheet. Re-runs the
     * same `load(...)` with the last [HostTmuxSessionPickerRequest] held
     * in the current `ConnectError` state. No-op if the user dismissed
     * the sheet already (state is `Idle`).
     */
    fun retry() {
        val current = _state.value
        val request = when (current) {
            is HostTmuxSessionPickerState.ConnectError -> current.request
            is HostTmuxSessionPickerState.Fallback -> current.request
            is HostTmuxSessionPickerState.Loading -> current.request
            is HostTmuxSessionPickerState.Ready -> current.request
            HostTmuxSessionPickerState.Idle -> null
        } ?: return
        load(request)
    }

    /**
     * Issue #109: Cancel button shown next to the "Connecting to ..."
     * spinner. Aborts the in-flight gateway call (so the SSH connect
     * suspension is cancelled cooperatively) and returns the sheet to
     * `Idle`, dismissing it.
     */
    fun cancelLoading() {
        loadJob?.cancel()
        loadJob = null
        _state.value = HostTmuxSessionPickerState.Idle
    }

    fun dismiss() {
        loadJob?.cancel()
        loadJob = null
        _state.value = HostTmuxSessionPickerState.Idle
    }

    /**
     * Issue #463: refresh the project-switcher sibling list for the
     * session screen's header crumb. Reads ONLY the warm live `-CC` client
     * for [host] (no SSH fallback) and keeps the sessions whose working
     * directory matches [projectPath] (the current pane's cwd). When the
     * host has no live client yet, the previously-known sibling list is
     * retained so the dropdown still opens instantly.
     */
    fun refreshProjectSiblings(
        host: HostEntity,
        keyPath: String,
        currentSessionName: String,
        projectPath: String?,
    ) {
        projectSwitcherJob?.cancel()
        projectSwitcherJob = viewModelScope.launch {
            val result = gateway.listSessionsFromLiveClient(host, keyPath)
            val rows = (result as? HostTmuxSessionListResult.Sessions)?.rows
            if (rows == null) {
                // No live client (or a transient warm-query error): keep the
                // last known siblings rather than collapsing the dropdown.
                _projectSwitcher.value = _projectSwitcher.value.copy(
                    currentSessionName = currentSessionName,
                )
                return@launch
            }
            _projectSwitcher.value = ProjectSwitcherState(
                currentSessionName = currentSessionName,
                projectPath = projectPath,
                siblings = projectSiblings(rows, projectPath, currentSessionName),
            )
        }
    }

    /**
     * Reset the switcher (e.g. when the screen leaves). Cancels any
     * in-flight warm query.
     */
    fun clearProjectSiblings() {
        projectSwitcherJob?.cancel()
        projectSwitcherJob = null
        _projectSwitcher.value = ProjectSwitcherState()
    }

    private fun projectSiblings(
        rows: List<HostTmuxSessionRow>,
        projectPath: String?,
        currentSessionName: String,
    ): List<HostTmuxSessionRow> {
        val normalisedProject = projectPath?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val inProject = if (normalisedProject == null) {
            // We don't know this session's project path — fall back to the
            // path the current session reports in the live list so the
            // switcher still scopes to siblings sharing that directory.
            val currentRowPath = rows
                .firstOrNull { it.name == currentSessionName }
                ?.path
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }
            if (currentRowPath == null) {
                rows
            } else {
                rows.filter { it.path?.trimEnd('/') == currentRowPath }
            }
        } else {
            rows.filter { it.path?.trimEnd('/') == normalisedProject }
        }
        return inProject.sortedWith(
            compareByDescending<HostTmuxSessionRow> { it.lastActivity ?: it.createdAt ?: 0L }
                .thenBy { it.name },
        )
    }

    /**
     * Issue #463: state backing the in-session project switcher dropdown.
     * [siblings] are the sessions in the current project (including the
     * current one, marked by [currentSessionName]). [hasSiblingsToSwitch]
     * is true only when there is at least one OTHER session to switch to —
     * the header crumb hides its chevron otherwise.
     */
    data class ProjectSwitcherState(
        val currentSessionName: String = "",
        val projectPath: String? = null,
        val siblings: List<HostTmuxSessionRow> = emptyList(),
    ) {
        val hasSiblingsToSwitch: Boolean
            get() = siblings.any { it.name != currentSessionName }
    }

    internal companion object {
        /**
         * Last-resort UI ceiling: gateway paths have their own tighter bounds,
         * but the picker must still leave Loading if a future implementation
         * accidentally parks forever.
         */
        const val LOAD_TIMEOUT_MS: Long = 12_000L
    }
}
