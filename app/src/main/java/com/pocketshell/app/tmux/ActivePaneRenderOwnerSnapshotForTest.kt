package com.pocketshell.app.tmux

/**
 * Issue #966 connected-proof owner snapshot. Identity binds the visible TerminalView session to
 * the exact active VM pane/model; settlement fields expose positive completion invariants for
 * attach seed and asynchronous refresh-client/reseed work, without a timing guess.
 */
internal data class ActivePaneRenderOwnerSnapshotForTest(
    val paneId: String,
    val windowId: String,
    val sessionId: String,
    val targetSessionName: String?,
    val connectGeneration: Long,
    val clientIdentity: Int?,
    val stateIdentity: Int,
    val terminalSessionIdentity: Int?,
    val emulatorIdentity: Int?,
    val modelMutationEpoch: Long,
    val modelDrainBacklogged: Boolean,
    val seedOperationInFlight: Boolean,
    val sizeOperationsInFlight: Int,
    val automaticHealOperationsInFlight: Int,
    val automaticHealActivityEpoch: Long,
    val controlSizeGeneration: Long,
    val effectiveColumns: Int,
    val effectiveRows: Int,
    val appliedColumns: Int,
    val appliedRows: Int,
    val lastSeedAtMs: Long?,
    val renderedNonBlankChars: Int,
    val partiallyBlank: Boolean,
    val renderLooksSuspect: Boolean,
    val coherent: Boolean,
) {
    val attachResizeSeedSettled: Boolean
        get() = coherent &&
            emulatorIdentity != null &&
            !modelDrainBacklogged &&
            !seedOperationInFlight &&
            sizeOperationsInFlight == 0 &&
            automaticHealOperationsInFlight == 0 &&
            effectiveColumns > 0 &&
            effectiveRows > 0 &&
            appliedColumns == effectiveColumns &&
            appliedRows == effectiveRows &&
            lastSeedAtMs != null

    fun sameOwnerAs(other: ActivePaneRenderOwnerSnapshotForTest): Boolean =
        paneId == other.paneId &&
            windowId == other.windowId &&
            sessionId == other.sessionId &&
            targetSessionName == other.targetSessionName &&
            connectGeneration == other.connectGeneration &&
            clientIdentity == other.clientIdentity &&
            stateIdentity == other.stateIdentity &&
            terminalSessionIdentity == other.terminalSessionIdentity &&
            emulatorIdentity == other.emulatorIdentity
}
