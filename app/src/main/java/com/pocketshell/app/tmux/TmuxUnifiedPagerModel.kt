package com.pocketshell.app.tmux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Small screen-facing holder so identity/page derivation does not grow the
 * already-large [TmuxSessionScreen] frame.
 */
internal data class UnifiedPagerModel(
    val targetEpoch: UnifiedPagerTargetEpoch,
    val pages: List<UnifiedPagerPage>,
)

@Composable
internal fun rememberUnifiedPagerModel(
    conn: TmuxSessionConnectionRuntime,
    sessionName: String,
    initialWindowIndex: Int?,
    viewModel: TmuxSessionViewModel,
): UnifiedPagerModel {
    val targetEpoch = remember(conn.targetSessionId, initialWindowIndex) {
        newUnifiedPagerTargetEpoch(conn.targetSessionId)
    }
    val pages = remember(conn.unifiedPanes, targetEpoch, sessionName, viewModel) {
        unifiedPagerPages(
            panes = conn.unifiedPanes,
            targetEpoch = targetEpoch,
            targetSessionName = sessionName,
            sessionNameForPane = viewModel::sessionNameForUnifiedPane,
        )
    }
    return remember(targetEpoch, pages) {
        UnifiedPagerModel(targetEpoch = targetEpoch, pages = pages)
    }
}
