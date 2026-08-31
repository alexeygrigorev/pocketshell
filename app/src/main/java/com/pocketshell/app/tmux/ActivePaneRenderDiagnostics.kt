package com.pocketshell.app.tmux

/**
 * Immutable diagnostics for the active render owner.
 *
 * This structure deliberately contains observations only. Proof ordering,
 * settlement predicates, retry orchestration, and mutation assertions belong
 * to test source sets and are not shipped in the application.
 */
internal data class ActivePaneRenderDiagnostics(
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
)
