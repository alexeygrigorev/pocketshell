package com.pocketshell.app.tmux

internal fun buildBlackFrameObservedDiagnosticFields(
    pane: TmuxPaneState,
    blackClass: String,
    sessionName: String?,
    captureText: String?,
    nowMs: Long,
    lastSeedAtMs: Long?,
    lastOutputAtMs: Long?,
    connectionStatusName: String,
    foreground: Boolean,
    screenOn: Boolean,
    unverifiedStreak: Int? = null,
): Array<Pair<String, Any?>> {
    val state = pane.terminalState
    val fields = mutableListOf<Pair<String, Any?>>(
        "class" to blackClass,
        "paneId" to pane.paneId,
        "windowId" to pane.windowId,
        "session" to sessionName,
        "renderedChars" to state.renderedNonBlankCharCount(),
        "captureBytes" to (captureText?.length ?: 0),
        "visibleRows" to state.visibleRowCount(),
        "msSinceLastSeed" to (lastSeedAtMs?.let { nowMs - it } ?: -1L),
        "msSinceLastOutput" to (lastOutputAtMs?.let { nowMs - it } ?: -1L),
        "connectionStatus" to connectionStatusName,
        "foreground" to foreground,
        "screenOn" to screenOn,
        "partialBlank" to state.visibleScreenIsPartiallyBlank(),
    )
    if (unverifiedStreak != null) {
        fields += "unverifiedStreak" to unverifiedStreak
    }
    return fields.toTypedArray()
}

internal fun buildWatchdogLivenessDiagnosticFields(
    pane: TmuxPaneState,
    sessionName: String?,
    generation: Long,
    clientHash: Int,
    atMs: Long,
    tick: Int,
    foreground: Boolean,
    screenOn: Boolean,
    backedOff: Boolean,
): Array<Pair<String, Any?>> =
    arrayOf(
        "paneId" to pane.paneId,
        "windowId" to pane.windowId,
        "session" to sessionName,
        "generation" to generation,
        "clientHash" to clientHash,
        "atMs" to atMs,
        "tick" to tick,
        "foreground" to foreground,
        "screenOn" to screenOn,
        "backedOff" to backedOff,
    )
