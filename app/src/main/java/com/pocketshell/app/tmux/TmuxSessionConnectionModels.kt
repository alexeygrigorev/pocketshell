package com.pocketshell.app.tmux

internal sealed interface RuntimeCacheEviction {
    data object HostWide : RuntimeCacheEviction
    data class TargetRuntime(val key: TmuxRuntimeKey) : RuntimeCacheEviction
}

internal data class ConnectIntent(
    val target: TmuxSessionViewModel.ConnectionTarget,
    val trigger: TmuxConnectTrigger,
    val generation: Long,
)

internal data class PendingReattach(
    val target: TmuxSessionViewModel.ConnectionTarget,
    val generation: Long,
    val intendedTarget: TmuxSessionViewModel.ConnectionTarget?,
    val intendedTrigger: TmuxConnectTrigger?,
)

internal data class LifecycleReattachNetworkCoalesce(
    val target: TmuxSessionViewModel.ConnectionTarget,
    val generation: Long,
)

internal data class PausedAutoReconnect(
    val target: TmuxSessionViewModel.ConnectionTarget,
    val reason: String,
)

internal data class AttachMilestone(
    val attempt: Int,
    val sessionName: String,
    val startedAtMs: Long,
    val trigger: TmuxConnectTrigger,
    var firstPaneOutputLogged: Boolean = false,
    var firstPaneListReadyLogged: Boolean = false,
    var firstTerminalBridgeLogged: Boolean = false,
    var firstCaptureReadyLogged: Boolean = false,
    var firstRemoteRefreshLogged: Boolean = false,
)

internal sealed interface PaneReconcileResult {
    data class Ready(val paneCount: Int) : PaneReconcileResult
    data class Failed(val cause: Throwable) : PaneReconcileResult
    data object NoClient : PaneReconcileResult
}
