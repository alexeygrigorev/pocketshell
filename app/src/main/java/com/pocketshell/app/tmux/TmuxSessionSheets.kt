package com.pocketshell.app.tmux

import android.net.Uri
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import com.pocketshell.app.cards.SessionCardFeedSheet
import com.pocketshell.app.cards.SessionCardInteractions
import com.pocketshell.app.cards.SessionCardsRemoteSource
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.app.snippets.SnippetPickerSheet
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyModifierState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TmuxSessionSheets(
    showMicSheet: Boolean,
    promptComposerViewModel: PromptComposerViewModel,
    composerAgentKind: AgentKind?,
    composerTargetKey: String,
    micSheetAutoStartRecording: Boolean,
    onDismissMicSheet: () -> Unit,
    connectionLost: Boolean,
    sendTargetSnapshotProvider: (withEnter: Boolean) -> PromptComposerViewModel.SendTargetSnapshot,
    onSend: suspend (PromptComposerViewModel.SendRequest) -> Boolean,
    composerHostId: Long?,
    onStageAttachments: suspend (List<Uri>) -> Result<List<String>>,
    showSnippetPicker: Boolean,
    snippetsHostId: Long,
    snippetKindFilter: SnippetKind,
    onDismissSnippetPicker: () -> Unit,
    onSnippetSend: (snippet: SnippetEntity, withEnter: Boolean) -> Unit,
    showCardFeedSheet: Boolean,
    sessionCards: List<SessionCardsRemoteSource.SessionCard>,
    sessionCardInteractions: SessionCardInteractions,
    onDismissCardFeedSheet: () -> Unit,
    showHotkeysPanel: Boolean,
    hotkeysPaneId: String?,
    sessionLive: Boolean,
    ctrlModifierState: KeyModifierState,
    onHotkey: (paneId: String, binding: KeyBinding) -> Unit,
    onDismissHotkeys: () -> Unit,
) {
    if (showMicSheet) {
        // PromptComposerSheet drives dictation + the one-field API-key entry
        // dialog. The caller owns route selection; this leaf only hosts the sheet
        // so the session-screen mega-composable carries fewer inline regions.
        PromptComposerSheet(
            viewModel = promptComposerViewModel,
            agentKind = composerAgentKind,
            composerTargetKey = composerTargetKey,
            autoStartRecording = micSheetAutoStartRecording,
            onDismiss = onDismissMicSheet,
            connectionLost = connectionLost,
            sendTargetSnapshotProvider = sendTargetSnapshotProvider,
            onSend = onSend,
            collectSendRequests = false,
            hostId = composerHostId,
            onStageAttachments = onStageAttachments,
        )
    }

    if (showSnippetPicker && snippetsHostId != 0L) {
        SnippetPickerSheet(
            hostId = snippetsHostId,
            onDismiss = onDismissSnippetPicker,
            kindFilter = snippetKindFilter,
            onSnippetSend = onSnippetSend,
        )
    }

    if (showCardFeedSheet) {
        SessionCardFeedSheet(
            cards = sessionCards,
            interactions = sessionCardInteractions,
            onDismiss = onDismissCardFeedSheet,
        )
    }

    val paneId = hotkeysPaneId
    if (showHotkeysPanel && paneId != null) {
        TerminalHotkeysSheet(
            sections = TmuxHotkeyPanelSections,
            enabled = sessionLive,
            ctrlModifierState = ctrlModifierState,
            onKey = { binding -> onHotkey(paneId, binding) },
            onDismiss = onDismissHotkeys,
        )
    }
}
