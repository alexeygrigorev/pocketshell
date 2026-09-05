package com.pocketshell.next.composer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import com.pocketshell.uikit.components.SheetHeader

const val COMPOSER_TITLE_TAG: String = "composer-title"
const val COMPOSER_CLOSE_TAG: String = "composer-close"
const val COMPOSER_SHEET_TITLE: String = "Prompt Composer"

/** What a mic tap does, given the current permission and recording state. */
enum class MicTapAction {
    /** Already recording, or RECORD_AUDIO is granted: start or stop dictation. */
    StartOrStop,

    /** First tap on a fresh install: ask for RECORD_AUDIO before the recognizer. */
    RequestPermission,
}

/**
 * The RECORD_AUDIO gate (#2521).
 *
 * The rewrite's `ComposerBar` called `SpeechRecognizer` with no runtime
 * prompt, which is the dictation crash on a fresh install. A tap while
 * already recording always stops (the permission is already held). A tap
 * that would START recording requests the permission first when it is not
 * granted; denied does not start the recognizer.
 */
fun decideMicTap(hasRecordAudioPermission: Boolean, recording: Boolean): MicTapAction =
    if (recording || hasRecordAudioPermission) {
        MicTapAction.StartOrStop
    } else {
        MicTapAction.RequestPermission
    }

/**
 * Prompt Composer as a floating [androidx.compose.material3.ModalBottomSheet]
 * over the terminal (D11, #2521).
 *
 * Restore of the v0.4.47 chrome — title, close, draft, mic, Insert / Send —
 * on the rewrite send path (no outbound queue). Opening this sheet must not
 * sit in the session column: the terminal underneath keeps its cell count.
 *
 * [hasRecordAudioPermission] is a test seam. Production leaves it null and
 * reads `ContextCompat.checkSelfPermission`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptComposerSheet(
    state: ComposerUiState,
    onDismiss: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInsert: () -> Unit,
    onAttach: () -> Unit,
    onMicTap: () -> Unit,
    onCancelRecording: () -> Unit,
    onToggleHistory: () -> Unit,
    onTogglePreview: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onDiscard: () -> Unit,
    onPermissionDenied: () -> Unit,
    modifier: Modifier = Modifier,
    hasRecordAudioPermission: (() -> Boolean)? = null,
) {
    val context = LocalContext.current
    val permissionGranted: () -> Boolean = hasRecordAudioPermission ?: {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onMicTap() else onPermissionDenied()
    }

    val dismiss = {
        onCancelRecording()
        onDismiss()
    }

    ComposerModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        PromptComposerContent(
            state = state,
            onClose = dismiss,
            onDraftChange = onDraftChange,
            onSend = onSend,
            onInsert = onInsert,
            onAttach = onAttach,
            onMicTap = {
                when (
                    decideMicTap(
                        hasRecordAudioPermission = permissionGranted(),
                        recording = state.recording == RecordingState.Recording,
                    )
                ) {
                    MicTapAction.StartOrStop -> onMicTap()
                    MicTapAction.RequestPermission ->
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onCancelRecording = onCancelRecording,
            onToggleHistory = onToggleHistory,
            onTogglePreview = onTogglePreview,
            onRemoveAttachment = onRemoveAttachment,
            onDismissNotice = onDismissNotice,
            onDiscard = onDiscard,
        )
    }
}

/**
 * Sheet body without the modal window, so host-JVM tests can drive Insert /
 * Send / mic without Robolectric dropping clicks on a `ModalBottomSheet`.
 */
@Composable
fun PromptComposerContent(
    state: ComposerUiState,
    onClose: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInsert: () -> Unit,
    onAttach: () -> Unit,
    onMicTap: () -> Unit,
    onCancelRecording: () -> Unit,
    onToggleHistory: () -> Unit,
    onTogglePreview: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.navigationBarsPadding()) {
        SheetHeader(
            title = COMPOSER_SHEET_TITLE,
            titleTestTag = COMPOSER_TITLE_TAG,
            onClose = onClose,
            closeContentDescription = "Close Prompt Composer",
            closeTestTag = COMPOSER_CLOSE_TAG,
        )
        ComposerBar(
            state = state,
            onDraftChange = onDraftChange,
            onSend = onSend,
            onInsert = onInsert,
            onAttach = onAttach,
            onMicTap = onMicTap,
            onCancelRecording = onCancelRecording,
            onToggleHistory = onToggleHistory,
            onTogglePreview = onTogglePreview,
            onRemoveAttachment = onRemoveAttachment,
            onDismissNotice = onDismissNotice,
            onDiscard = onDiscard,
        )
    }
}
