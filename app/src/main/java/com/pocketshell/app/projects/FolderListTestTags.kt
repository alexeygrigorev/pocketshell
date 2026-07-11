package com.pocketshell.app.projects

const val FOLDER_LIST_NEW_SESSION_FAB_TAG: String = "folder-list:new-session-fab"
const val FOLDER_LIST_BROWSE_REPOS_TAG: String = "folder-list:browse-repos"
const val FOLDER_LIST_BROWSE_FILES_TAG: String = "folder-list:browse-files"
const val FOLDER_LIST_USAGE_TAG: String = "folder-list:usage"
const val FOLDER_LIST_SETTINGS_TAG: String = "folder-list:settings"
const val FOLDER_LIST_VIEW_TOGGLE_TAG: String = "folder-list:view-toggle"
const val FOLDER_LIST_WORKSPACE_SETTINGS_TAG: String = "folder-list:workspace-settings"
const val FOLDER_LIST_ASSISTANT_TAG: String = "folder-list:assistant"
/** Host-detail header `⋮` kebab overflow button (#522 item 2). */
const val FOLDER_LIST_OVERFLOW_TAG: String = "folder-list:overflow"
const val FOLDER_LIST_ASSISTANT_PANEL_TAG: String = "folder-list:assistant:panel"
const val FOLDER_LIST_ASSISTANT_PROMPT_TAG: String = "folder-list:assistant:prompt"
const val FOLDER_LIST_ASSISTANT_PROMPT_MIC_TAG: String = "folder-list:assistant:prompt-mic"
const val FOLDER_LIST_ASSISTANT_SUBMIT_TAG: String = "folder-list:assistant:submit"
const val FOLDER_LIST_ASSISTANT_CLOSE_TAG: String = "folder-list:assistant:close"
const val FOLDER_LIST_ACTION_STATUS_TAG: String = "folder-list:action-status"
const val FOLDER_LIST_ACTION_STATUS_DISMISS_TAG: String = "folder-list:action-status:dismiss"

// Issue #518 — "Stop session" confirmation dialog.
const val STOP_SESSION_DIALOG_TAG: String = "folder-list:stop-session:dialog"
const val STOP_SESSION_CONFIRM_TAG: String = "folder-list:stop-session:confirm"
const val STOP_SESSION_CANCEL_TAG: String = "folder-list:stop-session:cancel"
const val RENAME_SESSION_DIALOG_TAG: String = "folder-list:rename-session:dialog"
const val RENAME_SESSION_FIELD_TAG: String = "folder-list:rename-session:field"
const val RENAME_SESSION_CONFIRM_TAG: String = "folder-list:rename-session:confirm"
const val RENAME_SESSION_CANCEL_TAG: String = "folder-list:rename-session:cancel"

// Issue #1155 — "this session no longer exists, create a new one in this
// folder, or go home?" recovery prompt. Owned app-level by `MainActivity`
// (via `StaleSessionPromptController`) so it also surfaces on the cold-restore
// path where the folder tree was never opened. Tags kept in this package so the
// existing connected E2E imports resolve unchanged.
const val STALE_SESSION_DIALOG_TAG: String = "folder-list:stale-session:dialog"
const val STALE_SESSION_CONFIRM_TAG: String = "folder-list:stale-session:confirm"
const val STALE_SESSION_GO_HOME_TAG: String = "folder-list:stale-session:go-home"

fun folderRowTestTag(path: String): String = "folder-list:row:$path"
fun folderHeaderClickTestTag(path: String): String = "folder-list:header-click:$path"
fun folderHeaderLabelTag(path: String): String = "folder-list:header:$path"
fun folderCountPillTestTag(path: String): String = "folder-list:count:$path"
fun folderListFlatRowTestTag(sessionName: String): String = "folder-list:flat-row:$sessionName"
fun folderListFlatRowStatusDotTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:status"
/** Tags the leading terminal tile glyph on a flat host-detail row (#522 item 3). */
fun folderListFlatRowTileTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:tile"
fun folderListFlatRowBadgeTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:badge"
/** Issue #858: tags the non-default profile chip on a flat host-detail row. */
fun folderListFlatRowProfileChipTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:profile"
/** Issue #1237: tags the agent-state chip on a flat host-detail row. */
fun folderListFlatRowAgentStateChipTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:agent-state"
fun folderListFlatRowActionsTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:stop"

fun folderListFlatRowOpenMenuItemTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:open:item"
fun folderListFlatRowRenameMenuItemTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:rename:item"
fun folderListFlatRowStopMenuItemTestTag(sessionName: String): String =
    "folder-list:flat-row:$sessionName:stop:item"
fun folderDetailRowTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName"
fun folderDetailCreateTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:create"
fun folderDetailActionsTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:actions"
fun folderDetailDisclosureTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:disclosure"
fun folderStatusDotTestTag(folderPath: String): String =
    "folder-list:detail:$folderPath:status"
fun folderSessionStatusDotTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:status"
/** Tags the leading terminal tile glyph on a tree session child row (#522 item 3). */
fun folderSessionTileTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:tile"
fun folderSessionBadgeTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:badge"
/** Issue #858: tags the non-default profile chip on a tree session child row. */
fun folderSessionProfileChipTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:profile"
/** Issue #1237: tags the agent-state chip (idle / waiting / working) on a session row. */
fun folderSessionAgentStateChipTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:agent-state"

fun folderSessionOpenMenuItemTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:open:item"
fun folderSessionRenameMenuItemTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:rename:item"
fun folderSessionStopMenuItemTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:stop:item"
/** Tags the `├─/└─` tree connector cell on an expanded session child row (#503). */
fun folderSessionConnectorTestTag(folderPath: String, sessionName: String): String =
    "folder-list:detail:$folderPath:$sessionName:connector"
fun folderSessionWindowConnectorTestTag(
    folderPath: String,
    sessionName: String,
    windowIndex: Int?,
    windowName: String?,
): String = "folder-list:detail:$folderPath:$sessionName:window:${windowStableKey(windowIndex, windowName)}:connector"
fun folderSessionWindowRowTestTag(
    folderPath: String,
    sessionName: String,
    windowIndex: Int?,
    windowName: String?,
): String = "folder-list:detail:$folderPath:$sessionName:window:${windowStableKey(windowIndex, windowName)}"
fun folderSessionWindowStatusDotTestTag(
    folderPath: String,
    sessionName: String,
    windowIndex: Int?,
    windowName: String?,
): String = "${folderSessionWindowRowTestTag(folderPath, sessionName, windowIndex, windowName)}:status"
fun folderSessionWindowTileTestTag(
    folderPath: String,
    sessionName: String,
    windowIndex: Int?,
    windowName: String?,
): String = "${folderSessionWindowRowTestTag(folderPath, sessionName, windowIndex, windowName)}:tile"
fun folderTreeRootTestTag(path: String): String = "folder-list:tree-root:$path"
fun folderTreeRootLabelTag(path: String): String = "folder-list:tree-root:$path:label"
fun folderTreeRootCountTag(path: String): String = "folder-list:tree-root:$path:count"
fun folderTreeRootCreateTestTag(path: String): String = "folder-list:tree-root:$path:create"
fun folderTreeRootActionsTestTag(path: String): String = "folder-list:tree-root:$path:actions"
fun folderTreeRootEmptyHintAddTestTag(path: String): String =
    "folder-list:tree-root:$path:empty-hint:add"
fun folderTreeRootEmptyHintActionPlusTestTag(path: String): String =
    "folder-list:tree-root:$path:empty-hint:action-plus"

private fun windowStableKey(windowIndex: Int?, windowName: String?): String =
    windowIndex?.let { "w$it" } ?: windowName?.ifBlank { null } ?: "unknown"
